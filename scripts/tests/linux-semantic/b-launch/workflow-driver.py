"""One manual disposable-VM operation. No stage, Engine or caller command API.

Public approval is provisioned separately after independent review/publication.
The fixed bootstrap executes only captured bytes matching that approved closure.
"""
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import selectors
import signal
import stat
import subprocess
import sys
import time
import types

HERE = 'scripts/tests/linux-semantic/b-launch/'
WORKFLOW = '.github/workflows/clb91-b-native-acceptance.yml'
NAME = 'CLB-91 B Native Acceptance'
REPO = 'koteev-m/clubs_bot'
DRIVER = HERE+'workflow-driver.py'
SOURCES = tuple(HERE+n for n in ('native-entry.py','native-protocol.py','native-harness.py',
                                'prototype.py','native-sources.json'))+(DRIVER,WORKFLOW)
MANIFEST = HERE+'workflow-sources.json'
ROOT = '/opt/clb91-b-native-sources'
ARTIFACT = 'clb91-b-native-result.json'
CONTEXT_KEYS = ('GITHUB_REPOSITORY','GITHUB_WORKFLOW','GITHUB_WORKFLOW_REF','GITHUB_SHA',
    'GITHUB_RUN_ID','GITHUB_RUN_NUMBER','GITHUB_RUN_ATTEMPT','GITHUB_EVENT_NAME','GITHUB_REF',
    'GITHUB_ACTOR','GITHUB_TRIGGERING_ACTOR','RUNNER_OS','RUNNER_ARCH','RUNNER_ENVIRONMENT')

# Fixed reviewed code, NOT caller-supplied script/path/command. Public hash and
# strict JSON context are the only data arguments; they are never shell-expanded.
# The initial Python/sudo/runner trust still requires independent host approval.
BOOTSTRAP = '''import hashlib,os,re,stat,sys
try:
 def identity(a):return (a.st_dev,a.st_ino,a.st_uid,a.st_gid,a.st_mode,a.st_nlink,a.st_size,a.st_mtime_ns,a.st_ctime_ns)
 assert len(sys.argv)==3 and re.fullmatch('[0-9a-f]{64}',sys.argv[1]) and len(sys.argv[2])<=4096
 path='scripts/tests/linux-semantic/b-launch/workflow-driver.py'
 fd=os.open(path,os.O_RDONLY|os.O_NONBLOCK|os.O_NOFOLLOW)
 try:
  a=os.fstat(fd)
  assert stat.S_ISREG(a.st_mode) and a.st_nlink==1 and not a.st_mode&0o022 and 0<a.st_size<=65536
  assert a.st_uid in (0,os.stat('.').st_uid)
  raw=bytearray()
  while len(raw)<=65536:
   part=os.read(fd,min(65536,65537-len(raw)))
   if not part:break
   raw.extend(part)
  assert len(raw)==a.st_size and identity(os.fstat(fd))==identity(a) and identity(os.stat(path,follow_symlinks=False))==identity(a)
  assert hashlib.sha256(raw).hexdigest()==sys.argv[1]
 finally:os.close(fd)
 ns={'__name__':'approved_native_driver'}
 exec(compile(bytes(raw),'<approved-native-driver>','exec'),ns)
 code=ns['root_operation'](sys.argv[2])
except BaseException:code=1
sys.exit(code)
'''


class Refused(Exception): pass


def need(value):
    if not value: raise Refused() from None


def unique(pairs):
    value = {}
    for key,item in pairs:
        need(key not in value); value[key] = item
    return value


def decode(raw):
    need(type(raw) is str and 0 < len(raw) <= 4096)
    return json.loads(raw,object_pairs_hook=unique,
        parse_constant=lambda _:(_ for _ in ()).throw(Refused()))


