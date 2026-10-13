"""CLB-91 ciphertext evidence and private format-2 approval; no provisioning.

All subprocess output stays private. The caller supplies the existing bounded,
process-group-owning capture and private-root validator. Root/OS and the approved
runtime/source profile are trusted; OpenPGP encryption is NOT sender authentication.
"""
import base64
import contextlib
import datetime
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path
import re
import signal
import stat
import struct
import subprocess
import tempfile
import time
import zipfile

REPOSITORY = 'koteev-m/clubs_bot'
WORKFLOW = '.github/workflows/corrected-stage-release.yml'
VERSION = '2.5.24'
LIMIT = 131072
TTL = 86400
NUMBER = r'[1-9][0-9]{0,19}'
SHA = r'[0-9a-f]{40}'
FINGERPRINT = r'[0-9A-F]{40}'
PRODUCER_PATHS = (WORKFLOW, 'scripts/deploy/corrected-stage-release.py',
    'scripts/deploy/root_binding_review.py', 'scripts/deploy/release_authority.py',
    'scripts/deploy/release_private_root.py', 'scripts/deploy/release-status.pattern',
    'scripts/deploy/remote-compose-release.sh')
REASONS = frozenset(('INPUT', 'CRYPTO_RUNTIME', 'RECEIVER', 'ENCRYPTION', 'DECRYPTION',
    'PRODUCER', 'ARTIFACT', 'EXPIRED', 'APPROVAL_REQUIRED', 'APPROVAL_INVALID',
    'LEGACY_AUTHORITY_FORBIDDEN', 'PRIVATE_BOUNDARY', 'BUSY', 'REPLAY', 'CLEANUP'))


class Refused(Exception):
    def __init__(self, reason):
        super().__init__(reason if reason in REASONS else 'INPUT')


def need(condition, reason='INPUT'):
    if not condition:
        raise Refused(reason)


def unique(pairs):
    value = {}
    for key, item in pairs:
        need(key not in value)
        value[key] = item
    return value


def decode(data):
    need(type(data) in (str, bytes) and 0 < len(data) <= LIMIT)
    try:
        return json.loads(data, object_pairs_hook=unique,
            parse_constant=lambda _: (_ for _ in ()).throw(Refused('INPUT')))
    except (ValueError, UnicodeError, RecursionError):
        raise Refused('INPUT') from None


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()


def exact(value, fields, reason='INPUT'):
    need(type(value) is dict and set(value) == set(fields), reason)


def numeric(value):
    return type(value) is str and re.fullmatch(NUMBER, value) is not None


def seconds(value):
    need(type(value) is int and value >= 0)
    return value


def timestamp(value):
    try:
        return int(datetime.datetime.strptime(value, '%Y-%m-%dT%H:%M:%SZ').replace(
            tzinfo=datetime.timezone.utc).timestamp())
    except (ValueError, TypeError):
        raise Refused('PRODUCER') from None


def file_identity(value):
    return (value.st_dev, value.st_ino, value.st_uid, value.st_gid, value.st_mode,
            value.st_nlink, value.st_size, value.st_mtime_ns, value.st_ctime_ns)


