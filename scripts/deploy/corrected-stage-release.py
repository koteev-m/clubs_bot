#!/usr/bin/env -S python3 -I -S -B
"""Bounded CLB-82 executor for the additive descriptor-based helper phases."""
import sys
if __name__ == "__main__" and not (sys.flags.isolated and sys.flags.no_site):
    raise SystemExit("corrected-stage:v=1 result=blocked category=PYTHON_STARTUP_INVALID")

import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import shlex
import signal
import stat
import subprocess
import time
import uuid
import secrets
import struct

# -I -S excludes the checkout, cwd, user site and startup hooks before any
# import. Load only these exact source files; never consult .pyc or add a
# repository directory to sys.path. Their dependency closure is stdlib only.
import types

def source_module(name):
    path = Path(__file__).resolve().with_name(name + ".py")
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, "rb") as source:
        module = types.ModuleType(name)
        module.__file__ = str(path)
        exec(compile(source.read(), str(path), "exec"), module.__dict__)
    return module

open_canonical_root = source_module("release_private_root").open_canonical_root
_authority = source_module("release_authority")
AuthorityError, REFERENCE, verify_prior = (
    _authority.AuthorityError, _authority.REFERENCE, _authority.verify_prior)

ROOT = Path(__file__).resolve().parents[2]
HELPER_PATH = "scripts/deploy/remote-compose-release.sh"
# Working implementation identity. No unpublished/future commit is invented.
# A separately approved stage configuration must bind these bytes to a real
# Git commit before execution. Fixtures supply their own isolated Git objects.
IMPLEMENTATION_BLOB = "fc09080ba4864133ca23ec5c777339b881094279"
IMPLEMENTATION_SHA256 = "48bcbafde22b90dc3902cac0ba80754239964466ce612d11565dd1fdeb75e2ec"
IMPLEMENTATION_SIZE = 174422
IMPLEMENTATION_PATTERN = r"([0-9a-f]{40}):" + IMPLEMENTATION_BLOB + ":" + IMPLEMENTATION_SHA256
INCIDENT = _authority.INCIDENT
COMPOSE_PATH = "/opt/clubs-bot-stage"
ACK = b"release-operation:v=1 result=success\n"


class Rejected(Exception):
    """Only fixed categories, never untrusted subprocess diagnostics."""


def require(condition, category):
    if not condition:
        raise Rejected(category)


def authorization(run_number, implementation, binding_digest):
    # A new workflow run number requires a new exact authorization; attempt >1
    # is never a mutation capability. This text is authority, not status evidence.
    return "|".join(("authorize-resume-start", *INCIDENT.values(), COMPOSE_PATH,
                     implementation, binding_digest, run_number))


def validate_request(env):
    require(env.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
            and env.get("GITHUB_REF") == "refs/heads/main"
            and env.get("GITHUB_REF_TYPE") == "branch"
            and env.get("REPOSITORY_DEFAULT_BRANCH") == "main"
            and env.get("GITHUB_REPOSITORY") == "koteev-m/clubs_bot", "DISPATCH_INVALID")
    require(all(env.get(key) == value for key, value in INCIDENT.items()), "INCIDENT_INVALID")
    require(re.fullmatch(IMPLEMENTATION_PATTERN, env.get("IMPLEMENTATION", "")), "IMPLEMENTATION_INVALID")
    require(env.get("ACTION", "inspect") in {"inspect", "resume-start"}, "ACTION_INVALID")
    require(re.fullmatch(REFERENCE, env.get("PRIOR_STATUS", "")), "PRIOR_STATUS_REFERENCE_REQUIRED")
    for key in ("GITHUB_RUN_ID", "GITHUB_RUN_NUMBER", "GITHUB_RUN_ATTEMPT"):
        require(re.fullmatch(r"[1-9][0-9]{0,19}", env.get(key, "")), "RUN_INVALID")
    if env.get("ACTION", "inspect") == "resume-start":
        require(env["GITHUB_RUN_ATTEMPT"] == "1", "RERUN_FORBIDDEN")
        parts = env.get("AUTHORIZATION", "").split("|")
        require(len(parts) == 10 and re.fullmatch(r"[0-9a-f]{64}", parts[-2])
                and env["AUTHORIZATION"] == authorization(env["GITHUB_RUN_NUMBER"], env["IMPLEMENTATION"], parts[-2]),
                "AUTHORIZATION_REQUIRED")
    else:
        require(not env.get("AUTHORIZATION"), "INSPECT_AUTHORIZATION_INVALID")


