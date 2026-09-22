"""Synthetic FD fixture only; not part of either credentialed source closure."""
import hashlib
import os
from pathlib import Path
from runtime_inventory_fixtures import FixtureOS as BaseOS


class FixtureOS(BaseOS):
    def open(self, path, flags, *args, **kwargs):
        if path == '/proc/self/maps':
            assert not flags & (os.O_WRONLY|os.O_RDWR|os.O_CREAT)
            self.opens.append(path)
            fd=os.open(Path(self.root)/'proc/self/maps',flags)
            self.owned.add(fd)
            return fd
        return super().open(path,flags,*args,**kwargs)

    def geteuid(self): return 0  # Explicit owner substitution; no production option.


def make_fixture(root, reference, packages):
    root=Path(root)
    def put(path,data,mode=0o644):
        p=root/path.lstrip('/'); p.parent.mkdir(parents=True,exist_ok=True)
        p.write_bytes(data); p.chmod(mode); return p
    reference={**reference,'files':dict(reference['files'])}
    for name in reference['files']:
        data=b'PRIVATE synthetic non-executable artifact\n'+name.encode()
        put(name,data); reference['files'][name]=hashlib.sha256(data).hexdigest()
    for name,target in reference['aliases'].items():
        if name.startswith('/bin/'): name='/usr/bin/'+name.rsplit('/',1)[1]
        p=root/name.lstrip('/')
        if str(p)==str(root/target.lstrip('/')): continue
        p.symlink_to(target.rsplit('/',1)[1])
    (root/'bin').symlink_to('usr/bin')
    put('/var/lib/dpkg/status',b'\n\n'.join(('Package: '+n+'\nStatus: install ok installed\nVersion: '+v+'\nArchitecture: amd64\nDescription: PRIVATE ignored\n').encode() for n,v in packages.items()))
    put('/proc/self/maps',b'00100000-00200000 r-xp 00000000 00:01 123 /usr/bin/python3.12\n')
    (root/'run/user/0').mkdir(parents=True); (root/'run/user/0').chmod(0o700)
    return put,reference


# Only synthetic subprocesses use these diagnostics. No production transport or
# private-channel redaction is changed; messages contain fixed categories/counts.
def failure(stage, code=None, stdout=b'', stderr=b'', *, authenticated=False, result=None, reason=None, cleanup_failed=False):
    message=(f'synthetic-bootstrap stage={stage} exit={code if code is not None else "not_started"} '
             f'stdout_bytes={len(stdout)} stderr_bytes={len(stderr)} authenticated={"yes" if authenticated else "no"}')
    if result is not None: message += f' result={result} reason={reason}'
    if cleanup_failed: message += ' cleanup=failed'
    raise AssertionError(message) from None