def os_libraries(data):
    """Only system libraries may remain dynamic in the accepted static GNU build.

    Mach-O thin 64-bit only. Linux must be fully static: no interpreter/dynamic
    segment. No caller assertion of closure completeness replaces this check.
    """
    if data[:4] == b'\xcf\xfa\xed\xfe':
        need(len(data) >= 32, 'CRYPTO_RUNTIME')
        _, _, _, filetype, count, size, _, _ = struct.unpack('<8I', data[:32])
        need(filetype == 2 and 0 < count <= 128 and size <= 65536 and 32 + size <= len(data), 'CRYPTO_RUNTIME')
        pos = 32
        for _ in range(count):
            command, length = struct.unpack('<II', data[pos:pos+8])
            need(length >= 8 and pos + length <= 32 + size, 'CRYPTO_RUNTIME')
            if command in (0xc, 0x18, 0x80000018, 0x1f, 0x8000001f, 0x20, 0x23, 0x80000023):
                offset = struct.unpack('<I', data[pos+8:pos+12])[0]
                need(24 <= offset < length, 'CRYPTO_RUNTIME')
                library = data[pos+offset:pos+length].split(b'\0')[0]
                need(library.startswith((b'/usr/lib/', b'/System/Library/'))
                     and b'..' not in library, 'CRYPTO_RUNTIME')
            need(command not in (0x8000001c, 0x27), 'CRYPTO_RUNTIME')  # no rpath/dyld environment
            pos += length
        need(pos == 32 + size, 'CRYPTO_RUNTIME')
    elif data[:5] == b'\x7fELF\x02':
        need(data[5] == 1 and len(data) >= 64, 'CRYPTO_RUNTIME')
        offset = struct.unpack('<Q', data[32:40])[0]
        size, count = struct.unpack('<HH', data[54:58])
        need(struct.unpack('<H',data[16:18])[0] == 2 and size == 56 and 0 < count <= 128
             and offset + size*count <= len(data), 'CRYPTO_RUNTIME')
        need(all(struct.unpack('<I', data[offset+i*size:offset+i*size+4])[0] not in (2,3)
                 for i in range(count)), 'CRYPTO_RUNTIME')
    else:
        raise Refused('CRYPTO_RUNTIME')


