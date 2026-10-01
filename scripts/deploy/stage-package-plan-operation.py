#!/usr/bin/env python3
"""CLB-131 fixed read-only APT transaction diagnostic. Run only through a published trusted channel.

Output is one bounded JSON object. No stage files are created. This script is not
an execution authority or an installer. Invoke as python3 -I -S -B from memory.
"""
import hashlib
import io
import json
import os
from pathlib import Path
import re
import selectors
import signal
import stat
import subprocess
import sys
import time
from urllib.parse import urlsplit

SCHEMA = 'clb131-package-evidence-v1'
MAIN = '0c934da1b76ad6916feaf2bb52d88a9b42ca2f27'
TARGET_SHA256 = '9c9ba991a9edcea28ed9b24ee6f3d2f9928a748883cc923fd621bf012fb42e02'
ACCEPTED_PATHS = ('/etc/ld.so.cache', '/usr/bin/dash', '/usr/bin/findmnt', '/usr/bin/python3.12', '/usr/bin/ruby3.2', '/usr/lib/locale/C.utf8/LC_CTYPE', '/usr/lib/python3.12/__pycache__/_compression.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/_weakrefset.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/ast.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/base64.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/bisect.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/bz2.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/contextlib.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/copyreg.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/enum.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/fnmatch.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/functools.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/hashlib.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/hmac.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/ipaddress.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/keyword.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/locale.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/lzma.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/operator.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/pathlib.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/platform.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/random.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/reprlib.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/secrets.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/selectors.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/shlex.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/shutil.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/signal.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/struct.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/subprocess.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/tempfile.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/threading.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/types.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/warnings.cpython-312.pyc', '/usr/lib/python3.12/__pycache__/weakref.cpython-312.pyc', '/usr/lib/python3.12/_collections_abc.py', '/usr/lib/python3.12/_compression.py', '/usr/lib/python3.12/_weakrefset.py', '/usr/lib/python3.12/abc.py', '/usr/lib/python3.12/ast.py', '/usr/lib/python3.12/base64.py', '/usr/lib/python3.12/bisect.py', '/usr/lib/python3.12/bz2.py', '/usr/lib/python3.12/codecs.py', '/usr/lib/python3.12/collections/__init__.py', '/usr/lib/python3.12/collections/__pycache__/__init__.cpython-312.pyc', '/usr/lib/python3.12/collections/__pycache__/abc.cpython-312.pyc', '/usr/lib/python3.12/collections/abc.py', '/usr/lib/python3.12/contextlib.py', '/usr/lib/python3.12/copyreg.py', '/usr/lib/python3.12/encodings/__init__.py', '/usr/lib/python3.12/encodings/__pycache__/__init__.cpython-312.pyc', '/usr/lib/python3.12/encodings/__pycache__/aliases.cpython-312.pyc', '/usr/lib/python3.12/encodings/__pycache__/utf_8.cpython-312.pyc', '/usr/lib/python3.12/encodings/aliases.py', '/usr/lib/python3.12/encodings/utf_8.py', '/usr/lib/python3.12/enum.py', '/usr/lib/python3.12/fnmatch.py', '/usr/lib/python3.12/functools.py', '/usr/lib/python3.12/genericpath.py', '/usr/lib/python3.12/hashlib.py', '/usr/lib/python3.12/hmac.py', '/usr/lib/python3.12/importlib/_bootstrap_external.py', '/usr/lib/python3.12/io.py', '/usr/lib/python3.12/ipaddress.py', '/usr/lib/python3.12/json/__init__.py', '/usr/lib/python3.12/json/__pycache__/__init__.cpython-312.pyc', '/usr/lib/python3.12/json/__pycache__/decoder.cpython-312.pyc', '/usr/lib/python3.12/json/__pycache__/encoder.cpython-312.pyc', '/usr/lib/python3.12/json/__pycache__/scanner.cpython-312.pyc', '/usr/lib/python3.12/json/decoder.py', '/usr/lib/python3.12/json/encoder.py', '/usr/lib/python3.12/json/scanner.py', '/usr/lib/python3.12/keyword.py', '/usr/lib/python3.12/lib-dynload/_bz2.cpython-312-x86_64-linux-gnu.so', '/usr/lib/python3.12/lib-dynload/_hashlib.cpython-312-x86_64-linux-gnu.so', '/usr/lib/python3.12/lib-dynload/_json.cpython-312-x86_64-linux-gnu.so', '/usr/lib/python3.12/lib-dynload/_lzma.cpython-312-x86_64-linux-gnu.so', '/usr/lib/python3.12/locale.py', '/usr/lib/python3.12/lzma.py', '/usr/lib/python3.12/ntpath.py', '/usr/lib/python3.12/operator.py', '/usr/lib/python3.12/os.py', '/usr/lib/python3.12/pathlib.py', '/usr/lib/python3.12/platform.py', '/usr/lib/python3.12/posixpath.py', '/usr/lib/python3.12/random.py', '/usr/lib/python3.12/re/__init__.py', '/usr/lib/python3.12/re/__pycache__/__init__.cpython-312.pyc', '/usr/lib/python3.12/re/__pycache__/_casefix.cpython-312.pyc', '/usr/lib/python3.12/re/__pycache__/_compiler.cpython-312.pyc', '/usr/lib/python3.12/re/__pycache__/_constants.cpython-312.pyc', '/usr/lib/python3.12/re/__pycache__/_parser.cpython-312.pyc', '/usr/lib/python3.12/re/_casefix.py', '/usr/lib/python3.12/re/_compiler.py', '/usr/lib/python3.12/re/_constants.py', '/usr/lib/python3.12/re/_parser.py', '/usr/lib/python3.12/reprlib.py', '/usr/lib/python3.12/secrets.py', '/usr/lib/python3.12/selectors.py', '/usr/lib/python3.12/shlex.py', '/usr/lib/python3.12/shutil.py', '/usr/lib/python3.12/signal.py', '/usr/lib/python3.12/stat.py', '/usr/lib/python3.12/struct.py', '/usr/lib/python3.12/subprocess.py', '/usr/lib/python3.12/tempfile.py', '/usr/lib/python3.12/threading.py', '/usr/lib/python3.12/types.py', '/usr/lib/python3.12/urllib/__init__.py', '/usr/lib/python3.12/urllib/__pycache__/__init__.cpython-312.pyc', '/usr/lib/python3.12/urllib/__pycache__/parse.cpython-312.pyc', '/usr/lib/python3.12/urllib/parse.py', '/usr/lib/python3.12/warnings.py', '/usr/lib/python3.12/weakref.py', '/usr/lib/python3.12/zipimport.py', '/usr/lib/ruby/3.2.0/forwardable.rb', '/usr/lib/ruby/3.2.0/forwardable/impl.rb', '/usr/lib/ruby/3.2.0/json.rb', '/usr/lib/ruby/3.2.0/json/common.rb', '/usr/lib/ruby/3.2.0/json/ext.rb', '/usr/lib/ruby/3.2.0/json/generic_object.rb', '/usr/lib/ruby/3.2.0/json/version.rb', '/usr/lib/ruby/3.2.0/ostruct.rb', '/usr/lib/ruby/3.2.0/psych.rb', '/usr/lib/ruby/3.2.0/psych/class_loader.rb', '/usr/lib/ruby/3.2.0/psych/coder.rb', '/usr/lib/ruby/3.2.0/psych/core_ext.rb', '/usr/lib/ruby/3.2.0/psych/exception.rb', '/usr/lib/ruby/3.2.0/psych/handler.rb', '/usr/lib/ruby/3.2.0/psych/handlers/document_stream.rb', '/usr/lib/ruby/3.2.0/psych/json/ruby_events.rb', '/usr/lib/ruby/3.2.0/psych/json/stream.rb', '/usr/lib/ruby/3.2.0/psych/json/tree_builder.rb', '/usr/lib/ruby/3.2.0/psych/json/yaml_events.rb', '/usr/lib/ruby/3.2.0/psych/nodes.rb', '/usr/lib/ruby/3.2.0/psych/nodes/alias.rb', '/usr/lib/ruby/3.2.0/psych/nodes/document.rb', '/usr/lib/ruby/3.2.0/psych/nodes/mapping.rb', '/usr/lib/ruby/3.2.0/psych/nodes/node.rb', '/usr/lib/ruby/3.2.0/psych/nodes/scalar.rb', '/usr/lib/ruby/3.2.0/psych/nodes/sequence.rb', '/usr/lib/ruby/3.2.0/psych/nodes/stream.rb', '/usr/lib/ruby/3.2.0/psych/omap.rb', '/usr/lib/ruby/3.2.0/psych/parser.rb', '/usr/lib/ruby/3.2.0/psych/scalar_scanner.rb', '/usr/lib/ruby/3.2.0/psych/set.rb', '/usr/lib/ruby/3.2.0/psych/stream.rb', '/usr/lib/ruby/3.2.0/psych/streaming.rb', '/usr/lib/ruby/3.2.0/psych/syntax_error.rb', '/usr/lib/ruby/3.2.0/psych/tree_builder.rb', '/usr/lib/ruby/3.2.0/psych/versions.rb', '/usr/lib/ruby/3.2.0/psych/visitors.rb', '/usr/lib/ruby/3.2.0/psych/visitors/depth_first.rb', '/usr/lib/ruby/3.2.0/psych/visitors/emitter.rb', '/usr/lib/ruby/3.2.0/psych/visitors/json_tree.rb', '/usr/lib/ruby/3.2.0/psych/visitors/to_ruby.rb', '/usr/lib/ruby/3.2.0/psych/visitors/visitor.rb', '/usr/lib/ruby/3.2.0/psych/visitors/yaml_tree.rb', '/usr/lib/x86_64-linux-gnu/gconv/gconv-modules.cache', '/usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2', '/usr/lib/x86_64-linux-gnu/libblkid.so.1.1.0', '/usr/lib/x86_64-linux-gnu/libbz2.so.1.0.4', '/usr/lib/x86_64-linux-gnu/libc.so.6', '/usr/lib/x86_64-linux-gnu/libcap.so.2.66', '/usr/lib/x86_64-linux-gnu/libcrypt.so.1.1.0', '/usr/lib/x86_64-linux-gnu/libcrypto.so.3', '/usr/lib/x86_64-linux-gnu/libexpat.so.1.9.1', '/usr/lib/x86_64-linux-gnu/libgmp.so.10.5.0', '/usr/lib/x86_64-linux-gnu/liblzma.so.5.4.5', '/usr/lib/x86_64-linux-gnu/libm.so.6', '/usr/lib/x86_64-linux-gnu/libmount.so.1.1.0', '/usr/lib/x86_64-linux-gnu/libpcre2-8.so.0.11.2', '/usr/lib/x86_64-linux-gnu/libruby-3.2.so.3.2.3', '/usr/lib/x86_64-linux-gnu/libselinux.so.1', '/usr/lib/x86_64-linux-gnu/libsmartcols.so.1.1.0', '/usr/lib/x86_64-linux-gnu/libudev.so.1.7.8', '/usr/lib/x86_64-linux-gnu/libyaml-0.so.2.0.9', '/usr/lib/x86_64-linux-gnu/libz.so.1.3', '/usr/lib/x86_64-linux-gnu/ruby/3.2.0/enc/encdb.so', '/usr/lib/x86_64-linux-gnu/ruby/3.2.0/enc/trans/transdb.so', '/usr/lib/x86_64-linux-gnu/ruby/3.2.0/json/ext/generator.so', '/usr/lib/x86_64-linux-gnu/ruby/3.2.0/json/ext/parser.so', '/usr/lib/x86_64-linux-gnu/ruby/3.2.0/psych.so', '/usr/lib/x86_64-linux-gnu/ruby/3.2.0/stringio.so', '/usr/local/bin/docker-compose')
REQUEST = (
    ('libblkid1','2.39.3-9ubuntu6.6'), ('libbz2-1.0','1.0.8-5.1ubuntu0.1'),
    ('libc6','2.39-0ubuntu8.9'), ('libexpat1','2.6.1-2ubuntu0.5'),
    ('liblzma5','5.6.1+really5.4.5-1ubuntu0.3'), ('libmount1','2.39.3-9ubuntu6.6'),
    ('libpython3.12-minimal','3.12.3-1ubuntu0.17'),
    ('libpython3.12-stdlib','3.12.3-1ubuntu0.17'),
    ('libsmartcols1','2.39.3-9ubuntu6.6'), ('libssl3t64','3.0.13-0ubuntu3.16'),
    ('openssl','3.0.13-0ubuntu3.16'),
    ('libudev1','255.4-1ubuntu8.17'), ('python3.12-minimal','3.12.3-1ubuntu0.17'),
    ('util-linux','2.39.3-9ubuntu6.6'), ('zlib1g','1:1.3.dfsg-3.1ubuntu2.2'),
    ('ruby','1:3.2~ubuntu1'), ('ruby3.2','3.2.3-1ubuntu0.24.04.8'),
    ('libruby','1:3.2~ubuntu1'), ('libruby3.2','3.2.3-1ubuntu0.24.04.8'),
    ('python3.12','3.12.3-1ubuntu0.17'), ('libc-bin','2.39-0ubuntu8.9'),
)
KNOWN_DEPENDENCIES = ('ruby-rubygems','rubygems-integration','rake','ruby-net-telnet',
    'ruby-xmlrpc','ruby-webrick','ruby-sdbm','libedit2','libncurses6','libcrypt1',
    'libffi8','libgmp10','libtinfo6','libyaml-0-2')