def capture(argv, payload=b"", *, timeout=30, limit=32768, env=None, pass_fds=()):
    """No named stdout/stderr captures; bounded memory and bounded process lifetime."""
    child = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.DEVNULL, env=env, pass_fds=pass_fds,
                             start_new_session=True)
    output = bytearray()
    offset = 0
    deadline = time.monotonic() + timeout
    try:
        with selectors.DefaultSelector() as poll:
            os.set_blocking(child.stdin.fileno(), False)
            os.set_blocking(child.stdout.fileno(), False)
            poll.register(child.stdout, selectors.EVENT_READ)
            if payload:
                poll.register(child.stdin, selectors.EVENT_WRITE)
            else:
                child.stdin.close()
            while poll.get_map():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return 124, bytes(output)
                for key, _ in poll.select(min(remaining, 0.2)):
                    if key.fileobj is child.stdin:
                        try:
                            offset += os.write(child.stdin.fileno(), payload[offset:offset + 4096])
                        except BrokenPipeError:
                            offset = len(payload)
                        if offset == len(payload):
                            poll.unregister(child.stdin)
                            child.stdin.close()
                    else:
                        chunk = os.read(child.stdout.fileno(), 4096)
                        if not chunk:
                            poll.unregister(child.stdout)
                        else:
                            output.extend(chunk)
                            if len(output) > limit:
                                return 125, b""
            return child.wait(timeout=max(0.01, deadline - time.monotonic())), bytes(output)
    except (subprocess.TimeoutExpired, InterruptedError):
        return 124, bytes(output)
    finally:
        if child.poll() is None:
            os.killpg(child.pid, signal.SIGKILL)
            child.wait(timeout=2)
        child.stdin.close()
        child.stdout.close()


def helper_snapshot(env=None):
    env = os.environ if env is None else env
    approved = env.get("CLB82_APPROVED_IMPLEMENTATION", "")
    match = re.fullmatch(IMPLEMENTATION_PATTERN, approved)
    require(match and env.get("IMPLEMENTATION") == approved, "IMPLEMENTATION_NOT_APPROVED")
    implementation_revision = match[1]
    # No replace refs, attributes, checkout paths, filters, URLs or input script bodies.
    git_env = {"PATH": os.environ["PATH"]}
    git_env.update(GIT_NO_REPLACE_OBJECTS="1", GIT_OPTIONAL_LOCKS="0", GIT_LITERAL_PATHSPECS="1", LC_ALL="C")

    def git(*args, limit=1024):
        code, data = capture(["git", "--no-replace-objects", "-C", str(ROOT), *args],
                             env=git_env, limit=limit)
        require(code == 0, "IMPLEMENTATION_INVALID")
        return data

    require(git("cat-file", "-t", implementation_revision) == b"commit\n", "IMPLEMENTATION_INVALID")
    expected_entry = f"100644 blob {IMPLEMENTATION_BLOB}\t{HELPER_PATH}\0".encode()
    require(git("ls-tree", "--full-tree", "-z", implementation_revision, "--", HELPER_PATH) == expected_entry,
            "IMPLEMENTATION_INVALID")
    require(git("cat-file", "-t", IMPLEMENTATION_BLOB) == b"blob\n", "IMPLEMENTATION_INVALID")
    require(git("cat-file", "-s", IMPLEMENTATION_BLOB) == f"{IMPLEMENTATION_SIZE}\n".encode(), "IMPLEMENTATION_INVALID")
    snapshot = git("cat-file", "blob", IMPLEMENTATION_BLOB, limit=IMPLEMENTATION_SIZE)
    require(len(snapshot) == IMPLEMENTATION_SIZE
            and hashlib.sha256(snapshot).hexdigest() == IMPLEMENTATION_SHA256
            and hashlib.sha1(f"blob {len(snapshot)}\0".encode() + snapshot).hexdigest() == IMPLEMENTATION_BLOB,
            "IMPLEMENTATION_INVALID")
    return snapshot


