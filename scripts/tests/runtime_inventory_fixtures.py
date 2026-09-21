"""Disposable inventory filesystem surrogate; never imported by production.

Real descriptor operations on synthetic files; only absolute root/proc link and
fixture-owner UID are substituted. Linux system test separately uses real root
ownership. No fixture paths or surrogate logic enter a production source bundle.
"""
import errno
import os
from pathlib import Path
import stat
import types


class FixtureOS:
    def __init__(self, root):
        self.root = str(root)
        self.owned, self.opens = set(), []
        self.read_bytes = 0
        self.after_read = None
        self.bad_owner = False
        self.close_failure = False

    def __getattr__(self, key):
        if key == 'environ': raise AssertionError('environment access')
        return getattr(os, key)

    def open(self, path, flags, *args, **kwargs):
        assert not flags & (os.O_WRONLY | os.O_RDWR | os.O_CREAT | os.O_TRUNC | os.O_APPEND)
        self.opens.append(path)
        if path == '/': path = self.root
        else: assert kwargs.get('dir_fd') in self.owned and '/' not in path
        fd = os.open(path, flags, *args, **kwargs)
        self.owned.add(fd)
        return fd

    def adjusted(self, value):
        fields = {k: getattr(value, k) for k in dir(value) if k.startswith('st_')}
        fields['st_uid'] = 12345 if self.bad_owner else 0
        return types.SimpleNamespace(**fields)

    def fstat(self, fd): return self.adjusted(os.fstat(fd))
    def stat(self, *args, **kwargs): return self.adjusted(os.stat(*args, **kwargs))
    def readlink(self, path, **kwargs):
        if path == '/proc/self/exe': return '/usr/bin/python3.12'
        return os.readlink(path, **kwargs)

    def read(self, fd, size):
        data = os.read(fd, size)
        self.read_bytes += len(data)
        if self.after_read:
            callback, self.after_read = self.after_read, None
            callback()
        return data

    def close(self, fd):
        os.close(fd)
        self.owned.remove(fd)
        if self.close_failure: raise OSError(errno.EIO, 'PRIVATE close detail')


def make_fixture(root):
    root = Path(root)
    def put(path, data, mode=0o644):
        target = root/path.lstrip('/')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        target.chmod(mode)
        return target
    put('/usr/bin/python3.12', b'not an executable; PRIVATE artifact contents', 0o755)
    (root/'usr/bin/python3').symlink_to('python3.12')
    put('/usr/local/bin/docker-compose', b'not executed PRIVATE compose fixture', 0o755)
    put('/usr/bin/ruby3.2', b'not executed PRIVATE ruby fixture', 0o755)
    (root/'usr/bin/ruby').symlink_to('ruby3.2')
    put('/usr/lib/ruby/3.2.0/psych.rb', b'raise "PRIVATE never run"\n')
    put('/usr/lib/os-release', b'ID=ubuntu\nVERSION_ID="24.04"\nPRETTY_NAME="PRIVATE ignored"\n')
    (root/'etc').mkdir()
    (root/'etc/os-release').symlink_to('../usr/lib/os-release')
    put('/var/lib/dpkg/status', b'Package: python3\nStatus: install ok installed\nVersion: 3.12.3-0ubuntu2\nArchitecture: amd64\nDescription: PRIVATE ignored\n\nPackage: ruby\nStatus: install ok installed\nVersion: 3.2.2-1\nArchitecture: amd64\n')
    return put