GUARD = ('systemd','systemd-sysv','systemd-timesyncd','systemd-resolved',
    'systemd-dev','udev','libudev1')
HIGH_IMPACT = ('libc6','libc-bin','libssl3t64','libssl3','openssl','libmount1','libblkid1',
    'libsmartcols1','util-linux','mount','python3.12','python3.12-minimal',
    'libpython3.12-minimal','libpython3.12-stdlib','ruby','ruby3.2','libruby','libruby3.2')
EXTRA = ('libsystemd0', 'libudev1', 'udev', 'systemd', 'systemd-sysv',
    'systemd-timesyncd', 'systemd-resolved', 'systemd-dev')
PKG_RE = re.compile(r'[a-z0-9][a-z0-9+.-]{0,99}(?::[a-z0-9-]{1,20})?\Z')
VERSION_RE = re.compile(r'[A-Za-z0-9.+:~_-]{1,100}\Z')
LABEL_RE = re.compile(r'[A-Za-z0-9.+_/-]{1,160}\Z')
SIDE_EFFECTS = {
    'ldconfig': re.compile(r'\bldconfig\b'),
    'initramfs': re.compile(r'\bupdate-initramfs\b'),
    'alternatives': re.compile(r'\bupdate-alternatives\b'),
    'systemd_helper': re.compile(r'\bdeb-systemd-helper\b'),
    'systemd_invoke': re.compile(r'\bdeb-systemd-invoke\b'),
    'invoke_rc_d': re.compile(r'\binvoke-rc\.d\b'),
    'systemctl': re.compile(r'\bsystemctl\b'),
    'daemon_reload': re.compile(r'\bdaemon-reload\b'),
    'restart': re.compile(r'\b(?:restart|try-restart|force-reload)\b'),
    'certificates': re.compile(r'\bupdate-ca-certificates\b'),
    'python_bytecompile': re.compile(r'\b(?:py3compile|compileall|py_compile|py3clean)\b'),
    'triggers': re.compile(r'\b(?:dpkg-trigger|interest(?:-await|-noawait)?|activate(?:-await|-noawait)?)\b'),
}
MAX_OUTPUT = 1048576
START = time.monotonic()

