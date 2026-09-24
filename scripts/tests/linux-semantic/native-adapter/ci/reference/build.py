#!/usr/bin/env python3
"""Two fresh deterministic rootfs builds, using an explicitly checked reference image and archive.

No apt, package scripts, network, chroot or privileged container is used.
Docker is a local test harness, not a proposed production launcher dependency.
"""
import argparse, hashlib, io, json, os, pathlib, posixpath, re, shutil, subprocess, tarfile, tempfile, time, uuid
P = pathlib.Path
HERE = P(__file__).resolve().parent
FLAGS = ['--rm', '--pull=never', '--platform', 'linux/amd64', '--network', 'none', '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--user', '1000:1000', '--pids-limit', '64', '--memory', '768m', '--tmpfs', '/tmp:rw,nosuid,nodev,noexec,size=64m,mode=1777']
commands = []
def sha(b): return hashlib.sha256(b).hexdigest()
def run(args, *, output=None, input=None, timeout=120):
    args=list(args)
    owned=None
    if args[:2]==['docker','run']:
        owned={'token':uuid.uuid4().hex,'directory':tempfile.TemporaryDirectory(prefix='owned-container-',dir=HERE)}
        owned['cidfile']=P(owned['directory'].name)/'cid'
        args[2:2]=['--label','clb91.build-invocation='+owned['token'],'--cidfile',str(owned['cidfile'])]
    start = time.time()
    record={'argv':args,'exit':None,'primary_error':None,'cleanup':None}
    def control(argv):
        q=subprocess.run(argv,capture_output=True,timeout=10)
        if q.returncode!=0 or len(q.stdout)>65536 or len(q.stderr)>65536: raise RuntimeError('owned_container_control_failed')
        return q.stdout.decode('ascii').strip()
    def cleanup():
        if owned is None:return {'status':'not_applicable'}
        result={'status':'unknown','container':None,'removed':False}
        try:
            # Exact unique invocation label, never a global container inventory.
            ids=control(['docker','container','ls','--all','--quiet','--no-trunc','--filter','label=clb91.build-invocation='+owned['token']]).splitlines()
            if len(ids)>1: raise RuntimeError('owned_container_ambiguous')
            if ids:
                cid=ids[0]
                if not re.fullmatch('[0-9a-f]{64}',cid):raise RuntimeError('owned_container_identity')
                if owned['cidfile'].exists() and owned['cidfile'].read_text().strip()!=cid:raise RuntimeError('owned_container_cid_mismatch')
                labels=json.loads(control(['docker','container','inspect',cid,'--format','{{json .Config.Labels}}']))
                if labels.get('clb91.build-invocation')!=owned['token']:raise RuntimeError('owned_container_label_mismatch')
                result['container']=cid
                control(['docker','container','rm','--force',cid]);result['removed']=True
                remain=control(['docker','container','ls','--all','--quiet','--no-trunc','--filter','id='+cid])
                if remain:raise RuntimeError('owned_container_remaining')
            result['status']='confirmed_absent'
        except Exception as e:
            result['error']=str(e)
        finally:
            owned['directory'].cleanup()
        return result
    try:
        p = subprocess.run(args, input=input, stdout=output or subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, env={'PATH':os.environ['PATH'],'HOME':os.environ['HOME'],'LANG':'C','DOCKER_BUILDKIT':'1'})
        record.update(exit=p.returncode,stderr_bytes=len(p.stderr))
    except BaseException as e:
        record['primary_error']=type(e).__name__
        raise
    finally:
        record['cleanup']=cleanup()
        record['elapsed_seconds']=round(time.time()-start,3)
        commands.append(record)
    if record['cleanup'].get('error'):raise RuntimeError('owned_container_cleanup_failed')
    if p.returncode: raise RuntimeError(f'command failed {args!r}: {p.stderr.decode(errors="replace")[:2000]}')
    return p
def archive(items):
    b=io.BytesIO()
    with tarfile.open(fileobj=b,mode='w',format=tarfile.USTAR_FORMAT) as tf:
        for name,data in sorted(items.items()):
            ti=tarfile.TarInfo(name);ti.mode=0o444;ti.size=len(data);tf.addfile(ti,io.BytesIO(data))
    return b.getvalue()