class Crypto:
    """Exact GPG build, absent ambient configuration, isolated public keyring.

    Decrypt uses a dedicated owner-only reviewer keyring and an owned fixed agent.
    This code never reads private-key bytes; only GPG's locally supervised agent
    may use them. Production receiver/runtime acceptance is external, never learned.
    """
    def __init__(self, profile, public_key, fingerprint, capture, safe_root, temporary_root):
        self.profile = decode(profile) if type(profile) in (str, bytes) else profile
        exact(self.profile, ('format','version','gpg','agent','gpgconf','sysconfdir','files'), 'CRYPTO_RUNTIME')
        need(type(self.profile['format']) is int and self.profile['format'] == 1
             and self.profile['version'] == VERSION, 'CRYPTO_RUNTIME')
        need(type(fingerprint) is str and re.fullmatch(FINGERPRINT, fingerprint), 'RECEIVER')
        need(type(public_key) is bytes and 0 < len(public_key) <= 65536
             and public_key.startswith(b'-----BEGIN PGP PUBLIC KEY BLOCK-----\n')
             and public_key.rstrip().endswith(b'-----END PGP PUBLIC KEY BLOCK-----')
             and public_key.count(b'-----BEGIN') == 1
             and all(b in (9,10,13) or 32 <= b <= 126 for b in public_key), 'RECEIVER')
        self.key, self.fingerprint = public_key, fingerprint
        self.capture, self.safe_root, self.temporary_root = capture, safe_root, temporary_root
        self.held, self.home, self.directory, self.agent_process = [], None, None, None
        self.subkey = None

    def recheck(self):
        for path, fd, observed in self.held:
            need(file_identity(os.fstat(fd)) == observed and
                 file_identity(os.stat(path, follow_symlinks=False)) == observed, 'CRYPTO_RUNTIME')
        config = Path(self.profile['sysconfdir'])
        if config.exists():
            fd = self.safe_root(str(config)); os.close(fd)
            need(not list(config.iterdir()), 'CRYPTO_RUNTIME')
        else:
            parent = self.safe_root(str(config.parent)); os.close(parent)
            need(not config.is_symlink(), 'CRYPTO_RUNTIME')
        if self.home:
            for name in ('common.conf','gpg.conf','gpg-agent.conf','dirmngr.conf'):
                need(not os.path.lexists(str(Path(self.home)/name)), 'CRYPTO_RUNTIME')

    def run(self, args, payload=b'', limit=LIMIT, statuses=False, home=None):
        self.recheck()
        directory = home or self.home
        for name in ('common.conf','gpg.conf','gpg-agent.conf','dirmngr.conf'):
            need(not os.path.lexists(str(Path(directory)/name)), 'CRYPTO_RUNTIME')
        env = {'PATH':'/usr/bin:/bin','HOME':directory,'LC_ALL':'C'}
        with tempfile.TemporaryFile(dir=self.temporary_root) as status:
            argv = [self.profile['gpg'], '--no-options', '--homedir', directory,
                '--batch', '--no-tty', '--no-autostart', '--disable-dirmngr',
                '--no-auto-key-retrieve', '--no-auto-key-import', '--auto-key-locate', 'clear',
                '--pinentry-mode', 'error', '--status-fd', str(status.fileno()), *args]
            code, output = self.capture(argv, payload, env=env, pass_fds=(status.fileno(),),
                                         timeout=30, limit=limit)
            status.seek(0); records = status.read(16385)
            need(len(records) <= 16384 and code == 0, 'DECRYPTION' if '--decrypt' in args else 'ENCRYPTION')
            self.recheck()
            for name in ('common.conf','gpg.conf','gpg-agent.conf','dirmngr.conf'):
                need(not os.path.lexists(str(Path(directory)/name)), 'CRYPTO_RUNTIME')
            return (output, records) if statuses else output

    def __enter__(self):
        files = self.profile['files']
        need(type(files) is dict and set(files) == {self.profile[k] for k in ('gpg','agent','gpgconf')}, 'CRYPTO_RUNTIME')
        try:
            for path, digest in files.items():
                need(type(path) is str and os.path.isabs(path) and
                     type(digest) is str and re.fullmatch(r'[0-9a-f]{64}', digest), 'CRYPTO_RUNTIME')
                parent_path = str(Path(path).parent)
                parent = self.safe_root(parent_path)
                self.held.append((parent_path,parent,file_identity(os.fstat(parent))))
                fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
                value = os.fstat(fd); self.held.append((path,fd,file_identity(value)))
                need(stat.S_ISREG(value.st_mode) and value.st_uid in (0,os.geteuid())
                     and value.st_nlink == 1 and not value.st_mode & 0o022
                     and value.st_mode & 0o111 and value.st_size <= 32*1024*1024, 'CRYPTO_RUNTIME')
                data = bytearray()
                while True:
                    chunk = os.read(fd,65536)
                    if not chunk: break
                    data.extend(chunk); need(len(data) <= 32*1024*1024, 'CRYPTO_RUNTIME')
                need(len(data) == value.st_size and hashlib.sha256(data).hexdigest() == digest, 'CRYPTO_RUNTIME')
                os_libraries(data)
            fd = self.safe_root(self.temporary_root); os.close(fd)
            config = Path(self.profile['sysconfdir'])
            config_parent = self.safe_root(str(config.parent))
            self.held.append((str(config.parent),config_parent,file_identity(os.fstat(config_parent))))
            # GPG constructs a Unix-domain agent address even for a public-only
            # import. Use a short private child; an unsupported address refuses.
            self.directory = tempfile.TemporaryDirectory(prefix='g-',dir=self.temporary_root)
            self.home = self.directory.name
            self.recheck()
            version = self.run(['--version'],limit=4096)
            need(version.splitlines()[0] == ('gpg (GnuPG) '+VERSION).encode(), 'CRYPTO_RUNTIME')
            code, config = self.capture([self.profile['gpgconf'],'--list-dirs','sysconfdir'],
                env={'PATH':'/usr/bin:/bin','HOME':self.home,'GNUPGHOME':self.home,'LC_ALL':'C'},timeout=10,limit=4096)
            need(code == 0 and config.strip().decode() == self.profile['sysconfdir'], 'CRYPTO_RUNTIME')
            self.run(['--import-options','only-pubkeys','--import'],self.key)
            records = self.run(['--with-colons','--with-fingerprint','--with-subkey-fingerprint','--list-keys']).decode().splitlines()
            primary, encryption = [], []
            kind = None
            for record in records:
                parts = record.split(':')
                if parts[0] in ('pub','sub'):
                    need(len(parts)>11 and parts[1] not in ('r','e','d','i')
                         and (not parts[6] or int(parts[6])>int(time.time())), 'RECEIVER')
                    kind = (parts[0], 'e' in parts[11])
                elif parts[0] == 'fpr' and kind:
                    need(re.fullmatch(FINGERPRINT,parts[9]), 'RECEIVER')
                    if kind[0] == 'pub': primary.append(parts[9])
                    if kind[1]: encryption.append(parts[9])
                    kind = None
                need(parts[0] not in ('sec','ssb'), 'RECEIVER')
            need(primary == [self.fingerprint] and len(encryption) == 1, 'RECEIVER')
            self.subkey = encryption[0]
            return self
        except BaseException:
            self.close(); raise

    def encrypt(self, plaintext):
        need(type(plaintext) is bytes and len(plaintext) <= LIMIT)
        data, records = self.run(['--trust-model','always','--recipient',self.subkey+'!',
            '--cipher-algo','AES256','--force-ocb','--compress-algo','none','--encrypt'],plaintext,statuses=True)
        need(b'[GNUPG:] END_ENCRYPTION\n' in records and
             re.search(rb'\[GNUPG:\] BEGIN_ENCRYPTION 0 9(?: 2)?\n',records), 'ENCRYPTION')
        need(len(data)>64 and len(data)<=LIMIT and plaintext not in data, 'ENCRYPTION')
        return data

    @contextlib.contextmanager
    def agent(self, homedir):
        fd = self.safe_root(homedir)
        try:
            need(os.fstat(fd).st_uid == os.geteuid() and stat.S_IMODE(os.fstat(fd).st_mode)==0o700, 'PRIVATE_BOUNDARY')
            need(not any(os.path.lexists(str(Path(homedir)/name)) for name in ('common.conf','gpg.conf','gpg-agent.conf','dirmngr.conf')), 'CRYPTO_RUNTIME')
            need(not any(os.path.lexists(str(Path(homedir)/name)) for name in ('S.gpg-agent','S.gpg-agent.extra','S.gpg-agent.browser','S.gpg-agent.ssh')), 'BUSY')
            self.recheck()
            child = subprocess.Popen([self.profile['agent'],'--no-options','--homedir',homedir,
                '--daemon','--no-detach','--disable-scdaemon','--no-allow-external-cache'],
                stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,
                env={'PATH':'/usr/bin:/bin','HOME':homedir,'LC_ALL':'C'},start_new_session=True)
            self.agent_process = child
            deadline = time.monotonic()+5
            while not (Path(homedir)/'S.gpg-agent').exists():
                need(time.monotonic()<deadline,'DECRYPTION'); time.sleep(.02)
            value = os.stat(Path(homedir)/'S.gpg-agent',follow_symlinks=False)
            need(stat.S_ISSOCK(value.st_mode) and value.st_uid==os.geteuid(),'DECRYPTION')
            yield
        finally:
            try:
                child = self.agent_process
                if child:
                    # Keep the leader unreaped until group cleanup, as capture does.
                    try: os.killpg(child.pid,signal.SIGTERM)
                    except ProcessLookupError: pass
                    try: child.wait(timeout=2)
                    except subprocess.TimeoutExpired:
                        os.killpg(child.pid,signal.SIGKILL); child.wait(timeout=2)
                    self.agent_process = None
            except BaseException:
                raise Refused('CLEANUP') from None
            finally:
                os.close(fd)

    def decrypt(self, ciphertext, homedir):
        with self.agent(homedir):
            data, records = self.run(['--decrypt'],ciphertext,statuses=True,home=homedir)
            need(b'[GNUPG:] DECRYPTION_OKAY\n' in records and
                 re.search(rb'\[GNUPG:\] DECRYPTION_INFO 0 9 2(?: 0)?\n',records), 'DECRYPTION')
            keys = re.findall(rb'\[GNUPG:\] DECRYPTION_KEY ([0-9A-F]{40}) ([0-9A-F]{40}) [^\n]*\n',records)
            need(keys == [(self.subkey.encode(),self.fingerprint.encode())], 'RECEIVER')
            return data

    def close(self):
        failed = False
        if self.directory:
            try: self.directory.cleanup()
            except BaseException: failed = True
            self.directory = None
        for _,fd,_ in reversed(self.held):
            try: os.close(fd)
            except BaseException: failed = True
        self.held.clear()
        need(not failed,'CLEANUP')

    def __exit__(self,*_):
        self.close()


