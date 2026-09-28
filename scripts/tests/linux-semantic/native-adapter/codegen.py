#!/usr/bin/env python3
"""Exact candidate source/runtime -> closed C contract; no execution or download."""
import base64,hashlib,json,pathlib,struct,re
P=pathlib.Path;H=P(__file__).resolve().parent;R=H/'reference'
sha=lambda b:hashlib.sha256(b).hexdigest()
assert not R.is_symlink() and not (R/'pins.json').is_symlink(),'reference_type'
ledger=(R/'pins.json').read_bytes()
assert sha(ledger)=='ada8f2c752449a661cb287373d414dade90d9c24daeaea98a3f4c17d4480bab4','reference_ledger'
pins=json.loads(ledger)
raw={}
for name,x in pins.items():
 assert name not in ('.','..') and re.fullmatch('[A-Za-z0-9_.-]+',name) and not (R/name).is_symlink(),'reference_name_or_type'
 b=(R/name).read_bytes();assert len(b)==x['bytes'] and sha(b)==x['sha256'],name;raw[name]=b
manifest=json.loads(raw['candidate-runtime.json']);entries=json.loads(raw['rootfs-manifest.json'])
assert sha(raw['candidate-runtime.json'])=='68fc3b06b2198b06f1583c0bb1157669c4df6d8c9548934ed3c79315d6e82649'
assert sha(raw['stage-compose-env-semantic-operation.py'])=='af328463da08079defb51b163af1309faa7820e5dd9ded6da910501e251d47b6'
assert len(manifest['files'])==196 and len(manifest['aliases'])==23 and len(entries)==267
names=('stage-compose-diagnostic-operation.py','stage-compose-env-file-plan.py','release_private_root.py','stage-compose-env-semantic-operation.py','stage-compose-env-semantic-runtime.json')
sources={n:raw['candidate-runtime.json'] if n==names[-1] else raw[n] for n in names}
encoded=json.dumps({n:base64.b64encode(v).decode() for n,v in sources.items()},sort_keys=True,separators=(',',':')).encode()
control=json.dumps({'principal':'prototype','sha256':sha(encoded)},sort_keys=True,separators=(',',':')).encode()
tail=struct.pack('!I',len(control))+control+encoded
assert len(tail)+32<=525348
out=['/* Generated from pinned candidate inputs; no caller-selected commands. */', '#ifndef CLB91_CONTRACT_H','#define CLB91_CONTRACT_H','static const struct contract_entry CONTRACT_ENTRIES[] = {']
for x in entries:
 kind={'file':1,'directory':2,'symlink':3}[x['type']]
 out.append('{%s,%d,%s,%d,%s,%s,%d},'%(json.dumps(x['path']),kind,x['mode'].replace('0o','0'),x.get('bytes',0),json.dumps(x.get('sha256','')),json.dumps(x.get('target','')),x['mtime']))
out += ['};','#define CONTRACT_ENTRY_COUNT (sizeof(CONTRACT_ENTRIES)/sizeof(CONTRACT_ENTRIES[0]))']
for name,b in [('BOOTSTRAP',raw['bootstrap.py']),('REQUEST_TAIL',tail)]:
 out.append('static const unsigned char '+name+'[] = {')
 terminated=b+b'\0'
 for i in range(0,len(terminated),24):out.append(','.join('0x%02x'%x for x in terminated[i:i+24])+',')
 out.extend(['};','#define '+name+'_LEN (sizeof('+name+')-1)'])
out.extend(['static const char CONTRACT_MANIFEST_SHA256[] = "'+sha(raw['candidate-runtime.json'])+'";', 'static const char CONTRACT_OPERATION_SHA256[] = "'+sha(sources[names[3]])+'";','#endif'])
header=('\n'.join(out)+'\n').encode();(H/'adapter/generated_contract.h').write_bytes(header)
(H/'generated-identity.json').write_text(json.dumps({'header_bytes':len(header),'header_sha256':sha(header),'bootstrap_sha256':sha(raw['bootstrap.py']),'request_tail_bytes':len(tail),'request_tail_sha256':sha(tail),'encoded_sha256':sha(encoded),'source_hashes':{n:sha(v) for n,v in sources.items()},'runtime_entries':len(entries),'runtime_files':len(manifest['files']),'runtime_aliases':len(manifest['aliases'])},indent=2,sort_keys=True)+'\n')
print(json.dumps({'header_bytes':len(header),'header_sha256':sha(header),'source_count':len(sources)}))