def image_build(root_tar, out, tag):
    dockerfile=b'FROM scratch\nADD rootfs.tar /\nUSER 1000:1000\nENV PYTHONDONTWRITEBYTECODE=1\nWORKDIR /opt/clubs-bot-stage\n'
    context=archive({'Dockerfile':dockerfile,'rootfs.tar':root_tar})
    (out/'Dockerfile').write_bytes(dockerfile)
    p=run(['docker','build','--platform','linux/amd64','--network','none','--pull=false','--no-cache','-t',tag,'-'],input=context,timeout=180)
    (out/(tag.rsplit('-',1)[-1]+'.build-log.txt')).write_bytes(p.stdout+p.stderr)
    image=run(['docker','image','inspect',tag,'--format','{{.Id}}']).stdout.decode().strip()
    return image
def build(label, destination, image, archive):
    out=destination/label;out.mkdir(exist_ok=False)
    with (out/'generator-rootfs.tar').open('wb') as f:
        p=run(['docker','run',*FLAGS,'--mount',f'type=bind,src={HERE},dst=/build,readonly','--mount',f'type=bind,src={archive},dst=/input-libruby.deb,readonly','--entrypoint','/usr/bin/python3.12',image,'-I','-S','-B','/build/export_inputs.py'],output=f)
    source_evidence=json.loads(p.stderr)
    (out/'source-evidence.json').write_text(json.dumps(source_evidence,indent=2,sort_keys=True)+'\n')
    generator=image_build((out/'generator-rootfs.tar').read_bytes(),out,f'clb91-private-{destination.name}-{label}-generator')
    code="import subprocess,sys; subprocess.run(['/usr/sbin/ldconfig.real','-X','-i','-C','/tmp/own-cache','-f','/etc/ld.so.conf'],check=True,timeout=20); sys.stdout.buffer.write(open('/tmp/own-cache','rb').read())"
    p=run(['docker','run',*FLAGS,'--entrypoint','/usr/bin/python3.12',generator,'-I','-S','-B','-c',code])
    cache=p.stdout
    assert cache.startswith(b'glibc-ld.so.cache'), cache[:80]
    (out/'generated-ld.so.cache').write_bytes(cache)
    listing=run(['docker','run',*FLAGS,'--mount',f'type=bind,src={out}/generated-ld.so.cache,dst=/tmp/generated-cache,readonly','--entrypoint','/usr/sbin/ldconfig.real',generator,'-p','-C','/tmp/generated-cache']).stdout
    (out/'cache-entries.txt').write_bytes(listing)
    contents=[]
    with tarfile.open(out/'generator-rootfs.tar','r:') as tf:
        for ti in tf:
            if ti.name=='usr/sbin/ldconfig.real': continue
            data=tf.extractfile(ti).read() if ti.isfile() else None
            contents.append((ti,data))
    ti=tarfile.TarInfo('etc/ld.so.cache');ti.mode=0o444;ti.size=len(cache);contents.append((ti,cache))
    manifest=[]
    with tarfile.open(out/'rootfs.tar','w',format=tarfile.USTAR_FORMAT) as tf:
        for ti,data in sorted(contents,key=lambda x:x[0].name):
            tf.addfile(ti,io.BytesIO(data) if data is not None else None)
            manifest.append({'path':'/'+ti.name,'type':'file' if ti.isfile() else 'symlink' if ti.issym() else 'directory','uid':ti.uid,'gid':ti.gid,'mode':oct(ti.mode),'mtime':ti.mtime,**({'bytes':len(data),'sha256':sha(data)} if data is not None else {'target':ti.linkname} if ti.issym() else {})})
    (out/'rootfs-manifest.json').write_text(json.dumps(manifest,indent=2,sort_keys=True)+'\n')
    accepted=json.loads((HERE/'accepted-manifest.json').read_bytes())
    accepted['files']['/etc/ld.so.cache']=sha(cache)
    for ti,data in contents:
        if data is not None: accepted['files']['/'+ti.name]=sha(data)
    # Resolve every infrastructure / SONAME link against this exact tar tree,
    # never the builder host filesystem. Keep the four accepted alias meanings.
    nodes={'/'+ti.name:ti for ti,_ in contents}
    def resolve(path):
        path=posixpath.normpath(path)
        for _ in range(64):
            parts=path.split('/')[1:]
            for i in range(len(parts)):
                prefix='/'+'/'.join(parts[:i+1]);node=nodes.get(prefix)
                assert node is not None, ('alias_missing_component',prefix)
                if node.issym():
                    target=node.linkname
                    if not target.startswith('/'): target=posixpath.join(posixpath.dirname(prefix),target)
                    path=posixpath.normpath(posixpath.join(target,*parts[i+1:]))
                    break
            else:
                assert path in nodes
                return path
        raise AssertionError('alias_cycle_or_depth')
    for alias,expected in accepted['aliases'].items(): assert resolve(alias)==expected
    for path,node in nodes.items():
        if node.issym(): accepted['aliases'][path]=resolve(path)
    (out/'candidate-runtime.json').write_text(json.dumps(accepted,indent=2,sort_keys=True)+'\n')
    final=image_build((out/'rootfs.tar').read_bytes(),out,f'clb91-private-{destination.name}-{label}-runtime')
    result={'label':label,'image':final,'generator_image':generator,'generated_pyc_count':len(source_evidence['pyc']),'cache_sha256':sha(cache),'rootfs_sha256':sha((out/'rootfs.tar').read_bytes()),'manifest_sha256':sha((out/'rootfs-manifest.json').read_bytes()),'candidate_runtime_sha256':sha((out/'candidate-runtime.json').read_bytes()),'entry_count':len(manifest),'regular_files':sum(x['type']=='file' for x in manifest),'symlinks':sum(x['type']=='symlink' for x in manifest)}
    (out/'result.json').write_text(json.dumps(result,indent=2,sort_keys=True)+'\n')
    print(json.dumps(result),flush=True)
    return result
