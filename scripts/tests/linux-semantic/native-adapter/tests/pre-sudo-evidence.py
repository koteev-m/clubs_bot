"""CLB99 unprivileged, fixed three-binary evidence gate. No execution/limit API.

Only Linux ENODATA from fd-based security.capability proves absence. The permit
is internal call-order state, not authentication against hostile Python/root.
All gate FDs close before the existing capture/Popen path. No candidate FD added.
"""
import errno, hashlib, json, os, pathlib, re, stat, sys
P=pathlib.Path
BOUND=16384
BINARY_BOUND=16*1024*1024
ROLES=('launcher','helper','adapter')
TOOLS=('cc1','collect2','as','ld','crt1.o','crti.o','crtn.o','crtbeginT.o','crtend.o','libgcc.a','libgcc_eh.a','libc.a','libc_nonshared.a')
SOURCE_ROLES=('adapter','helper','worker_record','fd_budget','resource_envelope','namespace_handles','bind_probe','generated_contract','launcher','launcher_limit_core','launcher_sha256','coordinator','gate')
ORIGINAL='378d20642b46e538643a1374f21126ebbf57922d176311cec57011b194a13853'
INSTRUMENTATION='feacdb64901ad02edcea870ec7e910a079e5aceee12bd116ef3cf5b7f537dd14'
_TOKEN=object()
class Refused(ValueError):pass
def digest(raw):return hashlib.sha256(raw).hexdigest()
def valid_hash(s):return type(s) is str and re.fullmatch('[0-9a-f]{64}',s) is not None
def number(n):return type(n) is int and 0<=n<2**64
def identity(s):return (s.st_dev,s.st_ino,s.st_mode,s.st_uid,s.st_gid,s.st_nlink,s.st_size,s.st_mtime_ns,s.st_ctime_ns)
def no_symlinks(path):
    path=P(path)
    if not path.is_absolute() or path.resolve(strict=True)!=path:raise Refused('pre_sudo_path')
    return path

def capabilities_absent(fd):
    if sys.platform!='linux' or not hasattr(os,'getxattr'):raise Refused('capability_evidence_unavailable')
    try:os.getxattr(fd,'security.capability')
    except OSError as e:
        if e.errno==errno.ENODATA:return True
        raise Refused('capability_state_unknown') from None
    raise Refused('file_capabilities_present')  # Includes empty/malformed xattr.

def read_fd(fd,bound):
    data=bytearray()
    while len(data)<=bound:
        part=os.read(fd,min(65536,bound+1-len(data)))
        if not part:break
        data.extend(part)
    if len(data)>bound:raise Refused('pre_sudo_read_bound')
    return bytes(data)

def snapshot(path,expected,elf_check):
    path=no_symlinks(path)
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK|os.O_CLOEXEC)
    try:
        a=os.fstat(fd)
        if not stat.S_ISREG(a.st_mode) or a.st_nlink!=1 or not 0<a.st_size<=BINARY_BOUND:raise Refused('pre_sudo_file_type')
        if (a.st_uid,a.st_gid)!=(os.getuid(),os.getgid()):raise Refused('pre_sudo_owner')
        if stat.S_IMODE(a.st_mode)!=0o755:raise Refused('pre_sudo_mode')
        capabilities_absent(fd)
        raw=read_fd(fd,BINARY_BOUND)
        if digest(raw)!=expected['sha256'] or len(raw)!=expected['bytes']:raise Refused('pre_sudo_binary_hash')
        elf_check(raw)
        if identity(a)!=identity(os.fstat(fd)) or identity(a)!=identity(path.lstat()):raise Refused('pre_sudo_file_drift')
        return {'sha256':digest(raw),'bytes':len(raw),'architecture':'ELF64_X86_64_STATIC',
                'uid':a.st_uid,'gid':a.st_gid,'mode':stat.S_IMODE(a.st_mode),
                'regular':True,'symlink':False,'setuid':False,'setgid':False,
                'group_writable':False,'other_writable':False,'file_capabilities_absent':True,
                'identity_status':'PASS','device':a.st_dev,'inode':a.st_ino,'nlink':a.st_nlink,
                'mtime_ns':a.st_mtime_ns,'ctime_ns':a.st_ctime_ns}
    finally:os.close(fd)

def normalized_build(compiler,sources,binding_sha):
    if type(compiler) is not dict or type(sources) is not dict or set(sources)!=set(SOURCE_ROLES) or any(not valid_hash(v) for v in sources.values()) or not valid_hash(binding_sha):raise Refused('pre_sudo_build_metadata')
    expected={}
    try:
        for role,key in [('launcher','verification_launcher'),('helper','fixture_runner'),('adapter','adapter')]:
            r=compiler[key]
            if type(r) is not dict or any(r.get(k)!=v for k,v in {'class':'ELF64','machine':'x86_64','interpreter':False,'dynamic_segment':False}.items()) or type(r.get('interpreter')) is not bool or type(r.get('dynamic_segment')) is not bool or not valid_hash(r.get('sha256')) or not number(r.get('bytes')) or not 0<r['bytes']<=BINARY_BOUND:raise Refused('pre_sudo_build_metadata')
            expected[role]={'sha256':r['sha256'],'bytes':r['bytes']}
        if compiler['verification_launcher']['source_sha256']!=sources['launcher'] or compiler['verification_launcher']['binding_sha256']!=binding_sha:raise Refused('pre_sudo_build_binding')
        cc=compiler['executable']['sha256'];version=compiler['version'];inputs=compiler['inputs']['selected_inputs']
        if not valid_hash(cc) or type(version) is not str or not 0<len(version)<=256 or type(inputs) is not dict or set(inputs)!=set(TOOLS):raise Refused('pre_sudo_compiler_metadata')
        selected={}
        for name in TOOLS:
            r=inputs[name]
            if not valid_hash(r.get('sha256')) or not number(r.get('bytes')) or not 0<r['bytes']<=64*1024*1024:raise Refused('pre_sudo_compiler_metadata')
            selected[name]={'sha256':r['sha256'],'bytes':r['bytes']}
    except (KeyError,TypeError,AttributeError):raise Refused('pre_sudo_build_metadata') from None
    bindings={'original_clb91_publication':ORIGINAL,'clb97_derivative_manifest':INSTRUMENTATION,
              'source_sha256':dict(sources),'generated_binding_sha256':binding_sha}
    toolchain={'compiler_sha256':cc,'version_sha256':digest(version.encode()),'selected_inputs':selected,
               'coverage':'SELECTED_COMPILER_CRT_ARCHIVES_NOT_WHOLE_RUNNER'}
    return expected,bindings,toolchain

