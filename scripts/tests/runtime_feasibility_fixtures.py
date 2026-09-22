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