def approval(raw,context,requested,now):
    value = decode(raw)
    need(type(value) is dict and set(value) == {'format','repository','workflow','revision',
        'closure_sha256','native_sha256','run_number','expires_at'})
    need(type(value['format']) is int and value['format'] == 1)
    need(value['repository'] == REPO and value['workflow'] == WORKFLOW)
    need(type(value['revision']) is str and re.fullmatch('[0-9a-f]{40}',value['revision']))
    need(value['revision'] == requested == context['GITHUB_SHA'])
    for key in ('closure_sha256','native_sha256'):
        need(type(value[key]) is str and re.fullmatch('[0-9a-f]{64}',value[key]))
    need(type(value['run_number']) is str and re.fullmatch('[1-9][0-9]{0,19}',value['run_number']))
    need(value['run_number'] == context['GITHUB_RUN_NUMBER'])
    need(type(value['expires_at']) is int and now < value['expires_at'] <= now+3600)
    expected = dict(GITHUB_REPOSITORY=REPO,GITHUB_WORKFLOW=NAME,
        GITHUB_WORKFLOW_REF=REPO+'/'+WORKFLOW+'@refs/heads/main',GITHUB_REF='refs/heads/main',
        GITHUB_EVENT_NAME='workflow_dispatch',GITHUB_RUN_ATTEMPT='1',
        GITHUB_ACTOR='koteev-m',GITHUB_TRIGGERING_ACTOR='koteev-m',
        RUNNER_OS='Linux',RUNNER_ARCH='X64',RUNNER_ENVIRONMENT='github-hosted')
    need(set(context) == set(CONTEXT_KEYS))
    need(all(type(item) is str and len(item) <= 256 for item in context.values()))
    need(all(context[key] == item for key,item in expected.items()))
    need(re.fullmatch('[1-9][0-9]{0,19}',context['GITHUB_RUN_ID']))
    return value


def identity(info):
    return (info.st_dev,info.st_ino,info.st_uid,info.st_gid,info.st_mode,
            info.st_nlink,info.st_size,info.st_mtime_ns,info.st_ctime_ns)