def encoded(doc):
    raw=(json.dumps(doc,sort_keys=True,separators=(',',':'),ensure_ascii=True)+'\n').encode('ascii')
    if len(raw)>BOUND:raise Refused('pre_sudo_evidence_bound')
    return raw

def evidence_dir(path):
    path=no_symlinks(path)
    fd=os.open(path,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW|os.O_CLOEXEC)
    a=os.fstat(fd)
    if not stat.S_ISDIR(a.st_mode) or stat.S_IMODE(a.st_mode)!=0o700 or (a.st_uid,a.st_gid)!=(os.getuid(),os.getgid()):
        os.close(fd);raise Refused('pre_sudo_evidence_directory')
    return fd

def read_saved(folder,raw):
    d=evidence_dir(folder)
    try:
        fd=os.open('compiler.json',os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK|os.O_CLOEXEC,dir_fd=d)
        try:
            a=os.fstat(fd)
            if not stat.S_ISREG(a.st_mode) or a.st_nlink!=1 or stat.S_IMODE(a.st_mode)!=0o600 or (a.st_uid,a.st_gid)!=(os.getuid(),os.getgid()) or a.st_size!=len(raw):raise Refused('pre_sudo_saved_identity')
            actual=read_fd(fd,BOUND)
            if actual!=raw or json.loads(actual)!=json.loads(raw) or identity(a)!=identity(os.fstat(fd)) or identity(a)!=identity(os.stat('compiler.json',dir_fd=d,follow_symlinks=False)):raise Refused('pre_sudo_readback')
        finally:os.close(fd)
    finally:os.close(d)
    return digest(raw)

def persist(folder,raw):
    d=evidence_dir(folder);fd=None
    try:
        try:os.stat('compiler.json',dir_fd=d,follow_symlinks=False)
        except FileNotFoundError:pass
        else:raise Refused('pre_sudo_evidence_exists')
        fd=os.open('compiler.pre-sudo.tmp',os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW|os.O_CLOEXEC,0o600,dir_fd=d)
        pos=0;interruptions=0
        while pos<len(raw):
            try:n=os.write(fd,raw[pos:])
            except InterruptedError:
                interruptions+=1
                if interruptions>8:raise Refused('pre_sudo_write')
                continue
            if n<=0:raise Refused('pre_sudo_write')
            pos+=n
        os.fsync(fd);os.close(fd);fd=None
        os.replace('compiler.pre-sudo.tmp','compiler.json',src_dir_fd=d,dst_dir_fd=d)
        os.fsync(d)
    finally:
        if fd is not None:os.close(fd)
        os.close(d)
    read_saved(folder,raw)

class _Permit:
    def __init__(self,token,folder,raw,paths,expected,elf_check):
        if token is not _TOKEN:raise Refused('pre_sudo_permit')
        self.folder,self.raw,self.paths,self.expected,self.elf_check=folder,raw,dict(paths),dict(expected),elf_check
        self.used=False
    def validate_saved(self):return read_saved(self.folder,self.raw)
    def consume(self,expected_launcher_sha):
        if self.used:raise Refused('pre_sudo_permit_consumed')
        self.used=True  # Failure cannot authorize an implicit second attempt.
        doc=json.loads(self.raw)
        if expected_launcher_sha!=self.expected['launcher']['sha256']:raise Refused('pre_sudo_launcher_binding')
        for role in ROLES:
            if snapshot(self.paths[role],self.expected[role],self.elf_check)!=doc['binaries'][role]:raise Refused('pre_sudo_recheck')
        return self.validate_saved()

def admit(folder,paths,compiler,sources,binding_sha,elf_check):
    if os.getuid()==0 or os.geteuid()==0:raise Refused('pre_sudo_unprivileged_required')
    if set(paths)!=set(ROLES):raise Refused('pre_sudo_binary_roles')
    expected,bindings,toolchain=normalized_build(compiler,sources,binding_sha)
    observations={r:snapshot(paths[r],expected[r],elf_check) for r in ROLES}
    doc={'schema':1,'phase':'pre_sudo','identity_status':'PASS','binaries':observations,'bindings':bindings,'toolchain':toolchain}
    raw=encoded(doc);persist(folder,raw)
    return _Permit(_TOKEN,folder,raw,paths,expected,elf_check)