def bound_inputs(image, archive):
    # The coordinator verifies the reconstructed reference manifest/package set
    # before passing its immutable image ID. No tag, search or local fallback.
    if not re.fullmatch('sha256:[0-9a-f]{64}', image): raise ValueError('image_identity')
    path=P(archive)
    if not path.is_absolute() or any(c in str(path) for c in ('\n','\r',',')) or '..' in path.parts: raise ValueError('archive_path')
    for parent in (path,*path.parents):
        if parent.is_symlink(): raise ValueError('archive_symlink')
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
    try:
        import stat
        before=os.fstat(fd)
        if not stat.S_ISREG(before.st_mode) or before.st_size!=5342224: raise ValueError('archive_identity')
        raw=bytearray()
        while len(raw)<=5342224:
            part=os.read(fd,min(65536,5342225-len(raw)))
            if not part: break
            raw.extend(part)
        identity=lambda st:(st.st_dev,st.st_ino,st.st_size,st.st_mtime_ns,st.st_ctime_ns)
        if identity(before)!=identity(os.fstat(fd)) or identity(before)!=identity(path.lstat()): raise ValueError('archive_drift')
        if len(raw)!=5342224 or sha(raw)!='77c2e98ccfba2c4a4e5edb48ca8c0eb041702e1579eabc40cf07064fd4f3033e': raise ValueError('archive_identity')
    finally: os.close(fd)
    return image,str(path)

def main():
    p=argparse.ArgumentParser();p.add_argument('destination');p.add_argument('--labels',nargs='+',default=['a','b']);p.add_argument('--image',required=True);p.add_argument('--archive',required=True);a=p.parse_args()
    image,archive=bound_inputs(a.image,a.archive)
    dest=P(a.destination).resolve();dest.mkdir(exist_ok=True)
    try:
        results=[build(label,dest,image,archive) for label in a.labels]
        comparison={'builds':results,'independently_generated':True,'architecture':'linux/amd64 requested; execution environment recorded by native coordinator','rootfs_identical':len({r['rootfs_sha256'] for r in results})==1,'metadata_content_manifest_identical':len({r['manifest_sha256'] for r in results})==1,'candidate_manifest_identical':len({r['candidate_runtime_sha256'] for r in results})==1}
        (dest/'comparison.json').write_text(json.dumps(comparison,indent=2,sort_keys=True)+'\n')
        assert all(comparison[k] for k in ('rootfs_identical','metadata_content_manifest_identical','candidate_manifest_identical'))
    finally:
        (dest/'commands.json').write_text(json.dumps(commands,indent=2,sort_keys=True)+'\n')
if __name__=='__main__':main()