def captured(path,bound):
    # Open every fixed relative edge without following a symlink. No caller path.
    need(path in SOURCES+(MANIFEST,))
    parent = os.open('.',os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    try:
        for part in path.split('/')[:-1]:
            child = os.open(part,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=parent)
            os.close(parent); parent = child
        name = path.split('/')[-1]
        fd = os.open(name,os.O_RDONLY|os.O_NONBLOCK|os.O_NOFOLLOW,dir_fd=parent)
        try:
            info = os.fstat(fd)
            need(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and not info.st_mode & 0o022)
            need(info.st_uid in (0,os.stat('.').st_uid))
            need(0 < info.st_size <= bound)
            raw = bytearray()
            while len(raw) <= bound:
                part = os.read(fd,min(65536,bound+1-len(raw)))
                if not part: break
                raw.extend(part)
            need(len(raw) == info.st_size and len(raw) <= bound)
            need(identity(os.fstat(fd)) == identity(info))
            need(identity(os.stat(name,dir_fd=parent,follow_symlinks=False)) == identity(info))
            return bytes(raw)
        finally: os.close(fd)
    finally: os.close(parent)


def closure(accepted):
    raw = captured(MANIFEST,4096)
    need(hashlib.sha256(raw).hexdigest() == accepted['closure_sha256'])
    value = decode(raw.decode('ascii'))
    need(type(value) is dict and set(value) == {'format','files'})
    need(type(value['format']) is int and value['format'] == 1)
    need(type(value['files']) is dict and set(value['files']) == set(SOURCES))
    buffers = {}
    for name in SOURCES:
        buffers[name] = captured(name,131072)
        need(hashlib.sha256(buffers[name]).hexdigest() == value['files'][name])
    need(hashlib.sha256(buffers[HERE+'native-sources.json']).hexdigest() == accepted['native_sha256'])
    return value,buffers


def host_tools():
    """Read-only bootstrap checks, not supplier/native attestation."""
    need(sys.platform == 'linux' and platform.machine() == 'x86_64')
    need(sys.flags.isolated and sys.flags.no_site and sys.dont_write_bytecode)
    leases = []
    for name in ('/usr/bin/sudo','/usr/bin/python3'):
        edge = os.lstat(name)
        need(edge.st_uid == 0)
        target = Path(name).resolve()
        for parent in target.parents:
            info = parent.stat()
            need(info.st_uid == 0 and stat.S_ISDIR(info.st_mode) and not info.st_mode & 0o022)
        fd = os.open(target,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
        try:
            info = os.fstat(fd)
            need(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022)
            header = os.read(fd,64)
            need(header[:6] == b'\x7fELF\x02\x01' and header[18:20] == b'\x3e\x00')
            need(identity(os.fstat(fd)) == identity(info))
            leases.append((name,identity(edge),str(target),identity(info)))
        finally: os.close(fd)
    return leases


def recheck_tools(leases):
    for name,edge,target,expected in leases:
        need(identity(os.lstat(name)) == edge and str(Path(name).resolve()) == target)
        need(identity(os.stat(target,follow_symlinks=False)) == expected)


def module(name,raw):
    value = types.ModuleType(name); sys.modules[name] = value
    exec(compile(raw,'<verified-native-source>','exec'),value.__dict__)
    return value


def root_operation(raw):
    # This entry is dormant locally. All reads/preflight precede root fixture writes.
    need(sys.platform == 'linux' and os.getuid() == os.geteuid() == 0)
    request = decode(raw)
    need(type(request) is dict and set(request) == {'approval','context','requested'})
    context = request['context']
    accepted = approval(request['approval'],context,request['requested'],int(time.time()))
    _,buffers = closure(accepted)
    # No token/secret/sudo environment is handed into the synthetic worker.
    os.environ.clear(); os.environ.update(context)
    os.environ['PATH'] = '/usr/bin:/bin'
    E = module('clb91_workflow_entry',buffers[HERE+'native-entry.py'])
    R = module('clb91_workflow_protocol',buffers[HERE+'native-protocol.py'])
    H = module('clb91_workflow_native',buffers[HERE+'native-harness.py'])
    P = module('clb91_workflow_prototype',buffers[HERE+'prototype.py'])
    H.P,H.R = P,R
    result = R.new(accepted['revision'],accepted['native_sha256'])
    result['prerequisites']['source'] = 'PASS'
    try: H.preflight(result)  # no mkdir/chown/mount before this strict gate
    except H.Prerequisite as failure:
        result.update(verdict='FAIL',reason=failure.name)
        data = R.encode(result)
        need(os.write(1,data) == len(data))
        return 1
    need(not os.path.lexists(ROOT))
    owned = []
    clean = False
    code,data = 1,b''
    def remember(path): owned.append((path,identity(os.lstat(path))))
    old = {}
    def stop(_sig,_frame): raise Refused()
    try:
        for sig in (signal.SIGINT,signal.SIGTERM,signal.SIGHUP):
            old[sig] = signal.getsignal(sig); signal.signal(sig,stop)
        os.mkdir(ROOT,0o700); remember(ROOT)
        for name in E.NAMES+('native-sources.json',):
            path = ROOT+'/'+name
            fd = os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o400)
            try:
                content = buffers[HERE+name]
                need(os.write(fd,content) == len(content))
                os.fchmod(fd,0o400)
            finally: os.close(fd)
            remember(path)
        # Before root native handoff recheck expiry, exact closure and root copy.
        approval(request['approval'],context,request['requested'],int(time.time()))
        closure(accepted)
        E.__file__ = ROOT+'/native-entry.py'
        read_fd,write_fd = os.pipe(); saved = os.dup(1)
        try:
            os.dup2(write_fd,1); os.close(write_fd)
            code = E.main(['--source-contract',accepted['native_sha256'],
                          '--revision',accepted['revision'],'--execute-disposable-native'])
        finally: os.dup2(saved,1); os.close(saved)
        try: data = os.read(read_fd,4097)
        finally: os.close(read_fd)
        result = R.parse(data,code,accepted['revision'],accepted['native_sha256'])
        need(result['run_id'] in ('NOT_RUN',context['GITHUB_RUN_ID']))
    finally:
        clean = True
        for path,expected in reversed(owned):
            try:
                current = identity(os.lstat(path))
                # Directory contents change its times/size, never its object/owner/mode.
                if path == ROOT: need(current[:6] == expected[:6])
                else: need(current == expected)
                if path == ROOT: os.rmdir(path)
                else: os.unlink(path)
            except BaseException: clean = False
        for sig,handler in old.items():
            try: signal.signal(sig,handler)
            except BaseException: clean = False
        need(clean and not os.path.lexists(ROOT))
    # No native PASS leaves the process until source-copy cleanup is confirmed.
    need(os.write(1,data) == len(data))
    return code


def capture(argv,cancelled):
    """Bounded stdout/stderr and exact owned sudo process; never print internals.

    Root operation has its own 120s alarm. A lost/unreaped process is refusal,
    with no artifact and mandatory disposable VM teardown, never a retry.
    """
    need(argv[:7] == ['/usr/bin/sudo','-n','--','/usr/bin/python3','-I','-S','-B'])
    need(len(argv) == 11 and argv[7:9] == ['-c',BOOTSTRAP])
    proc = subprocess.Popen(argv,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,start_new_session=True,env={'PATH':'/usr/bin:/bin','LC_ALL':'C'})
    selector = selectors.DefaultSelector(); output,errors = bytearray(),bytearray()
    try:
        for stream,buffer in ((proc.stdout,output),(proc.stderr,errors)):
            os.set_blocking(stream.fileno(),False); selector.register(stream,selectors.EVENT_READ,buffer)
        end = time.monotonic()+150
        while selector.get_map():
            need(not cancelled() and time.monotonic() < end)
            for key,_ in selector.select(.05):
                part = os.read(key.fd,4097)
                if not part: selector.unregister(key.fileobj)
                else:
                    key.data.extend(part)
                    need(len(output) <= 4096 and len(errors) <= 4096)
        need(not cancelled())
        code = proc.wait(timeout=1)
        need(not errors)
        return code,bytes(output)
    finally:
        selector.close()
        # No PID is signaled after reap. sudo may relay TERM to its child; this
        # is not an OS-enforced descendant cleanup guarantee.
        if proc.poll() is None:
            try: proc.terminate()
            except ProcessLookupError: pass
            try: proc.wait(timeout=2)
            except subprocess.TimeoutExpired: raise Refused() from None
        proc.stdout.close(); proc.stderr.close()


def publish(data,code,protocol,accepted,run_id,cancelled):
    need(type(data) is bytes and 0 < len(data) <= 4096)
    result = protocol.parse(data,code,accepted['revision'],accepted['native_sha256'])
    need(result['run_id'] in ('NOT_RUN',run_id))
    need(result['verdict'] in ('PASS','FAIL'))
    # Runner-owned file-command path is used only unprivileged, no caller input.
    output = os.environ['GITHUB_OUTPUT']
    parent = Path(os.environ['RUNNER_TEMP']).resolve()/'_runner_file_commands'
    need(Path(output).parent.resolve() == parent and Path(output).name.startswith('set_output_'))
    need(not cancelled())
    marker = b'evidence=validated\n'
    owned,command,initial = None,None,None
    try:
        command = os.open(output,os.O_RDWR|os.O_APPEND|os.O_NOFOLLOW|os.O_NONBLOCK)
        info = os.fstat(command)
        need(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid() and info.st_nlink == 1)
        initial = info.st_size
        fd = os.open(ARTIFACT,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
        try:
            owned = os.fstat(fd)
            need(os.write(fd,data) == len(data))
        finally: os.close(fd)
        need(not cancelled())
        need(os.write(command,marker) == len(marker))
        need(not cancelled())
    except BaseException:
        # Roll back only our exact append/object, never someone else's data.
        if command is not None and initial is not None:
            current = os.fstat(command)
            suffix = os.pread(command,20,initial)
            if current.st_size == initial+len(suffix) and marker.startswith(suffix):
                os.ftruncate(command,initial)
        if owned is not None:
            current = os.lstat(ARTIFACT)
            need((current.st_dev,current.st_ino) == (owned.st_dev,owned.st_ino))
            os.unlink(ARTIFACT)
        raise
    finally:
        if command is not None: os.close(command)


def main():
    interrupted,previous = [],{}
    running = False
    def stop(_sig,_frame):
        interrupted.append(True)
        # During capture let its bounded supervisor terminate/reap. Once it
        # returns, signal cancellation must abort parsing/publication directly.
        if not running: raise Refused()
    try:
        need(sys.argv[1:] == [] and os.getuid() == os.geteuid() != 0)
        context = {name:os.environ[name] for name in CONTEXT_KEYS}
        raw = os.environ.get('CLB91_B_NATIVE_APPROVAL','')
        requested = os.environ.get('CLB91_APPROVED_REVISION','')
        accepted = approval(raw,context,requested,int(time.time()))
        model,buffers = closure(accepted)
        tools = host_tools()
        R = module('clb91_workflow_result',buffers[HERE+'native-protocol.py'])
        request = json.dumps(dict(approval=raw,context=context,requested=requested),separators=(',',':'))
        need(len(request) <= 4096)
        for sig in (signal.SIGINT,signal.SIGTERM,signal.SIGHUP):
            previous[sig] = signal.getsignal(sig)
            signal.signal(sig,stop)
        argv = ['/usr/bin/sudo','-n','--','/usr/bin/python3','-I','-S','-B','-c',BOOTSTRAP,
                model['files'][DRIVER],request]
        recheck_tools(tools)
        running = True
        try: code,data = capture(argv,lambda:bool(interrupted))
        finally: running = False
        need(not interrupted)
        result = R.parse(data,code,accepted['revision'],accepted['native_sha256'])
        need(result['run_id'] in ('NOT_RUN',context['GITHUB_RUN_ID']))
        closure(accepted)  # drift after execution prevents artifact/PASS
        need(not interrupted)
        publish(data,code,R,accepted,context['GITHUB_RUN_ID'],lambda:bool(interrupted))
        need(not interrupted)
        print('CLB91_B_NATIVE_'+result['verdict'])
        return 0 if result['verdict'] == 'PASS' else 1
    except BaseException:
        print('CLB91_B_NATIVE_REFUSED')
        return 1
    finally:
        for sig,handler in previous.items(): signal.signal(sig,handler)


if __name__ == '__main__': raise SystemExit(main())