def producer(env):
    need(env.get('GITHUB_REPOSITORY') == REPOSITORY and env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
         and env.get('GITHUB_REF') == 'refs/heads/main' and env.get('GITHUB_JOB') == 'execute'
         and env.get('GITHUB_WORKFLOW_REF') == REPOSITORY+'/'+WORKFLOW+'@refs/heads/main', 'PRODUCER')
    value = {key:env.get(key,'') for key in ('GITHUB_RUN_ID','GITHUB_RUN_NUMBER','GITHUB_RUN_ATTEMPT','GITHUB_SHA')}
    need(all(numeric(value[k]) for k in ('GITHUB_RUN_ID','GITHUB_RUN_NUMBER','GITHUB_RUN_ATTEMPT'))
         and re.fullmatch(SHA,value['GITHUB_SHA']), 'PRODUCER')
    return dict(repository=REPOSITORY,workflow=WORKFLOW,run_id=value['GITHUB_RUN_ID'],
        run_number=value['GITHUB_RUN_NUMBER'],attempt=value['GITHUB_RUN_ATTEMPT'],source_revision=value['GITHUB_SHA'])


def envelope(binding, provenance, fingerprint, now=None):
    now = int(time.time()) if now is None else seconds(now)
    return dict(format=2,receiver=fingerprint,issued_at=now,expires_at=now+TTL,
                producer=provenance,binding=binding)


