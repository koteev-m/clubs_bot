#!/usr/bin/env -S python3 -I -S -B
"""Human-only Mac private review; fixed GitHub reads, no provisioning/execution.

Never invoke with an actual keyring from an automated coding session. Public
output is a fixed category; only mode-0600 files inside the owner's mode-0700
directory may contain binding/approval. GitHub auth and source proof precede
decryption. Successful decrypt never approves a candidate.
"""
import sys
if not (sys.flags.isolated and sys.flags.no_site):
    raise SystemExit('clb91-review:v=2 result=blocked category=PYTHON_STARTUP_INVALID')
import argparse
import contextlib
import fcntl
import json
import os
TLS_OVERRIDES=('SSL_CERT_FILE','SSL_CERT_DIR','SSLKEYLOGFILE','OPENSSL_CONF',
               'OPENSSL_CONF_INCLUDE','OPENSSL_MODULES','REQUESTS_CA_BUNDLE','CURL_CA_BUNDLE')
if any(name in os.environ for name in TLS_OVERRIDES):
    raise SystemExit('clb91-review:v=2 result=blocked category=PRODUCER')
import hashlib
from pathlib import Path
import re
import resource
import signal
import stat
import time
import types
import urllib.error
import urllib.parse
import urllib.request

def source(name):
    path=Path(__file__).resolve().with_name(name)
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    with os.fdopen(fd,'rb') as stream:
        result=types.ModuleType(name);result.__file__=str(path)
        exec(compile(stream.read(),str(path),'exec'),result.__dict__)
    return result

runner=source('corrected-stage-release.py')
review=runner._review

class QuietArguments(argparse.ArgumentParser):
    def error(self, _): raise review.Refused('INPUT')


@contextlib.contextmanager
def private_input(path, limit=review.LIMIT):
    root=runner.open_canonical_root(str(Path(path).parent))
    fd=None
    try:
        fd=os.open(Path(path).name,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=root)
        before=os.fstat(fd)
        review.need(stat.S_ISREG(before.st_mode) and before.st_uid==os.geteuid()
                    and before.st_nlink==1 and stat.S_IMODE(before.st_mode)==0o600
                    and 0 < before.st_size<=limit,'PRIVATE_BOUNDARY')
        data=bytearray()
        while len(data)<=limit:
            chunk=os.read(fd,min(65536,limit+1-len(data)))
            if not chunk: break
            data.extend(chunk)
        review.need(len(data)==before.st_size,'PRIVATE_BOUNDARY')
        yield bytes(data)
        review.need(review.file_identity(os.fstat(fd))==review.file_identity(before)
            and review.file_identity(os.stat(Path(path).name,dir_fd=root,follow_symlinks=False))==review.file_identity(before), 'PRIVATE_BOUNDARY')
    finally:
        if fd is not None: os.close(fd)
        os.close(root)


HTTP_GET = r"""
import sys,os,json,ssl,urllib.request,urllib.error,urllib.parse,re,resource
resource.setrlimit(resource.RLIMIT_CORE,(0,0))
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*_):return None
value=json.loads(sys.stdin.buffer.read(4097));token=value['token'];route=value['route']
assert re.fullmatch(r'[A-Za-z0-9_]{20,256}',token)
assert re.fullmatch(r'[A-Za-z0-9_./?=&-]{1,512}',route)
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=ssl.create_default_context()),NoRedirect())
request=urllib.request.Request('https://api.github.com/repos/koteev-m/clubs_bot/'+route,
 headers={'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2026-03-10','User-Agent':'CLB91-private-review'},method='GET')
try:response=opener.open(request,timeout=20)
except urllib.error.HTTPError as error:
 try:
  assert route.endswith('/zip') and error.code==302
  location=error.headers.get('Location','');parsed=urllib.parse.urlsplit(location)
  assert parsed.scheme=='https' and not parsed.username and not parsed.password and parsed.port in (None,443)
  assert re.fullmatch(r'[a-z0-9-]+\.blob\.core\.windows\.net',parsed.hostname or '')
 finally:error.close()
 response=opener.open(urllib.request.Request(location,headers={'User-Agent':'CLB91-private-review'}),timeout=20)
with response:
 assert response.status==200
 data=response.read(1048577)
assert len(data)<=1048576
sys.stdout.buffer.write(data)
"""


