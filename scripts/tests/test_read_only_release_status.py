#!/usr/bin/env python3
"""Executable fail-closed coverage for the deployment-principal status channel."""

from __future__ import annotations

import base64
import hashlib
import itertools
import os
from pathlib import Path
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPOSITORY_ROOT / "scripts/deploy/read-only-release-status.sh"
VALIDATOR = REPOSITORY_ROOT / "scripts/validate-quiesced-deployment.sh"
WORKFLOW = REPOSITORY_ROOT / ".github/workflows/release-status.yml"
HELPER_REPOSITORY_PATH = "scripts/deploy/remote-compose-release.sh"

REVISION = "44497dcd28139cef865c3f98ac3f2c4a5afac636"
CANDIDATE_IMAGE = (
    "ghcr.io/koteev-m/clubs_bot/app-bot@sha256:"
    "ddf5486e02835855178cc3b30bd2f22899335131e6dc388def20feac328016fe"
)
TRUSTED_STATUS = (
    b"release-status:v=1 status_available=yes owner_match=yes revision_match=yes "
    b"digest_match=yes checkpoint=candidate_start_begun operation_result=remote_failure "
    b"migration_evidence=present app_state=absent abort_permitted=no "
    b"resume_permitted=no failure_category=none\n"
)
UNTRUSTED_STATUS = (
    b"release-status:v=1 status_available=no owner_match=no revision_match=no "
    b"digest_match=no checkpoint=unavailable operation_result=unavailable "
    b"migration_evidence=unknown app_state=unknown abort_permitted=no "
    b"resume_permitted=no failure_category=untrusted_state_root\n"
)
TRUSTED_CHANNEL = (
    TRUSTED_STATUS
    + b"release-status-channel:v=1 result=trusted category=STATUS_TRUSTED\n"
)
UNTRUSTED_CHANNEL = (
    UNTRUSTED_STATUS
    + b"release-status-channel:v=1 result=untrusted category=STATUS_UNTRUSTED\n"
)

STRICT_ARGUMENT = "--strict"
STRICT_ACCOUNTING_PROBE_ARGUMENT = "--strict-accounting-probe"
EXPECTED_METHOD_COUNT = 80
EXPECTED_SUBTEST_COUNT = 322
PROBE_METHOD_COUNT = 2
PROBE_SUBTEST_COUNT = 2
SUMMARY_PREFIX = "release-status-suite:v=1"
ZERO_OUTCOMES = {
    "failures": 0,
    "errors": 0,
    "skipped": 0,
    "expected_failures": 0,
    "unexpected_successes": 0,
}


def strict_summary(
    *,
    methods: int,
    subtests: int,
    failures: int,
    errors: int,
    skipped: int,
    expected_failures: int,
    unexpected_successes: int,
) -> str:
    return (
        f"{SUMMARY_PREFIX} methods={methods} subtests={subtests} "
        f"failures={failures} errors={errors} skipped={skipped} "
        f"expected_failures={expected_failures} "
        f"unexpected_successes={unexpected_successes}"
    )


def strict_counters_are_accepted(
    counters: dict[str, int], expected_methods: int, expected_subtests: int
) -> bool:
    return counters == {
        "methods": expected_methods,
        "subtests": expected_subtests,
        **ZERO_OUTCOMES,
    }


def strict_summary_is_exact(
    raw: bytes, expected_methods: int, expected_subtests: int
) -> bool:
    expected = strict_summary(
        methods=expected_methods,
        subtests=expected_subtests,
        **ZERO_OUTCOMES,
    ).encode("ascii") + b"\n"
    return raw == expected

STATUS_FIELD_ORDER = (
    "status_available",
    "owner_match",
    "revision_match",
    "digest_match",
    "checkpoint",
    "operation_result",
    "migration_evidence",
    "app_state",
    "abort_permitted",
    "resume_permitted",
    "failure_category",
)
TRUSTED_STATUS_VALUES = {
    "status_available": "yes",
    "owner_match": "yes",
    "revision_match": "yes",
    "digest_match": "yes",
    "checkpoint": "candidate_start_begun",
    "operation_result": "remote_failure",
    "migration_evidence": "present",
    "app_state": "absent",
    "abort_permitted": "no",
    "resume_permitted": "no",
    "failure_category": "none",
}


def status_record(**overrides: str) -> bytes:
    values = TRUSTED_STATUS_VALUES | overrides
    fields = " ".join(f"{name}={values[name]}" for name in STATUS_FIELD_ORDER)
    return f"release-status:v=1 {fields}\n".encode("ascii")


def channel(result: str, category: str) -> bytes:
    return f"release-status-channel:v=1 result={result} category={category}\n".encode()


_OWNER_COUNTER = itertools.count(1)
_KNOWN_HOSTS_ENTRY: str | None = None


