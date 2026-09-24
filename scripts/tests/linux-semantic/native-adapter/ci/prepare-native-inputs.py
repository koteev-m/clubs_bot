#!/usr/bin/env python3
"""Future manual CI preparation only; never imported by the trusted adapter.

No external preparation is executed by unit tests. Production trust does not rest
on this host Python: generated runtime bytes are checked independently by the
static adapter against its embedded candidate closure before execution.
"""
import argparse, hashlib, importlib.util, json, os, pathlib, platform, re
import shutil, signal, subprocess, sys, time, selectors, uuid
P=pathlib.Path
HERE=P(__file__).resolve().parent
REF=HERE/'reference'
ROOTFS='f78a1d704c746a17ee5c848ab0667ca64859bf48bc94fdc45eb46b40791b3f22'
MANIFEST='68fc3b06b2198b06f1583c0bb1157669c4df6d8c9548934ed3c79315d6e82649'
INPUTS='a365d37e6bf45dacfe672364d2e3493bc3c786e692940e4ee0566b8f97fd5833'
LOG_LIMIT=8*2**20
SOURCE_PINS='f8e2e703dfa651334462c343d14b0ad4b1911d2f5ec900451012af12f983d282'

def sha(x): return hashlib.sha256(x).hexdigest()
def verify_sources(ref=REF):
    if ref.is_symlink() or (ref/'SOURCE-PINS.json').is_symlink():raise ValueError('source_type')
    raw_pins=(ref/'SOURCE-PINS.json').read_bytes()
    if sha(raw_pins)!=SOURCE_PINS:raise ValueError('source_pins_identity')
    pins=json.loads(raw_pins)
    for name,row in pins.items():
        if name in ('.','..') or not re.fullmatch('[A-Za-z0-9_.-]+',name):raise ValueError('source_name')
        p=ref/name
        if p.is_symlink() or not p.is_file():raise ValueError('source_type')
        raw=p.read_bytes()
        if len(raw)!=row['bytes'] or sha(raw)!=row['sha256']:raise ValueError('source_identity:'+name)
    if pins['amd64-inputs.json']['sha256']!=INPUTS:raise ValueError('input_lock')
    return pins

def native_guard(system,machine,runner,event):
    if (system,machine,runner,event)!=('Linux','x86_64','X64','workflow_dispatch'):
        raise ValueError('native_manual_ci_required')

def builder_argv(builder, destination, image, archive):
    if not re.fullmatch('sha256:[0-9a-f]{64}',image):raise ValueError('image_identity')
    archive=P(archive)
    if not archive.is_absolute() or '..' in archive.parts or any(c in str(archive) for c in ('\n','\r',',')):raise ValueError('archive_path')
    return [sys.executable,'-I','-S','-B',str(builder),str(destination),'--labels','native','--image',image,'--archive',str(archive)]

def checked_final(path):
    raw=(path/'rootfs.tar').read_bytes()
    if len(raw)!=59392000 or sha(raw)!=ROOTFS:raise ValueError('rootfs_identity')
    if sha((path/'candidate-runtime.json').read_bytes())!=MANIFEST:raise ValueError('manifest_identity')
    return {'rootfs_bytes':len(raw),'rootfs_sha256':ROOTFS,'candidate_manifest_sha256':MANIFEST}

def cleanup_owned(cidfile,token,control):
    """One exact label/CID ownership protocol, including create interruption."""
    result={'status':'UNKNOWN','container':None,'removed':False}
    ids=control(['docker','container','ls','--all','--quiet','--no-trunc','--filter','label=clb91.native-input='+token]).splitlines()
    if len(ids)>1:raise ValueError('owned_container_ambiguous')
    if ids:
        cid=ids[0]
        if not re.fullmatch('[0-9a-f]{64}',cid):raise ValueError('owned_container_identity')
        if cidfile.exists() and cidfile.read_text().strip()!=cid:raise ValueError('owned_container_cid_mismatch')
        labels=json.loads(control(['docker','container','inspect',cid,'--format','{{json .Config.Labels}}']))
        if labels.get('clb91.native-input')!=token:raise ValueError('owned_container_label_mismatch')
        result['container']=cid
        control(['docker','container','rm','--force',cid]);result['removed']=True
        if control(['docker','container','ls','--all','--quiet','--no-trunc','--filter','id='+cid]):raise ValueError('owned_container_remaining')
    result['status']='confirmed_absent'
    return result

