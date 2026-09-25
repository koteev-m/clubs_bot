"""Verification-only strict launcher record. No execution, no generic logging."""
import json,re
SOURCE_SHA256='ebb0618eff1f3bede1cb2934d9c59d1bda77f9371f74d373b0d5eaf7fffc5ccf'
MAX_LINE=1024
KEYS={'clb95_launcher','status','launcher_source_sha256','launcher_uid','launcher_euid',
      'nofile_entry_soft','nofile_entry_hard','nofile_forced_soft',
      'nofile_post_force_soft','nofile_post_force_hard'}
class EvidenceError(ValueError):pass

def duplicate_free(items):
    out={}
    for k,v in items:
        if k in out:raise EvidenceError('launcher_duplicate_key')
        out[k]=v
    return out

def limit(v):
    return v is None or (type(v) is int and 0<=v<=18446744073709551615)

def parse_line(line):
    if type(line) is not bytes or not 0<len(line)<=MAX_LINE:raise EvidenceError('launcher_line_bound')
    if not line.endswith(b'\n') or line.count(b'\n')!=1:raise EvidenceError('launcher_line_framing')
    if any(x<32 or x>126 for x in line[:-1]):raise EvidenceError('launcher_line_encoding')
    try:
        row=json.loads(line.decode('ascii'),object_pairs_hook=duplicate_free,
                       parse_constant=lambda _: (_ for _ in ()).throw(EvidenceError('launcher_number')))
    except (UnicodeError,ValueError,RecursionError):raise EvidenceError('launcher_json') from None
    if type(row) is not dict or set(row)!=KEYS:raise EvidenceError('launcher_schema')
    if type(row['clb95_launcher']) is not int or row['clb95_launcher']!=1 or row['status']!='forced':raise EvidenceError('launcher_status')
    if row['launcher_source_sha256']!=SOURCE_SHA256:raise EvidenceError('launcher_source_identity')
    if any(type(row[k]) is not int or row[k]!=0 for k in ['launcher_uid','launcher_euid']):raise EvidenceError('launcher_principal')
    for k in ['nofile_entry_soft','nofile_entry_hard','nofile_forced_soft','nofile_post_force_soft','nofile_post_force_hard']:
        if not limit(row[k]):raise EvidenceError('launcher_limit_type')
    soft,hard=row['nofile_entry_soft'],row['nofile_entry_hard']
    if soft is not None and soft<1024:raise EvidenceError('launcher_entry_soft')
    if hard is not None and (hard<1098 or soft is None or soft>hard):raise EvidenceError('launcher_entry_hard')
    if row['nofile_forced_soft']!=1024 or row['nofile_post_force_soft']!=1024:raise EvidenceError('launcher_force_mismatch')
    if row['nofile_post_force_hard']!=hard:raise EvidenceError('launcher_hard_changed')
    return row

class LauncherLine:
    """Exactly one first stderr line; *all* subsequent stderr is forbidden."""
    def __init__(self):self.pending=bytearray();self.record=None;self.failed=False
    def feed(self,chunk):
        try:
            if self.failed:raise EvidenceError('launcher_previous_failure')
            if type(chunk) is not bytes:raise EvidenceError('launcher_chunk')
            if not chunk:return
            if self.record is not None:raise EvidenceError('launcher_extra_stderr')
            self.pending.extend(chunk)
            if len(self.pending)>MAX_LINE:raise EvidenceError('launcher_line_bound')
            if b'\n' in self.pending:
                end=self.pending.index(b'\n')+1
                if end!=len(self.pending):raise EvidenceError('launcher_extra_stderr')
                self.record=parse_line(bytes(self.pending));self.pending.clear()
        except EvidenceError:
            self.failed=True;self.pending.clear();self.record=None;raise
    def finish(self):
        if self.failed:raise EvidenceError('launcher_previous_failure')
        if self.record is None:raise EvidenceError('launcher_line_missing')
        if self.pending:raise EvidenceError('launcher_line_incomplete')
        return dict(self.record)

def merge_record(record,binary_sha256):
    # binary_sha256 comes from coordinator's pre-exec validation, NOT stderr.
    if type(binary_sha256) is not str or not re.fullmatch('[0-9a-f]{64}',binary_sha256):raise EvidenceError('launcher_binary_identity')
    out=dict(record);out['launcher_binary_sha256']=binary_sha256
    # No worker result or PASS status is invented here.
    return out