@contextlib.contextmanager
def pinned_hosts(env):
    pin = env.get("SSH_KNOWN_HOSTS", "").encode()
    require(0 < len(pin) <= 65536 and all(b in (9, 10) or 32 <= b <= 126 for b in pin), "PIN_INVALID")
    lines = pin.splitlines()
    require(lines and all(re.fullmatch(rb"(?:@(?:cert-authority|revoked) )?[A-Za-z0-9._*?:|=+/\[\],-]+[ \t]+[A-Za-z0-9@._+-]+[ \t]+[A-Za-z0-9+/]+={0,2}(?:[ \t]+[ -~]*)?", line) for line in lines), "PIN_INVALID")
    root_fd = open_canonical_root(env.get("TMPDIR") or env.get("RUNNER_TEMP", ""))
    descriptor = None
    name = ".clubs-corrected-pin-" + uuid.uuid4().hex
    try:
        descriptor = os.open(name, os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=root_fd)
        os.unlink(name, dir_fd=root_fd)
        value = os.fstat(descriptor)
        require(stat.S_ISREG(value.st_mode) and stat.S_IMODE(value.st_mode) == 0o600
                and value.st_uid == os.geteuid() and value.st_nlink == 0, "LOCAL_FAILURE")
        os.write(descriptor, pin)
        os.lseek(descriptor, 0, os.SEEK_SET)
        # Match the existing status channel: on Linux, address the retained
        # parent descriptor even if OpenSSH closes inherited descriptors.
        reference = f"/proc/{os.getpid()}/fd/{descriptor}"
        if not os.path.exists(reference):
            reference = f"/dev/fd/{descriptor}"
        require(os.path.exists(reference), "LOCAL_FAILURE")
        code, fingerprints = capture(["ssh-keygen", "-lf", reference], pass_fds=(descriptor,), limit=65536,
                                     env={"PATH": env.get("PATH", os.defpath), "LC_ALL": "C"})
        require(code == 0 and len(fingerprints.splitlines()) == len(lines), "PIN_INVALID")
        os.lseek(descriptor, 0, os.SEEK_SET)
        yield descriptor, reference
    finally:
        if descriptor is not None:
            os.ftruncate(descriptor, 0)
            os.close(descriptor)
        os.close(root_fd)


def parse_status(raw):
    require(0 < len(raw) <= 1024 and raw.endswith(b"\n") and raw.count(b"\n") == 1
            and all(b == 10 or 32 <= b <= 126 for b in raw), "STATUS_MALFORMED")
    pattern = Path(__file__).with_name("release-status.pattern").read_text().strip()
    line = raw[:-1].decode("ascii")
    require(re.fullmatch(pattern, line), "STATUS_MALFORMED")
    return dict(field.split("=", 1) for field in line.split(" ")[1:])


def trusted(status):
    return all(status[k] == "yes" for k in ("status_available", "owner_match", "revision_match", "digest_match")) and status["failure_category"] == "none"


def validate_binding(value, env, implementation):
    require(isinstance(value, dict) and set(value) ==
            {"version", "incident", "implementation", "principal", "compose", "backing", "objects", "configuration"}, "ROOT_BINDING_INVALID")
    require(value["version"] == 1 and value["implementation"] == implementation
            and value["incident"] == dict(owner=env["RELEASE_OWNER"], environment="stage", revision=env["EXPECTED_REVISION"], image=env["IMAGE_DIGEST"]),
            "ROOT_BINDING_INVALID")
    principal, compose, objects, config = (value[key] for key in ("principal", "compose", "objects", "configuration"))
    require(isinstance(principal, dict) and set(principal) == {"name", "uid"}
            and type(principal["uid"]) is int and principal["uid"] > 0
            and isinstance(principal["name"], str) and re.fullmatch(r"[a-zA-Z0-9_][a-zA-Z0-9._-]*", principal["name"])
            and principal["name"] not in {"root", "hookah-staging"}
            and (not env.get("SSH_USER") or principal["name"] == env["SSH_USER"]), "ROOT_BINDING_INVALID")
    require(isinstance(compose, dict) and set(compose) == {"path", "project", "service"}
            and compose["path"] == env.get("COMPOSE_PATH", COMPOSE_PATH) and compose["service"] == "app"
            and isinstance(compose["project"], str) and re.fullmatch(r"[a-zA-Z0-9_.-]{1,128}", compose["project"]), "ROOT_BINDING_INVALID")
    require(isinstance(value["backing"], str) and re.fullmatch(r"mount-v2:[0-9a-f]{64}", value["backing"]), "ROOT_BINDING_INVALID")
    require(isinstance(objects, dict) and 8 <= len(objects) <= 40
            and {"parent", "root", "state", "results", "ledger", "application_lock", "operation_lock"} <= set(objects)
            and all(isinstance(k, str) and re.fullmatch(r"[a-zA-Z0-9_./-]{1,1024}", k)
                    and isinstance(v, list) and len(v) == 2 and all(type(n) is int and n >= 0 for n in v)
                    for k, v in objects.items()), "ROOT_BINDING_INVALID")
    require(isinstance(config, dict) and set(config) == {"main", "override", "release", "dotenv", "resolved"}
            and all(isinstance(v, str) and re.fullmatch(r"[0-9a-f]{64}", v) for v in config.values()), "ROOT_BINDING_INVALID")