def run_synthetic(argv, payload, limit, *, timeout=110):
    """Bound both pipes while retaining the child until owned-group cleanup."""
    import selectors
    import signal
    import subprocess
    import time
    stdout, stderr = bytearray(), bytearray()
    try:
        child=subprocess.Popen(argv,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
            env=dict(PATH='/usr/bin:/bin',LC_ALL='C'),start_new_session=True)
    except OSError: failure('spawn')
    stage, offset, deadline = None, 0, time.monotonic()+timeout
    try:
        with selectors.DefaultSelector() as poll:
            for stream in (child.stdin,child.stdout,child.stderr): os.set_blocking(stream.fileno(),False)
            poll.register(child.stdout,selectors.EVENT_READ,(stdout,limit))
            poll.register(child.stderr,selectors.EVENT_READ,(stderr,4096))
            if payload: poll.register(child.stdin,selectors.EVENT_WRITE,None)
            else: child.stdin.close()
            while poll.get_map() and stage is None:
                remaining=deadline-time.monotonic()
                if remaining<=0: stage='timeout'; break
                for key,_ in poll.select(remaining):
                    if key.fileobj is child.stdin:
                        try: offset+=os.write(child.stdin.fileno(),payload[offset:offset+4096])
                        except BrokenPipeError: offset=len(payload)
                        if offset==len(payload): poll.unregister(child.stdin); child.stdin.close()
                    else:
                        buffer,bound=key.data
                        data=os.read(key.fileobj.fileno(),min(4096,bound+1-len(buffer)))
                        buffer.extend(data)
                        if len(buffer)>bound: stage='bounds'; break
                        if not data: poll.unregister(key.fileobj)
            # EOF only closes the capture phase. Observe termination without
            # reaping: the retained leader pins its PID/PGID until group cleanup,
            # as in the existing production capture primitive.
            while stage is None:
                exited=os.waitid(os.P_PID,child.pid,os.WEXITED|os.WNOHANG|os.WNOWAIT)
                if exited is not None: break
                remaining=deadline-time.monotonic()
                if remaining<=0: stage='timeout'; break
                time.sleep(min(remaining,0.01))
    except OSError:
        stage='io'
    finally:
        # Same retained-group cleanup rule as the existing capture primitive.
        cleanup_failed, permission_denied = False, False
        try: os.killpg(child.pid,signal.SIGKILL)
        except ProcessLookupError: pass
        except PermissionError: permission_denied=True
        except OSError: cleanup_failed=True
        try: child.wait(timeout=2)
        except (OSError,subprocess.TimeoutExpired): cleanup_failed=True
        finally:
            for stream in (child.stdin,child.stdout,child.stderr):
                try: stream.close()
                except OSError: cleanup_failed=True
        if permission_denied:
            # Darwin may deny killing an already zombie-only group. Accept only
            # proven absence after reap, with no second kill/recycled-PID risk.
            try: os.killpg(child.pid,0)
            except ProcessLookupError: pass
            except OSError: cleanup_failed=True
            else: cleanup_failed=True
    out,err=bytes(stdout),bytes(stderr)
    if cleanup_failed: failure(stage or 'cleanup',child.returncode,out,err,cleanup_failed=True)
    if stage: failure(stage,child.returncode,out,err)
    return subprocess.CompletedProcess([],child.returncode,out,err)


def checked_response(response, runner, protocol, nonce, *, expected_code=None):
    import hashlib
    import hmac
    import json
    import re
    out,err,code=response.stdout,response.stderr,response.returncode
    def fail(stage,**extra): failure(stage,code,out,err,**extra)
    if len(out)>runner.FRAME_LIMIT or len(err)>4096: fail('bounds')
    if code not in (0,1) or err: fail('subprocess')
    if not out: fail('empty')
    match=re.fullmatch(rb'clb91-runtime-feasibility-auth:v=1 tag=([0-9a-f]{64}) (runtime-feasibility:v=1 [^\r\n]*\n)',out)
    if match is None: fail('framing')
    if not hmac.compare_digest(match[1],hmac.new(nonce,match[2],hashlib.sha256).hexdigest().encode()): fail('authentication')
    try: line,status=runner.parse_result(out,code,nonce,protocol)
    except Exception: fail('schema',authenticated=True)
    if expected_code is not None and status!=expected_code:
        # Only the fully verified fixed result vocabulary can enter a message.
        value=json.loads(line[len(protocol.PREFIX):])
        fail('collect',authenticated=True,result=value['result'],reason=value.get('reason','none'))
    return line,status


def captured_fixture_source(repository, directory):
    """Test-only absolute-root/UID substitution; real Linux descriptor I/O."""
    base=(repository/'scripts/tests/runtime_inventory_fixtures.py').read_text()
    # Include only the filesystem surrogate in remote fixture sources. Diagnostic
    # helpers above never run inside the production bootstrap/collector namespace.
    own=(repository/'scripts/tests/runtime_feasibility_fixtures.py').read_text().split('\n# Only synthetic subprocesses',1)[0]
    own=own.replace('from runtime_inventory_fixtures import FixtureOS as BaseOS','BaseOS = base.FixtureOS')
    return ('\nbase=__import__("types").ModuleType("fixture_base")\nexec('+repr(base)+',base.__dict__)\n'
        'fixture=__import__("types").ModuleType("fixture")\nfixture.base=base\nexec('+repr(own)+',fixture.__dict__)\n'
        'os=fixture.FixtureOS('+repr(str(directory))+')\nI.os=os\n').encode()
