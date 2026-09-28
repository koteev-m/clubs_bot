#!/usr/bin/env python3
"""Future native-only test coordinator. Never the semantic capture launcher.

No downloads, package manager, arbitrary command interface or host Python under
sudo. Only the compiled, source-reviewed static fixture runner receives root.
"""
import argparse, hashlib, json, os, pathlib, platform, selectors, signal
import re, stat, struct, subprocess, tarfile, time, tempfile
P = pathlib.Path
HERE = P(__file__).resolve().parent.parent
ROOTFS = (59392000, 'f78a1d704c746a17ee5c848ab0667ca64859bf48bc94fdc45eb46b40791b3f22')
PINS_SHA = 'ada8f2c752449a661cb287373d414dade90d9c24daeaea98a3f4c17d4480bab4'
ENV = {'PATH':'/usr/bin:/bin', 'LC_ALL':'C', 'HOME':'/nonexistent'}
MAX_OUTPUT = 65536
class Refused(Exception): pass
sha = lambda b: hashlib.sha256(b).hexdigest()

def read_exact(path, bound):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        a = os.fstat(fd)
        if not stat.S_ISREG(a.st_mode) or a.st_size > bound: raise Refused('file_type_or_bound')
        data = bytearray()
        while len(data) <= bound:
            part = os.read(fd, min(65536, bound + 1 - len(data)))
            if not part: break
            data.extend(part)
        identity = lambda s: (s.st_dev,s.st_ino,s.st_size,s.st_mtime_ns,s.st_ctime_ns)
        if len(data) != a.st_size or identity(a) != identity(os.fstat(fd)) or identity(a) != identity(os.lstat(path)):
            raise Refused('file_drift')
        return bytes(data)
    finally: os.close(fd)

def source_identity():
    if (HERE/'reference').is_symlink(): raise Refused('reference_directory_type')
    raw = read_exact(HERE/'reference/pins.json', 8192)
    if sha(raw) != PINS_SHA: raise Refused('reference_ledger_identity')
    pins = json.loads(raw)
    for name, rec in pins.items():
        if name in ('.','..') or not re.fullmatch('[A-Za-z0-9_.-]+',name): raise Refused('reference_name')
        raw = read_exact(HERE/'reference'/name, 1048576)
        if len(raw) != rec['bytes'] or sha(raw) != rec['sha256']: raise Refused('reference_identity')
    generated = json.loads(read_exact(HERE/'generated-identity.json',8192))
    raw = read_exact(HERE/'adapter/generated_contract.h',1048576)
    if len(raw) != 821681 or sha(raw) != 'a42038d3ac9e71950b3be96b946431368090d04274043e34c900f44c45621531' or len(raw) != generated['header_bytes'] or sha(raw) != generated['header_sha256']: raise Refused('generated_contract_identity')
    return {'reference_ledger':PINS_SHA, 'generated_contract':sha(raw), 'manifest':pins['candidate-runtime.json']['sha256']}

def elf_static(raw):
    if len(raw)<64 or raw[:7] != b'\x7fELF\x02\x01\x01': raise Refused('elf_format')
    header = struct.unpack_from('<HHIQQQIHHHHHH',raw,16)
    typ,machine,version,entry,phoff,shoff,flags,ehsize,phsize,phnum,*_ = header
    if typ not in (2,3) or machine!=62 or version!=1 or ehsize!=64 or phsize!=56 or not 1<=phnum<=64:
        raise Refused('elf_native_static')
    if phoff+phsize*phnum > len(raw): raise Refused('elf_truncated')
    for i in range(phnum):
        ph = struct.unpack_from('<IIQQQQQQ',raw,phoff+i*56)
        if ph[0] in (2,3): raise Refused('elf_dynamic_or_interpreter')
        if ph[2]+ph[5] > len(raw): raise Refused('elf_segment_bound')
    return {'class':'ELF64','machine':'x86_64','interpreter':False,'dynamic_segment':False,'sha256':sha(raw),'bytes':len(raw)}

def native_guard(authorized, system=None, machine=None, env=None, uid=None):
    env = os.environ if env is None else env
    if not authorized: raise Refused('future_permission_not_indicated')
    if (system or platform.system())!='Linux' or (machine or platform.machine())!='x86_64' or env.get('RUNNER_ARCH')!='X64' or env.get('GITHUB_EVENT_NAME')!='workflow_dispatch':
        raise Refused('native_manual_runner_required')
    if (os.geteuid() if uid is None else uid)==0: raise Refused('coordinator_must_be_unprivileged')