class Refuse(Exception):
    pass

def need(test, reason='BOUNDS_OR_UNEXPECTED_DATA'):
    if not test: raise Refuse(reason)

def tick():
    need(time.monotonic()-START < 100, 'TIME_LIMIT')

def bounded_file(path, maximum):
    tick()
    fd=os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        st=os.fstat(fd)
        need(stat.S_ISREG(st.st_mode) and st.st_size<=maximum, 'UNSAFE_OR_LARGE_FILE')
        data=os.read(fd,maximum+1)
        need(len(data)<=maximum and not os.read(fd,1), 'UNSAFE_OR_LARGE_FILE')
        return data,st
    finally: os.close(fd)

def run(argv, maximum=131072, timeout=20):
    """Fixed argv only, bounded pipes, no shell, minimal environment, no retries."""
    tick()
    env={'PATH':'/usr/sbin:/usr/bin:/sbin:/bin','LC_ALL':'C','DEBIAN_FRONTEND':'noninteractive'}
    p=subprocess.Popen(argv,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,env=env,start_new_session=True,close_fds=True)
    sel=selectors.DefaultSelector()
    chunks=[bytearray(),bytearray()]
    deadline=min(time.monotonic()+timeout,START+100)
    try:
        for i,stream in enumerate((p.stdout,p.stderr)):
            os.set_blocking(stream.fileno(),False);sel.register(stream,selectors.EVENT_READ,i)
        while sel.get_map():
            need(time.monotonic()<deadline,'COMMAND_TIMEOUT')
            for key,_ in sel.select(min(.2,max(0,deadline-time.monotonic()))):
                part=os.read(key.fileobj.fileno(),8192)
                if not part:sel.unregister(key.fileobj);continue
                chunks[key.data].extend(part)
                need(len(chunks[0])+len(chunks[1])<=maximum,'PLAN_TOO_LARGE' if argv[0]=='apt-get' else 'COMMAND_OUTPUT_TOO_LARGE')
        remain=deadline-time.monotonic()
        need(remain>0,'COMMAND_TIMEOUT')
        code=p.wait(timeout=remain)
        return code,bytes(chunks[0]),bytes(chunks[1])
    finally:
        if p.poll() is None:
            os.killpg(p.pid,signal.SIGKILL);p.wait()
        sel.close();p.stdout.close();p.stderr.close()