def main():
    ap=argparse.ArgumentParser();ap.add_argument('work');ap.add_argument('--authorized-external-preparation',action='store_true');a=ap.parse_args()
    if not a.authorized_external_preparation:raise ValueError('future_permission_not_indicated')
    native_guard(platform.system(),platform.machine(),os.environ.get('RUNNER_ARCH'),os.environ.get('GITHUB_EVENT_NAME'))
    pins=verify_sources();work=P(a.work).resolve()
    if work.exists():raise ValueError('work_collision')
    work.mkdir(mode=0o700);evidence=work/'evidence';evidence.mkdir();(work/'home').mkdir();(work/'docker-config').mkdir()
    env={'PATH':'/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin','HOME':str(work/'home'),'DOCKER_CONFIG':str(work/'docker-config'),'LANG':'C','LC_ALL':'C','DOCKER_BUILDKIT':'1','PYTHONDONTWRITEBYTECODE':'1'}
    def cancel(*_):raise KeyboardInterrupt()
    previous={n:signal.signal(n,cancel) for n in (signal.SIGINT,signal.SIGTERM,signal.SIGHUP)}
    commands=[];status={'verdict':'PREPARATION_FAILED','external_preparation_requested':True,'adapter_test':'NOT_RUN','package_scripts':'NOT_RUN'}
    def run(label,argv,limit=600,capture=False):
        record={'label':label,'argv':argv,'timeout_seconds':limit};commands.append(record)
        log=evidence/(label+'.log')
        with log.open('xb') as f:
            q=subprocess.Popen(argv,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,start_new_session=True)
            end=time.monotonic()+limit;size=0;sel=selectors.DefaultSelector();sel.register(q.stdout,selectors.EVENT_READ)
            try:
                while sel.get_map():
                    remain=end-time.monotonic()
                    if remain<=0:raise subprocess.TimeoutExpired(argv,limit)
                    for key,_ in sel.select(remain):
                        chunk=os.read(key.fileobj.fileno(),65536)
                        if not chunk:sel.unregister(key.fileobj);break
                        if size+len(chunk)>LOG_LIMIT:raise ValueError('preparation_log_bound')
                        f.write(chunk);size+=len(chunk)
                record['exit']=q.wait(timeout=max(.01,end-time.monotonic()))
            except BaseException as exc:
                record['primary_error']=type(exc).__name__
                try:os.killpg(q.pid,signal.SIGTERM)
                except ProcessLookupError:pass
                try:q.wait(timeout=15)
                except subprocess.TimeoutExpired:os.killpg(q.pid,signal.SIGKILL);q.wait()
                record['descendant_daemon_cleanup']='UNKNOWN for external preparation; adapter not started'
                raise
            finally:sel.close();q.stdout.close()
        record['output_bytes']=log.stat().st_size
        if record['output_bytes']>LOG_LIMIT:raise ValueError('preparation_log_bound')
        if record['exit']!=0:raise ValueError('preparation_command_failed:'+label)
        return log.read_bytes() if capture else None
    def owned_run(label,argv,limit):
        token=uuid.uuid4().hex;cidfile=work/(label+'.cid')
        if cidfile.exists():raise ValueError('owned_container_collision')
        argv=argv[:2]+['--label','clb91.native-input='+token,'--cidfile',str(cidfile)]+argv[2:]
        primary=None;result=None;record=None
        def control(args):
            p=subprocess.run(args,env=env,capture_output=True,timeout=10)
            if p.returncode or len(p.stdout)>65536 or len(p.stderr)>65536:raise ValueError('owned_container_control_failed')
            return p.stdout.decode('ascii').strip()
        try:
            result=run(label,argv,limit,True);record=commands[-1]
        except BaseException as error:
            primary=error;record=commands[-1]
        finally:
            try:record['owned_cleanup']=cleanup_owned(cidfile,token,control)
            except BaseException as error:record['owned_cleanup']={'status':'UNKNOWN','error':str(error) if isinstance(error,ValueError) else type(error).__name__}
        if primary is not None:raise primary
        if record['owned_cleanup']['status']!='confirmed_absent':raise ValueError('owned_container_cleanup_failed')
        return result
    try:
        arch=run('docker-architecture',['docker','info','--format','{{.Architecture}}'],30,True).decode().strip()
        if arch not in ('x86_64','amd64'):raise ValueError('docker_native_architecture')
        run('fetch',[sys.executable,'-I','-S','-B',str(REF/'prepare-amd64-downloads.py'),str(work/'downloads')],900)
        run('context',[sys.executable,'-I','-S','-B',str(REF/'prepare-amd64-context.py'),str(work/'downloads'),str(work/'reference-build')],60)
        # Existing exact Dockerfile installs only in a disposable offline image.
        status['package_scripts']='DISPOSABLE_REFERENCE_IMAGE_ONLY'
        tag='clb91-adapter-reference:'+str(os.getpid())
        run('reference-build',['docker','build','--platform','linux/amd64','--pull=false','--no-cache','--network=none','-t',tag,str(work/'reference-build')],600)
        image=run('reference-image',['docker','image','inspect',tag,'--format','{{.Id}}'],30,True).decode().strip()
        if not re.fullmatch('sha256:[0-9a-f]{64}',image):raise ValueError('reference_image_identity')
        # Derive the actual profile and package inventory before accepting image as source.
        flags=['docker','run','--rm','--pull=never','--platform','linux/amd64','--network=none','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--user','1000:1000']
        actual=owned_run('reference-manifest',flags+['--mount',f'type=bind,src={REF},dst=/material,readonly',image,'python3','-I','-S','-B','/material/derive-amd64-manifest.py','/material/arm64-runtime-reference.json'],90)
        if actual!=(REF/'amd64-runtime-candidate.json').read_bytes():raise ValueError('reference_manifest_mismatch')
        packages=owned_run('reference-packages',flags+[image,'cat','/toolchain-packages.txt'],30)
        if packages!=(REF/'amd64-packages.txt').read_bytes():raise ValueError('reference_packages_mismatch')
        build=work/'candidate-build';build.mkdir()
        for name in ('export_inputs.py','accepted-manifest.json','additional-inputs.json'):shutil.copyfile(REF/name,build/name)
        archive=work/'downloads/debs/libruby3.2_3.2.3-1ubuntu0.24.04.8_amd64.deb'
        source=(REF/'build.py').read_bytes();(build/'build.py').write_bytes(source)
        status['builder_source_sha256']=sha(source)
        run('candidate-build',builder_argv(build/'build.py',work/'runtime',image,archive),300)
        status.update(checked_final(work/'runtime/native'));status.update(verdict='EXACT_NATIVE_INPUTS_PREPARED',reference_image=image)
        return 0
    except BaseException as exc:
        status['primary_error']=str(exc) if isinstance(exc,ValueError) else type(exc).__name__
        return 1
    finally:
        for n,handler in previous.items():signal.signal(n,handler)
        (evidence/'commands.json').write_text(json.dumps(commands,indent=2,sort_keys=True)+'\n');(evidence/'status.json').write_text(json.dumps(status,indent=2,sort_keys=True)+'\n')
if __name__=='__main__':
    try:raise SystemExit(main())
    except ValueError as e:print(json.dumps({'verdict':'STOP_BEFORE_PREPARATION','reason':str(e)}));raise SystemExit(1)