def verify_artifact(api, expected, artifact_id, source_bytes, now=None):
    """Authenticate the producer independently, before exposing decrypted bytes.

    api is an authenticated fixed-repository GET transport, not envelope metadata.
    expected and source_bytes come from the reviewer's approved immutable Git ref.
    """
    now = int(time.time()) if now is None else seconds(now)
    exact(expected,('repository','workflow','run_id','attempt','source_revision'))
    need(expected['repository']==REPOSITORY and expected['workflow']==WORKFLOW
         and numeric(expected['run_id']) and numeric(expected['attempt'])
         and re.fullmatch(SHA,expected['source_revision']) and numeric(artifact_id),'PRODUCER')
    run = api('actions/runs/'+expected['run_id']+'/attempts/'+expected['attempt'])
    need(type(run) is dict and str(run.get('id'))==expected['run_id']
         and str(run.get('run_attempt'))==expected['attempt'] and run.get('event')=='workflow_dispatch'
         and run.get('head_branch')=='main' and run.get('head_sha')==expected['source_revision']
         and run.get('path')==WORKFLOW and run.get('status')=='completed' and run.get('conclusion')=='success'
         and run.get('repository',{}).get('full_name')==REPOSITORY
         and run.get('head_repository',{}).get('full_name')==REPOSITORY, 'PRODUCER')
    workflow = api('actions/workflows/corrected-stage-release.yml')
    need(workflow.get('path')==WORKFLOW and workflow.get('id')==run.get('workflow_id'), 'PRODUCER')
    jobs = api('actions/runs/'+expected['run_id']+'/attempts/'+expected['attempt']+'/jobs?per_page=100')
    matches = [j for j in jobs.get('jobs',[]) if j.get('name')=='deployment-principal-corrected-stage']
    need(jobs.get('total_count')==len(jobs.get('jobs',[])) and len(matches)==1
         and matches[0].get('conclusion')=='success'
         and str(matches[0].get('run_id'))==expected['run_id'], 'PRODUCER')
    steps = matches[0].get('steps',[])
    for name in ('Execute bounded corrected helper','Publish encrypted binding evidence'):
        found = [s for s in steps if s.get('name')==name]
        need(len(found)==1 and found[0].get('conclusion')=='success', 'PRODUCER')
    upload = next(s for s in steps if s.get('name')=='Publish encrypted binding evidence')
    uploaded_begin, uploaded_end = timestamp(upload.get('started_at')), timestamp(upload.get('completed_at'))
    need(uploaded_begin <= uploaded_end, 'PRODUCER')
    need(type(source_bytes) is dict and set(source_bytes)==set(PRODUCER_PATHS),'PRODUCER')
    for path,local in source_bytes.items():
        source = api('contents/'+path+'?ref='+expected['source_revision'])
        try: remote=base64.b64decode(source['content'].replace('\n',''),validate=True)
        except (ValueError,KeyError,TypeError): raise Refused('PRODUCER') from None
        need(source.get('encoding')=='base64' and remote==local,'PRODUCER')
    artifact = api('actions/artifacts/'+artifact_id)
    identity=artifact.get('workflow_run',{})
    need(str(artifact.get('id'))==artifact_id and artifact.get('name')=='clb91-binding-'+expected['run_id']+'-'+expected['attempt']
         and artifact.get('expired') is False and str(identity.get('id'))==expected['run_id']
         and identity.get('head_sha')==expected['source_revision'] and identity.get('head_branch')=='main'
         and type(artifact.get('size_in_bytes')) is int and 0 < artifact['size_in_bytes']<=LIMIT,'ARTIFACT')
    created,expires=timestamp(artifact.get('created_at')),timestamp(artifact.get('expires_at'))
    need(uploaded_begin <= created <= uploaded_end, 'PRODUCER')
    need(created<=now+300 and created+TTL>=now and now<expires,'EXPIRED')
    archive=api('actions/artifacts/'+artifact_id+'/zip')
    need(type(archive) is bytes and 0<len(archive)<=LIMIT
         and artifact.get('digest')=='sha256:'+hashlib.sha256(archive).hexdigest(),'ARTIFACT')
    try:
        with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
            entries=zipped.infolist()
            need(len(entries)==1 and entries[0].filename=='binding.pgp' and
                 64 < entries[0].file_size<=LIMIT and entries[0].compress_size<=LIMIT
                 and not entries[0].flag_bits & 1 and
                 not stat.S_ISLNK(entries[0].external_attr>>16),'ARTIFACT')
            data=zipped.read(entries[0])
    except (zipfile.BadZipFile, RuntimeError, OSError): raise Refused('ARTIFACT') from None
    receipt=dict(**expected,artifact_id=artifact_id,ciphertext_sha256=hashlib.sha256(data).hexdigest(),
                 run_number=str(run['run_number']),created_at=created,expires_at=min(expires,created+TTL))
    return data,receipt