def safe_text(raw, maximum=262144, allow_uri=False):
    need(len(raw)<=maximum,'OUTPUT_TOO_LARGE')
    text=raw.decode('utf-8')
    need(all(ch=='\n' or ch=='\t' or 32<=ord(ch)<=126 for ch in text),'UNSAFE_TEXT')
    forbidden=r'(?i)(?:password\s*=|token\s*=|secret\s*=|-----BEGIN)' if allow_uri else r'(?i)(?:://|password\s*=|token\s*=|secret\s*=|-----BEGIN)'
    need(not re.search(forbidden,text),'UNSAFE_TEXT')
    return text

def files_in(directory, suffixes, count=32):
    try:names=sorted(os.listdir(directory))
    except FileNotFoundError:return []
    need(len(names)<=256,'FILE_COUNT_LIMIT')
    result=[]
    for name in names:
        if name.startswith('.') or not any(name.endswith(x) for x in suffixes):continue
        need(re.fullmatch(r'[A-Za-z0-9_.+-]{1,120}',name),'UNSAFE_FILENAME')
        result.append(str(Path(directory)/name))
    need(len(result)<=count,'FILE_COUNT_LIMIT')
    return result

def safe_url(raw):
    need(len(raw)<=512,'UNSAFE_SOURCE')
    u=urlsplit(raw)
    need(u.scheme in ('http','https','file') and not u.fragment,'UNSAFE_SOURCE')
    redacted=bool(u.username or u.password or u.query)
    host=u.hostname or ''
    need(len(host)<=160 and re.fullmatch(r'[A-Za-z0-9.-]*',host),'UNSAFE_SOURCE')
    path=u.path or '/'
    need(len(path)<=240 and re.fullmatch(r'/[A-Za-z0-9._~%/+:-]*',path),'UNSAFE_SOURCE')
    if redacted or re.search(r'(?i)(?:token|secret|passwd|password|key=)|[A-Za-z0-9_-]{65,}',path):
        path='/REDACTED'
        redacted=True
    return {'scheme':u.scheme,'host':host,'port':u.port,'path':path,'credential_redacted':redacted}

def source_rows():
    paths=['/etc/apt/sources.list']+files_in('/etc/apt/sources.list.d',('.list','.sources'))
    rows=[]; total=0
    for path in paths:
        try:raw,_=bounded_file(path,65536)
        except FileNotFoundError:continue
        total+=len(raw);need(total<=262144,'SOURCE_BOUNDS')
        body=raw.decode('utf-8');need(len(body.splitlines())<=512,'SOURCE_BOUNDS')
        if path.endswith('.sources'):
            for stanza in re.split(r'\n\s*\n',body):
                if not stanza.strip():continue
                fields={}
                for line in stanza.splitlines():
                    if not line.strip() or line.lstrip().startswith('#'):continue
                    need(not line[0].isspace() and ':' in line,'MALFORMED_SOURCE')
                    k,v=line.split(':',1);need(k not in fields,'DUPLICATE_SOURCE_FIELD');fields[k]=v.strip()
                if not fields:continue
                need(set(fields).issubset({'Types','URIs','Suites','Components','Architectures','Signed-By','Enabled','Trusted','Check-Valid-Until'}),'UNEXPECTED_SOURCE_FIELD')
                uris=fields.get('URIs','').split();suites=fields.get('Suites','').split()
                need(fields.get('Types','') in ('deb','deb deb-src','deb-src deb','deb-src') and uris and suites,'MALFORMED_SOURCE')
                need(fields.get('Enabled','yes').lower() in ('yes','no'),'MALFORMED_SOURCE')
                signed=fields.get('Signed-By','')
                rows.append({'file':Path(path).name,'uris':[safe_url(x) for x in uris],
                    'suites':suites,'components':fields.get('Components','').split(),
                    'architectures':fields.get('Architectures','').split(),
                    'signed_by':signed if signed.startswith('/') and len(signed)<=200 else ('embedded_public_key' if 'BEGIN PGP PUBLIC KEY BLOCK' in signed else 'unresolved'),
                    'enabled':fields.get('Enabled','yes').lower()=='yes',
                    'trusted_override':fields.get('Trusted'),
                    'valid_until_override':fields.get('Check-Valid-Until')})
        else:
            for line in body.splitlines():
                line=line.strip()
                if not line or line.startswith('#'):continue
                match=re.fullmatch(r'(deb|deb-src)\s+(?:\[([^]]{1,300})\]\s+)?(\S{1,512})\s+(\S{1,80})(?:\s+(.{1,400}))?',line)
                need(match is not None,'MALFORMED_SOURCE')
                opts=match[2] or ''
                option_tokens=opts.split()
                need(len(option_tokens)<=8 and all(re.fullmatch(r'(?:arch|signed-by|trusted|check-valid-until)=[^\s]{1,200}',t) for t in option_tokens),'UNEXPECTED_SOURCE_OPTION')
                option_keys=[t.split('=',1)[0] for t in option_tokens]
                need(len(option_keys)==len(set(option_keys)),'DUPLICATE_SOURCE_OPTION')
                signed=re.search(r'(?:^|\s)signed-by=([^\s]+)',opts)
                arch=re.search(r'(?:^|\s)arch=([^\s]+)',opts)
                rows.append({'file':Path(path).name,'uris':[safe_url(match[3])],
                    'suites':[match[4]],'components':(match[5] or '').split(),
                    'architectures':arch[1].split(',') if arch else [],
                    'signed_by':signed[1] if signed and signed[1].startswith('/') and len(signed[1])<=200 else 'unresolved',
                    'enabled':True,
                    'trusted_override':next((t.split('=',1)[1] for t in option_tokens if t.startswith('trusted=')),None),
                    'valid_until_override':next((t.split('=',1)[1] for t in option_tokens if t.startswith('check-valid-until=')),None)})
        need(len(rows)<=64,'SOURCE_BOUNDS')
    for row in rows:
        for key in ('suites','components','architectures'):
            need(len(row[key])<=16 and all(LABEL_RE.fullmatch(v) for v in row[key]),'UNSAFE_SOURCE')
        need(row['trusted_override'] in (None,'yes','no') and row['valid_until_override'] in (None,'yes','no'),'UNSAFE_SOURCE_OPTION')
        if row['signed_by'].startswith('/'):
            need(re.fullmatch(r'/(?:usr/share/keyrings|etc/apt/keyrings|etc/apt/trusted\.gpg\.d)/[A-Za-z0-9_.+-]{1,100}\.(?:gpg|asc)',row['signed_by']) is not None,'UNSAFE_KEY_PATH')
            need(str(Path(row['signed_by']).resolve())==row['signed_by'],'UNSAFE_KEY_PATH')
    return rows

