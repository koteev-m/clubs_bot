#!/usr/bin/env python3
"""Check exactly the proposed synthetic artifact allowlist; never scan host data."""
import argparse,json,os,pathlib,stat
P=pathlib.Path
NATIVE=('environment.json','result.json','commands.json','compiler.json')
LIMIT=2**20

def checked_file(path):
 fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
 try:
  a=os.fstat(fd)
  if not stat.S_ISREG(a.st_mode) or a.st_size>LIMIT:raise ValueError('artifact_type_or_bound')
  raw=os.read(fd,LIMIT+1)
  if len(raw)!=a.st_size or len(raw)>LIMIT or not os.path.samestat(a,path.lstat()):raise ValueError('artifact_changed_or_bound')
  json.loads(raw)
  b=os.fstat(fd)
  if (a.st_dev,a.st_ino,a.st_size,a.st_mtime_ns,a.st_ctime_ns)!=(b.st_dev,b.st_ino,b.st_size,b.st_mtime_ns,b.st_ctime_ns):raise ValueError('artifact_changed')
  return len(raw)
 finally:os.close(fd)

def check(root):
 root=P(root)
 if not root.is_absolute() or root.is_symlink():raise ValueError('artifact_root')
 entries=[]
 for base,names in ((root/'clb91-adapter-inputs/evidence',('status.json',)),(root/'clb91-native-adapter/evidence',NATIVE)):
  for p in (base.parent,base):
   if p.exists() and (p.is_symlink() or not p.is_dir()):raise ValueError('artifact_directory_type')
  if not base.exists():continue
  if base.parent.name=='clb91-native-adapter' and set(x.name for x in base.iterdir())-set(NATIVE):raise ValueError('artifact_allowlist')
  for name in names:
   p=base/name
   if p.is_symlink():raise ValueError('artifact_symlink')
   if p.exists():entries.append((str(p),checked_file(p)))
 if not entries:raise ValueError('no_structured_evidence')
 if sum(x[1] for x in entries)>LIMIT:raise ValueError('artifact_total_bound')
 return {'checked_files':len(entries),'bytes':sum(x[1] for x in entries),'limit_bytes':LIMIT,'scope':'synthetic artifact representation only; no native success inferred'}
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('runner_temp');a=p.parse_args();print(json.dumps(check(a.runner_temp),sort_keys=True))