class GitHub:
    """Fixed repository GET; existing process-group capture bounds total lifetime."""
    def __init__(self, token, expected, artifact_id):
        review.need(not any(name in os.environ for name in TLS_OVERRIDES),'PRODUCER')
        review.need(type(token) is str and re.fullmatch(r'[A-Za-z0-9_]{20,256}',token),'PRODUCER')
        self.token=token
        run=expected['run_id'];attempt=expected['attempt'];sha=expected['source_revision']
        self.allowed={'actions/runs/'+run+'/attempts/'+attempt,
            'actions/runs/'+run+'/attempts/'+attempt+'/jobs?per_page=100',
            'actions/workflows/corrected-stage-release.yml','actions/artifacts/'+artifact_id,
            'actions/artifacts/'+artifact_id+'/zip',
            *('contents/'+path+'?ref='+sha for path in review.PRODUCER_PATHS)}

    def __call__(self, route):
        review.need(route in self.allowed,'PRODUCER')
        payload=review.encoded(dict(token=self.token,route=route))
        code,data=runner.capture([sys.executable,'-I','-S','-B','-c',HTTP_GET],payload,
            env={'PATH':'/usr/bin:/bin','LC_ALL':'C'},timeout=30,limit=1048576)
        review.need(code==0,'PRODUCER')
        if route.endswith('/zip'):return data
        try:return json.loads(data,object_pairs_hook=review.unique)
        except (ValueError,UnicodeError):raise review.Refused('PRODUCER') from None


def approved_source(revision):
    """Only reviewer-approved immutable local Git objects, never API TOFU."""
    review.need(re.fullmatch(review.SHA,revision),'PRODUCER')
    env={'PATH':os.environ.get('PATH',os.defpath),'LC_ALL':'C','GIT_NO_REPLACE_OBJECTS':'1','GIT_OPTIONAL_LOCKS':'0',
         'GIT_CONFIG_NOSYSTEM':'1','GIT_CONFIG_GLOBAL':'/dev/null','GIT_CONFIG_SYSTEM':'/dev/null','GIT_LITERAL_PATHSPECS':'1'}
    def git(*args):
        code,data=runner.capture(['git','--no-replace-objects','-C',str(runner.ROOT),*args],env=env,limit=1048576)
        review.need(code==0,'PRODUCER');return data
    review.need(git('cat-file','-t',revision)==b'commit\n','PRODUCER')
    result={}
    for path in (*review.PRODUCER_PATHS,'scripts/deploy/review-root-binding.py'):
        entry=git('ls-tree','--full-tree','-z',revision,'--',path)
        match=re.fullmatch(rb'100644 blob ([0-9a-f]{40})\t'+re.escape(path.encode())+rb'\x00',entry)
        review.need(match is not None,'PRODUCER')
        data=git('cat-file','blob',match[1].decode())
        review.need(hashlib.sha1(('blob '+str(len(data))+'\0').encode()+data).hexdigest()==match[1].decode(),'PRODUCER')
        # The actual local consumer must be the independently reviewed source.
        review.need((runner.ROOT/path).read_bytes()==data,'PRODUCER')
        if path in review.PRODUCER_PATHS: result[path]=data
    return result


@contextlib.contextmanager
def review_lock(directory):
    base=runner.open_canonical_root(directory);fd=None
    try:
        info=os.fstat(base)
        review.need(info.st_uid==os.geteuid() and stat.S_IMODE(info.st_mode)==0o700,'PRIVATE_BOUNDARY')
        fd=os.open('.review.lock',os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW|os.O_NONBLOCK,0o600,dir_fd=base)
        info=os.fstat(fd)
        review.need(stat.S_ISREG(info.st_mode) and info.st_uid==os.geteuid() and info.st_nlink==1
                    and stat.S_IMODE(info.st_mode)==0o600,'PRIVATE_BOUNDARY')
        try: fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError: raise review.Refused('BUSY') from None
        yield
        review.need(review.file_identity(os.stat('.review.lock',dir_fd=base,follow_symlinks=False))==review.file_identity(os.fstat(fd)), 'PRIVATE_BOUNDARY')
    finally:
        if fd is not None: os.close(fd)
        os.close(base)


def confirmation(receipt, run_number):
    # Decryption success and an argument/flag cannot substitute for this action.
    # This proves local operator intent, NOT GitHub account ownership. Protected
    # secret provisioning remains an independent account-authority boundary.
    phrase='APPROVE '+receipt['artifact_id']+' '+receipt['ciphertext_sha256']+' '+run_number
    fd=os.open('/dev/tty',os.O_RDWR|os.O_NOFOLLOW)
    with os.fdopen(fd,'r+',buffering=1) as terminal:
        review.need(os.isatty(terminal.fileno()),'APPROVAL_REQUIRED')
        terminal.write('After reviewing the full private binding and provenance, type: '+phrase+'\n')
        review.need(terminal.readline(256).rstrip('\n')==phrase,'APPROVAL_REQUIRED')