def file_digest(path,maximum):
    tick()
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
    try:
        st=os.fstat(fd)
        need(stat.S_ISREG(st.st_mode) and st.st_size<=maximum,'UNSAFE_OR_LARGE_INDEX')
        h=hashlib.sha256(); size=0
        while True:
            part=os.read(fd,65536)
            if not part:break
            size+=len(part);need(size<=maximum,'UNSAFE_OR_LARGE_INDEX')
            h.update(part);tick()
        need(size==st.st_size,'INDEX_CHANGED_DURING_READ')
        return h.hexdigest()
    finally:os.close(fd)

def decompressed_index_digest(path,maximum=268435456):
    """APT's own read-only cat-file handles the on-disk compression format."""
    tick()
    env={'PATH':'/usr/sbin:/usr/bin:/sbin:/bin','LC_ALL':'C'}
    p=subprocess.Popen(['/usr/lib/apt/apt-helper','cat-file',path],
        stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
        env=env,start_new_session=True,close_fds=True)
    sel=selectors.DefaultSelector();h=hashlib.sha256();size=0;err=bytearray()
    deadline=min(time.monotonic()+20,START+100)
    try:
        for i,stream in enumerate((p.stdout,p.stderr)):
            os.set_blocking(stream.fileno(),False);sel.register(stream,selectors.EVENT_READ,i)
        while sel.get_map():
            need(time.monotonic()<deadline,'INDEX_DECOMPRESSION_TIMEOUT')
            for key,_ in sel.select(min(.2,max(0,deadline-time.monotonic()))):
                part=os.read(key.fileobj.fileno(),65536)
                if not part:sel.unregister(key.fileobj);continue
                if key.data==0:
                    size+=len(part);need(size<=maximum,'INDEX_DECOMPRESSED_TOO_LARGE');h.update(part)
                else:
                    err.extend(part);need(len(err)<=8192,'INDEX_DECOMPRESSION_ERROR_TOO_LARGE')
        need(p.wait(timeout=max(.01,deadline-time.monotonic()))==0,'INDEX_DECOMPRESSION_FAILED')
        return h.hexdigest(),size
    finally:
        if p.poll() is None:os.killpg(p.pid,signal.SIGKILL);p.wait()
        sel.close();p.stdout.close();p.stderr.close()

def local_index_matches(filename, local):
    return filename==local or filename.startswith(local+'.')

def release_refs(text, prefix, package_files):
    """Validate the entire byte-bounded section; retain only local relation inputs."""
    refs=None; in_section=False; entries=0
    # bounded_file already caps the whole Release at 2 MiB. Iterate lines without
    # a quantifier/slice that could accept a valid prefix and hide a bad tail.
    for line in io.StringIO(text):
        tick()
        if line.startswith('SHA256:'):
            need(refs is None and re.fullmatch(r'SHA256:[ \t]*\r?\n?',line), 'MALFORMED_INDEX_HASH')
            refs=[]; in_section=True
            continue
        if not in_section:continue
        # Clear-signed text may separate the signature with a blank line. Keep
        # scanning: a blank must not hide later malformed or ambiguous entries.
        if line in ('\n','\r\n'):continue
        if not line.startswith((' ','\t')):
            need(entries>0 and re.fullmatch(r'(?:[A-Za-z][A-Za-z0-9-]*:[^\n]*|-----BEGIN PGP SIGNATURE-----\r?)\n?',line), 'MALFORMED_INDEX_HASH')
            in_section=False
            continue
        m=re.fullmatch(r'[ \t]+([0-9a-f]{64})[ \t]+([0-9]{1,15})[ \t]+([A-Za-z0-9._+/-]{1,180})[ \t]*\r?\n?',line)
        need(m is not None,'MALFORMED_INDEX_HASH')
        entries+=1
        if '/binary-amd64/Packages' not in m[3]:continue
        local=prefix+m[3].replace('/','_')
        if any(local_index_matches(name,local) for name in package_files):
            # Same evidence/schema cap, now on useful refs only. Never truncate
            # or deduplicate: duplicates/collisions must still reach ambiguity.
            need(len(refs)<32,'INDEX_REFS_LIMIT')
            refs.append({'path':m[3],'sha256':m[1],'size':int(m[2])})
    need(refs is None or entries>0,'MALFORMED_INDEX_HASH')
    return refs

