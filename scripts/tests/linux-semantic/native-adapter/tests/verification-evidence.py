"""CLB-97 verification consumer; no execution or privileged authority.

Call only on bytes returned by the closed coordinator capture of exact verified
launcher/helper/adapter binaries. Hash arguments are trusted coordinator binding,
NOT self-authentication of JSON. Worker perr is parsed inside adapter; this
consumer validates its bounded summary independently of launcher stderr.
"""
import hashlib,json,pathlib,re,types
P=pathlib.Path
p=P(__file__).resolve().with_name('launcher_evidence.py')
raw=p.read_bytes()
if p.is_symlink() or hashlib.sha256(raw).hexdigest()!='9badea856e07ec88bb73451729d83ad31ae7ff138752f8f335bdca94e138aa56':
    raise RuntimeError('launcher_parser_identity')
L=types.ModuleType('clb96_exact_launcher_parser');exec(compile(raw,'clb96_exact_launcher_parser','exec'),L.__dict__);del raw
WORKER_KEYS={'version','role','stage','basis','identity_verified','status','nofile_soft','nofile_hard','record_bytes','stderr_total'}
class Refused(ValueError):pass

def exact_hash(value):return type(value) is str and re.fullmatch('[0-9a-f]{64}',value) is not None

def trusted_binding(expected,observed):
    if not exact_hash(expected) or not exact_hash(observed) or expected!=observed:raise Refused('execution_identity')

def helper_json(raw):
    if type(raw) is not bytes or not 0<len(raw)<=65536:raise Refused('helper_output_bound')
    try:
        result=json.loads(raw,object_pairs_hook=L.duplicate_free,parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
    except (ValueError,UnicodeError,RecursionError):raise Refused('helper_json') from None
    if type(result) is not dict:raise Refused('helper_schema')
    return result

def worker_summary(report):
    adapter=report.get('adapter_result')
    if type(adapter) is not dict:raise Refused('worker_missing')
    w=adapter.get('worker_rlimit')
    if type(w) is not dict or set(w)!=WORKER_KEYS:raise Refused('worker_schema')
    if any(type(w[k]) is not int for k in ['version','nofile_soft','nofile_hard','record_bytes','stderr_total']):raise Refused('worker_number')
    if w!={'version':1,'role':'worker','stage':'pre_exec','basis':'getrlimit','identity_verified':True,'status':'CONFIRMED','nofile_soft':1024,'nofile_hard':1024,'record_bytes':56,'stderr_total':56} or type(w['identity_verified']) is not bool:
        raise Refused('worker_observation')
    # Observation alone cannot turn exec failure or semantic/cleanup refusal
    # into verification PASS. Fixed exit/result fields must agree.
    if adapter.get('primary')!='semantic_complete' or type(adapter.get('exit')) is not int or adapter['exit']!=0 or adapter.get('signal')!=0 or adapter.get('cleanup_error') is not False or adapter.get('original_recheck') is not True or adapter.get('view_held_fd_identity') is not True:
        raise Refused('adapter_failed')
    if report.get('verdict')!='PASS' or report.get('cleanup')!='confirmed' or report.get('cleanup_error') is not False or report.get('adapter_started') is not True:
        raise Refused('helper_failed')
    controls=report.get('negative_controls')
    if type(controls) is not dict or set(controls)!={'wrong_uid','symlink','runtime_hash','actual_tmpfs_backing'} or any(v is not True for v in controls.values()):raise Refused('negative_controls')
    return dict(w)

def merge(launcher_stderr,helper_stdout,*,expected_launcher_sha256,observed_launcher_sha256,expected_adapter_sha256,observed_adapter_sha256):
    trusted_binding(expected_launcher_sha256,observed_launcher_sha256)
    trusted_binding(expected_adapter_sha256,observed_adapter_sha256)
    try:
        stream=L.LauncherLine();stream.feed(launcher_stderr);launcher=L.merge_record(stream.finish(),observed_launcher_sha256)
    except L.EvidenceError:raise Refused('launcher_evidence') from None
    worker=worker_summary(helper_json(helper_stdout))
    return {'launcher':launcher,'worker':worker,'adapter_binary_sha256':observed_adapter_sha256,
            'measurement_basis':'instrumented_worker_pre_exec','identity_basis':'externally_verified_closed_execution','status':'VERIFICATION_EVIDENCE_ACCEPTED'}

def merge_captured(command,helper_stdout,*,expected_launcher_sha256,expected_adapter_sha256,observed_adapter_sha256):
    """Use CLB-96 capture's already validated numeric record, never raw stderr.
    command and helper_stdout must come from the SAME closed capture call.
    This function does not create execution identity from externally supplied JSON.
    """
    if type(command) is not dict or command.get('primary') is not None or command.get('privileged_fixture') is not True or type(command.get('exit')) is not int or command['exit']!=0:
        raise Refused('capture_failed')
    launcher=command.get('launcher_evidence')
    if type(launcher) is not dict or set(launcher)!=L.KEYS|{'launcher_binary_sha256'}:raise Refused('launcher_evidence')
    values={k:launcher[k] for k in L.KEYS}
    try:line=(json.dumps(values,separators=(',',':'),ensure_ascii=True)+'\n').encode('ascii')
    except (TypeError,ValueError,RecursionError):raise Refused('launcher_evidence') from None
    return merge(line,helper_stdout,expected_launcher_sha256=expected_launcher_sha256,
                 observed_launcher_sha256=launcher['launcher_binary_sha256'],
                 expected_adapter_sha256=expected_adapter_sha256,observed_adapter_sha256=observed_adapter_sha256)