def binding_control(env, snapshot):
    approved = env.get("CLB82_APPROVED_IMPLEMENTATION", "")
    require(approved == env["IMPLEMENTATION"], "IMPLEMENTATION_NOT_APPROVED")
    revision, blob, digest = approved.split(":")
    pin = env.get("CLB82_AUTHORIZED_ROOT_BINDING", "")
    require(len(pin) <= 24576, "ROOT_BINDING_INVALID")
    try:
        binding = json.loads(pin, object_pairs_hook=_authority.unique_object) if pin else None
    except Exception:
        raise Rejected("ROOT_BINDING_INVALID") from None
    require(binding is None or (isinstance(binding, dict) and set(binding) ==
            {"version", "incident", "implementation", "principal", "compose", "backing", "objects", "configuration"}),
            "ROOT_BINDING_INVALID")
    implementation = dict(revision=revision, blob=blob, sha256=digest, size=len(snapshot), path=HELPER_PATH, mode="100644", type="blob")
    if binding is not None:
        validate_binding(binding, env, implementation)
    if env.get("ACTION", "inspect") == "resume-start":
        require(binding is not None, "ROOT_BINDING_REQUIRED")
        digest_pin = hashlib.sha256(json.dumps(binding, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        require(env["AUTHORIZATION"] == authorization(env["GITHUB_RUN_NUMBER"], approved, digest_pin), "AUTHORIZATION_REQUIRED")
    return dict(version=1, implementation=dict(revision=revision, blob=blob, sha256=digest, size=len(snapshot),
                path=HELPER_PATH, mode="100644", type="blob"), binding=binding,
                authorization=hashlib.sha256(env.get("AUTHORIZATION", "").encode()).hexdigest(),
                token=secrets.token_hex(32), principal=env["SSH_USER"])


# The transport carries helper and bounded control bytes over stdin. FD 4 is a
# private data capture, never a caller-supplied root/lock FD. No token in argv.
REMOTE_BOUND_BOOTSTRAP = r"""
import hashlib,json,os,stat,struct,subprocess,sys,tempfile
if not (sys.flags.isolated and sys.flags.no_site):raise SystemExit(1)
header=sys.stdin.buffer.read(8)
if len(header)!=8:raise SystemExit(1)
csize,hsize=struct.unpack('!II',header)
if not (0<csize<=32768 and 100000<hsize<262144):raise SystemExit(1)
control=sys.stdin.buffer.read(csize);helper=sys.stdin.buffer.read(hsize)
if len(control)!=csize or len(helper)!=hsize or sys.stdin.buffer.read(1):raise SystemExit(1)
value=json.loads(control);impl=value['implementation']
if hashlib.sha256(helper).hexdigest()!=impl['sha256'] or len(helper)!=impl['size']:raise SystemExit(1)
if hashlib.sha1(('blob '+str(len(helper))+'\0').encode()+helper).hexdigest()!=impl['blob']:raise SystemExit(1)
env={k:v for k,v in os.environ.items() if not k.startswith('BASH_FUNC_') and k not in ('BASH_ENV','ENV','SHELLOPTS','BASHOPTS','CDPATH')}
with tempfile.TemporaryFile(dir=os.path.realpath('/tmp')) as data:
 info=os.fstat(data.fileno())
 if not (stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode)==0o600 and info.st_uid==os.geteuid() and info.st_nlink==0):raise SystemExit(1)
 data.write(control);data.flush();data.seek(0)
 os.dup2(data.fileno(),4,inheritable=True)
 result=subprocess.run(['bash','--noprofile','--norc','-s','--',*sys.argv[1:]],input=helper,pass_fds=(4,),env=env)
 raise SystemExit(result.returncode)
"""


def execute(env, snapshot, remote, control):
    """One durable claim, one separate resume submission, bounded reconciliation."""
    def phase(name, timeout):
        return remote(snapshot, ["corrected-start", env["RELEASE_OWNER"], "stage", env["COMPOSE_PATH"],
                                 env["EXPECTED_REVISION"], env["IMAGE_DIGEST"], name], timeout, control)

    def status(name):
        code, raw = phase(name, 90)
        require(code == 0, "STATUS_UNAVAILABLE")
        lines = raw.splitlines(keepends=True)
        require(len(lines) == (2 if name == "inspect" else 1), "STATUS_MALFORMED")
        parsed = parse_status(lines[0])
        require(trusted(parsed), "STATUS_UNTRUSTED")
        if name == "inspect":
            prefix = b"corrected-binding-candidate:v=1 "
            require(lines[1].startswith(prefix) and len(lines[1]) <= 24576, "ROOT_BINDING_INVALID")
            try:
                candidate = json.loads(lines[1][len(prefix):], object_pairs_hook=_authority.unique_object)
            except Exception:
                raise Rejected("ROOT_BINDING_INVALID") from None
            validate_binding(candidate, env, control["implementation"])
            # The captured response is private. SSH_USER is protected input;
            # expose its hash for review, never the username in logs/artifacts.
            # This public observation is deliberately not an authorized pin.
            public_candidate = {**candidate, "principal": {
                "name_sha256": hashlib.sha256(candidate["principal"]["name"].encode()).hexdigest(),
                "uid": candidate["principal"]["uid"]}}
            print(prefix.decode() + json.dumps(public_candidate, sort_keys=True, separators=(",", ":")), flush=True)
        print(f"corrected-stage-observation:v=1 requested_operation={'resume-start' if name == 'reconcile' else 'start'}", flush=True)
        print(lines[0].decode(), end="", flush=True)
        return parsed

    before = status("inspect")
    if env.get("ACTION", "inspect") == "inspect":
        return "INSPECTED"
    require(before["checkpoint"] == "migration_completed" and before["migration_evidence"] == "present"
            and before["app_state"] == "absent" and before["resume_permitted"] == "yes"
            and before["abort_permitted"] == "no" and before["operation_result"] == "remote_failure", "FRESH_START_GATE_REJECTED")
    code, acknowledgement = phase("claim", 90)
    require(code == 0 and acknowledgement == b"release-authorization:v=1 consumed=yes\n", "AUTHORIZATION_CONSUMED_OR_UNKNOWN")
    print("corrected-stage-authorization:v=1 consumed=yes", flush=True)
    print("corrected-stage-submission:v=1 requested_operation=resume-start attempted=yes", flush=True)
    code, acknowledgement = phase("resume-start", 180)
    after = status("reconcile")
    if (after["checkpoint"] == "candidate_healthy" and after["app_state"] == "candidate_running"
            and after["migration_evidence"] == "present" and after["operation_result"] == "success"
            and after["resume_permitted"] == "yes" and after["abort_permitted"] == "no"):
        return "SUCCESS" if code == 0 and acknowledgement == ACK else "COMPLETED_ACK_LOST"
    raise Rejected("SEPARATE_DECISION_REQUIRED")


def main():
    os.umask(0o077)
    def interrupted(_signum, _frame):
        for watched in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
            signal.signal(watched, signal.SIG_IGN)
        raise InterruptedError

    for watched in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
        signal.signal(watched, interrupted)
    env = os.environ.copy()
    validate_request(env)
    require(sys.argv[1:] in ([], ["--validate"], ["--verify-prior"], ["--validate-execution"]), "INPUT_INVALID")
    if sys.argv[1:] == ["--validate"]:
        with open(env["GITHUB_OUTPUT"], "a") as output:
            for key in (*INCIDENT, "IMPLEMENTATION", "ACTION", "AUTHORIZATION", "PRIOR_STATUS"):
                value = env.get(key, "inspect" if key == "ACTION" else "")
                output.write(f"{key}={value}\n")
        return "SYNTAX_VALIDATED"
    if sys.argv[1:] == ["--validate-execution"]:
        snapshot = helper_snapshot(env)
        binding_control({**env, "SSH_USER": ""}, snapshot)
        return "EXECUTION_AUTHORITY_VALIDATED"
    if sys.argv[1:] == ["--verify-prior"]:
        verify_prior(env, capture)
        return "PRIOR_STATUS_VERIFIED"
    require(env.get("COMPOSE_PATH") == COMPOSE_PATH, "COMPOSE_PATH_INVALID")
    require(re.fullmatch(r"[a-zA-Z0-9_][a-zA-Z0-9._-]*", env.get("SSH_USER", ""))
            and env["SSH_USER"] not in {"root", "hookah-staging"}, "PRINCIPAL_INVALID")
    require(re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]*", env.get("SSH_HOST", ""))
            and re.fullmatch(r"[0-9]{1,5}", env.get("SSH_PORT", ""))
            and 1 <= int(env["SSH_PORT"]) <= 65535, "SSH_TARGET_INVALID")
    prior = verify_prior(env, capture)
    snapshot = helper_snapshot(env)
    control = binding_control(env, snapshot)
    with pinned_hosts(env) as (descriptor, reference):
        # Child tools inherit only operational necessities, never the pin or any
        # GitHub/environment secret variable. SSH agent is the sole credential.
        child_env = {k: env[k] for k in ("PATH", "SSH_AUTH_SOCK") if k in env}
        child_env["LC_ALL"] = "C"

        def transport(data, command, timeout):
            os.lseek(descriptor, 0, os.SEEK_SET)
            command = ('test "$(id -un)" = ' + shlex.quote(env["SSH_USER"])
                       + ' && test "$(id -u)" != 0 && exec ' + command)
            return capture(["ssh", "-p", env["SSH_PORT"], "-F", "/dev/null",
                            "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
                            "-o", f"UserKnownHostsFile={reference}", "-o", "GlobalKnownHostsFile=/dev/null",
                            "-o", "KnownHostsCommand=none", "-o", "VerifyHostKeyDNS=no", "-o", "UpdateHostKeys=no",
                            "-o", "ProxyCommand=none", "-o", "ProxyJump=none", "-o", "PermitLocalCommand=no",
                            "-o", "ConnectTimeout=15", "-o", "ConnectionAttempts=1", "--",
                            env["SSH_USER"] + "@" + env["SSH_HOST"], command],
                           data, timeout=timeout, env=child_env, pass_fds=(descriptor,))

        def remote(data, argv, timeout, control):
            control_bytes = json.dumps(control, sort_keys=True, separators=(",", ":")).encode()
            payload = struct.pack("!II", len(control_bytes), len(data)) + control_bytes + data
            return transport(payload, shlex.join(["python3", "-I", "-S", "-B", "-c", REMOTE_BOUND_BOOTSTRAP, *argv]), timeout)

        print("corrected-stage-provenance:" + json.dumps({**INCIDENT,
              "implementation": control["implementation"], "binding_sha256": (hashlib.sha256(json.dumps(control["binding"], sort_keys=True, separators=(",", ":")).encode()).hexdigest() if control["binding"] else None),
              "implementation_path": HELPER_PATH, "implementation_size": len(snapshot),
              "principal_sha256": hashlib.sha256(env["SSH_USER"].encode()).hexdigest(),
              "run_id": env["GITHUB_RUN_ID"], "run_number": env["GITHUB_RUN_NUMBER"],
              "attempt": env["GITHUB_RUN_ATTEMPT"], "action": env.get("ACTION", "inspect"),
              "prior_status": prior}, sort_keys=True), flush=True)
        return execute(env, snapshot, remote, control)


if __name__ == "__main__":
    try:
        result = main()
    except (Rejected, AuthorityError) as failure:
        print("corrected-stage:v=1 result=blocked category=" + str(failure))
        sys.exit(1)
    except BaseException:
        # Signals/interruptions must not disclose raw stderr or trigger a retry.
        print("corrected-stage:v=1 result=blocked category=LOCAL_FAILURE")
        sys.exit(1)
    print("corrected-stage:v=1 result=ok category=" + result)