def index_rows(sources):
    names=files_in('/var/lib/apt/lists',('InRelease','Release','Packages','Packages.lz4','Packages.xz','Packages.gz'),128)
    package_files=[Path(path).name for path in names if 'Packages' in Path(path).name]
    rows=[];total_package_bytes=0
    keypaths=sorted({s['signed_by'] for s in sources if s['signed_by'].startswith('/')})
    for path in names:
        st=os.stat(path,follow_symlinks=False)
        need(stat.S_ISREG(st.st_mode),'UNSAFE_INDEX')
        row={'file':Path(path).name,'size':st.st_size,'mtime_ns':st.st_mtime_ns}
        if 'Packages' in Path(path).name:
            total_package_bytes+=st.st_size
            need(total_package_bytes<=536870912,'INDEX_TOTAL_TOO_LARGE')
            row['sha256']=file_digest(path,134217728)
            row['signed_relation']='UNKNOWN'
            try:
                row['uncompressed_sha256'],row['uncompressed_size']=decompressed_index_digest(path)
            except OSError:
                row['uncompressed_sha256']=None
        if path.endswith(('InRelease','Release')):
            raw,_=bounded_file(path,2097152)
            row['sha256']=hashlib.sha256(raw).hexdigest()
            text=raw.decode('utf-8')
            for key in ('Origin','Label','Suite','Codename','Date','Valid-Until'):
                m=re.search(r'^'+re.escape(key)+r':\s*(.{0,160})$',text,re.M)
                if m:row[key.lower().replace('-','_')]=m[1]
            prefix=row['file'].removesuffix('InRelease').removesuffix('Release')
            refs=release_refs(text,prefix,package_files)
            if refs is not None:row['amd64_package_refs']=refs
            row['signature']={'status':'UNKNOWN'}
            if path.endswith('InRelease') and len(keypaths)==1:
                key=keypaths[0]
                try:
                    key_stat=os.stat(key,follow_symlinks=False)
                    need(stat.S_ISREG(key_stat.st_mode) and key_stat.st_size<=1048576 and key_stat.st_uid==0 and not key_stat.st_mode & 0o022,'UNSAFE_KEY_FILE')
                    code,out,err=run(['gpgv','--status-fd','1','--keyring',key,path],32768,12)
                    m=re.search(rb'^\[GNUPG:\] VALIDSIG ([0-9A-F]{40,64})\b',out,re.M)
                    if code==0 and m:row['signature']={'status':'VERIFIED_WITH_CONFIGURED_PUBLIC_KEY','fingerprint':m[1].decode()}
                except OSError:pass
        rows.append(row)
    releases=[r for r in rows if r['file'].endswith(('InRelease','Release'))]
    for row in rows:
        if 'Packages' not in row['file'] or not row.get('uncompressed_sha256'):continue
        matches=[]
        for release in releases:
            prefix=release['file'].removesuffix('InRelease').removesuffix('Release')
            for ref in release.get('amd64_package_refs',[]):
                local=prefix+ref['path'].replace('/','_')
                if local_index_matches(row['file'],local):
                    matches.append((release,ref))
        need(len(matches)<=1,'INDEX_RELATION_AMBIGUOUS')
        if matches:
            release,ref=matches[0]
            row['release_file']=release['file']
            if row['uncompressed_sha256']==ref['sha256'] and row['uncompressed_size']==ref['size']:
                row['signed_relation']='MATCHED_VERIFIED_RELEASE' if release['signature']['status']=='VERIFIED_WITH_CONFIGURED_PUBLIC_KEY' else 'MATCHED_RELEASE_SIGNATURE_UNKNOWN'
            else:row['signed_relation']='MISMATCH_SIGNED_REFERENCE'
    return rows

def preferences():
    paths=['/etc/apt/preferences']+files_in('/etc/apt/preferences.d',('',),32)
    rows=[];total=0
    for path in paths:
        try:raw,_=bounded_file(path,32768)
        except (FileNotFoundError,IsADirectoryError):continue
        total+=len(raw);need(total<=65536,'PREFERENCE_BOUNDS')
        for stanza in re.split(rb'\n\s*\n',raw):
            if not stanza.strip():continue
            fields={}
            for line in stanza.decode('utf-8').splitlines():
                if not line.strip() or line.lstrip().startswith('#'):continue
                need(':' in line and not line[0].isspace(),'MALFORMED_PREFERENCE')
                k,v=line.split(':',1);need(k in ('Package','Pin','Pin-Priority') and k not in fields,'MALFORMED_PREFERENCE')
                need(len(v)<=256 and not re.search(r'(?i)(password|token|secret|://)',v),'UNSAFE_PREFERENCE')
                fields[k]=v.strip()
            if fields:rows.append({'file':Path(path).name,**fields})
            need(len(rows)<=128,'PREFERENCE_BOUNDS')
    return rows

def config_value(key):
    code,out,err=run(['apt-config','shell','CLB131',key],4096,8)
    need(code==0,'APT_CONFIG_FAILED')
    if not out:return None
    m=re.fullmatch(rb'CLB131=\'([^\'\n]{0,120})\';?\n?',out)
    need(m is not None,'APT_CONFIG_UNEXPECTED')
    return m[1].decode('ascii')

def parse_plan(raw):
    body=safe_text(raw,262144)
    lines=body.splitlines()
    need(len(lines)<=4096 and all(len(x)<=1024 for x in lines),'PLAN_TOO_LARGE')
    actions=[]
    for line in lines:
        m=re.match(r'^(Inst|Conf|Remv) ([a-z0-9][a-z0-9+.-]{0,99}(?::[a-z0-9-]{1,20})?)(?:\s|$)',line)
        if m:actions.append({'action':m[1],'package':m[2],'line':line})
    need(len(actions)<=512,'PLAN_TOO_LARGE')
    names=sorted({x['package'].split(':')[0] for x in actions})
    need(len(names)<=128,'UNEXPECTED_PACKAGE_EXPANSION')
    requested={name for name,_ in REQUEST}
    return {'raw_lines':lines,'actions':actions,'expanded_packages':sorted(set(names)-requested),
        'systemd_udev_effects':[x for x in actions if x['package'].split(':')[0] in GUARD],
        'high_impact_effects':[x for x in actions if x['package'].split(':')[0] in HIGH_IMPACT]}