def verify_envelope(value, receipt, fingerprint, validate_binding, implementation, incident, compose_path, now=None):
    now=int(time.time()) if now is None else seconds(now)
    exact(value,('format','receiver','issued_at','expires_at','producer','binding'))
    need(type(value['format']) is int and value['format']==2 and value['receiver']==fingerprint,'RECEIVER')
    need(seconds(value['issued_at'])<=now+300 and now<seconds(value['expires_at'])
         and 0 < value['expires_at']-value['issued_at']<=TTL
         and receipt['created_at']-300<=value['issued_at']<=receipt['created_at']+300,'EXPIRED')
    expected={k:receipt[k] for k in ('repository','workflow','run_id','run_number','attempt','source_revision')}
    need(value['producer']==expected,'PRODUCER')
    validate_binding(value['binding'],dict(incident,COMPOSE_PATH=compose_path),implementation)
    return value


def make_approval(value, receipt, run_number, reviewer, confirmed, now=None, expires_at=None):
    now=int(time.time()) if now is None else seconds(now)
    need(reviewer=='koteev-m' and confirmed is True,'APPROVAL_REQUIRED')
    need(numeric(run_number) and int(run_number)>int(receipt['run_number']),'APPROVAL_INVALID')
    expires_at=min(now+3600,value['expires_at'],receipt['expires_at']) if expires_at is None else seconds(expires_at)
    need(now<expires_at<=min(now+TTL,value['expires_at'],receipt['expires_at']),'EXPIRED')
    return dict(format=2,decision='approve',reviewer=reviewer,reviewed_at=now,expires_at=expires_at,
        evidence=receipt,receiver=value['receiver'],binding=value['binding'],
        execution=dict(action='resume-start',run_number=run_number,attempt='1',source_revision=receipt['source_revision']))