def known_hosts_entry() -> str:
    global _KNOWN_HOSTS_ENTRY
    if _KNOWN_HOSTS_ENTRY is not None:
        return _KNOWN_HOSTS_ENTRY
    with tempfile.TemporaryDirectory(prefix="status-known-host-key-") as directory:
        key_path = Path(directory) / "host-key"
        subprocess.run(
            ["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", str(key_path)],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        key_type, key_blob, *_ = (key_path.with_suffix(".pub")).read_text(
            encoding="ascii"
        ).split()
    _KNOWN_HOSTS_ENTRY = f"[stage.invalid]:22 {key_type} {key_blob}\n"
    return _KNOWN_HOSTS_ENTRY


class RunnerHarness:
    """Runs the real runner and makes fake SSH execute its transmitted wrapper."""

    def __init__(
        self,
        response: bytes = TRUSTED_STATUS,
        ssh_stderr: bytes = b"",
        ssh_exit: int = 0,
        ssh_mode: str = "execute",
    ) -> None:
        temporary_parent = os.environ.get("RUNNER_TEMP") or None
        self.temporary = tempfile.TemporaryDirectory(
            prefix="read-only-release-status-test-", dir=temporary_parent
        )
        self.root = Path(self.temporary.name).resolve()
        self.local_bin = self.root / "local-bin"
        self.remote_bin = self.root / "remote-bin"
        self.local_bin.mkdir()
        self.remote_bin.mkdir()
        self.runner_tmp = self.root / "runner-tmp"
        self.runner_tmp.mkdir(mode=0o700)
        self.response_file = self.root / "remote-response"
        self.stderr_file = self.root / "ssh-stderr"
        self.count_file = self.root / "ssh-count"
        self.wrapper_file = self.root / "remote-wrapper"
        self.ssh_args_file = self.root / "ssh-args"
        self.helper_args_file = self.root / "helper-args"
        self.helper_marker_file = self.root / "helper-marker"
        self.helper_invocation_file = self.root / "helper-invocations"
        self.decode_audit_file = self.root / "decoded-snapshot-hashes"
        self.race_identity_file = self.root / "race-identity"
        self.remote_stat_audit_file = self.root / "remote-stat-audit"
        self.virtual_owner_evidence_file = self.root / "virtual-owner-evidence"
        self.known_hosts_path_file = self.root / "known-hosts-path"
        self.known_hosts_copy = self.root / "known-hosts-copy"
        self.known_hosts_mode_file = self.root / "known-hosts-mode"
        self.private_root_mode_file = self.root / "private-root-mode"
        self.private_root_path_file = self.root / "private-root-path"
        self.private_file_modes = self.root / "private-file-modes"
        self.ssh_signal_ready_file = self.root / "ssh-signal-ready"
        self.preexisting_entry_audit_file = self.root / "preexisting-entry-audit"
        self.sentinel = self.root / "pre-existing-sentinel"
        self.sentinel.write_text("do-not-delete\n", encoding="ascii")
        self.response_file.write_bytes(response)
        self.stderr_file.write_bytes(ssh_stderr)

        self.release_owner = f"{os.getpid()}-{next(_OWNER_COUNTER)}"
        self.incident_tag = "deploy-stage-44497dc"
        self.helper_path = Path(f"/tmp/clubs-bot-release-{self.release_owner}.sh")
        self.helper_links: list[Path] = []
        self._write_original_helper()

        self.real_stat = shutil.which("stat")
        self.real_sha256sum = shutil.which("sha256sum")
        self.real_head = shutil.which("head")
        self.real_python3 = shutil.which("python3")
        if not all((self.real_stat, self.real_sha256sum, self.real_head, self.real_python3)):
            raise unittest.SkipTest("stat, sha256sum, head, and python3 are required")
        self._write_remote_tools()
        self._write_fake_ssh()

        system_path = os.environ.get("PATH", "/usr/bin:/bin")
        self.environment = os.environ.copy()
        self.environment.update(
            {
                "APP_ENV": "stage",
                "INCIDENT_TAG": self.incident_tag,
                "RELEASE_OWNER": self.release_owner,
                "EXPECTED_REVISION": REVISION,
                "IMAGE_DIGEST": CANDIDATE_IMAGE,
                "REQUESTED_OPERATION": "start",
                "EXPECTED_HELPER_SHA256": self.helper_hash,
                "SSH_USER": "deployment",
                "SSH_HOST": "stage.invalid",
                "SSH_PORT": "22",
                "COMPOSE_PATH": "/srv/clubs-bot",
                "SSH_KNOWN_HOSTS": known_hosts_entry(),
                "TMPDIR": str(self.runner_tmp),
                "RUNNER_TEMP": str(self.runner_tmp),
                "PATH": f"{self.local_bin}:{system_path}",
                "FAKE_SYSTEM_PATH": system_path,
                "FAKE_REMOTE_BIN": str(self.remote_bin),
                "FAKE_SSH_RESPONSE_FILE": str(self.response_file),
                "FAKE_SSH_STDERR_FILE": str(self.stderr_file),
                "FAKE_SSH_COUNT_FILE": str(self.count_file),
                "FAKE_SSH_WRAPPER_FILE": str(self.wrapper_file),
                "FAKE_SSH_ARGS_FILE": str(self.ssh_args_file),
                "FAKE_KNOWN_HOSTS_PATH_FILE": str(self.known_hosts_path_file),
                "FAKE_KNOWN_HOSTS_COPY": str(self.known_hosts_copy),
                "FAKE_KNOWN_HOSTS_MODE_FILE": str(self.known_hosts_mode_file),
                "FAKE_PRIVATE_ROOT_MODE_FILE": str(self.private_root_mode_file),
                "FAKE_PRIVATE_ROOT_PATH_FILE": str(self.private_root_path_file),
                "FAKE_PRIVATE_FILE_MODES": str(self.private_file_modes),
                "FAKE_SSH_SIGNAL_READY_FILE": str(self.ssh_signal_ready_file),
                "FAKE_PREEXISTING_ENTRY_AUDIT_FILE": str(
                    self.preexisting_entry_audit_file
                ),
                "FAKE_SSH_EXIT": str(ssh_exit),
                "FAKE_SSH_MODE": ssh_mode,
                "REMOTE_RESPONSE_FILE": str(self.response_file),
                "REMOTE_ARGS_FILE": str(self.helper_args_file),
                "REMOTE_HELPER_MARKER_FILE": str(self.helper_marker_file),
                "REMOTE_HELPER_INVOCATION_FILE": str(self.helper_invocation_file),
                "REMOTE_HELPER_PATH": str(self.helper_path),
                "REMOTE_RACE_DONE_FILE": str(self.root / "race-done"),
                "REMOTE_STAT_AUDIT_FILE": str(self.remote_stat_audit_file),
                "REMOTE_DECODE_AUDIT_FILE": str(self.decode_audit_file),
                "REMOTE_RACE_IDENTITY_FILE": str(self.race_identity_file),
                "REMOTE_VIRTUAL_OWNER_EVIDENCE_FILE": str(
                    self.virtual_owner_evidence_file
                ),
                "REAL_STAT": self.real_stat,
                "REAL_SHA256SUM": self.real_sha256sum,
                "REAL_HEAD": self.real_head,
                "REAL_PYTHON3": self.real_python3,
                "RACE_REPLACEMENT": str(self.root / "race-replacement"),
            }
        )

    def _write_original_helper(self) -> None:
        source = b"""#!/usr/bin/env bash
set -euo pipefail
printf '1\\n' >>"$REMOTE_HELPER_INVOCATION_FILE"
printf '%s\\0' \"$@\" >\"$REMOTE_ARGS_FILE\"
printf 'original\\n' >\"$REMOTE_HELPER_MARKER_FILE\"
cat -- \"$REMOTE_RESPONSE_FILE\"
"""
        descriptor = os.open(
            self.helper_path,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL,
            0o600,
        )
        try:
            os.write(descriptor, source)
        finally:
            os.close(descriptor)
        self.helper_path.chmod(0o644)
        self.helper_hash = hashlib.sha256(source).hexdigest()
        replacement = self.root / "race-replacement"
        replacement.write_bytes(source.replace(b"original", b"evilmark"))
        replacement.chmod(0o644)

    def _write_remote_tools(self) -> None:
        fake_stat = self.remote_bin / "stat"
        fake_stat.write_text(
            """#!/usr/bin/env python3
import os
import stat
import sys

arguments = sys.argv[1:]
with open(os.environ["REMOTE_STAT_AUDIT_FILE"], "a", encoding="utf-8") as audit:
    audit.write(repr(arguments) + "\\n")
virtual_owner = os.environ.get("REMOTE_VIRTUAL_HELPER_UID")
if virtual_owner:
    with open(
        os.environ["REMOTE_VIRTUAL_OWNER_EVIDENCE_FILE"], "w", encoding="ascii"
    ) as evidence:
        evidence.write(
            f"helper_uid={virtual_owner} wrapper_euid={os.geteuid()}\\n"
        )
if os.environ.get("REMOTE_REJECT_OWNER_QUERY") == "yes" and any(
    "%u" in argument for argument in arguments
):
    raise SystemExit(97)
helper_target = arguments[-1] if arguments else ""
helper_format = any(
    marker in argument
    for argument in arguments
    for marker in ("%a:%h:%d:%i:%s", "%Lp:%l:%d:%i:%z")
)
if helper_format and helper_target in {"/dev/fd/9", "/proc/self/fd/9", os.environ["REMOTE_HELPER_PATH"]}:
    metadata = os.fstat(9) if helper_target.endswith("/fd/9") else os.stat(helper_target)
    rendered_mode = os.environ.get(
        "REMOTE_VIRTUAL_HELPER_MODE", f"{stat.S_IMODE(metadata.st_mode):o}"
    )
    print(
        f"{rendered_mode}:{metadata.st_nlink}:"
        f"{metadata.st_dev}:{metadata.st_ino}:{metadata.st_size}"
    )
else:
    os.execv(os.environ["REAL_STAT"], [os.environ["REAL_STAT"], *arguments])
""",
            encoding="utf-8",
        )
        fake_stat.chmod(0o755)
        fake_sha = self.remote_bin / "sha256sum"
        fake_sha.write_text(
            """#!/usr/bin/env bash
set -euo pipefail
\"$REAL_SHA256SUM\" \"$@\"
""",
            encoding="utf-8",
        )
        fake_sha.chmod(0o755)
        fake_base64 = self.remote_bin / "base64"
        fake_base64.write_text(
            """#!/usr/bin/env python3
import base64
import hashlib
import os
from pathlib import Path
import sys

arguments = sys.argv[1:]
payload = sys.stdin.buffer.read()
if arguments and arguments[0] in {"-d", "--decode"}:
    if os.environ.get("REMOTE_DECODE_FAILURE") == "yes":
        raise SystemExit(1)
    try:
        decoded = base64.b64decode(payload, validate=True)
    except Exception:
        raise SystemExit(1)
    with open(os.environ["REMOTE_DECODE_AUDIT_FILE"], "ab") as audit:
        audit.write(hashlib.sha256(decoded).hexdigest().encode("ascii") + b"\\n")
    sys.stdout.buffer.write(decoded)
    raise SystemExit(0)

mode = os.environ.get("REMOTE_RACE_MODE", "none")
helper = Path(os.environ["REMOTE_HELPER_PATH"])
replacement = Path(os.environ["RACE_REPLACEMENT"])
if mode == "after-snapshot-path":
    temporary = helper.with_suffix(".replacement")
    temporary.write_bytes(replacement.read_bytes())
    temporary.chmod(0o644)
    os.replace(temporary, helper)
elif mode in {"after-snapshot-inplace", "after-snapshot-append", "after-snapshot-truncate"}:
    before = helper.stat().st_ino
    if mode == "after-snapshot-inplace":
        helper.write_bytes(replacement.read_bytes())
    elif mode == "after-snapshot-append":
        with helper.open("ab") as stream:
            stream.write(b"\\n# appended after snapshot\\n")
    else:
        with helper.open("r+b") as stream:
            stream.truncate(max(1, helper.stat().st_size // 2))
    after = helper.stat().st_ino
    Path(os.environ["REMOTE_RACE_IDENTITY_FILE"]).write_text(
        f"{before}:{after}\\n", encoding="ascii"
    )
sys.stdout.buffer.write(base64.encodebytes(payload))
""",
            encoding="utf-8",
        )
        fake_base64.chmod(0o755)
        fake_head = self.remote_bin / "head"
        fake_head.write_text(
            """#!/usr/bin/env python3
import os
from pathlib import Path
import sys

if os.environ.get("REMOTE_RACE_MODE") != "during-capture":
    os.execv(os.environ["REAL_HEAD"], [os.environ["REAL_HEAD"], *sys.argv[1:]])
if len(sys.argv) != 3 or sys.argv[1] != "-c":
    raise SystemExit(2)
count = int(sys.argv[2])
first_size = max(1, count // 2)
first = os.read(0, first_size)
helper = Path(os.environ["REMOTE_HELPER_PATH"])
before = helper.stat().st_ino
helper.write_bytes(Path(os.environ["RACE_REPLACEMENT"]).read_bytes())
after = helper.stat().st_ino
Path(os.environ["REMOTE_RACE_IDENTITY_FILE"]).write_text(
    f"{before}:{after}\\n", encoding="ascii"
)
remaining = bytearray()
while len(first) + len(remaining) < count:
    block = os.read(0, count - len(first) - len(remaining))
    if not block:
        break
    remaining.extend(block)
sys.stdout.buffer.write(first + bytes(remaining))
""",
            encoding="utf-8",
        )
        fake_head.chmod(0o755)

    def _write_fake_ssh(self) -> None:
        fake_ssh = self.local_bin / "ssh"
        fake_ssh.write_text(
            """#!/usr/bin/env bash
set -euo pipefail
count=0
if [ -f \"$FAKE_SSH_COUNT_FILE\" ]; then
  count=\"$(<\"$FAKE_SSH_COUNT_FILE\")\"
fi
printf '%s\\n' \"$((count + 1))\" >\"$FAKE_SSH_COUNT_FILE\"
printf '%s\\n' \"$@\" >\"$FAKE_SSH_ARGS_FILE\"
cat >\"$FAKE_SSH_WRAPPER_FILE\"
known_hosts_path=
for argument in \"$@\"; do
  case \"$argument\" in
    UserKnownHostsFile=*) known_hosts_path=\"${argument#UserKnownHostsFile=}\" ;;
  esac
done
if [ -n \"$known_hosts_path\" ]; then
  printf '%s\\n' \"$known_hosts_path\" >\"$FAKE_KNOWN_HOSTS_PATH_FILE\"
  cp -- \"$known_hosts_path\" \"$FAKE_KNOWN_HOSTS_COPY\"
  mode=\"$(stat -c '%a' \"$known_hosts_path\" 2>/dev/null || stat -f '%Lp' \"$known_hosts_path\")\"
  printf '%s\\n' \"$mode\" >\"$FAKE_KNOWN_HOSTS_MODE_FILE\"
  if [ -d /proc/self/fd ]; then
    descriptor_root=/proc/self/fd
  else
    descriptor_root=/dev/fd
  fi
  : >\"$FAKE_PRIVATE_FILE_MODES\"
  for private_record in known_hosts:10 stdout:11 stderr:12; do
    private_name=\"${private_record%%:*}\"
    private_descriptor=\"${private_record##*:}\"
    private_metadata=\"$(stat -c '%a:%h' \"$descriptor_root/$private_descriptor\" 2>/dev/null || stat -f '%Lp:%l' \"$descriptor_root/$private_descriptor\")\"
    printf '%s:%s\\n' \"$private_name\" \"$private_metadata\" >>\"$FAKE_PRIVATE_FILE_MODES\"
  done
fi
case \"$FAKE_SSH_MODE\" in
  transport)
    cat \"$FAKE_SSH_RESPONSE_FILE\"
    cat \"$FAKE_SSH_STDERR_FILE\" >&2
    exit \"$FAKE_SSH_EXIT\"
    ;;
  block)
    trap 'exit 143' TERM HUP INT
    while :; do sleep 1; done
    ;;
  ignore-term)
    trap '' TERM HUP INT
    : >"$FAKE_SSH_SIGNAL_READY_FILE"
    while :; do sleep 1; done
    ;;
  execute)
    remote_command=\"${*: -1}\"
    set +e
    PATH=\"$FAKE_REMOTE_BIN:$FAKE_SYSTEM_PATH\" bash -c \"$remote_command\" <\"$FAKE_SSH_WRAPPER_FILE\"
    remote_exit=$?
    set -e
    cat \"$FAKE_SSH_STDERR_FILE\" >&2
    exit \"$remote_exit\"
    ;;
  *) exit 98 ;;
esac
""",
            encoding="utf-8",
        )
        fake_ssh.chmod(0o755)

    def install_preexisting_private_entry(self, name: str, kind: str) -> None:
        if name not in {"known_hosts", "stdout", "stderr"}:
            raise ValueError(name)
        if kind not in {"regular", "symlink"}:
            raise ValueError(kind)
        fake_python = self.local_bin / "python3"
        fake_python.write_text(
            f"""#!{self.real_python3}
import hashlib
import os
import sys

if (
    len(sys.argv) >= 5
    and sys.argv[2] == "create-entry"
    and sys.argv[4] == os.environ["FAKE_PREEXISTING_PRIVATE_NAME"]
):
    directory_fd = int(sys.argv[3])
    name = sys.argv[4]
    if os.environ["FAKE_PREEXISTING_PRIVATE_KIND"] == "regular":
        descriptor = os.open(
            name,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL,
            0o600,
            dir_fd=directory_fd,
        )
        try:
            os.write(descriptor, b"preexisting-private-entry\\n")
        finally:
            os.close(descriptor)
    else:
        os.symlink(
            os.environ["FAKE_PREEXISTING_PRIVATE_TARGET"],
            name,
            dir_fd=directory_fd,
        )
    planted = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
    if os.environ["FAKE_PREEXISTING_PRIVATE_KIND"] == "regular":
        planted_fd = os.open(
            name,
            os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=directory_fd,
        )
        try:
            detail = hashlib.sha256(os.read(planted_fd, 4096)).hexdigest()
        finally:
            os.close(planted_fd)
    else:
        detail = os.readlink(name, dir_fd=directory_fd)
    with open(
        os.environ["FAKE_PREEXISTING_ENTRY_AUDIT_FILE"],
        "w",
        encoding="utf-8",
    ) as audit:
        audit.write(
            "%d\\t%d\\t%o\\t%d\\t%s\\t%s\\n"
            % (
                planted.st_dev,
                planted.st_ino,
                planted.st_mode & 0o7777,
                planted.st_nlink,
                os.environ["FAKE_PREEXISTING_PRIVATE_KIND"],
                detail,
            )
        )
os.execv(os.environ["REAL_PYTHON3"], [os.environ["REAL_PYTHON3"], *sys.argv[1:]])
""",
            encoding="utf-8",
        )
        fake_python.chmod(0o755)
        self.environment.update(
            {
                "FAKE_PREEXISTING_PRIVATE_NAME": name,
                "FAKE_PREEXISTING_PRIVATE_KIND": kind,
                "FAKE_PREEXISTING_PRIVATE_TARGET": str(self.sentinel),
            }
        )

    def install_bootstrap_probe(
        self,
        *,
        signal_point: str | None = None,
        signal_number: int = signal.SIGTERM,
        fail_name: str | None = None,
        fail_fstat_name: str | None = None,
    ) -> Path:
        private_names = {"known_hosts", "stdout", "stderr"}
        if signal_point is not None:
            phase, _, name = signal_point.partition(":")
            valid_transition = phase in {"after-open", "after-unlink"} and name in private_names
            valid_handoff = signal_point == "before-supervisor:handoff"
            if not (valid_transition or valid_handoff):
                raise ValueError(signal_point)
        if fail_name is not None and fail_name not in private_names:
            raise ValueError(fail_name)
        if fail_fstat_name is not None and fail_fstat_name not in private_names:
            raise ValueError(fail_fstat_name)
        site_directory = self.root / "bootstrap-probe"
        site_directory.mkdir()
        done_file = self.root / "bootstrap-probe-done"
        (site_directory / "sitecustomize.py").write_text(
            """import os
from pathlib import Path
import subprocess

_real_open = os.open
_real_fstat = os.fstat
_real_unlink = os.unlink
_real_popen = subprocess.Popen
_prefix = ".clubs-release-status-"
_done = Path(os.environ["BOOTSTRAP_PROBE_DONE"])
_private_descriptors = {}
_fstat_failed = False


def _name(value):
    if isinstance(value, bytes):
        value = value.decode("ascii", "ignore")
    if not isinstance(value, str) or not value.startswith(_prefix):
        return None
    return value.removeprefix(_prefix).replace("known-hosts", "known_hosts")


def _signal_if(point, name):
    if (
        name is not None
        and os.environ.get("BOOTSTRAP_SIGNAL_POINT") == f"{point}:{name}"
        and not _done.exists()
    ):
        _done.touch()
        os.kill(os.getpid(), int(os.environ["BOOTSTRAP_SIGNAL_NUMBER"]))


def guarded_open(path, *args, **kwargs):
    name = _name(path)
    if name is not None and os.environ.get("BOOTSTRAP_FAIL_NAME") == name:
        raise OSError("injected private descriptor creation failure")
    descriptor = _real_open(path, *args, **kwargs)
    if name is not None:
        _private_descriptors[descriptor] = name
    _signal_if("after-open", name)
    return descriptor


def guarded_fstat(descriptor):
    global _fstat_failed
    name = _private_descriptors.get(descriptor)
    if (
        name is not None
        and os.environ.get("BOOTSTRAP_FAIL_FSTAT_NAME") == name
        and not _fstat_failed
    ):
        _fstat_failed = True
        raise OSError("injected private descriptor fstat failure")
    return _real_fstat(descriptor)


def guarded_unlink(path, *args, **kwargs):
    name = _name(path)
    result = _real_unlink(path, *args, **kwargs)
    _signal_if("after-unlink", name)
    return result


def guarded_popen(*args, **kwargs):
    _signal_if("before-supervisor", "handoff")
    return _real_popen(*args, **kwargs)


os.open = guarded_open
os.fstat = guarded_fstat
os.unlink = guarded_unlink
subprocess.Popen = guarded_popen
""",
            encoding="utf-8",
        )
        self.environment.update(
            {
                "PYTHONPATH": str(site_directory),
                "BOOTSTRAP_PROBE_DONE": str(done_file),
                "BOOTSTRAP_SIGNAL_POINT": signal_point or "",
                "BOOTSTRAP_SIGNAL_NUMBER": str(signal_number),
                "BOOTSTRAP_FAIL_NAME": fail_name or "",
                "BOOTSTRAP_FAIL_FSTAT_NAME": fail_fstat_name or "",
            }
        )
        return done_file

    def close(self) -> None:
        for link in self.helper_links:
            try:
                if link.is_dir() and not link.is_symlink():
                    link.rmdir()
                else:
                    link.unlink()
            except FileNotFoundError:
                pass
        try:
            if self.helper_path.is_dir() and not self.helper_path.is_symlink():
                self.helper_path.rmdir()
            else:
                self.helper_path.unlink()
        except FileNotFoundError:
            pass
        self.temporary.cleanup()

    def run(
        self,
        overrides: dict[str, str | None] | None = None,
        timeout: float = 10,
        runner: Path = RUNNER,
    ) -> subprocess.CompletedProcess[bytes]:
        environment = self.environment.copy()
        for name, value in (overrides or {}).items():
            if value is None:
                environment.pop(name, None)
            else:
                environment[name] = value
        return subprocess.run(
            ["bash", str(runner)],
            cwd=REPOSITORY_ROOT,
            env=environment,
            capture_output=True,
            timeout=timeout,
            check=False,
        )

    def start(
        self,
        overrides: dict[str, str | None] | None = None,
        runner: Path = RUNNER,
    ) -> subprocess.Popen[bytes]:
        environment = self.environment.copy()
        for name, value in (overrides or {}).items():
            if value is None:
                environment.pop(name, None)
            else:
                environment[name] = value
        return subprocess.Popen(
            ["bash", str(runner)],
            cwd=REPOSITORY_ROOT,
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

    @property
    def ssh_count(self) -> int:
        if not self.count_file.exists():
            return 0
        return int(self.count_file.read_text(encoding="ascii").strip())

    def assert_cleaned(self, test: unittest.TestCase) -> None:
        test.assertEqual("do-not-delete\n", self.sentinel.read_text(encoding="ascii"))
        test.assertEqual([], list(self.runner_tmp.iterdir()))

    def assert_no_sensitive_residual(
        self, test: unittest.TestCase, roots: tuple[Path, ...]
    ) -> None:
        forbidden = (
            known_hosts_entry().encode(),
            TRUSTED_STATUS,
            UNTRUSTED_STATUS,
            b"raw-credential-and-host-sentinel",
        )
        for root in roots:
            if not root.exists():
                continue
            for candidate in root.rglob("*"):
                if not candidate.is_file() or candidate.is_symlink():
                    continue
                data = candidate.read_bytes()
                for secret in forbidden:
                    test.assertNotIn(secret, data, str(candidate))

    @property
    def helper_invocation_count(self) -> int:
        if not self.helper_invocation_file.exists():
            return 0
        return len(self.helper_invocation_file.read_text(encoding="ascii").splitlines())

    @property
    def malicious_marker_count(self) -> int:
        if not self.helper_marker_file.exists():
            return 0
        return self.helper_marker_file.read_text(encoding="ascii").splitlines().count(
            "evilmark"
        )

    def helper_arguments(self) -> list[str]:
        raw = self.helper_args_file.read_bytes()
        return [item.decode("utf-8") for item in raw.split(b"\0") if item]


class ReadOnlyReleaseStatusRunnerTest(unittest.TestCase):
    def run_harness(
        self,
        response: bytes = TRUSTED_STATUS,
        ssh_stderr: bytes = b"",
        ssh_exit: int = 0,
        ssh_mode: str = "execute",
        overrides: dict[str, str | None] | None = None,
    ) -> tuple[subprocess.CompletedProcess[bytes], RunnerHarness]:
        harness = RunnerHarness(response, ssh_stderr, ssh_exit, ssh_mode)
        self.addCleanup(harness.close)
        return harness.run(overrides), harness

    def test_trusted_status_executes_real_wrapper_and_returns_bounded_evidence(self) -> None:
        result, harness = self.run_harness(ssh_stderr=b"private-success-stderr")
        self.assertEqual(0, result.returncode, result)
        self.assertEqual(TRUSTED_CHANNEL, result.stdout)
        self.assertEqual(b"", result.stderr)
        self.assertNotIn(b"private-success-stderr", result.stdout + result.stderr)
        wrapper = harness.wrapper_file.read_bytes()
        self.assertIn(b'bash -s -- status "$release_owner"', wrapper)
        self.assertNotIn(b'bash "$helper_path"', wrapper)
        self.assertNotIn(b'bash "$helper_fd_path"', wrapper)
        self.assertEqual(1, harness.ssh_count)

    def test_canonical_untrusted_status_is_safe_and_fails_closed(self) -> None:
        result, harness = self.run_harness(UNTRUSTED_STATUS)
        self.assertEqual(1, result.returncode)
        self.assertEqual(UNTRUSTED_CHANNEL, result.stdout)
        self.assertEqual(b"", result.stderr)
        self.assertEqual(1, harness.ssh_count)

    def test_exactly_one_ssh_invokes_literal_status_with_exact_argument_order(self) -> None:
        result, harness = self.run_harness()
        self.assertEqual(0, result.returncode)
        self.assertEqual(1, harness.ssh_count)
        self.assertEqual(
            ["status", harness.release_owner, "stage", "/srv/clubs-bot", REVISION, CANDIDATE_IMAGE, "start"],
            harness.helper_arguments(),
        )

    def test_transport_failure_is_never_retried(self) -> None:
        result, harness = self.run_harness(TRUSTED_STATUS, b"connection reset private detail", 255, "transport")
        self.assertEqual(1, result.returncode)
        self.assertEqual(channel("unavailable", "SSH_TRANSPORT_FAILURE"), result.stdout)
        self.assertEqual(1, harness.ssh_count)

    def test_helper_hash_link_and_type_are_enforced(self) -> None:
        for variant in ("link", "type", "fifo", "hash"):
            with self.subTest(variant=variant):
                harness = RunnerHarness()
                self.addCleanup(harness.close)
                overrides: dict[str, str | None] = {}
                if variant == "link":
                    link = harness.root / "helper-hard-link"
                    os.link(harness.helper_path, link)
                    harness.helper_links.append(link)
                elif variant == "type":
                    harness.helper_path.unlink()
                    harness.helper_path.mkdir(mode=0o700)
                elif variant == "fifo":
                    harness.helper_path.unlink()
                    os.mkfifo(harness.helper_path, mode=0o600)
                else:
                    overrides["EXPECTED_HELPER_SHA256"] = "0" * 64
                result = harness.run(overrides)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(channel("unavailable", "SSH_REMOTE_FAILURE"), result.stdout)
                self.assertEqual(1, harness.ssh_count)

    def test_helper_size_and_snapshot_decode_fail_closed_before_execution(self) -> None:
        variants = ("empty", "oversized", "decode")
        for variant in variants:
            with self.subTest(variant=variant):
                harness = RunnerHarness()
                self.addCleanup(harness.close)
                overrides: dict[str, str | None] = {}
                if variant == "empty":
                    harness.helper_path.write_bytes(b"")
                    harness.helper_path.chmod(0o600)
                    overrides["EXPECTED_HELPER_SHA256"] = hashlib.sha256(b"").hexdigest()
                elif variant == "oversized":
                    payload = b"x" * 262145
                    harness.helper_path.write_bytes(payload)
                    harness.helper_path.chmod(0o600)
                    overrides["EXPECTED_HELPER_SHA256"] = hashlib.sha256(payload).hexdigest()
                else:
                    overrides["REMOTE_DECODE_FAILURE"] = "yes"
                result = harness.run(overrides)
                self.assertEqual(1, result.returncode)
                self.assertEqual(
                    channel("unavailable", "SSH_REMOTE_FAILURE"), result.stdout
                )
                self.assertEqual(0, harness.helper_invocation_count)
                self.assertEqual(0, harness.malicious_marker_count)
                self.assertEqual(1, harness.ssh_count)

    def test_helper_safe_mode_predicate_accepts_uploader_variants_and_rejects_unsafe_bits(self) -> None:
        for mode in (0o600, 0o644, 0o700, 0o755):
            with self.subTest(mode=f"{mode:o}", expected="accepted"):
                harness = RunnerHarness()
                self.addCleanup(harness.close)
                harness.helper_path.chmod(mode)
                overrides = (
                    {"REMOTE_VIRTUAL_HELPER_MODE": f"{mode:o}"}
                    if mode & 0o7000
                    else None
                )
                result = harness.run(overrides)
                self.assertEqual(0, result.returncode, result)
                self.assertEqual(1, harness.helper_invocation_count)
        for mode in (0o000, 0o620, 0o602, 0o664, 0o666, 0o777, 0o1644, 0o2644, 0o4644):
            with self.subTest(mode=f"{mode:o}", expected="rejected"):
                harness = RunnerHarness()
                self.addCleanup(harness.close)
                harness.helper_path.chmod(mode)
                overrides = (
                    {"REMOTE_VIRTUAL_HELPER_MODE": f"{mode:o}"}
                    if mode & 0o7000
                    else None
                )
                result = harness.run(overrides)
                self.assertEqual(1, result.returncode)
                self.assertEqual(
                    channel("unavailable", "SSH_REMOTE_FAILURE"), result.stdout
                )
                self.assertEqual(0, harness.helper_invocation_count)
                self.assertEqual(1, harness.ssh_count)

    def test_helper_with_different_virtual_owner_is_accepted(self) -> None:
        virtual_owner = str(os.geteuid() + 1)
        result, harness = self.run_harness(
            overrides={
                "REMOTE_REJECT_OWNER_QUERY": "yes",
                "REMOTE_VIRTUAL_HELPER_UID": virtual_owner,
            }
        )
        self.assertEqual(0, result.returncode, result)
        self.assertEqual(TRUSTED_CHANNEL, result.stdout)
        self.assertEqual(1, harness.ssh_count)
        self.assertEqual(
            f"helper_uid={virtual_owner} wrapper_euid={os.geteuid()}\n",
            harness.virtual_owner_evidence_file.read_text(encoding="ascii"),
        )
        self.assertNotIn(
            "%u", harness.remote_stat_audit_file.read_text(encoding="utf-8")
        )

    def test_helper_path_replacement_after_snapshot_cannot_change_executed_bytes(self) -> None:
        result, harness = self.run_harness(
            overrides={"REMOTE_RACE_MODE": "after-snapshot-path"}
        )
        self.assertEqual(0, result.returncode, result)
        self.assertEqual(TRUSTED_CHANNEL, result.stdout)
        self.assertEqual("original\n", harness.helper_marker_file.read_text(encoding="ascii"))
        self.assertNotEqual(harness.helper_hash, hashlib.sha256(harness.helper_path.read_bytes()).hexdigest())
        self.assertEqual(1, harness.helper_invocation_count)
        self.assertEqual(0, harness.malicious_marker_count)
        self.assertEqual(1, harness.ssh_count)

    def test_same_inode_overwrite_after_snapshot_executes_original_bytes(self) -> None:
        result, harness = self.run_harness(
            overrides={"REMOTE_RACE_MODE": "after-snapshot-inplace"}
        )
        before, after = harness.race_identity_file.read_text(encoding="ascii").strip().split(":")
        self.assertEqual(before, after, "the fixture must overwrite the same inode")
        self.assertEqual(0, result.returncode, result)
        self.assertEqual(TRUSTED_CHANNEL, result.stdout)
        self.assertEqual("original\n", harness.helper_marker_file.read_text(encoding="ascii"))
        self.assertEqual(1, harness.helper_invocation_count)
        self.assertEqual(0, harness.malicious_marker_count)
        self.assertEqual(1, harness.ssh_count)
        decoded_hashes = harness.decode_audit_file.read_text(encoding="ascii").splitlines()
        self.assertEqual(3, len(decoded_hashes))
        self.assertEqual(1, len(set(decoded_hashes)))
        self.assertEqual(harness.helper_hash, decoded_hashes[0])

    def test_append_and_truncate_after_snapshot_leave_capture_authoritative(self) -> None:
        for mode in ("after-snapshot-append", "after-snapshot-truncate"):
            with self.subTest(mode=mode):
                result, harness = self.run_harness(
                    overrides={"REMOTE_RACE_MODE": mode}
                )
                self.assertEqual(0, result.returncode, result)
                self.assertEqual(TRUSTED_CHANNEL, result.stdout)
                self.assertEqual(1, harness.helper_invocation_count)
                self.assertEqual(0, harness.malicious_marker_count)
                self.assertEqual(1, harness.ssh_count)

    def test_same_inode_overwrite_during_capture_rejects_without_execution(self) -> None:
        result, harness = self.run_harness(
            overrides={"REMOTE_RACE_MODE": "during-capture"}
        )
        before, after = harness.race_identity_file.read_text(encoding="ascii").strip().split(":")
        self.assertEqual(before, after, "the fixture must overwrite the same inode")
        self.assertEqual(1, result.returncode)
        self.assertEqual(channel("unavailable", "SSH_REMOTE_FAILURE"), result.stdout)
        self.assertEqual(0, harness.helper_invocation_count)
        self.assertEqual(0, harness.malicious_marker_count)
        self.assertNotIn(b"STATUS_TRUSTED", result.stdout)
        self.assertEqual(1, harness.ssh_count)

    def test_helper_symlink_is_rejected_by_executed_wrapper(self) -> None:
        harness = RunnerHarness()
        self.addCleanup(harness.close)
        target = harness.root / "helper-target"
        shutil.copy2(harness.helper_path, target)
        harness.helper_path.unlink()
        harness.helper_path.symlink_to(target)
        result = harness.run()
        self.assertEqual(1, result.returncode)
        self.assertEqual(channel("unavailable", "SSH_REMOTE_FAILURE"), result.stdout)

    def test_multiple_hard_links_are_rejected_by_executed_wrapper(self) -> None:
        harness = RunnerHarness()
        self.addCleanup(harness.close)
        link = harness.root / "second-link"
        os.link(harness.helper_path, link)
        harness.helper_links.append(link)
        result = harness.run()
        self.assertEqual(1, result.returncode)
        self.assertEqual(channel("unavailable", "SSH_REMOTE_FAILURE"), result.stdout)

    def test_helper_hash_mismatch_is_rejected_by_executed_wrapper(self) -> None:
        result, harness = self.run_harness(overrides={"EXPECTED_HELPER_SHA256": "f" * 64})
        self.assertEqual(1, result.returncode)
        self.assertEqual(channel("unavailable", "SSH_REMOTE_FAILURE"), result.stdout)
        self.assertFalse(harness.helper_args_file.exists())

    def test_malformed_required_inputs_fail_before_ssh_without_echo(self) -> None:
        variants: dict[str, str | None] = {
            "APP_ENV": None,
            "SSH_USER": "deploy user",
            "SSH_HOST": "host.invalid;id",
            "SSH_PORT": "65536",
            "INCIDENT_TAG": "deploy-stage-nothex",
            "RELEASE_OWNER": "owner",
            "EXPECTED_REVISION": "A" * 40,
            "IMAGE_DIGEST": "ghcr.io/other/image@sha256:" + "0" * 64,
            "EXPECTED_HELPER_SHA256": "not-a-hash",
        }
        for variable, value in variants.items():
            with self.subTest(variable=variable):
                result, harness = self.run_harness(overrides={variable: value})
                self.assertEqual(2, result.returncode)
                self.assertEqual(channel("unavailable", "INPUT_INVALID"), result.stdout)
                self.assertEqual(0, harness.ssh_count)

    def test_runner_owned_temporary_root_resolution_is_fail_closed(self) -> None:
        successful = (
            ({"TMPDIR": None, "RUNNER_TEMP": None}, False),
            ({"TMPDIR": "", "RUNNER_TEMP": None}, False),
        )
        for overrides, _ in successful:
            harness = RunnerHarness()
            self.addCleanup(harness.close)
            overrides["RUNNER_TEMP"] = str(harness.runner_tmp)
            with self.subTest(case="runner-temp-fallback", tmpdir=overrides["TMPDIR"]):
                result = harness.run(overrides)
                self.assertEqual(0, result.returncode, result)
                self.assertEqual(1, harness.ssh_count)
                harness.assert_cleaned(self)

        harness = RunnerHarness()
        self.addCleanup(harness.close)
        alternate = harness.root / "unused-runner-temp"
        alternate.mkdir(mode=0o700)
        alternate_sentinel = alternate / ".clubs-release-status-known-hosts"
        alternate_sentinel.write_text("unused\n", encoding="ascii")
        result = harness.run(
            {"TMPDIR": str(harness.runner_tmp), "RUNNER_TEMP": str(alternate)}
        )
        self.assertEqual(0, result.returncode, result)
        self.assertEqual("unused\n", alternate_sentinel.read_text(encoding="ascii"))

        invalid_roots: list[tuple[str, dict[str, str | None]]] = [
            ("unset", {"TMPDIR": None, "RUNNER_TEMP": None}),
            ("shared-tmp", {"TMPDIR": "/tmp", "RUNNER_TEMP": None}),
            ("wrong-owner", {"TMPDIR": "/", "RUNNER_TEMP": None}),
            ("relative", {"TMPDIR": "runner-tmp", "RUNNER_TEMP": None}),
        ]
        for name, overrides in invalid_roots:
            with self.subTest(case=name):
                result, rejected = self.run_harness(overrides=overrides)
                self.assertEqual(1, result.returncode)
                self.assertEqual(channel("unavailable", "LOCAL_FAILURE"), result.stdout)
                self.assertEqual(0, rejected.ssh_count)

        for kind in ("symlink", "unsafe-mode"):
            harness = RunnerHarness()
            self.addCleanup(harness.close)
            if kind == "symlink":
                candidate = harness.root / "temporary-root-link"
                candidate.symlink_to(harness.runner_tmp)
            else:
                candidate = harness.runner_tmp
                candidate.chmod(0o770)
            with self.subTest(case=kind):
                result = harness.run({"TMPDIR": str(candidate), "RUNNER_TEMP": None})
                self.assertEqual(1, result.returncode)
                self.assertEqual(channel("unavailable", "LOCAL_FAILURE"), result.stdout)
                self.assertEqual(0, harness.ssh_count)

    def test_ssh_port_is_normalized_and_empty_raw_value_is_rejected(self) -> None:
        result, harness = self.run_harness(overrides={"SSH_PORT": "2202"})
        self.assertEqual(0, result.returncode, result)
        arguments = harness.ssh_args_file.read_text(encoding="utf-8").splitlines()
        self.assertEqual("2202", arguments[arguments.index("-p") + 1])
        result, rejected = self.run_harness(overrides={"SSH_PORT": ""})
        self.assertEqual(2, result.returncode)
        self.assertEqual(channel("unavailable", "INPUT_INVALID"), result.stdout)
        self.assertEqual(0, rejected.ssh_count)

    def test_option_like_ssh_users_fail_before_ssh(self) -> None:
        for ssh_user in ("-v", "-Ffoo", "-lroot"):
            with self.subTest(ssh_user=ssh_user):
                result, harness = self.run_harness(
                    overrides={"SSH_USER": ssh_user}
                )
                self.assertEqual(2, result.returncode)
                self.assertEqual(
                    channel("unavailable", "INPUT_INVALID"), result.stdout
                )
                self.assertEqual(0, harness.ssh_count)

    def test_compose_path_canonical_negatives_fail_before_ssh(self) -> None:
        invalid_paths = ("srv/app", "/", "/srv/app/", "/srv//app", "/srv/app/.", "/srv/app/..", "/srv/./app", "/srv/../app", "/srv/app name", "/srv/app;id", "/tmp/app")
        for compose_path in invalid_paths:
            with self.subTest(compose_path=compose_path):
                result, harness = self.run_harness(overrides={"COMPOSE_PATH": compose_path})
                self.assertEqual(2, result.returncode)
                self.assertEqual(channel("unavailable", "INPUT_INVALID"), result.stdout)
                self.assertEqual(0, harness.ssh_count)

    def test_known_hosts_is_private_and_only_strict_host_source(self) -> None:
        result, harness = self.run_harness()
        self.assertEqual(0, result.returncode)
        arguments = harness.ssh_args_file.read_text(encoding="utf-8").splitlines()
        for required in ("-F", "/dev/null", "BatchMode=yes", "StrictHostKeyChecking=yes", "GlobalKnownHostsFile=/dev/null", "KnownHostsCommand=none", "VerifyHostKeyDNS=no", "ProxyCommand=none", "ProxyJump=none", "PermitLocalCommand=no", "ConnectTimeout=15", "ConnectionAttempts=1"):
            self.assertIn(required, arguments)
        target_index = arguments.index("deployment@stage.invalid")
        self.assertEqual("--", arguments[target_index - 1])
        self.assertEqual("600\n", harness.known_hosts_mode_file.read_text(encoding="ascii"))
        self.assertEqual(0o700, stat.S_IMODE(harness.runner_tmp.stat().st_mode))
        self.assertEqual(
            "known_hosts:600:0\nstdout:600:0\nstderr:600:0\n",
            harness.private_file_modes.read_text(encoding="ascii"),
        )
        self.assertEqual(known_hosts_entry().encode(), harness.known_hosts_copy.read_bytes())
        harness.assert_cleaned(self)

    def test_malformed_known_hosts_fails_before_ssh(self) -> None:
        variants = ("", "stage.invalid not-a-key invalid\n", known_hosts_entry().rstrip("\n") + "\r\n", known_hosts_entry().rstrip("\n") + "\x01\n", "x" * 65537)
        for value in variants:
            with self.subTest(length=len(value)):
                result, harness = self.run_harness(overrides={"SSH_KNOWN_HOSTS": value})
                self.assertEqual(2, result.returncode)
                expected = "INPUT_INVALID" if value == "" else "KNOWN_HOSTS_INVALID"
                self.assertEqual(channel("unavailable", expected), result.stdout)
                self.assertEqual(0, harness.ssh_count)
                harness.assert_cleaned(self)

    def test_nonzero_ssh_suppresses_trusted_looking_stdout(self) -> None:
        for ssh_exit, stderr, category in ((1, b"remote failed", "SSH_REMOTE_FAILURE"), (255, b"connection reset", "SSH_TRANSPORT_FAILURE")):
            with self.subTest(ssh_exit=ssh_exit):
                result, harness = self.run_harness(TRUSTED_STATUS, stderr, ssh_exit, "transport")
                self.assertEqual(1, result.returncode)
                self.assertEqual(channel("unavailable", category), result.stdout)
                self.assertNotIn(TRUSTED_STATUS, result.stdout)
                self.assertEqual(1, harness.ssh_count)

    def test_auth_and_timeout_transport_categories_are_normalized(self) -> None:
        for raw_stderr, category in ((b"Permission denied (publickey). secret", "SSH_AUTH_FAILURE"), (b"Connection timed out private-target", "SSH_TIMEOUT")):
            with self.subTest(category=category):
                result, _ = self.run_harness(TRUSTED_STATUS, raw_stderr, 255, "transport")
                self.assertEqual(channel("unavailable", category), result.stdout)
                self.assertNotIn(raw_stderr, result.stdout + result.stderr)

    def test_raw_remote_stderr_is_suppressed_on_success_and_failure(self) -> None:
        for ssh_exit, mode in ((0, "execute"), (1, "transport")):
            with self.subTest(ssh_exit=ssh_exit):
                sentinel = b"raw-credential-and-host-sentinel"
                result, _ = self.run_harness(TRUSTED_STATUS, sentinel, ssh_exit, mode)
                self.assertNotIn(sentinel, result.stdout + result.stderr)
                self.assertEqual(b"", result.stderr)

    def test_exact_byte_framing_rejects_suffixes_controls_and_line_variants(self) -> None:
        variants = (b"", TRUSTED_STATUS + TRUSTED_STATUS, TRUSTED_STATUS.rstrip(b"\n"), TRUSTED_STATUS + b"suffix", TRUSTED_STATUS + b"\n", TRUSTED_STATUS.rstrip(b"\n") + b"\x00\n", TRUSTED_STATUS.rstrip(b"\n") + b"\x01\n", TRUSTED_STATUS.rstrip(b"\n") + b"\r\n", b"x" * 1024 + b"\n")
        for response in variants:
            with self.subTest(length=len(response), suffix=response[-4:]):
                result, harness = self.run_harness(response)
                self.assertEqual(1, result.returncode)
                self.assertEqual(channel("unavailable", "STATUS_MALFORMED"), result.stdout)
                self.assertEqual(1, harness.ssh_count)

    def test_invalid_environment_operation_and_tag_pair_fail_before_ssh(self) -> None:
        for overrides in ({"APP_ENV": "dev"}, {"REQUESTED_OPERATION": "status"}, {"REQUESTED_OPERATION": "start;cleanup"}, {"APP_ENV": "prod", "INCIDENT_TAG": "deploy-stage-44497dc"}):
            with self.subTest(overrides=overrides):
                result, harness = self.run_harness(overrides=overrides)
                self.assertEqual(2, result.returncode)
                self.assertEqual(channel("unavailable", "INPUT_INVALID"), result.stdout)
                self.assertEqual(0, harness.ssh_count)

    def test_prod_environment_and_matching_tag_use_the_same_status_channel(self) -> None:
        result, harness = self.run_harness(
            overrides={
                "APP_ENV": "prod",
                "INCIDENT_TAG": "deploy-prod-44497dc",
            }
        )
        self.assertEqual(0, result.returncode, result)
        self.assertEqual("prod", harness.helper_arguments()[2])
        self.assertEqual(1, harness.ssh_count)

    def test_each_approved_requested_operation_remains_status_data_only(self) -> None:
        operations = (
            "preflight", "prepare", "publish", "quiesce", "migrate", "start",
            "cleanup", "abort", "retention", "helper-cleanup", "resume-quiesce",
            "resume-migrate", "resume-start", "resume-cleanup",
        )
        for operation in operations:
            with self.subTest(operation=operation):
                result, harness = self.run_harness(
                    overrides={"REQUESTED_OPERATION": operation}
                )
                self.assertEqual(0, result.returncode, result)
                arguments = harness.helper_arguments()
                self.assertEqual("status", arguments[0])
                self.assertEqual(operation, arguments[-1])
                self.assertEqual(1, harness.ssh_count)

    def test_status_available_no_is_untrusted_even_with_matching_identity(self) -> None:
        status = TRUSTED_STATUS.replace(b"status_available=yes", b"status_available=no")
        result, _ = self.run_harness(status)
        self.assertEqual(1, result.returncode)
        self.assertEqual(status + channel("untrusted", "STATUS_UNTRUSTED"), result.stdout)

    def test_resume_permitted_does_not_change_channel_trust(self) -> None:
        status = TRUSTED_STATUS.replace(b"resume_permitted=no", b"resume_permitted=yes")
        result, _ = self.run_harness(status)
        self.assertEqual(0, result.returncode, result)
        self.assertEqual(status + channel("trusted", "STATUS_TRUSTED"), result.stdout)

    def test_every_identity_boolean_value_has_exact_trust_classification(self) -> None:
        for field in (
            "status_available",
            "owner_match",
            "revision_match",
            "digest_match",
        ):
            for value in ("yes", "no"):
                with self.subTest(field=field, value=value):
                    status = status_record(**{field: value})
                    result, harness = self.run_harness(status)
                    trusted = value == "yes"
                    self.assertEqual(0 if trusted else 1, result.returncode, result)
                    self.assertEqual(
                        status
                        + channel(
                            "trusted" if trusted else "untrusted",
                            "STATUS_TRUSTED" if trusted else "STATUS_UNTRUSTED",
                        ),
                        result.stdout,
                    )
                    self.assertEqual(1, harness.ssh_count)

    def test_every_valid_status_enum_value_is_accepted_by_the_real_parser(self) -> None:
        enum_values = {
            "checkpoint": (
                "none", "maintenance_prepared", "prior_state_captured",
                "candidate_override_published", "app_stop_intent", "app_quiesced",
                "migration_started", "migration_completed", "candidate_start_begun",
                "candidate_healthy", "cleanup_started", "cleanup_completed",
                "abort_started", "abort_completed", "unavailable",
            ),
            "operation_result": (
                "success", "remote_failure", "incomplete_unknown", "unavailable", "malformed",
            ),
            "migration_evidence": (
                "present", "absent", "unknown",
                "migration_outcome_requires_incident_reconciliation",
            ),
            "app_state": (
                "old_running", "absent", "candidate_running", "replaced", "ambiguous", "unknown",
            ),
            "abort_permitted": ("yes", "no"),
            "resume_permitted": ("yes", "no"),
            "failure_category": ("none", "untrusted_state_root"),
        }
        for field, values in enum_values.items():
            for value in values:
                with self.subTest(field=field, value=value):
                    status = status_record(**{field: value})
                    result, harness = self.run_harness(status)
                    self.assertEqual(0, result.returncode, result)
                    self.assertEqual(
                        status + channel("trusted", "STATUS_TRUSTED"), result.stdout
                    )
                    self.assertEqual(1, harness.ssh_count)
                    self.assertEqual(1, harness.helper_invocation_count)

    def assert_parser_malformed(
        self, response: bytes, runner: Path = RUNNER
    ) -> None:
        harness = RunnerHarness(response)
        self.addCleanup(harness.close)
        result = harness.run(runner=runner)
        self.assertEqual(1, result.returncode)
        self.assertEqual(channel("unavailable", "STATUS_MALFORMED"), result.stdout)
        self.assertNotIn(response.rstrip(b"\n"), result.stdout)
        self.assertEqual(1, harness.ssh_count)
        self.assertEqual(1, harness.helper_invocation_count)

    def test_each_invalid_enum_and_structural_parser_mutation_is_rejected(self) -> None:
        invalid_enums = {
            "status_available": "evil",
            "owner_match": "evil",
            "revision_match": "evil",
            "digest_match": "evil",
            "checkpoint": "evil",
            "operation_result": "evil",
            "migration_evidence": "evil",
            "app_state": "evil",
            "abort_permitted": "maybe",
            "resume_permitted": "maybe",
            "failure_category": "evil",
        }
        for field, value in invalid_enums.items():
            with self.subTest(kind="invalid-enum", field=field, value=value):
                self.assert_parser_malformed(status_record(**{field: value}))

        canonical = status_record()
        tokens = canonical.rstrip(b"\n").split(b" ")
        reordered = b" ".join(tokens[:5] + [tokens[6], tokens[5]] + tokens[7:]) + b"\n"
        structural = {
            "unknown-field": canonical.rstrip(b"\n") + b" unknown=value\n",
            "missing-field": canonical.replace(b" app_state=absent", b"", 1),
            "reordered-field": reordered,
            "duplicate-field": canonical.replace(
                b" checkpoint=candidate_start_begun",
                b" checkpoint=candidate_start_begun checkpoint=candidate_start_begun",
                1,
            ),
            "prefix": b"prefix " + canonical,
            "suffix": canonical.rstrip(b"\n") + b" suffix\n",
            "control-byte": canonical.rstrip(b"\n") + b"\x01\n",
            "no-final-lf": canonical.rstrip(b"\n"),
            "additional-lf": canonical + b"\n",
            "leading-space": b" " + canonical,
            "trailing-space": canonical.rstrip(b"\n") + b" \n",
            "double-space": canonical.replace(b" ", b"  ", 1),
            "tab-separator": canonical.replace(b" ", b"\t", 1),
            "mixed-tab-space": canonical.replace(b" ", b" \t", 1),
            "non-ascii-whitespace": canonical.replace(b" ", b"\xc2\xa0", 1),
        }
        for name, response in structural.items():
            with self.subTest(kind="structure", name=name):
                self.assert_parser_malformed(response)

    def test_checkpoint_evil_mutation_calibrates_parser_tests_independent_of_hash_pins(self) -> None:
        canonical_source = RUNNER.read_text(encoding="utf-8")
        mutated_source = canonical_source.replace(
            "checkpoint=(none|maintenance_prepared",
            "checkpoint=(evil|none|maintenance_prepared",
            1,
        )
        self.assertNotEqual(canonical_source, mutated_source)
        temporary = tempfile.TemporaryDirectory(prefix="status-parser-mutation-")
        self.addCleanup(temporary.cleanup)
        mutated_runner = Path(temporary.name) / "read-only-release-status.sh"
        mutated_runner.write_text(mutated_source, encoding="utf-8")
        mutated_runner.chmod(0o700)
        with self.assertRaises(AssertionError):
            self.assert_parser_malformed(
                status_record(checkpoint="evil"), runner=mutated_runner
            )

    def test_identity_evil_mutation_calibrates_parser_tests_independent_of_hash_pins(self) -> None:
        canonical_source = RUNNER.read_text(encoding="utf-8")
        mutated_source = canonical_source.replace(
            "status_available=(yes|no)",
            "status_available=(evil|yes|no)",
            1,
        )
        self.assertNotEqual(canonical_source, mutated_source)
        temporary = tempfile.TemporaryDirectory(prefix="status-identity-mutation-")
        self.addCleanup(temporary.cleanup)
        mutated_runner = Path(temporary.name) / "read-only-release-status.sh"
        mutated_runner.write_text(mutated_source, encoding="utf-8")
        mutated_runner.chmod(0o700)
        with self.assertRaises(AssertionError):
            self.assert_parser_malformed(
                status_record(status_available="evil"), runner=mutated_runner
            )

    def test_whitespace_mutation_calibrates_parser_tests_independent_of_hash_pins(self) -> None:
        canonical_source = RUNNER.read_text(encoding="utf-8")
        mutated_source = canonical_source.replace(
            "^release-status:v=1 status_available=",
            "^release-status:v=1[[:space:]]+status_available=",
            1,
        )
        self.assertNotEqual(canonical_source, mutated_source)
        temporary = tempfile.TemporaryDirectory(prefix="status-whitespace-mutation-")
        self.addCleanup(temporary.cleanup)
        mutated_runner = Path(temporary.name) / "read-only-release-status.sh"
        mutated_runner.write_text(mutated_source, encoding="utf-8")
        mutated_runner.chmod(0o700)
        with self.assertRaises(AssertionError):
            self.assert_parser_malformed(
                status_record().replace(b" ", b"  ", 1), runner=mutated_runner
            )

    def test_each_identity_mismatch_is_untrusted(self) -> None:
        for field in (b"owner_match", b"revision_match", b"digest_match"):
            with self.subTest(field=field):
                status = TRUSTED_STATUS.replace(field + b"=yes", field + b"=no")
                result, _ = self.run_harness(status)
                self.assertEqual(1, result.returncode)
                self.assertEqual(status + channel("untrusted", "STATUS_UNTRUSTED"), result.stdout)

    def test_descriptor_only_cleanup_occurs_after_success_and_parser_failure(self) -> None:
        for response in (TRUSTED_STATUS, b"malformed\n"):
            with self.subTest(response=response[:16]):
                result, harness = self.run_harness(response)
                self.assertIn(result.returncode, (0, 1))
                harness.assert_cleaned(self)

    def test_private_root_bootstrap_creates_no_nested_run_directory(self) -> None:
        result, harness = self.run_harness()
        self.assertEqual(0, result.returncode, result)
        self.assertEqual([], list(harness.runner_tmp.iterdir()))
        self.assertNotIn("mktemp", RUNNER.read_text(encoding="utf-8"))
        self.assertEqual(1, harness.ssh_count)

    def test_each_anonymous_private_file_creation_failure_leaves_no_residual(self) -> None:
        for private_name in ("known_hosts", "stdout", "stderr"):
            with self.subTest(private_name=private_name):
                harness = RunnerHarness()
                self.addCleanup(harness.close)
                harness.install_bootstrap_probe(fail_name=private_name)
                result = harness.run()
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(channel("unavailable", "LOCAL_FAILURE"), result.stdout)
                self.assertEqual(0, harness.ssh_count)
                harness.assert_cleaned(self)

    def test_fstat_failure_after_private_open_leaves_no_linked_residual(self) -> None:
        for private_name in ("known_hosts", "stdout", "stderr"):
            with self.subTest(private_name=private_name):
                harness = RunnerHarness()
                self.addCleanup(harness.close)
                harness.install_bootstrap_probe(fail_fstat_name=private_name)
                result = harness.run()
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(
                    channel("unavailable", "LOCAL_FAILURE"), result.stdout
                )
                self.assertEqual(b"", result.stderr)
                self.assertEqual(0, harness.ssh_count)
                harness.assert_cleaned(self)

    def test_preexisting_private_names_fail_exclusive_creation_without_deletion(self) -> None:
        internal_names = {
            "known_hosts": ".clubs-release-status-known-hosts",
            "stdout": ".clubs-release-status-stdout",
            "stderr": ".clubs-release-status-stderr",
        }
        for logical_name, entry_name in internal_names.items():
            for kind in ("regular", "symlink"):
                with self.subTest(private_name=logical_name, kind=kind):
                    harness = RunnerHarness()
                    self.addCleanup(harness.close)
                    sentinel_inode = harness.sentinel.stat().st_ino
                    sentinel_hash = hashlib.sha256(
                        harness.sentinel.read_bytes()
                    ).hexdigest()
                    planted = harness.runner_tmp / entry_name
                    if kind == "regular":
                        planted.write_bytes(b"preexisting-private-entry\n")
                        planted.chmod(0o600)
                        detail = hashlib.sha256(planted.read_bytes()).hexdigest()
                    else:
                        planted.symlink_to(harness.sentinel)
                        detail = os.readlink(planted)
                    before = planted.lstat()
                    result = harness.run()
                    after = planted.lstat()
                    self.assertEqual(1, result.returncode)
                    self.assertEqual(
                        channel("unavailable", "LOCAL_FAILURE"), result.stdout
                    )
                    self.assertEqual(0, harness.ssh_count)
                    self.assertEqual(
                        (before.st_dev, before.st_ino, before.st_mode, before.st_nlink),
                        (after.st_dev, after.st_ino, after.st_mode, after.st_nlink),
                    )
                    if kind == "regular":
                        self.assertEqual(
                            detail, hashlib.sha256(planted.read_bytes()).hexdigest()
                        )
                    else:
                        self.assertEqual(detail, os.readlink(planted))
                    self.assertEqual(sentinel_inode, harness.sentinel.stat().st_ino)
                    self.assertEqual(
                        sentinel_hash,
                        hashlib.sha256(harness.sentinel.read_bytes()).hexdigest(),
                    )
                    harness.assert_no_sensitive_residual(self, (harness.runner_tmp,))

    def test_descriptor_cleanup_occurs_for_hup_int_and_term(self) -> None:
        for signal_number in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
            with self.subTest(signal=signal_number):
                harness = RunnerHarness(ssh_mode="block")
                self.addCleanup(harness.close)
                process = harness.start()
                deadline = time.monotonic() + 5
                while not harness.known_hosts_path_file.exists():
                    if process.poll() is not None:
                        self.fail(f"runner exited before signal: {process.returncode}")
                    if time.monotonic() >= deadline:
                        process.kill()
                        self.fail("fake SSH was not reached")
                    time.sleep(0.02)
                process.send_signal(signal_number)
                stdout, stderr = process.communicate(timeout=5)
                self.assertNotEqual(0, process.returncode)
                self.assertEqual(b"", stderr)
                self.assertIn(b"release-status-channel:v=1", stdout)
                harness.assert_cleaned(self)

    def test_repeated_signals_cannot_reenter_cleanup_transition(self) -> None:
        signal_pairs = (
            (signal.SIGHUP, signal.SIGTERM),
            (signal.SIGINT, signal.SIGHUP),
            (signal.SIGTERM, signal.SIGINT),
        )
        for iteration, (first_signal, second_signal) in enumerate(
            signal_pairs * 4
        ):
            with self.subTest(
                iteration=iteration,
                first=first_signal,
                second=second_signal,
            ):
                harness = RunnerHarness(ssh_mode="block")
                self.addCleanup(harness.close)
                process = harness.start()
                deadline = time.monotonic() + 5
                while not harness.known_hosts_path_file.exists():
                    if process.poll() is not None:
                        self.fail(
                            f"runner exited before repeated signals: {process.returncode}"
                        )
                    if time.monotonic() >= deadline:
                        process.kill()
                        self.fail("fake SSH was not reached")
                    time.sleep(0.02)
                process.send_signal(first_signal)
                process.send_signal(second_signal)
                stdout, stderr = process.communicate(timeout=5)
                self.assertNotEqual(0, process.returncode)
                self.assertEqual(b"", stderr)
                self.assertEqual(1, stdout.count(b"release-status-channel:v=1"))
                self.assertNotIn(b"STATUS_TRUSTED", stdout)
                self.assertLessEqual(harness.ssh_count, 1)
                harness.assert_cleaned(self)

    def test_term_ignoring_ssh_is_killed_within_bounded_cleanup_deadline(self) -> None:
        harness = RunnerHarness(ssh_mode="ignore-term")
        self.addCleanup(harness.close)
        process = harness.start()
        deadline = time.monotonic() + 5
        while not harness.ssh_signal_ready_file.exists():
            if process.poll() is not None:
                self.fail(f"runner exited before signal: {process.returncode}")
            if time.monotonic() >= deadline:
                process.kill()
                self.fail("TERM-ignoring fake SSH was not reached")
            time.sleep(0.02)
        started = time.monotonic()
        process.send_signal(signal.SIGTERM)
        stdout, stderr = process.communicate(timeout=5)
        self.assertLess(time.monotonic() - started, 4)
        self.assertNotEqual(0, process.returncode)
        self.assertEqual(
            channel("unavailable", "LOCAL_CLEANUP_FAILURE"), stdout
        )
        self.assertEqual(b"", stderr)
        self.assertEqual(1, harness.ssh_count)
        harness.assert_cleaned(self)

    def test_signals_during_every_create_unlink_transition_are_deferred_and_cleaned(self) -> None:
        for phase in ("after-open", "after-unlink"):
            for private_name in ("known_hosts", "stdout", "stderr"):
                for signal_number in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
                    with self.subTest(
                        phase=phase,
                        private_name=private_name,
                        signal=signal_number,
                    ):
                        harness = RunnerHarness()
                        self.addCleanup(harness.close)
                        sentinel_inode = harness.sentinel.stat().st_ino
                        sentinel_hash = hashlib.sha256(
                            harness.sentinel.read_bytes()
                        ).hexdigest()
                        done = harness.install_bootstrap_probe(
                            signal_point=f"{phase}:{private_name}",
                            signal_number=signal_number,
                        )
                        result = harness.run()
                        self.assertNotEqual(0, result.returncode)
                        self.assertEqual(
                            channel("unavailable", "LOCAL_FAILURE"), result.stdout
                        )
                        self.assertEqual(b"", result.stderr)
                        self.assertTrue(done.exists())
                        self.assertEqual(0, harness.ssh_count)
                        harness.assert_cleaned(self)
                        self.assertEqual(
                            sentinel_inode, harness.sentinel.stat().st_ino
                        )
                        self.assertEqual(
                            sentinel_hash,
                            hashlib.sha256(harness.sentinel.read_bytes()).hexdigest(),
                        )

    def test_signal_at_bootstrap_supervisor_handoff_cannot_be_lost(self) -> None:
        for signal_number in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
            with self.subTest(signal=signal_number):
                harness = RunnerHarness()
                self.addCleanup(harness.close)
                done = harness.install_bootstrap_probe(
                    signal_point="before-supervisor:handoff",
                    signal_number=signal_number,
                )
                result = harness.run()
                self.assertNotEqual(0, result.returncode)
                self.assertTrue(done.exists())
                self.assertEqual(b"", result.stderr)
                self.assertEqual(
                    channel("unavailable", "LOCAL_FAILURE"), result.stdout
                )
                self.assertNotIn(b"STATUS_TRUSTED", result.stdout)
                self.assertLessEqual(harness.ssh_count, 1)
                harness.assert_cleaned(self)

    def test_cleanup_collision_cannot_delete_preexisting_sibling(self) -> None:
        result, harness = self.run_harness(b"bad\n")
        self.assertEqual(1, result.returncode)
        harness.assert_cleaned(self)
        self.assertTrue(harness.sentinel.exists())

    def test_anchored_root_rebinding_cannot_delete_or_change_replacement_path(self) -> None:
        for signal_number in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
            with self.subTest(signal=signal_number):
                harness = RunnerHarness(ssh_mode="block")
                self.addCleanup(harness.close)
                process = harness.start()
                deadline = time.monotonic() + 5
                while not harness.known_hosts_path_file.exists():
                    if process.poll() is not None:
                        self.fail(f"runner exited before collision: {process.returncode}")
                    if time.monotonic() >= deadline:
                        process.kill()
                        self.fail("fake SSH was not reached")
                    time.sleep(0.02)
                original_directory = harness.runner_tmp
                moved_directory = harness.root / "moved-owned-private-root"
                original_directory.rename(moved_directory)
                original_directory.mkdir(mode=0o700)
                replacement_sentinel = original_directory / "replacement-sentinel"
                replacement_sentinel.write_text("preserve\n", encoding="ascii")
                replacement_inode = replacement_sentinel.stat().st_ino
                replacement_hash = hashlib.sha256(
                    replacement_sentinel.read_bytes()
                ).hexdigest()
                process.send_signal(signal_number)
                stdout, stderr = process.communicate(timeout=5)
                self.assertNotEqual(0, process.returncode)
                self.assertEqual(b"", stderr)
                self.assertEqual(
                    channel("unavailable", "LOCAL_FAILURE"), stdout
                )
                self.assertEqual(replacement_inode, replacement_sentinel.stat().st_ino)
                self.assertEqual(
                    replacement_hash,
                    hashlib.sha256(replacement_sentinel.read_bytes()).hexdigest(),
                )
                self.assertTrue(moved_directory.exists())
                self.assertEqual([], list(moved_directory.iterdir()))
                harness.assert_no_sensitive_residual(
                    self, (moved_directory, original_directory)
                )

    def test_replacement_private_names_are_never_deleted_by_descriptor_cleanup(self) -> None:
        harness = RunnerHarness(ssh_mode="block")
        self.addCleanup(harness.close)
        process = harness.start()
        deadline = time.monotonic() + 5
        while not harness.known_hosts_path_file.exists():
            if process.poll() is not None:
                self.fail(f"runner exited before replacement: {process.returncode}")
            if time.monotonic() >= deadline:
                process.kill()
                self.fail("fake SSH was not reached")
            time.sleep(0.02)
        private_root = harness.runner_tmp
        sentinels: dict[str, tuple[int, str]] = {}
        for private_name in (
            ".clubs-release-status-known-hosts",
            ".clubs-release-status-stdout",
            ".clubs-release-status-stderr",
        ):
            replacement = private_root / private_name
            replacement.write_text(f"replacement-{private_name}\n", encoding="ascii")
            sentinels[private_name] = (
                replacement.stat().st_ino,
                hashlib.sha256(replacement.read_bytes()).hexdigest(),
            )
        process.send_signal(signal.SIGTERM)
        stdout, stderr = process.communicate(timeout=5)
        self.assertNotEqual(0, process.returncode)
        self.assertEqual(channel("unavailable", "LOCAL_FAILURE"), stdout)
        self.assertEqual(b"", stderr)
        for private_name, (inode, digest) in sentinels.items():
            replacement = private_root / private_name
            self.assertEqual(inode, replacement.stat().st_ino)
            self.assertEqual(digest, hashlib.sha256(replacement.read_bytes()).hexdigest())
        harness.assert_no_sensitive_residual(self, (private_root,))

    def test_replacement_symlinks_cannot_delete_neighboring_sentinel(self) -> None:
        harness = RunnerHarness(ssh_mode="block")
        self.addCleanup(harness.close)
        sentinel_inode = harness.sentinel.stat().st_ino
        sentinel_hash = hashlib.sha256(harness.sentinel.read_bytes()).hexdigest()
        process = harness.start()
        deadline = time.monotonic() + 5
        while not harness.known_hosts_path_file.exists():
            if process.poll() is not None:
                self.fail(f"runner exited before symlink collision: {process.returncode}")
            if time.monotonic() >= deadline:
                process.kill()
                self.fail("fake SSH was not reached")
            time.sleep(0.02)
        private_root = harness.runner_tmp
        for private_name in (
            ".clubs-release-status-known-hosts",
            ".clubs-release-status-stdout",
            ".clubs-release-status-stderr",
        ):
            (private_root / private_name).symlink_to(harness.sentinel)
        process.send_signal(signal.SIGTERM)
        stdout, stderr = process.communicate(timeout=5)
        self.assertNotEqual(0, process.returncode)
        self.assertEqual(channel("unavailable", "LOCAL_FAILURE"), stdout)
        self.assertEqual(b"", stderr)
        self.assertEqual(sentinel_inode, harness.sentinel.stat().st_ino)
        self.assertEqual(
            sentinel_hash, hashlib.sha256(harness.sentinel.read_bytes()).hexdigest()
        )
        self.assertEqual(3, len(list(private_root.iterdir())))

    def test_runner_source_forbids_live_trust_fallback_and_lifecycle_paths(self) -> None:
        source = RUNNER.read_text(encoding="utf-8")
        self.assertEqual(1, source.count('ssh -p "$SSH_PORT"'))
        self.assertIn('temporary_root="${TMPDIR:-}"', source)
        self.assertIn('temporary_root="${RUNNER_TEMP:-}"', source)
        self.assertNotIn('${TMPDIR:-/tmp}', source)
        self.assertNotIn('${RUNNER_TEMP:-/tmp}', source)
        self.assertNotIn("mktemp", source)
        self.assertNotIn("run_directory", source)
        self.assertIn("os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW", source)
        self.assertIn("opened.append([descriptor, name, None, True])", source)
        self.assertIn("os.unlink(name, dir_fd=root_fd)", source)
        self.assertIn("os.fstat(descriptor).st_nlink != 0", source)
        self.assertIn("start_new_session=True", source)
        self.assertIn("os.killpg(child_process.pid, signum)", source)
        self.assertIn("signal.pthread_sigmask(signal.SIG_BLOCK, watched_signals)", source)
        self.assertIn("trap '' HUP INT TERM", source)
        self.assertIn('trap \'trap "" HUP INT TERM; handle_signal 143\' TERM', source)
        self.assertNotIn("ssh-keyscan", source)
        self.assertNotIn("eval ", source)
        self.assertNotRegex(source, r"(^|\n)\s*(scp|docker|docker-compose|psql)\s")
        self.assertEqual(1, source.count('bash -s -- status "$release_owner" "$app_env"'))
        self.assertNotIn('bash "$helper_path" status', source)
        self.assertNotIn('bash "$helper_fd_path" status', source)
        self.assertIn('readonly helper_snapshot_b64', source)
        self.assertNotIn("helper_owner", source)
        remote_wrapper = source.split("<<'REMOTE_STATUS' &\n", 1)[1].split(
            "\nREMOTE_STATUS\n", 1
        )[0]
        self.assertNotRegex(
            remote_wrapper,
            r"stat[^\n]*%u|\[\s+-O\s|test\s+-O\s|find[^\n]*-(?:user|uid)\s|ls\s+-n\s|chown\s|chgrp\s",
        )


class StrictAccountingProbeTest(unittest.TestCase):
    def test_accounting_required_method(self) -> None:
        self.assertTrue(True)  # ACCOUNTING_REQUIRED_ASSERTION

    def test_accounting_required_subtests(self) -> None:
        for value in ("alpha", "beta"):  # ACCOUNTING_REQUIRED_SUBTESTS
            with self.subTest(value=value):
                self.assertIn(value, {"alpha", "beta"})


class StrictAccountingContractTest(unittest.TestCase):
    def test_strict_counter_contract_rejects_every_nonzero_or_drifted_counter(self) -> None:
        canonical = {
            "methods": PROBE_METHOD_COUNT,
            "subtests": PROBE_SUBTEST_COUNT,
            **ZERO_OUTCOMES,
        }
        mutations = {
            "method-drift": {**canonical, "methods": PROBE_METHOD_COUNT - 1},
            "subtest-drift": {**canonical, "subtests": PROBE_SUBTEST_COUNT - 1},
            "failure": {**canonical, "failures": 1},
            "error": {**canonical, "errors": 1},
            "skip": {**canonical, "skipped": 1},
            "expected-failure": {**canonical, "expected_failures": 1},
            "unexpected-success": {**canonical, "unexpected_successes": 1},
        }
        self.assertTrue(
            strict_counters_are_accepted(
                canonical, PROBE_METHOD_COUNT, PROBE_SUBTEST_COUNT
            )
        )
        for name, counters in mutations.items():
            with self.subTest(name=name):
                self.assertFalse(
                    strict_counters_are_accepted(
                        counters, PROBE_METHOD_COUNT, PROBE_SUBTEST_COUNT
                    )
                )

    def test_strict_accounting_rejects_source_mutations_and_bad_summaries(self) -> None:
        source = Path(__file__).read_text(encoding="utf-8")
        skip_anchor = (
            "self.assertTrue(True)  # ACCOUNTING_REQUIRED_" + "ASSERTION"
        )
        method_anchor = (
            "def test_accounting_required_" + "method(self) -> None:"
        )
        subtest_anchor = (
            'for value in ("alpha", "beta"):  # ACCOUNTING_REQUIRED_'
            + "SUBTESTS"
        )
        source_mutations = {
            "skip-required-test": source.replace(
                skip_anchor,
                'raise unittest.SkipTest("accounting calibration")',
                1,
            ),
            "remove-required-method": source.replace(
                method_anchor,
                "def accounting_required_method(self) -> None:",
                1,
            ),
            "remove-required-subtest": source.replace(
                subtest_anchor,
                'for value in ("alpha",):  # ACCOUNTING_REQUIRED_SUBTESTS',
                1,
            ),
        }
        for anchor in (skip_anchor, method_anchor, subtest_anchor):
            self.assertEqual(1, source.count(anchor))
        for name, mutated_source in source_mutations.items():
            with self.subTest(name=name):
                self.assertNotEqual(source, mutated_source)
                with tempfile.TemporaryDirectory(
                    prefix="status-accounting-mutation-"
                ) as directory:
                    mutated = Path(directory) / "test_status.py"
                    mutated.write_text(mutated_source, encoding="utf-8")
                    result = subprocess.run(
                        [sys.executable, str(mutated), STRICT_ACCOUNTING_PROBE_ARGUMENT],
                        cwd=REPOSITORY_ROOT,
                        env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
                        capture_output=True,
                        timeout=10,
                        check=False,
                    )
                self.assertNotEqual(0, result.returncode, result)
                self.assertFalse(
                    strict_summary_is_exact(
                        result.stdout, PROBE_METHOD_COUNT, PROBE_SUBTEST_COUNT
                    )
                )
                self.assertNotIn(b"release-status-channel: OK", result.stdout)

        canonical_summary = (
            strict_summary(
                methods=PROBE_METHOD_COUNT,
                subtests=PROBE_SUBTEST_COUNT,
                **ZERO_OUTCOMES,
            ).encode("ascii")
            + b"\n"
        )
        for name, malformed in (
            ("duplicate-summary", canonical_summary + canonical_summary),
            (
                "altered-summary-count",
                canonical_summary.replace(b"methods=2", b"methods=3", 1),
            ),
            ("embedded-nul", canonical_summary[:-1] + b"\0\n"),
            ("oversized-summary", canonical_summary[:-1] + b"A" * 4096 + b"\n"),
        ):
            with self.subTest(name=name):
                self.assertFalse(
                    strict_summary_is_exact(
                        malformed, PROBE_METHOD_COUNT, PROBE_SUBTEST_COUNT
                    )
                )


def workflow_literal_run(step_name: str) -> str:
    """Return one literal Bash `run: |` block from the checked-in workflow."""

    lines = WORKFLOW.read_text(encoding="utf-8").splitlines()
    step_marker = f"      - name: {step_name}"
    try:
        step_start = lines.index(step_marker)
    except ValueError as error:
        raise AssertionError(f"workflow step is missing: {step_name}") from error

    run_start = None
    for index in range(step_start + 1, len(lines)):
        line = lines[index]
        if line.startswith("      - name: "):
            break
        if line == "        run: |":
            run_start = index + 1
            break
    if run_start is None:
        raise AssertionError(f"workflow step lacks a literal run block: {step_name}")

    block: list[str] = []
    for line in lines[run_start:]:
        if line.startswith("      - name: "):
            break
        if line and not line.startswith("          "):
            raise AssertionError(
                f"workflow run block has ambiguous indentation: {step_name}"
            )
        block.append(line[10:] if line else "")
    if not block:
        raise AssertionError(f"workflow step has an empty run block: {step_name}")
    return "\n".join(block).rstrip("\n") + "\n"


class GitBlobFlowResult:
    def __init__(
        self,
        process: subprocess.CompletedProcess[bytes],
        output: bytes,
        secret_consuming_steps: int,
        runner_invocations: int,
    ) -> None:
        self.process = process
        self.output = output
        self.secret_consuming_steps = secret_consuming_steps
        self.runner_invocations = runner_invocations


class GitBlobWorkflowHarness:
    """Executes the workflow's real incident revision/blob shell against real Git."""

    def __init__(
        self,
        entry_kind: str,
        *,
        incident_content: bytes = b"#!/usr/bin/env bash\necho incident-helper\n",
        incident_mode: int = 0o755,
        implementation_content: bytes = b"#!/usr/bin/env bash\necho implementation-helper\n",
    ) -> None:
        self.temporary = tempfile.TemporaryDirectory(
            prefix="release-status-git-blob-workflow-"
        )
        self.root = Path(self.temporary.name)
        self.incident = self.root / "incident"
        self.implementation = self.root / "implementation"
        self.bin = self.root / "bin"
        self.runner_temp = self.root / "runner-temp"
        self.github_output = self.root / "github-output"
        self.secret_marker = self.root / "secret-consuming-step-count"
        self.runner_marker = self.root / "runner-invocation-count"
        self.real_git = shutil.which("git")
        if self.real_git is None:
            self.temporary.cleanup()
            raise unittest.SkipTest("git is required")
        self.git_environment = {
            **os.environ,
            "GIT_AUTHOR_NAME": "Release Status Fixture",
            "GIT_AUTHOR_EMAIL": "release-status-fixture@example.invalid",
            "GIT_COMMITTER_NAME": "Release Status Fixture",
            "GIT_COMMITTER_EMAIL": "release-status-fixture@example.invalid",
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_OPTIONAL_LOCKS": "0",
            "GIT_TERMINAL_PROMPT": "0",
        }

        self._initialize_repository(self.implementation)
        self._write_regular_helper(
            self.implementation, implementation_content, 0o755
        )
        self._commit_all(self.implementation, "implementation helper")
        self.implementation_content = implementation_content

        self._initialize_repository(self.incident)
        self._create_incident_entry(entry_kind, incident_content, incident_mode)
        self.incident_content = incident_content
        self.incident_mode = incident_mode
        self.expected_revision = self._git_text(
            self.incident, "rev-parse", "HEAD"
        ).strip()
        self.runner_temp.mkdir(mode=0o700)

    def __enter__(self) -> GitBlobWorkflowHarness:
        return self

    def __exit__(self, *unused: object) -> None:
        self.temporary.cleanup()

    def _git(
        self,
        repository: Path,
        *arguments: str,
        check: bool = True,
    ) -> subprocess.CompletedProcess[bytes]:
        return subprocess.run(
            [self.real_git, "-C", str(repository), *arguments],
            env=self.git_environment,
            capture_output=True,
            timeout=10,
            check=check,
        )

    def _git_text(self, repository: Path, *arguments: str) -> str:
        return self._git(repository, *arguments).stdout.decode("ascii")

    def _initialize_repository(self, repository: Path) -> None:
        repository.mkdir()
        subprocess.run(
            [self.real_git, "init", "--quiet", str(repository)],
            env=self.git_environment,
            capture_output=True,
            timeout=10,
            check=True,
        )
        self._git(repository, "config", "core.filemode", "true")

    def _write_regular_helper(
        self, repository: Path, content: bytes, mode: int
    ) -> Path:
        helper = repository / HELPER_REPOSITORY_PATH
        helper.parent.mkdir(parents=True, exist_ok=True)
        helper.write_bytes(content)
        helper.chmod(mode)
        return helper

    def _commit_all(self, repository: Path, message: str) -> None:
        self._git(repository, "add", "--all")
        self._git(repository, "commit", "--quiet", "-m", message)

    def _create_incident_entry(
        self, entry_kind: str, content: bytes, mode: int
    ) -> None:
        if entry_kind == "regular":
            self._write_regular_helper(self.incident, content, mode)
            self._git(self.incident, "add", "--", HELPER_REPOSITORY_PATH)
            self._git(
                self.incident,
                "update-index",
                "--chmod=+x" if mode == 0o755 else "--chmod=-x",
                "--",
                HELPER_REPOSITORY_PATH,
            )
            self._git(self.incident, "commit", "--quiet", "-m", "incident helper")
            return
        if entry_kind == "root-ancestor-symlink":
            os.symlink("../implementation/scripts", self.incident / "scripts")
            self._commit_all(self.incident, "root ancestor symlink")
            return
        if entry_kind == "nested-ancestor-symlink":
            (self.incident / "scripts").mkdir()
            os.symlink(
                "../../implementation/scripts/deploy",
                self.incident / "scripts/deploy",
            )
            self._commit_all(self.incident, "nested ancestor symlink")
            return
        if entry_kind == "leaf-symlink":
            helper = self.incident / HELPER_REPOSITORY_PATH
            helper.parent.mkdir(parents=True)
            os.symlink(
                "../../../implementation/scripts/deploy/remote-compose-release.sh",
                helper,
            )
            self._commit_all(self.incident, "leaf helper symlink")
            return
        if entry_kind == "missing":
            (self.incident / "README").write_text("no helper\n", encoding="ascii")
            self._commit_all(self.incident, "missing helper")
            return
        if entry_kind == "tree":
            helper_tree = self.incident / HELPER_REPOSITORY_PATH
            helper_tree.mkdir(parents=True)
            (helper_tree / "payload").write_bytes(content)
            self._commit_all(self.incident, "helper path is a tree")
            return
        if entry_kind == "gitlink":
            (self.incident / "README").write_text("gitlink fixture\n", encoding="ascii")
            self._commit_all(self.incident, "gitlink target commit")
            target_commit = self._git_text(self.incident, "rev-parse", "HEAD").strip()
            self._git(
                self.incident,
                "update-index",
                "--add",
                "--cacheinfo",
                f"160000,{target_commit},{HELPER_REPOSITORY_PATH}",
            )
            self._git(self.incident, "commit", "--quiet", "-m", "helper gitlink")
            return
        raise AssertionError(f"unknown incident entry fixture: {entry_kind}")

    @property
    def filesystem_helper(self) -> Path:
        return self.incident / HELPER_REPOSITORY_PATH

    def tree_record(self) -> bytes:
        return self._git(
            self.incident,
            "ls-tree",
            "--full-tree",
            "-z",
            self.expected_revision,
            "--",
            HELPER_REPOSITORY_PATH,
        ).stdout

    def blob_oid(self) -> str:
        record = self.tree_record()
        metadata, path = record.removesuffix(b"\0").split(b"\t", 1)
        if path.decode("utf-8") != HELPER_REPOSITORY_PATH:
            raise AssertionError("unexpected helper path in fixture tree")
        _mode, object_type, object_id = metadata.decode("ascii").split(" ")
        if object_type != "blob":
            raise AssertionError("unexpected helper object type in fixture tree")
        return object_id

    def raw_blob(self) -> bytes:
        return self._git(self.incident, "cat-file", "blob", self.blob_oid()).stdout

    def _install_git_ls_tree_override(self, records: bytes) -> None:
        self.bin.mkdir(exist_ok=True)
        fake_git = self.bin / "git"
        fake_git.write_text(
            "#!/usr/bin/env python3\n"
            "import base64\n"
            "import os\n"
            "import sys\n"
            "if 'ls-tree' in sys.argv[1:]:\n"
            "    sys.stdout.buffer.write(base64.b64decode("
            "os.environ['FAKE_LS_TREE_RECORDS_B64']))\n"
            "    raise SystemExit(0)\n"
            "real_git = os.environ['REAL_GIT_FOR_FIXTURE']\n"
            "os.execv(real_git, [real_git, *sys.argv[1:]])\n",
            encoding="ascii",
        )
        fake_git.chmod(0o755)
        self.ls_tree_override = records

    @staticmethod
    def _line_count(path: Path) -> int:
        if not path.exists():
            return 0
        return len(path.read_bytes().splitlines())

    def run_pre_secret_flow(
        self, *, ls_tree_override: bytes | None = None
    ) -> GitBlobFlowResult:
        for output in (
            self.github_output,
            self.secret_marker,
            self.runner_marker,
        ):
            if output.exists():
                output.unlink()
        environment = {
            **os.environ,
            "EXPECTED_REVISION": self.expected_revision,
            "GITHUB_OUTPUT": str(self.github_output),
            "RUNNER_TEMP": str(self.runner_temp),
            "TMPDIR": str(self.runner_temp),
            "SECRET_CONSUMING_STEP_MARKER": str(self.secret_marker),
            "RUNNER_INVOCATION_MARKER": str(self.runner_marker),
        }
        if ls_tree_override is not None:
            self._install_git_ls_tree_override(ls_tree_override)
            environment.update(
                {
                    "PATH": f"{self.bin}:{environment.get('PATH', '')}",
                    "REAL_GIT_FOR_FIXTURE": self.real_git,
                    "FAKE_LS_TREE_RECORDS_B64": base64.b64encode(
                        ls_tree_override
                    ).decode("ascii"),
                }
            )
        script = (
            workflow_literal_run("Verify incident revision")
            + workflow_literal_run("Derive retained helper SHA-256")
            + "printf 'secret-consuming-step\\n' "
            + '>>"$SECRET_CONSUMING_STEP_MARKER"\n'
            + "printf 'runner-invocation\\n' "
            + '>>"$RUNNER_INVOCATION_MARKER"\n'
        )
        process = subprocess.run(
            ["bash", "-c", script],
            cwd=self.root,
            env=environment,
            capture_output=True,
            timeout=10,
            check=False,
        )
        output = self.github_output.read_bytes() if self.github_output.exists() else b""
        return GitBlobFlowResult(
            process,
            output,
            self._line_count(self.secret_marker),
            self._line_count(self.runner_marker),
        )


class ReleaseStatusWorkflowContractTest(unittest.TestCase):
    def assert_git_blob_flow_rejected(
        self, result: GitBlobFlowResult, diagnostic: str, runner_temp: Path
    ) -> None:
        self.assertEqual(2, result.process.returncode, result.process)
        self.assertEqual(b"", result.process.stdout)
        self.assertEqual((diagnostic + "\n").encode("ascii"), result.process.stderr)
        self.assertEqual(b"", result.output)
        self.assertEqual(0, result.secret_consuming_steps)
        self.assertEqual(0, result.runner_invocations)
        self.assertEqual([], list(runner_temp.glob("incident-helper-tree.*")))

    def test_exact_incident_git_blob_hash_accepts_regular_file_modes(self) -> None:
        content = b"#!/usr/bin/env bash\nprintf 'incident raw blob bytes\\n'\n"
        for name, mode, git_mode in (
            ("non-executable", 0o644, b"100644"),
            ("executable", 0o755, b"100755"),
        ):
            with self.subTest(name=name):
                with GitBlobWorkflowHarness(
                    "regular", incident_content=content, incident_mode=mode
                ) as harness:
                    record = harness.tree_record()
                    self.assertTrue(record.startswith(git_mode + b" blob "), record)
                    self.assertTrue(
                        record.endswith(
                            b"\t" + HELPER_REPOSITORY_PATH.encode("ascii") + b"\0"
                        ),
                        record,
                    )
                    self.assertEqual(content, harness.raw_blob())

                    result = harness.run_pre_secret_flow()

                    expected_hash = hashlib.sha256(content).hexdigest().encode("ascii")
                    self.assertEqual(0, result.process.returncode, result.process)
                    self.assertEqual(b"", result.process.stdout)
                    self.assertEqual(b"", result.process.stderr)
                    self.assertEqual(b"sha256=" + expected_hash + b"\n", result.output)
                    self.assertEqual(1, result.secret_consuming_steps)
                    self.assertEqual(1, result.runner_invocations)
                    self.assertEqual(
                        [], list(harness.runner_temp.glob("incident-helper-tree.*"))
                    )

    def test_incident_blob_hash_is_independent_of_implementation_checkout(self) -> None:
        incident_content = b"#!/usr/bin/env bash\necho exact-incident-blob\n"
        implementation_content = b"#!/usr/bin/env bash\necho current-main-helper\n"
        with GitBlobWorkflowHarness(
            "regular",
            incident_content=incident_content,
            implementation_content=implementation_content,
        ) as harness:
            self.assertNotEqual(incident_content, implementation_content)
            self.assertEqual(incident_content, harness.raw_blob())
            self.assertEqual(
                implementation_content,
                (harness.implementation / HELPER_REPOSITORY_PATH).read_bytes(),
            )
            harness.filesystem_helper.write_bytes(implementation_content)
            self.assertEqual(implementation_content, harness.filesystem_helper.read_bytes())
            self.assertEqual(incident_content, harness.raw_blob())

            result = harness.run_pre_secret_flow()

            self.assertEqual(0, result.process.returncode, result.process.stderr)
            incident_hash = hashlib.sha256(incident_content).hexdigest()
            implementation_hash = hashlib.sha256(implementation_content).hexdigest()
            derived_hash = result.output.decode("ascii").removeprefix("sha256=").strip()
            self.assertEqual(incident_hash, derived_hash)
            self.assertNotEqual(implementation_hash, derived_hash)

    def test_ancestor_symlink_bypasses_fail_before_secret_or_runner(self) -> None:
        for entry_kind in (
            "root-ancestor-symlink",
            "nested-ancestor-symlink",
        ):
            with self.subTest(entry_kind=entry_kind):
                with GitBlobWorkflowHarness(entry_kind) as harness:
                    self.assertTrue(harness.filesystem_helper.is_file())
                    self.assertFalse(harness.filesystem_helper.is_symlink())
                    self.assertTrue(
                        stat.S_ISREG(harness.filesystem_helper.stat().st_mode)
                    )
                    self.assertEqual(
                        harness.implementation_content,
                        harness.filesystem_helper.read_bytes(),
                    )
                    self.assertEqual(b"", harness.tree_record())

                    result = harness.run_pre_secret_flow()

                    self.assert_git_blob_flow_rejected(
                        result,
                        "read-only status channel incident helper tree entry missing",
                        harness.runner_temp,
                    )

    def test_non_regular_or_missing_incident_entries_fail_closed(self) -> None:
        for entry_kind, expected_prefix, diagnostic in (
            (
                "leaf-symlink",
                b"120000 blob ",
                "read-only status channel incident helper tree entry is not a regular blob",
            ),
            (
                "missing",
                b"",
                "read-only status channel incident helper tree entry missing",
            ),
            (
                "tree",
                b"040000 tree ",
                "read-only status channel incident helper tree entry is not a regular blob",
            ),
            (
                "gitlink",
                b"160000 commit ",
                "read-only status channel incident helper tree entry is not a regular blob",
            ),
        ):
            with self.subTest(entry_kind=entry_kind):
                with GitBlobWorkflowHarness(entry_kind) as harness:
                    self.assertTrue(
                        harness.tree_record().startswith(expected_prefix),
                        harness.tree_record(),
                    )

                    result = harness.run_pre_secret_flow()

                    self.assert_git_blob_flow_rejected(
                        result, diagnostic, harness.runner_temp
                    )

    def test_empty_or_oversized_incident_blobs_fail_before_secret_or_runner(self) -> None:
        for name, content in (
            ("empty", b""),
            ("oversized", b"x" * 262145),
        ):
            with self.subTest(name=name):
                with GitBlobWorkflowHarness(
                    "regular", incident_content=content
                ) as harness:
                    self.assertEqual(content, harness.raw_blob())

                    result = harness.run_pre_secret_flow()

                    self.assert_git_blob_flow_rejected(
                        result,
                        "read-only status channel incident helper Git blob identity invalid",
                        harness.runner_temp,
                    )

    def test_malformed_duplicate_or_wrong_ls_tree_records_fail_closed(self) -> None:
        with GitBlobWorkflowHarness("regular") as harness:
            canonical_record = harness.tree_record()
            wrong_path_record = canonical_record.replace(
                HELPER_REPOSITORY_PATH.encode("ascii"),
                b"scripts/deploy/not-the-required-helper.sh",
            )
            abbreviated_oid_record = canonical_record.replace(
                harness.blob_oid().encode("ascii"), b"deadbeef"
            )
            for name, records in (
                ("malformed", b"malformed record without a nul terminator"),
                ("duplicate", canonical_record + canonical_record),
                ("wrong-path", wrong_path_record),
                ("abbreviated-object-id", abbreviated_oid_record),
            ):
                with self.subTest(name=name):
                    result = harness.run_pre_secret_flow(ls_tree_override=records)

                    self.assert_git_blob_flow_rejected(
                        result,
                        "read-only status channel incident helper Git blob identity invalid",
                        harness.runner_temp,
                    )

    def make_fixture(
        self,
        workflow_text: str | None = None,
        runner_text: str | None = None,
        selfcheck_text: str | None = None,
    ) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory(prefix="release-status-workflow-fixture-")
        root = Path(temporary.name)
        for relative in (
            ".github/workflows/release-status.yml",
            "scripts/deploy/read-only-release-status.sh",
            "scripts/tests/test_read_only_release_status.py",
            "scripts/selfcheck-quality-gates.sh",
        ):
            source = REPOSITORY_ROOT / relative
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        if workflow_text is not None:
            (root / ".github/workflows/release-status.yml").write_text(workflow_text, encoding="utf-8")
        if runner_text is not None:
            (root / "scripts/deploy/read-only-release-status.sh").write_text(runner_text, encoding="utf-8")
        if selfcheck_text is not None:
            (root / "scripts/selfcheck-quality-gates.sh").write_text(
                selfcheck_text, encoding="utf-8"
            )
        return temporary, root

    def validate(self, root: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(["bash", str(VALIDATOR), str(root), "--status-channel-only"], cwd=REPOSITORY_ROOT, text=True, capture_output=True, timeout=10, check=False)

    def assert_workflow_rejected(self, workflow_text: str, diagnostic: str) -> None:
        canonical_hash = hashlib.sha256(WORKFLOW.read_bytes()).hexdigest()
        temporary, root = self.make_fixture(workflow_text=workflow_text)
        try:
            result = self.validate(root)
            self.assertNotEqual(0, result.returncode, result.stdout)
            diagnostics = [line for line in result.stderr.splitlines() if line]
            self.assertGreaterEqual(len(diagnostics), 1, result)
            self.assertEqual(f"read-only-status-contract: {diagnostic}", diagnostics[0])
            self.assertEqual(
                canonical_hash, hashlib.sha256(WORKFLOW.read_bytes()).hexdigest()
            )
        finally:
            temporary.cleanup()
        self.assertFalse(root.exists())

    def assert_runner_rejected(self, runner_text: str, diagnostic: str) -> None:
        temporary, root = self.make_fixture(runner_text=runner_text)
        try:
            result = self.validate(root)
            self.assertNotEqual(0, result.returncode, result.stdout)
            diagnostics = [line for line in result.stderr.splitlines() if line]
            self.assertGreaterEqual(len(diagnostics), 1, result)
            self.assertEqual(f"read-only-status-contract: {diagnostic}", diagnostics[0])
        finally:
            temporary.cleanup()
        self.assertFalse(root.exists())

    def assert_selfcheck_rejected(self, selfcheck_text: str, diagnostic: str) -> None:
        canonical_hash = hashlib.sha256(
            (REPOSITORY_ROOT / "scripts/selfcheck-quality-gates.sh").read_bytes()
        ).hexdigest()
        temporary, root = self.make_fixture(selfcheck_text=selfcheck_text)
        try:
            result = self.validate(root)
            self.assertNotEqual(0, result.returncode, result.stdout)
            diagnostics = [line for line in result.stderr.splitlines() if line]
            self.assertGreaterEqual(len(diagnostics), 1, result)
            self.assertEqual(f"read-only-status-contract: {diagnostic}", diagnostics[0])
            self.assertEqual(
                canonical_hash,
                hashlib.sha256(
                    (
                        REPOSITORY_ROOT
                        / "scripts/selfcheck-quality-gates.sh"
                    ).read_bytes()
                ).hexdigest(),
            )
        finally:
            temporary.cleanup()
        self.assertFalse(root.exists())

    def test_contract_validator_accepts_canonical_channel(self) -> None:
        result = self.validate(REPOSITORY_ROOT)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("read-only-status-contract: OK", result.stdout)

    def test_strict_selfcheck_invocation_and_counts_are_pinned(self) -> None:
        canonical = (
            REPOSITORY_ROOT / "scripts/selfcheck-quality-gates.sh"
        ).read_text(encoding="utf-8")
        variants = (
            (
                canonical.replace(
                    'test_read_only_release_status.py" --strict \\\n',
                    'test_read_only_release_status.py" \\\n',
                    1,
                ),
                "selfcheck must invoke the status suite in strict mode exactly once",
            ),
            (
                canonical.replace(
                    "release_status_expected_methods=80",
                    "release_status_expected_methods=999",
                    1,
                ),
                "selfcheck strict method count changed",
            ),
            (
                canonical.replace(
                    "release_status_expected_subtests=322",
                    "release_status_expected_subtests=999",
                    1,
                ),
                "selfcheck strict subtest count changed",
            ),
        )
        for mutated, diagnostic in variants:
            with self.subTest(diagnostic=diagnostic):
                self.assertNotEqual(canonical, mutated)
                self.assert_selfcheck_rejected(mutated, diagnostic)

    def test_main_guard_removal_and_late_guard_are_rejected_specifically(self) -> None:
        canonical = WORKFLOW.read_text(encoding="utf-8")
        removed = canonical.replace('          test "$GITHUB_REF" = "refs/heads/main"\n', "", 1)
        self.assert_workflow_rejected(removed, "main/ref/default-branch guard changed")
        late = canonical.replace("    steps:\n      - name: Require main dispatch\n", "    steps:\n      - name: Premature secret setup\n        uses: webfactory/ssh-agent@dc588b651fe13675774614f8e6a936a468676387\n        with:\n          ssh-private-key: ${{ secrets.SSH_PRIVATE_KEY }}\n      - name: Require main dispatch\n", 1)
        self.assert_workflow_rejected(late, "main dispatch guard must be the first executable step")

    def test_job_privilege_boundary_regressions_are_rejected(self) -> None:
        canonical = WORKFLOW.read_text(encoding="utf-8")
        variants = (
            (canonical.replace("    name: validate-status-request\n", "    name: validate-status-request\n    environment: stage\n", 1), "validate job must not use a protected environment"),
            (canonical.replace("    needs: validate\n", "", 1), "status job must require validate"),
            (canonical.replace("    environment: ${{ needs.validate.outputs.environment }}\n", "    environment: stage\n", 1), "status environment must use the validated environment"),
        )
        for mutated, diagnostic in variants:
            with self.subTest(diagnostic=diagnostic):
                self.assert_workflow_rejected(mutated, diagnostic)

    def test_checkout_and_revision_binding_regressions_are_rejected(self) -> None:
        canonical = WORKFLOW.read_text(encoding="utf-8")
        variants = (
            (canonical.replace("      - name: Checkout incident tag\n        uses: actions/checkout@692973e3d937129bcbf40652eb9f2f61becf3332 # v4.1.7\n", "      - name: Checkout incident tag\n        shell: bash\n        run: true\n", 1), "incident checkout must use only the validated incident tag"),
            (canonical.replace("          ref: refs/heads/main\n", "          ref: ${{ github.ref }}\n", 1), "implementation checkout must be pinned, main, isolated, and credential-free"),
            (canonical.replace("          ref: refs/tags/${{ needs.validate.outputs.incident_tag }}\n", "          ref: refs/tags/${{ inputs.incident_tag }}\n", 1), "incident checkout must use only the validated incident tag"),
            (canonical.replace('          test "$incident_head" = "$EXPECTED_REVISION"\n', "          true\n", 1), "incident revision equality check changed"),
        )
        for mutated, diagnostic in variants:
            with self.subTest(diagnostic=diagnostic):
                self.assert_workflow_rejected(mutated, diagnostic)

    def test_helper_hash_binding_regressions_are_rejected(self) -> None:
        canonical = WORKFLOW.read_text(encoding="utf-8")

        def replace_once(old: str, new: str) -> str:
            self.assertEqual(1, canonical.count(old), old)
            return canonical.replace(old, new, 1)

        tree_lookup = (
            '            if git -C incident ls-tree --full-tree -z "$EXPECTED_REVISION" '
            '-- "$helper_repository_path" |\n'
        )
        raw_blob_hash = (
            '          if ! helper_sha256_line="$(git -C incident cat-file blob '
            '"$helper_blob_oid" | sha256sum)"; then\n'
        )
        type_probe = (
            '          if ! helper_blob_type="$(git -C incident cat-file -t '
            '"$helper_blob_oid")"; then\n'
            '            reject_helper "incident helper Git blob identity invalid"\n'
            "          fi\n"
        )
        size_probe = (
            '          if ! helper_blob_size="$(git -C incident cat-file -s '
            '"$helper_blob_oid")"; then\n'
            '            reject_helper "incident helper Git blob identity invalid"\n'
            "          fi\n"
        )
        helper_marker = "      - name: Derive retained helper SHA-256\n"
        agent_marker = "      - name: Setup deployment SSH principal\n"
        runner_marker = "      - name: Read exact retained release status once\n"
        helper_start = canonical.index(helper_marker)
        agent_start = canonical.index(agent_marker)
        runner_start = canonical.index(runner_marker)
        helper_after_secret = (
            canonical[:helper_start]
            + canonical[agent_start:runner_start]
            + canonical[helper_start:agent_start]
            + canonical[runner_start:]
        )

        variants = (
            (
                "implementation-object-database",
                replace_once("git -C incident ls-tree", "git -C implementation ls-tree"),
                "incident helper object database must be incident",
            ),
            (
                "incident-filesystem-sha",
                replace_once(
                    raw_blob_hash,
                    '          if ! helper_sha256_line="$(sha256sum -- '
                    'incident/scripts/deploy/remote-compose-release.sh)"; then\n',
                ),
                "incident helper filesystem-path authority is forbidden",
            ),
            (
                "implementation-filesystem-sha",
                replace_once(
                    raw_blob_hash,
                    '          if ! helper_sha256_line="$(sha256sum -- '
                    'implementation/scripts/deploy/remote-compose-release.sh)"; then\n',
                ),
                "incident helper filesystem-path authority is forbidden",
            ),
            (
                "incident-filesystem-cat",
                replace_once(
                    raw_blob_hash,
                    '          if ! helper_sha256_line="$(cat '
                    "incident/scripts/deploy/remote-compose-release.sh | "
                    'sha256sum)"; then\n',
                ),
                "incident helper filesystem-path authority is forbidden",
            ),
            (
                "follow-symlinks",
                replace_once(
                    tree_lookup,
                    tree_lookup.replace("ls-tree ", "ls-tree --follow-symlinks "),
                ),
                "incident helper exact Git tree lookup changed",
            ),
            (
                "cat-file-filters",
                replace_once(
                    raw_blob_hash,
                    raw_blob_hash.replace(
                        'cat-file blob "$helper_blob_oid"',
                        'cat-file --filters "$helper_repository_path"',
                    ),
                ),
                "incident helper SHA must be derived from the exact raw Git blob",
            ),
            (
                "cat-file-textconv",
                replace_once(
                    raw_blob_hash,
                    raw_blob_hash.replace(
                        'cat-file blob "$helper_blob_oid"',
                        'cat-file --textconv "$EXPECTED_REVISION:$helper_repository_path"',
                    ),
                ),
                "incident helper SHA must be derived from the exact raw Git blob",
            ),
            (
                "duplicate-cardinality-check",
                replace_once(
                    "            if IFS= read -r -d '' -n 1 trailing_tree_data; then\n",
                    "            if false; then\n",
                ),
                "incident helper Git blob identity invalid",
            ),
            (
                "returned-path-check",
                replace_once(
                    '            if [[ "$tree_path" != "$helper_repository_path" ]] ||\n',
                    "            if false ||\n",
                ),
                "incident helper Git blob identity invalid",
            ),
            (
                "tree-entry-type-check",
                replace_once(
                    '            if [[ "$tree_type" != "blob" ]] ||\n',
                    "            if false ||\n",
                ),
                "incident helper tree entry is not a regular blob",
            ),
            (
                "tree-entry-mode-check",
                replace_once(
                    '               [[ "$tree_mode" != "100644" && "$tree_mode" != "100755" ]]; then\n',
                    "               [[ false ]]; then\n",
                ),
                "incident helper tree entry is not a regular blob",
            ),
            (
                "full-object-id-check",
                replace_once(
                    '          if [[ ! "$helper_blob_oid" =~ ^[0-9a-f]+$ ]] ||\n'
                    '             (( ${#helper_blob_oid} != expected_oid_length )); then\n'
                    '            reject_helper "incident helper Git blob identity invalid"\n'
                    "          fi\n",
                    "          if false; then\n"
                    '            reject_helper "incident helper Git blob identity invalid"\n'
                    "          fi\n",
                ),
                "incident helper Git blob identity invalid",
            ),
            (
                "cat-file-type-probe",
                replace_once(type_probe, '          helper_blob_type="blob"\n'),
                "incident helper Git blob identity invalid",
            ),
            (
                "cat-file-size-probe",
                replace_once(size_probe, '          helper_blob_size="1"\n'),
                "incident helper Git blob identity invalid",
            ),
            (
                "bounded-nonempty-size-check",
                replace_once(
                    '          if [[ ! "$helper_blob_size" =~ ^[1-9][0-9]{0,6}$ ]] ||\n',
                    "          if false ||\n",
                ),
                "incident helper Git blob identity invalid",
            ),
            (
                "git-show-instead-of-raw-blob",
                replace_once(
                    raw_blob_hash,
                    '          if ! helper_sha256_line="$(git -C incident show '
                    '"$EXPECTED_REVISION:$helper_repository_path" | sha256sum)"; then\n',
                ),
                "incident helper SHA must be derived from the exact raw Git blob",
            ),
            (
                "missing-no-replace-objects",
                replace_once("          export GIT_NO_REPLACE_OBJECTS=1\n", ""),
                "incident helper exact Git tree lookup changed",
            ),
            (
                "missing-optional-locks",
                replace_once("          export GIT_OPTIONAL_LOCKS=0\n", ""),
                "incident helper exact Git tree lookup changed",
            ),
            (
                "missing-literal-pathspecs",
                replace_once("          export GIT_LITERAL_PATHSPECS=1\n", ""),
                "incident helper exact Git tree lookup changed",
            ),
            (
                "unbounded-tree-record",
                replace_once(
                    "          readonly max_helper_tree_record_bytes=512\n",
                    "          readonly max_helper_tree_record_bytes=4096\n",
                ),
                "incident helper exact Git tree lookup changed",
            ),
            (
                "tree-lookup-not-exact-revision",
                replace_once(
                    tree_lookup,
                    tree_lookup.replace('"$EXPECTED_REVISION"', "HEAD"),
                ),
                "incident helper exact Git tree lookup changed",
            ),
            (
                "execute-incident-blob",
                replace_once(
                    raw_blob_hash,
                    '          git -C incident cat-file blob "$helper_blob_oid" | bash\n'
                    + raw_blob_hash,
                ),
                "incident checkout code execution is forbidden",
            ),
            (
                "hardcoded-helper-hash",
                replace_once(
                    '          helper_sha256="${helper_sha256_line%% *}"\n',
                    '          helper_sha256="' + "0" * 64 + '"\n',
                ),
                "constant helper SHA is forbidden",
            ),
            (
                "object-validation-after-secret-setup",
                helper_after_secret,
                "status step inventory or order changed",
            ),
        )
        for name, mutated, diagnostic in variants:
            with self.subTest(name=name):
                self.assertNotEqual(canonical, mutated)
                self.assert_workflow_rejected(mutated, diagnostic)

    def test_exact_workflow_hash_supplements_semantic_validation(self) -> None:
        canonical = WORKFLOW.read_text(encoding="utf-8")
        commented_validation = canonical.replace(
            '          [[ "$INPUT_RELEASE_OWNER" =~ ^[0-9]+-[0-9]+$ ]] || reject release_owner\n',
            '          # [[ "$INPUT_RELEASE_OWNER" =~ ^[0-9]+-[0-9]+$ ]] || reject release_owner\n',
            1,
        )
        self.assert_workflow_rejected(
            commented_validation,
            "workflow content SHA-256 changed outside the approved contract",
        )

    def test_concurrency_permission_and_timeout_regressions_are_rejected(self) -> None:
        canonical = WORKFLOW.read_text(encoding="utf-8")
        variants = (
            (canonical.replace("  group: payments-schema-${{ inputs.environment }}\n", "  group: release-status-${{ inputs.environment }}\n", 1), "status must share the non-cancelling environment release lock"),
            (canonical.replace("  cancel-in-progress: false\n", "  cancel-in-progress: true\n", 1), "status must share the non-cancelling environment release lock"),
            (canonical.replace("    permissions:\n      contents: read\n", "", 1), "every job must explicitly use contents: read"),
            (canonical.replace("    permissions:\n      contents: read\n", "    permissions:\n      contents: write\n", 1), "every job must explicitly use contents: read"),
            (canonical.replace("    timeout-minutes: 10\n", "    timeout-minutes: 11\n", 1), "status timeout must be at most 10 minutes"),
            (canonical.replace("    timeout-minutes: 5\n", "", 1), "validate timeout must be at most 5 minutes"),
        )
        for mutated, diagnostic in variants:
            with self.subTest(diagnostic=diagnostic):
                self.assert_workflow_rejected(mutated, diagnostic)

    def test_pinned_known_hosts_and_secret_allowlist_regressions_are_rejected(self) -> None:
        canonical = WORKFLOW.read_text(encoding="utf-8")
        self.assert_workflow_rejected(canonical.replace("          SSH_KNOWN_HOSTS: ${{ secrets.SSH_KNOWN_HOSTS }}\n", "", 1), "single retained status invocation changed")
        self.assert_workflow_rejected(canonical.replace("        run: implementation/scripts/deploy/read-only-release-status.sh\n", "        run: ssh-keyscan stage.invalid\n", 1), "live ssh-keyscan is forbidden")

    def test_runner_temp_root_and_ssh_port_fallback_regressions_are_rejected_exactly(self) -> None:
        canonical = WORKFLOW.read_text(encoding="utf-8")
        tmpdir_line = "          TMPDIR: ${{ runner.temp }}\n"
        runner_temp_line = "          RUNNER_TEMP: ${{ runner.temp }}\n"
        for name, mutated, diagnostic in (
            (
                "missing-tmpdir",
                canonical.replace(tmpdir_line, "", 1),
                "status runner TMPDIR must be exactly runner.temp",
            ),
            (
                "raw-tmp",
                canonical.replace(tmpdir_line, "          TMPDIR: /tmp\n", 1),
                "status runner TMPDIR must be exactly runner.temp",
            ),
            (
                "input-tmpdir",
                canonical.replace(
                    tmpdir_line,
                    "          TMPDIR: ${{ inputs.incident_tag }}\n",
                    1,
                ),
                "status runner TMPDIR must be exactly runner.temp",
            ),
            (
                "incident-checkout-tmpdir",
                canonical.replace(
                    tmpdir_line,
                    "          TMPDIR: ${{ github.workspace }}/incident\n",
                    1,
                ),
                "status runner TMPDIR must be exactly runner.temp",
            ),
            (
                "secret-tmpdir",
                canonical.replace(
                    tmpdir_line,
                    "          TMPDIR: ${{ secrets.COMPOSE_PATH }}\n",
                    1,
                ),
                "status runner TMPDIR must be exactly runner.temp",
            ),
            (
                "conditional-tmpdir",
                canonical.replace(
                    tmpdir_line,
                    "          TMPDIR: ${{ success() && runner.temp }}\n",
                    1,
                ),
                "status runner TMPDIR must be exactly runner.temp",
            ),
            (
                "missing-runner-temp",
                canonical.replace(runner_temp_line, "", 1),
                "status runner RUNNER_TEMP must be exactly runner.temp",
            ),
            (
                "missing-port-fallback",
                canonical.replace(
                    "          SSH_PORT: ${{ secrets.SSH_PORT || '22' }}\n",
                    "          SSH_PORT: ${{ secrets.SSH_PORT }}\n",
                    1,
                ),
                "status SSH_PORT must default empty secret to literal 22",
            ),
            (
                "wrong-port-fallback",
                canonical.replace(
                    "          SSH_PORT: ${{ secrets.SSH_PORT || '22' }}\n",
                    "          SSH_PORT: ${{ secrets.SSH_PORT || '2222' }}\n",
                    1,
                ),
                "status SSH_PORT must default empty secret to literal 22",
            ),
        ):
            with self.subTest(name=name):
                self.assert_workflow_rejected(mutated, diagnostic)

        late_tmpdir = canonical.replace(tmpdir_line, "", 1).replace(
            "        run: implementation/scripts/deploy/read-only-release-status.sh\n",
            "        run: implementation/scripts/deploy/read-only-release-status.sh\n"
            "      - name: Late temporary root\n"
            "        shell: bash\n"
            "        env:\n"
            "          TMPDIR: ${{ runner.temp }}\n"
            "        run: true\n",
            1,
        )
        self.assert_workflow_rejected(
            late_tmpdir, "status runner TMPDIR must be exactly runner.temp"
        )

    def test_mutation_and_artifact_paths_are_rejected_specifically(self) -> None:
        canonical = WORKFLOW.read_text(encoding="utf-8")
        replacements = (
            ("implementation/scripts/deploy/read-only-release-status.sh", "scripts/deploy/quiesced-release.sh", "deploy runner invocation is forbidden"),
            ("implementation/scripts/deploy/read-only-release-status.sh", "docker login ghcr.io", "registry login is forbidden"),
            ("implementation/scripts/deploy/read-only-release-status.sh", "docker pull example.invalid/image", "image pull is forbidden"),
            ("implementation/scripts/deploy/read-only-release-status.sh", "actions/upload-artifact@v4", "raw artifact publication is forbidden"),
        )
        for old, new, diagnostic in replacements:
            with self.subTest(new=new):
                self.assert_workflow_rejected(canonical.replace(old, new, 1), diagnostic)

    def test_scp_upload_fixture_fails_first_for_the_exact_upload_diagnostic(self) -> None:
        canonical = WORKFLOW.read_text(encoding="utf-8")
        canonical_hash = hashlib.sha256(WORKFLOW.read_bytes()).hexdigest()
        mutated = canonical.replace(
            "implementation/scripts/deploy/read-only-release-status.sh",
            "scp file host:/tmp/clubs-bot-release-helper.sh",
            1,
        )
        temporary, root = self.make_fixture(workflow_text=mutated)
        try:
            result = self.validate(root)
            self.assertNotEqual(0, result.returncode, result.stdout)
            diagnostics = [line for line in result.stderr.splitlines() if line]
            self.assertGreaterEqual(len(diagnostics), 1, result)
            self.assertEqual(
                "read-only-status-contract: read-only status channel must not upload or replace retained helper",
                diagnostics[0],
            )
            self.assertEqual(canonical_hash, hashlib.sha256(WORKFLOW.read_bytes()).hexdigest())
        finally:
            temporary.cleanup()
        self.assertFalse(root.exists())

    def test_second_or_missing_runner_invocation_is_rejected(self) -> None:
        canonical = WORKFLOW.read_text(encoding="utf-8")
        self.assert_workflow_rejected(canonical.replace("implementation/scripts/deploy/read-only-release-status.sh", "true", 1), "single retained status invocation changed")
        second = canonical.replace("        run: implementation/scripts/deploy/read-only-release-status.sh\n", "        run: |\n          implementation/scripts/deploy/read-only-release-status.sh\n          implementation/scripts/deploy/read-only-release-status.sh\n", 1)
        self.assert_workflow_rejected(second, "single retained status invocation changed")

    def test_conditional_continue_and_fail_open_shell_are_rejected(self) -> None:
        canonical = WORKFLOW.read_text(encoding="utf-8")
        conditional = canonical.replace("      - name: Verify incident revision\n", "      - name: Verify incident revision\n        if: success()\n", 1)
        self.assert_workflow_rejected(conditional, "status steps must remain unconditional and fail closed")
        continued = canonical.replace("      - name: Verify incident revision\n", "      - name: Verify incident revision\n        continue-on-error: true\n", 1)
        self.assert_workflow_rejected(continued, "status steps must remain unconditional and fail closed")
        fail_open = canonical.replace('          test "$incident_head" = "$EXPECTED_REVISION"\n', '          test "$incident_head" = "$EXPECTED_REVISION" || true\n', 1)
        self.assert_workflow_rejected(fail_open, "fail-open custom shell is forbidden")
        forced = canonical.replace('          test "$incident_head" = "$EXPECTED_REVISION"\n', "          ssh_exit=0\n", 1)
        self.assert_workflow_rejected(forced, "fail-open custom shell is forbidden")

    def test_incident_code_execution_and_hidden_ssh_are_rejected(self) -> None:
        canonical = WORKFLOW.read_text(encoding="utf-8")
        incident_exec = canonical.replace('          test "$incident_head" = "$EXPECTED_REVISION"\n', '          test "$incident_head" = "$EXPECTED_REVISION"\n          bash incident/scripts/deploy/remote-compose-release.sh status\n', 1)
        self.assert_workflow_rejected(incident_exec, "incident checkout code execution is forbidden")
        hidden_ssh = canonical.replace('          test "$incident_head" = "$EXPECTED_REVISION"\n', '          test "$incident_head" = "$EXPECTED_REVISION"\n          command ssh hidden.invalid true\n', 1)
        self.assert_workflow_rejected(hidden_ssh, "hidden SSH invocation is forbidden")

    def test_runner_semantic_mutations_are_rejected_with_specific_reasons(self) -> None:
        canonical = RUNNER.read_text(encoding="utf-8")
        mutations = (
            (canonical.replace("umask 077\n", "umask 077\nssh hidden.invalid true\n", 1), "runner must perform exactly one SSH operation"),
            (canonical.replace('bash -s -- status "$release_owner"', 'bash -s -- start "$release_owner"', 1), "runner must execute exactly one immutable helper snapshot in literal status mode"),
            (canonical.replace("umask 077\n", "umask 077\ndocker login ghcr.io\n", 1), "runner contains forbidden deployment or database command: docker"),
            (canonical.replace("umask 077\n", "umask 077\ndocker pull image\n", 1), "runner contains forbidden deployment or database command: docker"),
            (canonical.replace("umask 077\n", "umask 077\nscp file host:/tmp/file\n", 1), "runner contains forbidden secondary transport: scp"),
            (canonical.replace("ssh_exit=$?\n", "ssh_exit=0\n", 1), "transport nonzero path can expose or trust official stdout"),
            (
                canonical.replace(
                    '[[ ! "$SSH_USER" =~ ^[a-zA-Z0-9_][a-zA-Z0-9._-]*$ ]]',
                    '[[ ! "$SSH_USER" =~ ^[a-zA-Z0-9._-]+$ ]]',
                    1,
                ),
                'runner contract lacks: [[ ! "$SSH_USER" =~ ^[a-zA-Z0-9_][a-zA-Z0-9._-]*$ ]]',
            ),
            (
                canonical.replace(
                    "trap 'record_initialization_signal 143' TERM\n", "", 1
                ),
                "runner contract lacks: trap 'record_initialization_signal 143' TERM",
            ),
            (
                canonical.replace(
                    'readonly helper_snapshot_b64\n', '', 1
                ),
                "runner contract lacks: readonly helper_snapshot_b64",
            ),
            (
                canonical.replace(
                    'if ((helper_mode_value & 07022)); then',
                    'if ((helper_mode_value & 07002)); then',
                    1,
                ),
                "runner contract lacks: if ((helper_mode_value & 07022)); then",
            ),
            (
                canonical.replace(
                    '[ -f "$helper_path" ] && [ ! -L "$helper_path" ] || exit 41\n',
                    '[ -f "$helper_path" ] && [ ! -L "$helper_path" ] || exit 41\n[ -O "$helper_path" ] || exit 41\n',
                    1,
                ),
                "remote wrapper contains a forbidden helper owner gate",
            ),
            (
                canonical.replace(
                    'printf \'%s\' "$helper_snapshot_b64" |\n  base64 -d |\n  bash -s -- status',
                    'bash "$helper_fd_path" status "$release_owner"\nprintf \'%s\' "$helper_snapshot_b64" |\n  base64 -d |\n  bash -s -- status',
                    1,
                ),
                "remote wrapper executes the live helper path or descriptor",
            ),
            (
                canonical.replace(
                    'unexpected_exit() {\n',
                    'rm -rf "$private_root"\nunexpected_exit() {\n',
                    1,
                ),
                "runner contains path-based broad cleanup",
            ),
            (
                canonical.replace(
                    'os.unlink(name, dir_fd=root_fd)', 'os.unlink(name)', 2
                ),
                "runner contract lacks: os.unlink(name, dir_fd=root_fd)",
            ),
            (
                canonical.replace(
                    '        opened.append([descriptor, name, None, True])\n',
                    '',
                    1,
                ),
                "runner contract lacks: opened.append([descriptor, name, None, True])",
            ),
            (
                canonical.replace(
                    '                    original = os.stat(descriptor)\n',
                    '',
                    1,
                ),
                "runner contract lacks: original = os.stat(descriptor)",
            ),
            (
                canonical.replace('        start_new_session=True,\n', '', 1),
                "runner contract lacks: start_new_session=True",
            ),
            (
                canonical.replace(
                    "trap 'trap \"\" HUP INT TERM; handle_signal 143' TERM",
                    "trap 'handle_signal 143' TERM",
                    2,
                ),
                "runner contract lacks: trap 'trap \"\" HUP INT TERM; handle_signal 143' TERM",
            ),
            (
                canonical.replace(
                    '    signal.pthread_sigmask(signal.SIG_BLOCK, watched_signals)\n',
                    '',
                    1,
                ),
                "runner contract lacks: signal.pthread_sigmask(signal.SIG_BLOCK, watched_signals)",
            ),
            (
                canonical.replace(
                    '    if pending_signal != 0:\n'
                    '        return\n'
                    '    pending_signal = signum\n',
                    '    pending_signal = pending_signal or signum\n',
                    1,
                ),
                "runner contract lacks: if pending_signal != 0:",
            ),
            (
                canonical.replace(
                    'terminate_ssh_bounded', 'terminate_ssh_unbounded'
                ),
                "runner contract lacks: terminate_ssh_bounded() {",
            ),
            (
                canonical.replace(
                    'kill -KILL "$child_pid" 2>/dev/null || true\n', '', 1
                ),
                'runner contract lacks: kill -KILL "$child_pid"',
            ),
        )
        for mutated, diagnostic in mutations:
            with self.subTest(diagnostic=diagnostic):
                self.assert_runner_rejected(mutated, diagnostic)

    def test_exact_runner_hash_rejects_unrecognized_source_drift(self) -> None:
        canonical = RUNNER.read_text(encoding="utf-8")
        self.assert_runner_rejected(
            canonical + "# unauthorized source drift\n",
            "runner content SHA-256 changed outside the approved contract",
        )


class CountingTextTestResult(unittest.TextTestResult):
    def __init__(self, *args: object, **kwargs: object) -> None:
        super().__init__(*args, **kwargs)
        self.subtests_run = 0

    def addSubTest(
        self,
        test: unittest.case.TestCase,
        subtest: unittest.case.TestCase,
        outcome: tuple[type[BaseException], BaseException, object] | None,
    ) -> None:
        self.subtests_run += 1
        super().addSubTest(test, subtest, outcome)


class CountingTextTestRunner(unittest.TextTestRunner):
    resultclass = CountingTextTestResult


def run_strict_suite(
    suite: unittest.suite.TestSuite, expected_methods: int, expected_subtests: int
) -> int:
    runner = CountingTextTestRunner(stream=sys.stderr, verbosity=2)
    result = runner.run(suite)
    counters = {
        "methods": result.testsRun,
        "subtests": result.subtests_run,
        "failures": len(result.failures),
        "errors": len(result.errors),
        "skipped": len(result.skipped),
        "expected_failures": len(result.expectedFailures),
        "unexpected_successes": len(result.unexpectedSuccesses),
    }
    print(strict_summary(**counters), flush=True)
    return 0 if strict_counters_are_accepted(
        counters, expected_methods, expected_subtests
    ) else 1


if __name__ == "__main__":
    if sys.argv[1:] == [STRICT_ARGUMENT]:
        selected_suite = unittest.defaultTestLoader.loadTestsFromModule(
            sys.modules[__name__]
        )
        raise SystemExit(
            run_strict_suite(
                selected_suite, EXPECTED_METHOD_COUNT, EXPECTED_SUBTEST_COUNT
            )
        )
    if sys.argv[1:] == [STRICT_ACCOUNTING_PROBE_ARGUMENT]:
        selected_suite = unittest.defaultTestLoader.loadTestsFromTestCase(
            StrictAccountingProbeTest
        )
        raise SystemExit(
            run_strict_suite(
                selected_suite, PROBE_METHOD_COUNT, PROBE_SUBTEST_COUNT
            )
        )
    unittest.main(verbosity=2, testRunner=CountingTextTestRunner)