def package_state(names):
    need(len(names)<=160 and all(PKG_RE.fullmatch(n) for n in names),'PACKAGE_SET_LIMIT')
    fmt='${binary:Package}\t${Version}\t${Architecture}\t${db:Status-Abbrev}\t${Essential}\t${Priority}\n'
    code,out,err=run(['dpkg-query','-W','-f='+fmt,*names],65536,20)
    need(code in (0,1),'DPKG_QUERY_FAILED')
    rows={}
    for line in out.decode('utf-8').splitlines():
        cols=line.split('\t');need(len(cols)==6 and PKG_RE.fullmatch(cols[0]) and cols[0] not in rows,'DUPLICATE_PACKAGE_RECORD')
        need(cols[0] in names and len(line)<=512,'UNEXPECTED_PACKAGE_RECORD')
        rows[cols[0]]={'version':cols[1],'architecture':cols[2],
            'status_abbrev':cols[3],'essential':cols[4],'priority':cols[5]}
    hcode,hout,herr=run(['apt-mark','showhold'],32768,12)
    need(hcode==0,'HOLD_QUERY_FAILED')
    holds=safe_text(hout,32768).splitlines()
    need(len(holds)<=2048 and all(PKG_RE.fullmatch(x) for x in holds),'UNSAFE_HOLDS')
    for name in names:rows.setdefault(name,{'version':None,'architecture':None,'status_abbrev':None,'essential':None,'priority':None})
    for name in names:rows[name]['held']=name in holds
    return rows

def policy(names):
    need(len(names)<=160,'PACKAGE_SET_LIMIT')
    code,out,err=run(['apt-cache','-o','Dir::Cache::pkgcache=','-o','Dir::Cache::srcpkgcache=','policy',*names],131072,20)
    need(code==0,'APT_POLICY_FAILED')
    body=safe_text(out,131072,allow_uri=True)
    rows={}; current=None; current_version=None
    for line in body.splitlines():
        if re.fullmatch(r'[a-z0-9][a-z0-9+.-]{0,99}(?::[a-z0-9-]{1,20})?:',line):
            current=line[:-1];current_version=None;need(current in names and current not in rows,'DUPLICATE_PACKAGE_RECORD');rows[current]={}
        elif current and (m:=re.fullmatch(r'\s*(Installed|Candidate):\s*(\S{1,120})',line)):
            need(m[1].lower() not in rows[current],'DUPLICATE_POLICY_FIELD')
            rows[current][m[1].lower()]=m[2]
        elif current and (m:=re.fullmatch(r'\s*(?:\*\*\*\s+)?([A-Za-z0-9.+:~_-]{1,100})\s+(-?\d{1,8})',line)):
            current_version={'version':m[1],'priority':int(m[2]),'origins':[]}
            rows[current].setdefault('versions',[]).append(current_version)
        elif current and current_version and (m:=re.fullmatch(r'\s*(-?\d{1,8})\s+(https?://\S{1,512})\s+([A-Za-z0-9._+/-]{1,160})\s+([A-Za-z0-9._+/-]{1,160})\s+Packages',line)):
            current_version['origins'].append({'priority':int(m[1]),'uri':safe_url(m[2]),'suite_component':m[3],'architecture_index':m[4]})
            need(len(current_version['origins'])<=32,'POLICY_ORIGIN_LIMIT')
    return rows

def script_metadata(names,state):
    rows=[]
    for name in names:
        if name not in state or not state[name]['version']:continue
        for kind in ('preinst','postinst','prerm','postrm','triggers'):
            path='/var/lib/dpkg/info/'+name+'.'+kind
            try:raw,_=bounded_file(path,65536)
            except FileNotFoundError:continue
            text=raw.decode('utf-8','replace')
            rows.append({'package':name,'installed_version':state[name]['version'],
                'script_type':kind,'sha256':hashlib.sha256(raw).hexdigest(),
                'matched_side_effects':sorted(k for k,pattern in SIDE_EFFECTS.items() if pattern.search(text)),
                'candidate_script':False})
            need(len(rows)<=640,'SCRIPT_COUNT_LIMIT')
    return rows

def generated_impact(actions,scripts):
    categories={}
    for row in scripts:
        categories.setdefault(row['package'],set()).update(row['matched_side_effects'])
    impacts=[]
    for row in actions:
        if row['action']=='Conf':continue
        package=row['package'].split(':')[0]
        mechanisms=categories.get(row['package'],set()) | categories.get(package,set())
        artifacts=[]
        if package.startswith(('python3.12','libpython3.12')):
            artifacts.extend(p for p in ACCEPTED_PATHS if p.endswith('.pyc'))
            if package=='python3.12':artifacts.append('/usr/bin/python3')
        if package=='ruby':artifacts.append('/usr/bin/ruby')
        if package in ('libc-bin','libc6') or 'ldconfig' in mechanisms:
            artifacts.append('/etc/ld.so.cache')
        if package in ('util-linux','libmount1'):artifacts.append('/bin/findmnt')
        if artifacts:
            impacts.append({'package':package,'action':row['action'],
                'observed_installed_script_mechanisms':sorted(mechanisms),
                'accepted_artifacts_potentially_affected':sorted(set(artifacts)),
                'exact_byte_reproduction':'NOT_PROVEN',
                'candidate_script_mechanisms':'UNKNOWN'})
    need(len(impacts)<=128,'GENERATED_IMPACT_LIMIT')
    return impacts