def validate_tar(path):
    raw = read_exact(path, ROOTFS[0])
    if (len(raw),sha(raw)) != ROOTFS: raise Refused('rootfs_identity')
    expected = json.loads(read_exact(HERE/'reference/rootfs-manifest.json', 65536))
    import io
    with tarfile.open(fileobj=io.BytesIO(raw),mode='r:') as tf:
        members = tf.getmembers()
        if len(members)!=len(expected): raise Refused('rootfs_entry_count')
        for m,e in zip(members,expected):
            if '/' + m.name != e['path'] or m.uid!=0 or m.gid!=0 or m.mode!=int(e['mode'],8) or m.mtime!=e['mtime'] or m.pax_headers:
                raise Refused('rootfs_entry_metadata')
            if e['type']=='file':
                if not m.isfile() or m.size!=e['bytes'] or sha(tf.extractfile(m).read())!=e['sha256']: raise Refused('rootfs_file')
            elif e['type']=='directory':
                if not m.isdir(): raise Refused('rootfs_directory')
            elif not m.issym() or m.linkname!=e['target']: raise Refused('rootfs_alias')
    return raw, expected

def extract_runtime(raw, entries, dest):
    """Only exact reviewed members, no tar.extractall or root privileges."""
    import io
    dest.mkdir(mode=0o700)
    with tarfile.open(fileobj=io.BytesIO(raw),mode='r:') as tf:
        for e,m in zip(entries,tf.getmembers()):
            p = dest/e['path'].lstrip('/')
            if e['type']=='directory': p.mkdir(mode=0o700)
            elif e['type']=='symlink': p.symlink_to(e['target'])
            else:
                fd=os.open(p, os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
                try:
                    with tf.extractfile(m) as stream:
                        while True:
                            b=stream.read(65536)
                            if not b: break
                            view=memoryview(b)
                            while view: view=view[os.write(fd,view):]
                    os.fchmod(fd,int(e['mode'],8))
                finally: os.close(fd)
        for e in reversed(entries):
            p=dest/e['path'].lstrip('/')
            if e['type']=='directory': os.chmod(p,int(e['mode'],8))
            os.utime(p,(e['mtime'],e['mtime']),follow_symlinks=False)
    os.chmod(dest,0o555);os.utime(dest,(0,0))

def fixture_inventory(root):
    found={}
    def walk(path):
        for item in os.scandir(path):
            p=P(item.path);st=item.stat(follow_symlinks=False)
            found[str(p.relative_to(root))]=(st.st_dev,st.st_ino,stat.S_IFMT(st.st_mode))
            if stat.S_ISDIR(st.st_mode): walk(p)
    walk(root)
    return found

def cleanup_fixture(root, identity, inventory):
    st=root.lstat()
    if (st.st_dev,st.st_ino,st.st_uid)!=(identity.st_dev,identity.st_ino,os.getuid()): raise Refused('local_fixture_identity_or_owner')
    # No broad deletion: exact originally extracted object IDs only. An added,
    # replaced, or leftover synthetic source is a refusal, never an rm target.
    if fixture_inventory(root)!=inventory: raise Refused('local_fixture_shape_or_identity')
    for name in inventory:
        p=root/name;st=p.lstat()
        if st.st_uid!=os.getuid(): raise Refused('local_fixture_owner')
    for name in sorted(inventory,key=lambda n:len(P(n).parts)):
        p=root/name
        if stat.S_ISDIR(inventory[name][2]): os.chmod(p,0o700)
    for name in sorted(inventory,key=lambda n:len(P(n).parts),reverse=True):
        p=root/name;st=p.lstat()
        if (st.st_dev,st.st_ino,stat.S_IFMT(st.st_mode))!=inventory[name]: raise Refused('local_fixture_changed')
        if stat.S_ISDIR(st.st_mode): p.rmdir()
        else: p.unlink()
    root.rmdir()

def capture(argv, seconds, commands, *, privileged=False, require_exit=True):
    """Closed internal calls only. Raw compiler/helper stderr is never an artifact."""
    record={'argv':argv,'deadline_s':seconds,'privileged_fixture':privileged}
    commands.append(record)
    stopped=[False]; old={}
    def stop(sig,frame): stopped[0]=True
    for sig in (signal.SIGTERM,signal.SIGINT,signal.SIGHUP): old[sig]=signal.signal(sig,stop)
    proc=None; out=bytearray(); err=bytearray(); primary=None; forced=False
    try:
        proc=subprocess.Popen(argv,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=ENV,start_new_session=True)
        sel=selectors.DefaultSelector()
        for pipe,buf in ((proc.stdout,out),(proc.stderr,err)):
            os.set_blocking(pipe.fileno(),False);sel.register(pipe,selectors.EVENT_READ,buf)
        deadline=time.monotonic()+seconds; stopping=None
        while sel.get_map() or proc.poll() is None:
            if primary is None and (stopped[0] or time.monotonic()>deadline): primary='cancelled' if stopped[0] else 'timeout'
            if primary and stopping is None:

                try: os.killpg(proc.pid,signal.SIGTERM)
                except ProcessLookupError: pass
                stopping=time.monotonic()
            if stopping and time.monotonic()-stopping>10 and proc.poll() is None:
                # sudo may not relay/allow this signal; result explicitly UNKNOWN.
                try: os.killpg(proc.pid,signal.SIGKILL)
                except ProcessLookupError: pass
                forced=True
            for key,_ in sel.select(.05):
                b=os.read(key.fileobj.fileno(),8192)
                if not b: sel.unregister(key.fileobj);key.fileobj.close();continue
                key.data.extend(b)
                if len(key.data)>MAX_OUTPUT:
                    del key.data[MAX_OUTPUT:];primary=primary or 'output_bound'
            if stopping and time.monotonic()-stopping>15: break
        if proc.poll() is None:
            record['process_outcome']='UNKNOWN';primary=primary or 'process_unconfirmed'
        else: record['exit']=proc.returncode
        sel.close()
        record.update(primary=primary,stdout_bytes=len(out),stderr_bytes=len(err),stderr_sha256=sha(err),forced_termination=forced,
                      cleanup=('UNKNOWN' if privileged and (primary or forced) else 'not_inferred_from_transport'))
        if primary: raise Refused('command_'+primary)
        if proc.returncode and require_exit: raise Refused('command_nonzero')
        return bytes(out)
    finally:
        if proc is not None:
            for stream in (proc.stdout,proc.stderr):
                if stream and not stream.closed: stream.close()
        for sig,handler in old.items(): signal.signal(sig,handler)

def compiler_inputs(commands):
    records={}
    queries={**{n:'-print-prog-name='+n for n in ('cc1','collect2','as','ld')},
             **{n:'-print-file-name='+n for n in ('crt1.o','crti.o','crtn.o','crtbeginT.o','crtend.o','libgcc.a','libgcc_eh.a','libc.a','libc_nonshared.a')}}
    for name,arg in queries.items():
        answer=capture(['/usr/bin/gcc',arg],10,commands).decode('ascii').strip()
        if not answer or any(c.isspace() for c in answer): raise Refused('compiler_input_path')
        path=P(answer)
        if not path.is_absolute():
            if name not in ('as','ld') or answer!=name: raise Refused('compiler_input_unresolved')
            path=P('/usr/bin')/name
        path=path.resolve(strict=True)
        if not str(path).startswith(('/usr/','/lib/')): raise Refused('compiler_input_scope')
        raw=read_exact(path,64*1024*1024)
        records[name]={'path':str(path),'bytes':len(raw),'sha256':sha(raw)}
    return {'selected_inputs':records,'completeness':'selected compiler/linker/CRT/static archives only; headers, built-in specs and entire runner image are not independently pinned','authority':'candidate experiment build provenance, not approved production pins'}

def write_evidence(folder, files):
    encoded={n:(json.dumps(v,sort_keys=True,indent=2)+'\n').encode() for n,v in files.items()}
    if sum(map(len,encoded.values()))>524288: raise Refused('report_bound')
    for name,raw in encoded.items():
        fd=os.open(folder/name,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
        try: os.write(fd,raw)
        finally: os.close(fd)

def main():
    p=argparse.ArgumentParser();p.add_argument('--runtime-tar',required=True);p.add_argument('--work',required=True);p.add_argument('--authorized-native-test',action='store_true');a=p.parse_args()
    commands=[];compiler={};env={};result={'verdict':'BLOCKED','native_tests':'NOT_RUN','primary':None,'cleanup':'NOT_STARTED'};evidence=None;fixture=None;fixture_identity=None;inventory=None
    try:
        native_guard(a.authorized_native_test)
        root=P(a.work);base=P(os.environ['RUNNER_TEMP']).resolve()
        if not root.is_absolute() or root.parent.resolve()!=base or root.name!='clb91-native-adapter' or root.exists() or root.is_symlink(): raise Refused('owned_work_path')
        inputs=source_identity();raw,entries=validate_tar(P(a.runtime_tar))
        os.umask(0o077);root.mkdir(mode=0o700);evidence=root/'evidence';evidence.mkdir();build=root/'build';build.mkdir()
        env={'system':platform.system(),'machine':platform.machine(),'release':platform.release(),'coordinator_uid':os.getuid(),'coordinator_gid':os.getgid(),'runtime_inputs':inputs,'runtime_tar_sha256':ROOTFS[1],'kernel_and_runner_image':'external CI-provider trust, measured here; not production-approved pins'}
        fixture=P(tempfile.mkdtemp(prefix='clb91-native-',dir='/tmp'));fixture_identity=fixture.stat()
        extract_runtime(raw,entries,fixture/'runtime');inventory=fixture_inventory(fixture)
        gcc=P('/usr/bin/gcc').resolve();compiler['executable']={'path':str(gcc),'sha256':sha(read_exact(gcc,32*1024*1024))}
        version=capture(['/usr/bin/gcc','--version'],10,commands)
        compiler['version']=version.decode('ascii',errors='strict').splitlines()[0][:256]
        compiler['inputs']=compiler_inputs(commands)
        flags=['/usr/bin/gcc','-std=c11','-O2','-static','-fno-pie','-no-pie','-fstack-protector-strong','-D_FORTIFY_SOURCE=2','-Wl,--build-id=none','-Wall','-Wextra','-Wno-unused-function','-Wno-misleading-indentation']
        for target,source,extra in [('adapter',HERE/'adapter/adapter.c',[]),('core-tests',HERE/'adapter/core-tests.c',[])]:
            capture(flags+[str(source),'-o',str(build/target)]+extra,60,commands)
            compiler[target]=elf_static(read_exact(build/target,16*1024*1024))
        core=[json.loads(line) for line in capture([str(build/'core-tests')],20,commands).splitlines()]
        if not core or core[-1].get('summary',{}).get('failed')!=0: raise Refused('native_core_tests')
        capture(flags+[str(HERE/'tests/native-fixture-runner.c'),'-o',str(build/'native-fixture-runner'),'-DADAPTER_EXEC_SHA256="'+compiler['adapter']['sha256']+'"'],60,commands)
        compiler['fixture_runner']=elf_static(read_exact(build/'native-fixture-runner',16*1024*1024))
        result['core_tests']=core
        answer=capture(['/usr/bin/sudo','-n','--',str(build/'native-fixture-runner'),'--work',str(fixture),'--adapter',str(build/'adapter')],600,commands,privileged=True,require_exit=False)
        report=json.loads(answer)
        result.update(native_tests=report,cleanup=report.get('cleanup','UNKNOWN'))
        if commands[-1].get('exit')!=0: raise Refused('native_fixture_exit')
        if report.get('verdict')!='PASS' or report.get('cleanup')!='confirmed': raise Refused('native_fixture_result')
        result.update(verdict='PASS',native_tests=report,primary=None,cleanup=report['cleanup'])
    except (Refused,OSError,ValueError,KeyError) as e:
        result['primary']=str(e) if isinstance(e,Refused) else type(e).__name__
        if any(x.get('privileged_fixture') for x in commands) and result['cleanup']=='NOT_STARTED': result['cleanup']='UNKNOWN'
    finally:
        if fixture is not None:
            try:
                if any(x.get('privileged_fixture') for x in commands):
                    if result.get('cleanup')!='confirmed': raise Refused('privileged_cleanup_unconfirmed')
                    cleanup_fixture(fixture,fixture_identity,{})
                else:
                    if inventory is None: raise Refused('incomplete_extraction')
                    cleanup_fixture(fixture,fixture_identity,inventory)
                result['local_fixture_cleanup']='confirmed'
            except (OSError,Refused):
                result['local_fixture_cleanup']='UNKNOWN';result['verdict']='BLOCKED'
        if evidence:
            write_evidence(evidence,{'environment.json':env,'compiler.json':compiler,'commands.json':commands,'result.json':result})
    print(json.dumps(result,sort_keys=True))
    return 0 if result['verdict']=='PASS' else 1
if __name__=='__main__': raise SystemExit(main())