def approval(env, validate_binding, implementation, incident, compose_path, now=None):
    now=int(time.time()) if now is None else seconds(now)
    need(not env.get('AUTHORIZATION') and not env.get('CLB82_AUTHORIZED_ROOT_BINDING'),'LEGACY_AUTHORITY_FORBIDDEN')
    raw=env.get('CLB91_REVIEWED_ROOT_APPROVAL','')
    need(raw,'APPROVAL_REQUIRED')
    value=decode(raw)
    exact(value,('format','decision','reviewer','reviewed_at','expires_at','evidence','receiver','binding','execution'),'APPROVAL_INVALID')
    need(type(value['format']) is int and value['format']==2 and value['decision']=='approve'
         and value['reviewer']=='koteev-m' and re.fullmatch(FINGERPRINT,value['receiver'] or ''),'APPROVAL_INVALID')
    exact(value['execution'],('action','run_number','attempt','source_revision'),'APPROVAL_INVALID')
    exact(value['evidence'],('repository','workflow','run_id','attempt','source_revision','artifact_id',
          'ciphertext_sha256','run_number','created_at','expires_at'),'APPROVAL_INVALID')
    proof=value['evidence']; target=value['execution']
    producer(env)
    need(proof['repository']==REPOSITORY and proof['workflow']==WORKFLOW
         and all(numeric(proof[k]) for k in ('run_id','attempt','artifact_id','run_number'))
         and re.fullmatch(SHA,proof['source_revision']) and re.fullmatch(r'[0-9a-f]{64}',proof['ciphertext_sha256']),'APPROVAL_INVALID')
    need(target==dict(action='resume-start',run_number=env.get('GITHUB_RUN_NUMBER'),attempt='1',source_revision=env.get('GITHUB_SHA'))
         and env.get('GITHUB_RUN_ATTEMPT')=='1' and proof['source_revision']==target['source_revision']
         and int(target['run_number'])>int(proof['run_number']),'APPROVAL_INVALID')
    need(seconds(proof['created_at'])<=seconds(value['reviewed_at'])<=now+300
         and now<seconds(value['expires_at'])<=seconds(proof['expires_at'])
         and 0<proof['expires_at']-proof['created_at']<=TTL
         and 0<value['expires_at']-value['reviewed_at']<=TTL,'EXPIRED')
    validate_binding(value['binding'],dict(incident,SSH_USER=env.get('SSH_USER',''),COMPOSE_PATH=compose_path),implementation)
    return value


def private_authority(value):
    """Exact approved metadata; its digest is retained in the durable claim."""
    return {k:value[k] for k in ('reviewed_at','expires_at','execution','evidence','receiver')}


def write_private(directory, name, data, safe_root):
    need(name in ('review.json','approval.json','binding.pgp'), 'PRIVATE_BOUNDARY')
    base=safe_root(directory)
    descriptor=None
    identity=None
    failure=None
    try:
        need(os.fstat(base).st_uid==os.geteuid() and stat.S_IMODE(os.fstat(base).st_mode)==0o700,'PRIVATE_BOUNDARY')
        descriptor=os.open(name,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600,dir_fd=base)
        identity=file_identity(os.fstat(descriptor))[:6]
        need(type(data) is bytes and len(data)<=LIMIT)
        offset=0
        while offset<len(data):
            count=os.write(descriptor,data[offset:]);need(count>0,'CLEANUP');offset+=count
        os.fsync(descriptor);os.fsync(base)
        need(file_identity(os.stat(directory,follow_symlinks=False))[:6] == file_identity(os.fstat(base))[:6], 'PRIVATE_BOUNDARY')
        complete=file_identity(os.fstat(descriptor))
    except FileExistsError:
        failure=Refused('REPLAY')
    except BaseException as error:
        failure=error
    finally:
        if descriptor is not None:
            try: os.close(descriptor)
            except OSError: failure=Refused('CLEANUP')
        try: os.close(base)
        except OSError: failure=Refused('CLEANUP')
    if failure is not None:
        if identity is not None:
            cleanup=safe_root(directory)
            try:
                need(file_identity(os.stat(name,dir_fd=cleanup,follow_symlinks=False))[:6] == identity,'CLEANUP')
                os.unlink(name,dir_fd=cleanup);os.fsync(cleanup)
            finally: os.close(cleanup)
        raise failure
    return complete