def outside_mapping(accepted_paths,target_names):
    raw,_=bounded_file('/proc/self/maps',65536)
    paths=set()
    for line in raw.decode('ascii').splitlines():
        fields=line.split(None,5)
        need(len(fields)>=5,'MALFORMED_MAPS')
        if len(fields)==6 and fields[-1].startswith('/'):paths.add(fields[-1])
    extra=sorted(paths-set(accepted_paths))
    if len(extra)!=1:return {'classification':'UNKNOWN_REQUIRES_CONTRACT_DECISION','outside_count':len(extra),'reason':'not_exactly_one_current_collector_mapping'}
    path=extra[0]
    if not (len(path)<=256 and re.fullmatch(r'/(?:usr|lib|lib64)/[A-Za-z0-9._+/-]+',path)):
        return {'classification':'UNKNOWN_REQUIRES_CONTRACT_DECISION','outside_count':1,'path':'REDACTED_UNSAFE_PATH'}
    st=os.stat(path,follow_symlinks=False)
    kind='regular' if stat.S_ISREG(st.st_mode) else 'symlink' if stat.S_ISLNK(st.st_mode) else 'other'
    code,out,err=run(['dpkg-query','-S',path],4096,8)
    owner=None
    if code==0:
        m=re.fullmatch(rb'([a-z0-9+.-]{1,100})(?::[a-z0-9-]{1,20})?: '+re.escape(path.encode())+rb'\n?',out)
        if m:owner=m[1].decode()
    return {'classification':'UNKNOWN_REQUIRES_CONTRACT_DECISION',
        'outside_count':1,'path':path,'type':kind,'owning_package':owner,
        'current_collector_mapping':True,'same_as_CLB130_bootstrap':'NOT_PROVEN',
        'target_package_coverage_candidate':owner in target_names,'semantic_dependency':'UNKNOWN',
        'artifact_class':'shared_library' if path.endswith('.so') or '.so.' in path else 'data_or_other'}

def collect():
    arch=run(['dpkg','--print-architecture'],1024,5)
    need(arch[0]==0 and arch[1].strip()==b'amd64','UNEXPECTED_ARCHITECTURE')
    foreign=run(['dpkg','--print-foreign-architectures'],1024,5)
    need(foreign[0]==0,'ARCHITECTURE_QUERY_FAILED')
    osrel,_=bounded_file('/etc/os-release',4096)
    osid={}
    for line in osrel.decode('utf-8').splitlines():
        m=re.fullmatch(r'(ID|VERSION_ID|VERSION_CODENAME)=(?:"([A-Za-z0-9._-]{1,80})"|([A-Za-z0-9._-]{1,80}))',line)
        if m:osid[m[1]]=m[2] or m[3]
    need(osid.get('ID')=='ubuntu' and osid.get('VERSION_ID')=='24.04','UNEXPECTED_OS')
    sources=source_rows();indexes=index_rows(sources);prefs=preferences()
    config={key:config_value(key) for key in ('APT::Architecture','APT::Install-Recommends',
        'APT::Install-Suggests','APT::Get::Allow-Downgrades','APT::Get::Upgrade-Allow-New',
        'APT::Default-Release','APT::Get::Always-Include-Phased-Updates',
        'APT::Get::Never-Include-Phased-Updates','APT::Get::Allow-Change-Held-Packages',
        'APT::Get::Allow-Remove-Essential')}
    requested=[name for name,_ in REQUEST]
    initial=sorted(set(requested)|set(KNOWN_DEPENDENCIES)|set(EXTRA))
    before=package_state(initial)
    before_policy=policy(initial)
    args=[name+'='+version for name,version in REQUEST]
    command=['apt-get','-s','-o','Debug::NoLocking=1','-o','Dir::Cache::pkgcache=',
        '-o','Dir::Cache::srcpkgcache=','install',*args]
    code,out,err=run(command,262144,45)  # exactly one resolver simulation
    combined=out+(b'\n' if out and err else b'')+err
    plan=parse_plan(combined)
    expanded=plan['expanded_packages']
    extra_names=sorted(set(expanded)-set(initial))
    after=package_state(extra_names) if extra_names else {}
    after_policy=policy(extra_names) if extra_names else {}
    changed=sorted({x['package'] for x in plan['actions']})
    state={**before,**after};pol={**before_policy,**after_policy}
    scripts=script_metadata(changed,state)
    target_names=set(requested)
    result={'schema':SCHEMA,'main':MAIN,'target_sha256':TARGET_SHA256,
        'os':osid,'architecture':'amd64','foreign_architectures':safe_text(foreign[1],1024).splitlines(),
        'sources':sources,'indexes':indexes,'preferences':prefs,'apt_config':config,
        'package_state':state,'apt_policy':pol,'resolver':{'exit_status':code,'requested':args,**plan},
        'maintainer_scripts':scripts,'outside_mapping':outside_mapping(ACCEPTED_PATHS,target_names),
        'candidate_script_coverage':'UNKNOWN_FOR_NOT_CACHED_CANDIDATES',
        'generated_state_impact':generated_impact(plan['actions'],scripts),
        'systemd_udev_flag':'FORBIDDEN_SYSTEMD_UDEV_EXPANSION_PRESENT' if plan['systemd_udev_effects'] else 'NO_TRANSITION_OBSERVED',
        'generated_state_proof':'NOT_ESTABLISHED'}
    return result

def emit(result):
    data=json.dumps(result,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode('ascii')+b'\n'
    need(len(data)<=MAX_OUTPUT,'OUTPUT_TOO_LARGE')
    os.write(1,data)

def timeout_refusal(_signal,_frame):
    raise Refuse('TIME_LIMIT')

if __name__=='__main__':
    try:
        need(sys.flags.isolated and sys.flags.no_site and sys.dont_write_bytecode,'WRONG_INTERPRETER_MODE')
        need(len(sys.argv)==1,'UNEXPECTED_ARGUMENTS')
        signal.signal(signal.SIGALRM,timeout_refusal)
        signal.alarm(100)
        emit(collect())
    except (Refuse,OSError,UnicodeError,ValueError,subprocess.SubprocessError) as error:
        reason=str(error) if isinstance(error,Refuse) else 'READ_ONLY_COLLECTION_FAILED'
        try:emit({'schema':SCHEMA,'result':'UNAVAILABLE','reason':reason})
        except Exception:pass
        sys.exit(1)