def decide(args, publication):
    """Only own output is rolled back if a later lease/cleanup gate fails."""
    def publish(name,data):
        path=Path(args.directory)/name
        # No subprocess can inherit this mask. Record ownership before a queued
        # catchable cancellation can arrive; outer failure handling then revokes it.
        previous=signal.pthread_sigmask(signal.SIG_BLOCK,{signal.SIGHUP,signal.SIGINT,signal.SIGTERM})
        try:
            identity=review.write_private(args.directory,name,data,runner.open_canonical_root)
            publication.append((str(path),identity))
        finally: signal.pthread_sigmask(signal.SIG_SETMASK,previous)
    expected=dict(repository=review.REPOSITORY,workflow=review.WORKFLOW,run_id=args.run_id,
                  attempt=args.attempt,source_revision=args.source_revision)
    implementation=args.implementation.split(':')
    impl=dict(revision=implementation[0],blob=implementation[1],sha256=implementation[2],size=runner.IMPLEMENTATION_SIZE,
              path=runner.HELPER_PATH,mode='100644',type='blob')
    with review_lock(args.directory):
        sources=approved_source(args.source_revision)
        ciphertext,receipt=review.verify_artifact(GitHub(os.environ.get('GITHUB_TOKEN',''),expected,args.artifact_id),
                                                 expected,args.artifact_id,sources)
        with private_input(args.profile) as profile, private_input(args.public_key,65536) as public_key:
            with review.Crypto(profile,public_key,args.fingerprint,runner.capture,runner.open_canonical_root,args.directory) as crypto:
                value=review.decode(crypto.decrypt(ciphertext,args.keyring))
                review.verify_envelope(value,receipt,args.fingerprint,runner.validate_binding,impl,runner.INCIDENT,runner.COMPOSE_PATH)
        document=dict(envelope=value,evidence=receipt)
        if args.action=='review':
            review.need(args.run_number is None,'INPUT')
            publish('review.json',review.encoded(document))
            return 'REVIEW_REQUIRED'
        with private_input(str(Path(args.directory)/'review.json')) as reviewed:
            review.need(review.decode(reviewed)==document,'APPROVAL_INVALID')
            if args.action=='reject':
                publish('approval.json',review.encoded({'format':2,'decision':'reject'}))
                return 'REJECTED'
            review.need(review.numeric(args.run_number),'INPUT')
            confirmation(receipt,args.run_number)
            approved=review.make_approval(value,receipt,args.run_number,'koteev-m',True)
        publish('approval.json',review.encoded(approved))
        return 'APPROVED_PRIVATE'


def revoke(publication):
    for path,identity in reversed(publication):
        base=runner.open_canonical_root(str(Path(path).parent))
        try:
            review.need(review.file_identity(os.stat(Path(path).name,dir_fd=base,follow_symlinks=False))==identity,'CLEANUP')
            os.unlink(Path(path).name,dir_fd=base);os.fsync(base)
        finally:os.close(base)
    publication.clear()


def main(publication=None):
    resource.setrlimit(resource.RLIMIT_CORE,(0,0));os.umask(0o077)
    for watched in (signal.SIGHUP,signal.SIGINT,signal.SIGTERM): signal.signal(watched,runner.interrupted)
    parser=QuietArguments(add_help=False)
    parser.add_argument('action',choices=('review','approve','reject'))
    for name in ('directory','run-id','attempt','source-revision','artifact-id','implementation','profile','public-key','fingerprint','keyring'):
        parser.add_argument('--'+name,required=True)
    parser.add_argument('--run-number')
    args=parser.parse_args()
    review.need(sys.platform=='darwin','CRYPTO_RUNTIME')
    review.need(review.numeric(args.run_id) and review.numeric(args.attempt) and review.numeric(args.artifact_id)
                and re.fullmatch(review.SHA,args.source_revision),'INPUT')
    implementation=args.implementation.split(':')
    review.need(len(implementation)==3 and re.fullmatch(runner.IMPLEMENTATION_PATTERN,args.implementation),'INPUT')
    publication=[] if publication is None else publication
    try:
        return decide(args,publication)
    except BaseException:
        revoke(publication)
        raise


if __name__=='__main__':
    publication=[]
    try:
        result=main(publication)
        # All leases/agents are closed. Commit the finite public outcome while
        # catchable signals remain blocked until process exit, with no new work.
        signal.pthread_sigmask(signal.SIG_BLOCK,{signal.SIGHUP,signal.SIGINT,signal.SIGTERM})
        print('clb91-review:v=2 result=ok category='+result,flush=True)
    except review.Refused as error:
        try:revoke(publication)
        except BaseException:error=review.Refused('CLEANUP')
        print('clb91-review:v=2 result=blocked category='+str(error));sys.exit(1)
    except BaseException:
        try:revoke(publication)
        except BaseException:pass
        print('clb91-review:v=2 result=blocked category=INPUT');sys.exit(1)
