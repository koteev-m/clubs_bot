#!/usr/bin/env python3

from __future__ import annotations

import json
import hashlib
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import time
from typing import Any
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
REMOTE_HELPER = REPOSITORY_ROOT / "scripts" / "deploy" / "remote-compose-release.sh"
RUNNER = REPOSITORY_ROOT / "scripts" / "deploy" / "quiesced-release.sh"

OWNER = "12345-1"
REVISION = "a" * 40
OLD_REVISION = "b" * 40
IMAGE_REPOSITORY = "ghcr.io/example/clubs_bot/app-bot"
DIGEST = f"{IMAGE_REPOSITORY}@sha256:{'c' * 64}"
OLD_DIGEST = f"{IMAGE_REPOSITORY}@sha256:{'d' * 64}"
OLD_CONTAINER_ID = "1" * 64
MIGRATION_CONTAINER_ID = "2" * 64
CANDIDATE_CONTAINER_ID = "3" * 64
REPLACED_CONTAINER_ID = "4" * 64
OLD_IMAGE_ID = f"sha256:{'e' * 64}"
CANDIDATE_IMAGE_ID = f"sha256:{'f' * 64}"
REPLACED_IMAGE_ID = f"sha256:{'9' * 64}"
STARTED_AT = "2026-08-27T00:00:00Z"

SENSITIVE_VALUES = (
    "registry-token-must-not-leak",
    "release-user@stage-host.invalid",
    "/srv/private/clubs-compose",
    "5" * 64,
    "postgres-password-must-not-leak",
    "application-payload-must-not-leak",
    "raw-migration-output-must-not-leak",
    "arbitrary-child-stderr-must-not-leak",
)
SENSITIVE_TEXT = "\n".join(SENSITIVE_VALUES)


FAKE_DOCKER = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys


state_path = Path(os.environ["FAKE_DOCKER_STATE"])
log_path = Path(os.environ["FAKE_DOCKER_LOG"])
state = json.loads(state_path.read_text(encoding="utf-8"))
raw_args = sys.argv[1:]
with log_path.open("a", encoding="utf-8") as log:
    log.write(json.dumps(raw_args, separators=(",", ":")) + "\n")


def save():
    state_path.write_text(
        json.dumps(state, sort_keys=True, separators=(",", ":")),
        encoding="utf-8",
    )


def fail(action):
    code = int(state.get("fail_actions", {}).get(action, 0))
    if code:
        sentinel = state.get("failure_stderr", "")
        if sentinel:
            print(sentinel, file=sys.stderr)
        save()
        raise SystemExit(code)


def format_and_target(arguments):
    output_format = ""
    target = arguments[-1] if arguments else ""
    for index, value in enumerate(arguments):
        if value.startswith("--format="):
            output_format = value.split("=", 1)[1]
        elif value == "--format" and index + 1 < len(arguments):
            output_format = arguments[index + 1]
    return output_format, target


def effective_app_state():
    sequence = state.get("ps_sequence", [])
    if sequence:
        value = sequence.pop(0)
        state["ps_sequence"] = sequence
        save()
        return value
    return state["app_state"]


def container_for(app_state):
    if app_state in {"old_running", "old_stopped"}:
        return state["old_container_id"]
    if app_state == "candidate_running":
        return state["candidate_container_id"]
    if app_state == "replaced":
        return state["replaced_container_id"]
    return ""


def compose_app_container_ids(app_state):
    explicit_ids = state.get("compose_ps_ids")
    if explicit_ids is not None:
        return explicit_ids
    container_ids = []
    if state.get("migration_exists", False):
        container_ids.append(state["migration_container_id"])
    if app_state == "ambiguous":
        container_ids.extend(
            (state["old_container_id"], state["replaced_container_id"])
        )
    else:
        container = container_for(app_state)
        if container:
            container_ids.append(container)
    return container_ids


def inspect_value(output_format, target):
    if target == f"clubs-bot-migrate-{os.environ['FAKE_OWNER']}":
        target = state["migration_container_id"]
    if target == state["old_container_id"]:
        values = {
            "{{.State.Running}}": "false" if state["app_state"] == "old_stopped" else "true",
            "{{.Image}}": state["old_image_id"],
            "{{.Config.Image}}": state["old_digest"],
            '{{ index .Config.Labels "com.docker.compose.project" }}': state["compose_project"],
            '{{ index .Config.Labels "com.docker.compose.service" }}': "app",
            '{{ index .Config.Labels "com.docker.compose.oneoff" }}': state.get(
                "ordinary_oneoff_label", "False"
            ),
            "{{.State.StartedAt}}": state["started_at"],
            "{{.RestartCount}}": str(state["restart_count"]),
        }
        return values.get(output_format)
    if target == state["candidate_container_id"]:
        values = {
            "{{.State.Running}}": "true",
            "{{.Image}}": state["candidate_image_id"],
            "{{.Config.Image}}": state["digest"],
            '{{ index .Config.Labels "com.docker.compose.project" }}': state["compose_project"],
            '{{ index .Config.Labels "com.docker.compose.service" }}': "app",
            '{{ index .Config.Labels "com.docker.compose.oneoff" }}': state.get(
                "ordinary_oneoff_label", "False"
            ),
            "{{.State.StartedAt}}": state["candidate_started_at"],
            "{{.RestartCount}}": "0",
        }
        return values.get(output_format)
    if target == state["replaced_container_id"]:
        values = {
            "{{.State.Running}}": "true",
            "{{.Image}}": state["replaced_image_id"],
            "{{.Config.Image}}": state["replaced_digest"],
            '{{ index .Config.Labels "com.docker.compose.project" }}': state["compose_project"],
            '{{ index .Config.Labels "com.docker.compose.service" }}': "app",
            '{{ index .Config.Labels "com.docker.compose.oneoff" }}': state.get(
                "ordinary_oneoff_label", "False"
            ),
            "{{.State.StartedAt}}": state["candidate_started_at"],
            "{{.RestartCount}}": "0",
        }
        return values.get(output_format)
    if target == state["migration_container_id"]:
        values = {
            "{{.Config.Image}}": state["digest"],
            "{{.Image}}": state["candidate_image_id"],
            "{{.State.Running}}": "true" if state.get("migration_running", False) else "false",
            "{{.State.ExitCode}}": str(state["migration_exit"]),
            '{{ index .Config.Labels "com.docker.compose.project" }}': state["compose_project"],
            '{{ index .Config.Labels "com.docker.compose.service" }}': "app",
            '{{ index .Config.Labels "com.docker.compose.oneoff" }}': state.get(
                "migration_oneoff_label", "True"
            ),
        }
        return values.get(output_format)
    return None


args = list(raw_args)
if args[:1] == ["--config"]:
    if len(args) < 3:
        raise SystemExit(90)
    args = args[2:]

if not args:
    raise SystemExit(91)

command = args.pop(0)
if command == "login":
    fail("login")
    sys.stdin.read()
    raise SystemExit(0)
if command == "pull":
    fail("pull")
    raise SystemExit(0)
if command == "info":
    fail("info")
    raise SystemExit(0)

if command == "image":
    if not args or args.pop(0) != "inspect":
        raise SystemExit(92)
    output_format, target = format_and_target(args)
    fail("image_inspect")
    if "org.opencontainers.image.revision" in output_format:
        if target in {state["old_image_id"], state["old_digest"]}:
            print(state["old_revision"])
        elif target == state["replaced_image_id"]:
            print(state["replaced_revision"])
        else:
            print(state["candidate_revision"])
        raise SystemExit(0)
    if "RepoDigests" in output_format:
        if target in {state["old_image_id"], state["old_digest"]}:
            print(state["old_digest"])
        elif target == state["replaced_image_id"]:
            print(state["replaced_digest"])
        else:
            print(state["digest"])
        raise SystemExit(0)
    if output_format == "{{.Id}}":
        print(state["candidate_image_id"])
        raise SystemExit(0)
    raise SystemExit(93)

if command == "compose":
    while len(args) >= 2 and args[0] == "-f":
        args = args[2:]
    if not args:
        raise SystemExit(94)
    action = args.pop(0)
    if action == "config" and "--services" in args:
        fail("config_services")
        print("app" if state.get("compose_has_app", True) else "other")
        raise SystemExit(0)
    if action == "config" and "--images" in args:
        fail("config_images")
        print(state["digest"] if state.get("configured_digest", True) else state["old_digest"])
        raise SystemExit(0)
    if action == "ps":
        fail("compose_ps")
        app_state = effective_app_state()
        for container_id in compose_app_container_ids(app_state):
            print(container_id)
        raise SystemExit(0)
    if action == "stop":
        fail("compose_stop")
        state["lifecycle_counts"]["stop"] += 1
        state["app_state"] = "old_stopped"
        save()
        raise SystemExit(0)
    if action == "rm":
        fail("compose_rm")
        state["lifecycle_counts"]["rm"] += 1
        state["app_state"] = "absent"
        save()
        raise SystemExit(0)
    if action == "run":
        state["migration_invocations"] += 1
        save()
        fail("compose_run")
        state["migration_exists"] = True
        state["migration_running"] = True
        save()
        print(state["migration_container_id"])
        raise SystemExit(0)
    if action == "up":
        state["start_invocations"] += 1
        save()
        fail("compose_up")
        state["app_state"] = "candidate_running"
        save()
        raise SystemExit(0)
    if action == "exec":
        url = args[-1] if args else ""
        if url.endswith("/ready"):
            fail("ready")
        elif url.endswith("/health"):
            fail("health")
        else:
            raise SystemExit(95)
        raise SystemExit(0)
    raise SystemExit(96)

if command == "inspect":
    output_format, target = format_and_target(args)
    if not output_format:
        if target == f"clubs-bot-migrate-{os.environ['FAKE_OWNER']}":
            raise SystemExit(0 if state.get("migration_exists", False) else 1)
        raise SystemExit(1)
    if "com.docker.compose.oneoff" in output_format:
        fail("inspect_oneoff")
    value = inspect_value(output_format, target)
    if value is None:
        raise SystemExit(1)
    print(value)
    raise SystemExit(0)

if command == "wait":
    fail("wait")
    state["migration_running"] = False
    save()
    print(state["migration_exit"])
    raise SystemExit(0)
if command == "logs":
    fail("logs")
    sys.stdout.write(state["migration_log"])
    raise SystemExit(0)
if command == "rm":
    fail("docker_rm")
    state["migration_exists"] = False
    state["migration_running"] = False
    state["migration_removals"] += 1
    save()
    raise SystemExit(0)

raise SystemExit(97)
'''


FAKE_MV = r'''#!/usr/bin/env python3
import os
from pathlib import Path
import sys


args = [value for value in sys.argv[1:] if value not in {"-f", "--"}]
if len(args) != 2:
    raise SystemExit(90)
source, target = map(Path, args)
log_path = Path(os.environ["FAKE_MV_LOG"])
with log_path.open("a", encoding="utf-8") as log:
    log.write(f"{source}\t{target}\n")

suffix = os.environ.get("FAKE_MV_FAIL_DEST_SUFFIX", "")
if suffix and str(target).endswith(suffix):
    counter_path = Path(os.environ["FAKE_MV_COUNTER"])
    count = int(counter_path.read_text(encoding="ascii")) if counter_path.exists() else 0
    count += 1
    counter_path.write_text(str(count), encoding="ascii")
    if count == int(os.environ.get("FAKE_MV_FAIL_MATCH_AT", "1")):
        sentinel = os.environ.get("FAKE_CHILD_STDERR", "")
        if sentinel:
            print(sentinel, file=sys.stderr)
        raise SystemExit(73)

os.replace(source, target)
'''


FAKE_RM = r'''#!/usr/bin/env python3
import os
from pathlib import Path
import re
import shutil
import sys


raw_args = sys.argv[1:]
recursive = any(value in {"-r", "-R", "-rf", "-fr"} for value in raw_args)
force = any(value in {"-f", "-rf", "-fr"} for value in raw_args)
targets = [Path(value) for value in raw_args if not value.startswith("-")]
with Path(os.environ["FAKE_RM_LOG"]).open("a", encoding="utf-8") as log:
    log.write("\t".join(map(str, targets)) + "\n")

allowed_root = Path(os.environ["FAKE_RM_ALLOWED_ROOT"]).resolve()
for target in targets:
    resolved_parent = target.parent.resolve()
    allowed = resolved_parent == allowed_root or allowed_root in resolved_parent.parents
    allowed = allowed or bool(
        re.fullmatch(
            r"/tmp/(?:clubs-bot-docker-config|\.clubs-release-previous)\.[0-9]+-[0-9]+\.[A-Za-z0-9]+",
            str(target),
        )
    )
    if not allowed:
        raise SystemExit(91)

    suffix = os.environ.get("FAKE_RM_FAIL_DEST_SUFFIX", "")
    if suffix and str(target).endswith(suffix):
        counter_path = Path(os.environ["FAKE_RM_COUNTER"])
        count = int(counter_path.read_text(encoding="ascii")) if counter_path.exists() else 0
        count += 1
        counter_path.write_text(str(count), encoding="ascii")
        if count == int(os.environ.get("FAKE_RM_FAIL_MATCH_AT", "1")):
            sentinel = os.environ.get("FAKE_CHILD_STDERR", "")
            if sentinel:
                print(sentinel, file=sys.stderr)
            raise SystemExit(74)

    if target.is_dir() and not target.is_symlink():
        if not recursive:
            raise SystemExit(1)
        shutil.rmtree(target)
    elif target.exists() or target.is_symlink():
        target.unlink()
    elif not force:
        raise SystemExit(1)
'''


FAKE_FLOCK = r'''#!/usr/bin/env python3
import fcntl
import os
from pathlib import Path
import sys


with Path(os.environ["FAKE_FLOCK_LOG"]).open("a", encoding="utf-8") as log:
    log.write(" ".join(sys.argv[1:]) + "\n")
if os.environ.get("FAKE_FLOCK_REAL") == "yes":
    descriptor = int(sys.argv[-1])
    operation = fcntl.LOCK_SH if "-s" in sys.argv[1:] else fcntl.LOCK_EX
    if "-n" in sys.argv[1:]:
        operation |= fcntl.LOCK_NB
    try:
        fcntl.flock(descriptor, operation)
    except BlockingIOError:
        raise SystemExit(1)
raise SystemExit(1 if os.environ.get("FAKE_FLOCK_FAIL") == "yes" else 0)
'''


FAKE_STAT = r'''#!/usr/bin/env python3
import os
import stat
import sys


arguments = sys.argv[1:]
if len(arguments) == 3 and arguments[0] in {"-c", "-f"}:
    template = arguments[1]
    metadata = os.stat(arguments[2])
    values = {
        "%a": f"{stat.S_IMODE(metadata.st_mode):o}",
        "%Lp": f"{stat.S_IMODE(metadata.st_mode):o}",
        "%u": str(metadata.st_uid),
        "%h": str(metadata.st_nlink),
        "%l": str(metadata.st_nlink),
        "%Y": str(int(metadata.st_mtime)),
        "%m": str(int(metadata.st_mtime)),
    }
    rendered = template
    for marker in sorted(values, key=len, reverse=True):
        rendered = rendered.replace(marker, values[marker])
    if rendered != template:
        print(rendered)
        raise SystemExit(0)
os.execv(os.environ["FAKE_REAL_STAT"], [os.environ["FAKE_REAL_STAT"], *arguments])
'''


FAKE_FINDMNT = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys


arguments = sys.argv[1:]
with Path(os.environ["FAKE_FINDMNT_LOG"]).open("a", encoding="utf-8") as log:
    log.write(json.dumps(arguments, separators=(",", ":")) + "\n")
expected = [
    "--noheadings",
    "--pairs",
    "--output",
    "FSTYPE,SOURCE,FSROOT,TARGET",
    "--target",
]
if len(arguments) != 6 or arguments[:5] != expected:
    raise SystemExit(90)
path = arguments[5]

config = json.loads(Path(os.environ["FAKE_FINDMNT_CONFIG"]).read_text(encoding="utf-8"))
behavior = config.get("behavior", "normal")
if behavior == "failure":
    raise SystemExit(73)

counter_path = Path(os.environ["FAKE_FINDMNT_COUNTER"])
counter = int(counter_path.read_text(encoding="ascii")) if counter_path.exists() else 0
counter += 1
counter_path.write_text(str(counter), encoding="ascii")

identity = config["default"]
for candidate in sorted(config.get("mounts", []), key=lambda item: len(item["prefix"]), reverse=True):
    prefix = candidate["prefix"].rstrip("/")
    if path == prefix or path.startswith(prefix + "/"):
        identity = candidate
        break
if behavior == "mixed-snapshots" and counter % 2 == 0:
    identity = config.get("alternate", identity)


def encode(value):
    encoded = []
    for character in value:
        code = ord(character)
        if character in {'"', '\\'} or character.isspace() or code < 0x20 or code == 0x7f:
            encoded.extend(f"\\x{byte:02x}" for byte in character.encode("utf-8"))
        else:
            encoded.append(character)
    return "".join(encoded)


values = [
    ("FSTYPE", encode(identity["fstype"])),
    ("SOURCE", encode(identity["source"])),
    ("FSROOT", encode(identity["fsroot"])),
    ("TARGET", encode(identity["target"])),
]


def record(fields):
    return " ".join(f'{name}="{value}"' for name, value in fields)


if behavior == "empty":
    raise SystemExit(0)
if behavior == "multiple":
    print(record(values))
    print(record(values))
    raise SystemExit(0)
if behavior == "extra-fields":
    print(record(values) + ' OPTIONS="rw"')
    raise SystemExit(0)
if behavior.startswith("missing-"):
    missing = behavior.removeprefix("missing-").upper()
    print(record([(name, value) for name, value in values if name != missing]))
    raise SystemExit(0)
if behavior == "duplicate-field":
    print(record(values[:2] + [values[1]] + values[2:]))
    raise SystemExit(0)
if behavior == "reordered-fields":
    print(record([values[1], values[0], *values[2:]]))
    raise SystemExit(0)
if behavior == "malformed-escaping":
    print(record([values[0], ("SOURCE", r"bad\qsource"), *values[2:]]))
    raise SystemExit(0)
if behavior == "malformed-pairs":
    print('FSTYPE="ext4" SOURCE="unterminated FSROOT="/" TARGET="/approved"')
    raise SystemExit(0)
print(record(values))
'''


FAKE_STATUS_WRITE_AUDIT = r'''set -T
_clubs_status_write_audit() {
  [ "${FAKE_STATUS_WRITE_AUDIT_ENABLED:-}" = "yes" ] || return 0
  [ "${_CLUBS_STATUS_AUDIT_ACTIVE:-0}" = "0" ] || return 0
  _CLUBS_STATUS_AUDIT_ACTIVE=1
  local label=""
  case "$1" in
    mkdir\ *|*/mkdir\ *) label="mkdir" ;;
    touch\ *|*/touch\ *|mktemp\ *|*/mktemp\ *|cp\ *|*/cp\ *|install\ *|*/install\ *) label="create" ;;
    chmod\ *|*/chmod\ *) label="chmod" ;;
    mv\ *|*/mv\ *) label="rename" ;;
    rm\ *|*/rm\ *) label="unlink" ;;
    rmdir\ *|*/rmdir\ *) label="rmdir" ;;
    sync\ *|*/sync\ *) label="fsync" ;;
    truncate\ *|*/truncate\ *) label="truncate" ;;
    prune_terminal_artifacts*) label="prune" ;;
    :\ \>*|printf\ *\ \>*|exec\ [0-9]*\>\>*) label="open_for_write" ;;
  esac
  if [ -n "$label" ]; then
    builtin printf '%s\n' "$label" >>"$FAKE_STATUS_WRITE_AUDIT_LOG"
  fi
  _CLUBS_STATUS_AUDIT_ACTIVE=0
}
trap '_clubs_status_write_audit "$BASH_COMMAND"' DEBUG
'''


FAKE_SYNC = r'''#!/usr/bin/env bash
set -euo pipefail
[ "$#" = "1" ] || exit 90
target="$1"
kind=file
[ ! -d "$target" ] || kind=directory
printf '%s\t%s\n' "$kind" "$target" >>"$FAKE_SYNC_LOG"
fail_kind="${FAKE_SYNC_FAIL_KIND:-}"
fail_suffix="${FAKE_SYNC_FAIL_SUFFIX:-}"
fail_contains="${FAKE_SYNC_FAIL_CONTAINS:-}"
if { [ -z "$fail_kind" ] || [ "$fail_kind" = "$kind" ]; } &&
  { [ -z "$fail_suffix" ] || [[ "$target" == *"$fail_suffix" ]]; } &&
  { [ -z "$fail_contains" ] || [[ "$target" == *"$fail_contains"* ]]; } &&
  { [ -n "$fail_kind" ] || [ -n "$fail_suffix" ] || [ -n "$fail_contains" ]; }; then
  count=0
  if [ -f "$FAKE_SYNC_COUNTER" ]; then
    count="$(<"$FAKE_SYNC_COUNTER")"
  fi
  count=$((count + 1))
  printf '%s' "$count" >"$FAKE_SYNC_COUNTER"
  if [ "$count" = "${FAKE_SYNC_FAIL_MATCH_AT:-1}" ]; then
    [ -z "${FAKE_CHILD_STDERR:-}" ] || printf '%s\n' "$FAKE_CHILD_STDERR" >&2
    exit 75
  fi
fi
'''


FAKE_SSH = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import shlex
import sys


config_path = Path(os.environ["FAKE_SSH_CONFIG"])
log_path = Path(os.environ["FAKE_SSH_LOG"])
config = json.loads(config_path.read_text(encoding="utf-8"))
command = sys.argv[-1]
parts = shlex.split(command)
mode = parts[2] if len(parts) >= 3 and parts[0] == "bash" else "unknown"
entry = {
    "mode": mode,
    "command": command,
    "registry_token_present": "REGISTRY_READ_TOKEN" in os.environ,
}
with log_path.open("a", encoding="utf-8") as log:
    log.write(json.dumps(entry, separators=(",", ":")) + "\n")

sensitive = config.get("sensitive_stderr", "")
if sensitive:
    print(sensitive, file=sys.stderr)

if mode == "status":
    if config.get("status_stdout"):
        print(config["status_stdout"])
    raise SystemExit(int(config.get("status_exit", 0)))

if mode == config.get("fail_mode"):
    behavior = config.get("failure_behavior")
    if behavior == "exit1":
        raise SystemExit(1)
    if behavior == "exit255":
        raise SystemExit(255)
    if behavior == "malformed_ack":
        print("untrusted malformed acknowledgement")
        raise SystemExit(0)

if mode == "preflight":
    print(f"release-operation:v=1 result=success digest={os.environ['FAKE_DIGEST']}")
elif mode in {"prepare", "publish", "quiesce", "migrate", "start", "cleanup", "helper-cleanup"}:
    print("release-operation:v=1 result=success")
else:
    raise SystemExit(98)
'''


FAKE_SCP = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys


entry = {
    "args": sys.argv[1:],
    "registry_token_present": "REGISTRY_READ_TOKEN" in os.environ,
}
with Path(os.environ["FAKE_SCP_LOG"]).open("a", encoding="utf-8") as log:
    log.write(json.dumps(entry, separators=(",", ":")) + "\n")
if os.environ.get("FAKE_SENSITIVE_STDERR"):
    print(os.environ["FAKE_SENSITIVE_STDERR"], file=sys.stderr)
raise SystemExit(int(os.environ.get("FAKE_SCP_EXIT", "0")))
'''


def write_executable(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")
    path.chmod(0o700)


def canonical_override(digest: str, revision: str) -> bytes:
    return (
        "# clubs-bot-managed-quiesced-release\n"
        f"# revision: {revision}\n"
        "services:\n"
        "  app:\n"
        f"    image: {digest}\n"
    ).encode("utf-8")


def parse_record(path: Path) -> dict[str, str]:
    fields: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        name, separator, value = line.partition("=")
        if not separator or name in fields:
            raise AssertionError(f"malformed test record: {path}")
        fields[name] = value
    return fields


def mode(path: Path) -> int:
    return stat.S_IMODE(path.stat().st_mode)


class RemoteHarness:
    def __init__(
        self,
        *,
        prior_override: bool = True,
        app_env: str = "test",
        filesystem_type: str = "ext4",
        auto_preflight: bool = True,
    ) -> None:
        self.app_env = app_env
        # Production-mode fixtures must not inherit tempfile's /tmp fallback:
        # the real production guard intentionally rejects volatile roots. The
        # checkout is writable in local/CI runs and TemporaryDirectory removes
        # each fixture, including failure paths handled by the context manager.
        temporary_parent = "/tmp" if app_env == "test" else str(REPOSITORY_ROOT)
        self.temporary_directory = tempfile.TemporaryDirectory(
            prefix="clubs-release-state-",
            dir=temporary_parent,
        )
        self.root = Path(self.temporary_directory.name).resolve()
        self.volatile_root = self.root / "volatile"
        self.compose_path = self.root / "compose"
        self.fake_bin = self.root / "bin"
        self.volatile_root.mkdir()
        self.volatile_root.chmod(0o700)
        self.compose_path.mkdir()
        if app_env == "test":
            self.state_parent = self.root / "state"
            self.state_root = self.state_parent
            self.state_root.mkdir()
            self.state_root.chmod(0o700)
        else:
            self.state_parent = self.compose_path / ".clubs-bot-release-state"
            self.state_root = self.state_parent / app_env
        self.fake_bin.mkdir()
        (self.compose_path / "docker-compose.yml").write_text(
            "services:\n  app:\n    image: fixture\n",
            encoding="utf-8",
        )
        self.prior_override_bytes = canonical_override(OLD_DIGEST, OLD_REVISION)
        if prior_override:
            override = self.compose_path / "docker-compose.override.yml"
            override.write_bytes(self.prior_override_bytes)
            override.chmod(0o600)

        self.docker_state_path = self.root / "docker-state.json"
        self.docker_log = self.root / "docker.log"
        self.mv_log = self.root / "mv.log"
        self.mv_counter = self.root / "mv.counter"
        self.rm_log = self.root / "rm.log"
        self.rm_counter = self.root / "rm.counter"
        self.flock_log = self.root / "flock.log"
        self.findmnt_log = self.root / "findmnt.log"
        self.findmnt_counter = self.root / "findmnt.counter"
        self.findmnt_config = self.root / "findmnt.json"
        self.sync_log = self.root / "sync.log"
        self.sync_counter = self.root / "sync.counter"
        self.status_write_audit = self.root / "status-write-audit.bash"
        self.status_write_audit_log = self.root / "status-write-audit.log"
        for path in (
            self.docker_log,
            self.mv_log,
            self.rm_log,
            self.flock_log,
            self.findmnt_log,
            self.sync_log,
            self.status_write_audit_log,
        ):
            path.write_text("", encoding="utf-8")
        self.status_write_audit.write_text(FAKE_STATUS_WRITE_AUDIT, encoding="utf-8")
        self.status_write_audit.chmod(0o600)
        self.findmnt_config.write_text(
            json.dumps(
                {
                    "behavior": "normal",
                    "default": {
                        "prefix": str(self.root),
                        "fstype": filesystem_type,
                        "source": "/dev/mapper/clubs_bot_persistent",
                        "fsroot": "/",
                        "target": str(self.root),
                    },
                    "mounts": [],
                },
                sort_keys=True,
                separators=(",", ":"),
            ),
            encoding="utf-8",
        )
        self._write_docker_state(
            {
                "app_state": "old_running",
                "candidate_container_id": CANDIDATE_CONTAINER_ID,
                "candidate_image_id": CANDIDATE_IMAGE_ID,
                "candidate_revision": REVISION,
                "candidate_started_at": "2026-08-27T00:05:00Z",
                "compose_has_app": True,
                "compose_ps_ids": None,
                "compose_project": "clubs",
                "configured_digest": True,
                "digest": DIGEST,
                "fail_actions": {},
                "failure_stderr": SENSITIVE_TEXT,
                "lifecycle_counts": {"rm": 0, "stop": 0},
                "migration_container_id": MIGRATION_CONTAINER_ID,
                "migration_exists": False,
                "migration_exit": 0,
                "migration_invocations": 0,
                "migration_running": False,
                "migration_log": (
                    "migration-safe:v=1 event=started\n"
                    "migration-safe:v=1 event=completed applied=0\n"
                ),
                "migration_oneoff_label": "True",
                "migration_removals": 0,
                "old_container_id": OLD_CONTAINER_ID,
                "old_digest": OLD_DIGEST,
                "old_image_id": OLD_IMAGE_ID,
                "old_revision": OLD_REVISION,
                "ordinary_oneoff_label": "False",
                "ps_sequence": [],
                "replaced_container_id": REPLACED_CONTAINER_ID,
                "replaced_digest": f"{IMAGE_REPOSITORY}@sha256:{'8' * 64}",
                "replaced_image_id": REPLACED_IMAGE_ID,
                "replaced_revision": "7" * 40,
                "restart_count": 0,
                "start_invocations": 0,
                "started_at": STARTED_AT,
            }
        )
        write_executable(self.fake_bin / "docker", FAKE_DOCKER)
        write_executable(self.fake_bin / "mv", FAKE_MV)
        write_executable(self.fake_bin / "rm", FAKE_RM)
        write_executable(self.fake_bin / "flock", FAKE_FLOCK)
        write_executable(self.fake_bin / "findmnt", FAKE_FINDMNT)
        write_executable(self.fake_bin / "stat", FAKE_STAT)
        write_executable(self.fake_bin / "sync", FAKE_SYNC)
        self.env = os.environ.copy()
        self.env.update(
            {
                "FAKE_CHILD_STDERR": SENSITIVE_TEXT,
                "FAKE_DOCKER_LOG": str(self.docker_log),
                "FAKE_DOCKER_STATE": str(self.docker_state_path),
                "FAKE_FINDMNT_CONFIG": str(self.findmnt_config),
                "FAKE_FINDMNT_COUNTER": str(self.findmnt_counter),
                "FAKE_FINDMNT_LOG": str(self.findmnt_log),
                "FAKE_FLOCK_LOG": str(self.flock_log),
                "FAKE_MV_COUNTER": str(self.mv_counter),
                "FAKE_MV_LOG": str(self.mv_log),
                "FAKE_OWNER": OWNER,
                "FAKE_REAL_STAT": "/usr/bin/stat",
                "FAKE_RM_ALLOWED_ROOT": str(self.root),
                "FAKE_RM_COUNTER": str(self.rm_counter),
                "FAKE_RM_LOG": str(self.rm_log),
                "FAKE_SYNC_COUNTER": str(self.sync_counter),
                "FAKE_SYNC_LOG": str(self.sync_log),
                "FAKE_STATUS_WRITE_AUDIT_LOG": str(self.status_write_audit_log),
                "PATH": f"{self.fake_bin}{os.pathsep}{os.environ['PATH']}",
                "REMOTE_RELEASE_TESTING": "enabled",
                "REMOTE_RELEASE_TEST_ROOT": str(self.state_root),
                "REMOTE_RELEASE_VOLATILE_ROOT": str(self.volatile_root),
            }
        )
        if auto_preflight:
            preflight = self.run(
                "preflight",
                str(self.compose_path),
                IMAGE_REPOSITORY,
                "candidate",
                REVISION,
                "fixture-bot",
                input_text="fixture-registry-token\n",
            )
            if preflight.returncode != 0:
                raise AssertionError(
                    "fixture preflight failed "
                    f"rc={preflight.returncode} stdout={preflight.stdout!r} stderr={preflight.stderr!r}"
                )
        self.clear_command_logs()

    def __enter__(self) -> "RemoteHarness":
        return self

    def __exit__(self, *_: object) -> None:
        self.temporary_directory.cleanup()

    @property
    def lock_dir(self) -> Path:
        return self.state_root / f"clubs-bot-schema-{self.app_env}.lock"

    @property
    def finalizing_dir(self) -> Path:
        return self.state_root / f"clubs-bot-schema-{self.app_env}.finalizing"

    @property
    def result_dir(self) -> Path:
        return self.state_root / f"clubs-bot-schema-{self.app_env}.results"

    @property
    def disposal_dir(self) -> Path:
        return self.state_root / f".clubs-bot-schema-{self.app_env}.disposed.{OWNER}"

    @property
    def ledger_dir(self) -> Path:
        return self.state_root / f"clubs-bot-schema-{self.app_env}.migration-ledgers"

    @property
    def application_binding(self) -> Path:
        return self.state_parent / "application.binding"

    @property
    def active_anchor(self) -> Path:
        return self.state_parent / "active-candidate.anchor"

    @property
    def active_state_dir(self) -> Path | None:
        if self.lock_dir.is_dir():
            return self.lock_dir
        if self.finalizing_dir.is_dir():
            return self.finalizing_dir
        return None

    def close(self) -> None:
        self.temporary_directory.cleanup()

    def _write_docker_state(self, state: dict[str, Any]) -> None:
        self.docker_state_path.write_text(
            json.dumps(state, sort_keys=True, separators=(",", ":")),
            encoding="utf-8",
        )

    def docker_state(self) -> dict[str, Any]:
        return json.loads(self.docker_state_path.read_text(encoding="utf-8"))

    def update_docker_state(self, **updates: Any) -> None:
        state = self.docker_state()
        state.update(updates)
        self._write_docker_state(state)

    def fail_docker(self, action: str, exit_code: int = 41) -> None:
        state = self.docker_state()
        state["fail_actions"][action] = exit_code
        self._write_docker_state(state)

    def clear_docker_failures(self) -> None:
        state = self.docker_state()
        state["fail_actions"] = {}
        self._write_docker_state(state)

    def configure_mv_failure(self, suffix: str, match_at: int = 1) -> None:
        self.env["FAKE_MV_FAIL_DEST_SUFFIX"] = suffix
        self.env["FAKE_MV_FAIL_MATCH_AT"] = str(match_at)
        self.mv_counter.unlink(missing_ok=True)

    def clear_mv_failure(self) -> None:
        self.env.pop("FAKE_MV_FAIL_DEST_SUFFIX", None)
        self.env.pop("FAKE_MV_FAIL_MATCH_AT", None)
        self.mv_counter.unlink(missing_ok=True)

    def configure_rm_failure(self, suffix: str, match_at: int = 1) -> None:
        self.env["FAKE_RM_FAIL_DEST_SUFFIX"] = suffix
        self.env["FAKE_RM_FAIL_MATCH_AT"] = str(match_at)
        self.rm_counter.unlink(missing_ok=True)

    def clear_rm_failure(self) -> None:
        self.env.pop("FAKE_RM_FAIL_DEST_SUFFIX", None)
        self.env.pop("FAKE_RM_FAIL_MATCH_AT", None)
        self.rm_counter.unlink(missing_ok=True)

    def configure_sync_failure(
        self,
        *,
        kind: str = "",
        suffix: str = "",
        contains: str = "",
        match_at: int = 1,
    ) -> None:
        self.env["FAKE_SYNC_FAIL_KIND"] = kind
        self.env["FAKE_SYNC_FAIL_SUFFIX"] = suffix
        self.env["FAKE_SYNC_FAIL_CONTAINS"] = contains
        self.env["FAKE_SYNC_FAIL_MATCH_AT"] = str(match_at)
        self.sync_counter.unlink(missing_ok=True)

    def clear_sync_failure(self) -> None:
        self.env.pop("FAKE_SYNC_FAIL_KIND", None)
        self.env.pop("FAKE_SYNC_FAIL_SUFFIX", None)
        self.env.pop("FAKE_SYNC_FAIL_CONTAINS", None)
        self.env.pop("FAKE_SYNC_FAIL_MATCH_AT", None)
        self.sync_counter.unlink(missing_ok=True)

    def configure_mount_identity(
        self,
        path: Path,
        *,
        filesystem_type: str,
        source: str = "volatile-state",
        filesystem_root: str = "/",
        mount_target: Path | None = None,
    ) -> None:
        config = json.loads(self.findmnt_config.read_text(encoding="utf-8"))
        config["mounts"] = [
            entry for entry in config.get("mounts", []) if entry["prefix"] != str(path)
        ]
        config["mounts"].append(
            {
                "prefix": str(path),
                "fstype": filesystem_type,
                "source": source,
                "fsroot": filesystem_root,
                "target": str(mount_target or path),
            }
        )
        self.findmnt_config.write_text(
            json.dumps(config, sort_keys=True, separators=(",", ":")),
            encoding="utf-8",
        )

    def configure_findmnt_behavior(self, behavior: str) -> None:
        config = json.loads(self.findmnt_config.read_text(encoding="utf-8"))
        config["behavior"] = behavior
        self.findmnt_config.write_text(
            json.dumps(config, sort_keys=True, separators=(",", ":")),
            encoding="utf-8",
        )
        self.findmnt_counter.unlink(missing_ok=True)

    def configure_alternate_mount_identity(
        self,
        *,
        filesystem_type: str = "ext4",
        source: str = "/dev/mapper/clubs_bot_alternate",
        filesystem_root: str = "/alternate",
        mount_target: str = "/alternate-target",
    ) -> None:
        config = json.loads(self.findmnt_config.read_text(encoding="utf-8"))
        config["alternate"] = {
            "prefix": str(self.root),
            "fstype": filesystem_type,
            "source": source,
            "fsroot": filesystem_root,
            "target": mount_target,
        }
        self.findmnt_config.write_text(
            json.dumps(config, sort_keys=True, separators=(",", ":")),
            encoding="utf-8",
        )

    def clear_findmnt_behavior(self) -> None:
        self.configure_findmnt_behavior("normal")

    def simulate_process_reboot(self) -> None:
        for path in tuple(self.volatile_root.iterdir()):
            if path.is_dir() and not path.is_symlink():
                raise AssertionError(f"unexpected volatile directory in fixture: {path.name}")
            path.unlink()

    def run(
        self,
        remote_mode: str,
        *arguments: str,
        owner: str = OWNER,
        timeout: int = 120,
        input_text: str | None = None,
        environment: str | None = None,
    ) -> subprocess.CompletedProcess[str]:
        environment_values = self.env.copy()
        if remote_mode == "status":
            environment_values["BASH_ENV"] = str(self.status_write_audit)
            environment_values["FAKE_STATUS_WRITE_AUDIT_ENABLED"] = "yes"
        return subprocess.run(
            ["bash", str(REMOTE_HELPER), remote_mode, owner, environment or self.app_env, *arguments],
            cwd=REPOSITORY_ROOT,
            env=environment_values,
            input=input_text,
            capture_output=True,
            text=True,
            check=False,
            timeout=timeout,
        )

    def operation(self, operation: str) -> subprocess.CompletedProcess[str]:
        return self.run(operation, str(self.compose_path), DIGEST, REVISION)

    def preflight(
        self,
        *,
        environment: str | None = None,
        owner: str = OWNER,
    ) -> subprocess.CompletedProcess[str]:
        return self.run(
            "preflight",
            str(self.compose_path),
            IMAGE_REPOSITORY,
            "candidate",
            REVISION,
            "fixture-bot",
            owner=owner,
            environment=environment,
            input_text="fixture-registry-token\n",
        )

    def resume(self, target: str) -> subprocess.CompletedProcess[str]:
        return self.run("resume", target, str(self.compose_path), DIGEST, REVISION)

    def status(
        self,
        requested_operation: str,
        *,
        owner: str = OWNER,
        revision: str = REVISION,
        digest: str = DIGEST,
    ) -> subprocess.CompletedProcess[str]:
        return self.run(
            "status",
            str(self.compose_path),
            revision,
            digest,
            requested_operation,
            owner=owner,
        )

    def assert_success(self, result: subprocess.CompletedProcess[str]) -> None:
        if result.returncode != 0:
            raise AssertionError(
                f"operation failed rc={result.returncode}\nstdout={result.stdout!r}\nstderr={result.stderr!r}"
            )

    def progress_to(self, checkpoint: str) -> None:
        transitions = [
            ("prior_state_captured", lambda: self.operation("prepare")),
            ("candidate_override_published", lambda: self.operation("publish")),
            ("app_quiesced", lambda: self.operation("quiesce")),
            ("migration_completed", lambda: self.operation("migrate")),
            ("candidate_healthy", lambda: self.operation("start")),
        ]
        for reached, invoke in transitions:
            self.assert_success(invoke())
            if reached == checkpoint:
                return
        raise AssertionError(f"unsupported progress checkpoint: {checkpoint}")

    def checkpoint(self) -> str:
        state_dir = self.active_state_dir
        if state_dir is not None and (state_dir / "checkpoint").is_file():
            return (state_dir / "checkpoint").read_text(encoding="utf-8")
        completed = self.result_dir / f"{OWNER}.completed"
        if completed.is_file():
            return parse_record(completed)["checkpoint"]
        return "none"

    def physical_checkpoint(self) -> str:
        state_dir = self.active_state_dir
        if state_dir is not None and (state_dir / "checkpoint").is_file():
            return (state_dir / "checkpoint").read_text(encoding="utf-8")
        return self.checkpoint()

    def migration_ledger(self, owner: str = OWNER) -> dict[str, str]:
        return parse_record(self.ledger_dir / f"{owner}.ledger")

    def migration_outcome_exists(self, owner: str = OWNER) -> bool:
        return (self.ledger_dir / f"{owner}.outcome").is_file()

    def result(self) -> dict[str, str]:
        return parse_record(self.result_dir / f"{OWNER}.result")

    def clear_command_logs(self) -> None:
        self.docker_log.write_text("", encoding="utf-8")
        self.mv_log.write_text("", encoding="utf-8")
        self.rm_log.write_text("", encoding="utf-8")
        self.flock_log.write_text("", encoding="utf-8")
        self.findmnt_log.write_text("", encoding="utf-8")
        self.sync_log.write_text("", encoding="utf-8")
        self.status_write_audit_log.write_text("", encoding="utf-8")

    def findmnt_commands(self) -> list[list[str]]:
        return [
            json.loads(line)
            for line in self.findmnt_log.read_text(encoding="utf-8").splitlines()
            if line
        ]

    def status_filesystem_write_counts(self) -> dict[str, int]:
        categories = (
            "mkdir",
            "create",
            "open_for_write",
            "chmod",
            "rename",
            "unlink",
            "rmdir",
            "fsync",
            "truncate",
            "prune",
        )
        observed = self.status_write_audit_log.read_text(encoding="utf-8").splitlines()
        if any(value not in categories for value in observed):
            raise AssertionError("unbounded status write-audit category")
        return {category: observed.count(category) for category in categories}

    def docker_commands(self) -> list[list[str]]:
        return [
            json.loads(line)
            for line in self.docker_log.read_text(encoding="utf-8").splitlines()
            if line
        ]

    def rename_entries(self) -> list[tuple[Path, Path]]:
        entries: list[tuple[Path, Path]] = []
        for line in self.mv_log.read_text(encoding="utf-8").splitlines():
            if not line:
                continue
            source, target = line.split("\t", 1)
            entries.append((Path(source), Path(target)))
        return entries

    def lifecycle_commands(self) -> list[list[str]]:
        lifecycle: list[list[str]] = []
        for command in self.docker_commands():
            normalized = list(command)
            if normalized[:1] == ["--config"]:
                normalized = normalized[2:]
            if normalized[:1] == ["compose"]:
                terms = set(normalized)
                if terms.intersection({"stop", "rm", "run", "up"}):
                    lifecycle.append(command)
            elif normalized[:1] in (["wait"], ["rm"]):
                lifecycle.append(command)
        return lifecycle

    def write_state_value(self, name: str, value: str) -> None:
        state_dir = self.active_state_dir
        if state_dir is None:
            raise AssertionError("no active state")
        path = state_dir / name
        path.write_text(value, encoding="utf-8")
        path.chmod(0o600)

    def _write_record(self, path: Path, fields: dict[str, str], mtime: int) -> None:
        path.parent.mkdir(mode=0o700, exist_ok=True)
        path.write_text(
            "\n".join(f"{name}={value}" for name, value in fields.items()),
            encoding="utf-8",
        )
        path.chmod(0o600)
        os.utime(path, (mtime, mtime))

    def write_terminal_abort_artifact(
        self,
        artifact_owner: str,
        *,
        mtime: int,
        incomplete: bool = False,
        helper: bool = False,
    ) -> None:
        path_hash = hashlib.sha256(str(self.compose_path).encode()).hexdigest()
        result = {
            "result_version": "1",
            "owner": artifact_owner,
            "requested_operation": "abort",
            "checkpoint_before": "abort_started",
            "checkpoint_after": "unavailable" if incomplete else "abort_completed",
            "result": "incomplete_unknown" if incomplete else "success",
            "failure_category": "operation_in_progress" if incomplete else "success",
            "expected_revision": REVISION,
            "image_digest": DIGEST,
            "compose_path_hash": path_hash,
        }
        completed = {
            "completed_version": "1",
            "owner": artifact_owner,
            "expected_revision": REVISION,
            "image_digest": DIGEST,
            "compose_path_hash": path_hash,
            "checkpoint": "abort_completed",
            "disposition": "abort",
            "migration_evidence": "absent",
            "app_state": "old_running",
        }
        self._write_record(self.result_dir / f"{artifact_owner}.result", result, mtime)
        self._write_record(self.result_dir / f"{artifact_owner}.completed", completed, mtime)
        if helper:
            helper_path = self.volatile_root / f"clubs-bot-release-{artifact_owner}.sh"
            helper_path.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
            helper_path.chmod(0o700)
            os.utime(helper_path, (mtime, mtime))

    def write_terminal_migration_artifact(
        self,
        artifact_owner: str,
        *,
        mtime: int,
        completed_epoch: int,
        revision_digit: str,
        digest_digit: str,
    ) -> None:
        revision = revision_digit * 40
        digest = f"{IMAGE_REPOSITORY}@sha256:{digest_digit * 64}"
        path_hash = hashlib.sha256(str(self.compose_path).encode()).hexdigest()
        fingerprint = hashlib.sha256(
            f"v1|test|{artifact_owner}|{revision}|{digest}|{path_hash}".encode()
        ).hexdigest()
        created_epoch = completed_epoch - 1
        ledger = {
            "ledger_version": "1",
            "environment": "test",
            "owner": artifact_owner,
            "expected_revision": revision,
            "image_digest": digest,
            "compose_path_hash": path_hash,
            "operation": "migration",
            "state": "completed",
            "invocation_fingerprint": fingerprint,
            "result": "completed",
            "completion_checkpoint": "migration_completed",
            "created_epoch": str(created_epoch),
            "completed_epoch": str(completed_epoch),
        }
        outcome = {
            "outcome_version": "1",
            "environment": "test",
            "owner": artifact_owner,
            "expected_revision": revision,
            "image_digest": digest,
            "compose_path_hash": path_hash,
            "operation": "migration",
            "state": "succeeded",
            "invocation_fingerprint": fingerprint,
            "bounded_result": "migration_succeeded",
            "completion_checkpoint": "migration_process_succeeded",
            "recorded_epoch": str(completed_epoch),
        }
        result = {
            "result_version": "1",
            "owner": artifact_owner,
            "requested_operation": "cleanup",
            "checkpoint_before": "cleanup_started",
            "checkpoint_after": "cleanup_completed",
            "result": "success",
            "failure_category": "success",
            "expected_revision": revision,
            "image_digest": digest,
            "compose_path_hash": path_hash,
        }
        completed = {
            "completed_version": "1",
            "owner": artifact_owner,
            "expected_revision": revision,
            "image_digest": digest,
            "compose_path_hash": path_hash,
            "checkpoint": "cleanup_completed",
            "disposition": "release",
            "migration_evidence": "present",
            "app_state": "candidate_running",
        }
        self._write_record(self.ledger_dir / f"{artifact_owner}.ledger", ledger, mtime)
        self._write_record(self.ledger_dir / f"{artifact_owner}.outcome", outcome, mtime)
        self._write_record(self.result_dir / f"{artifact_owner}.result", result, mtime)
        self._write_record(self.result_dir / f"{artifact_owner}.completed", completed, mtime)


class RunnerHarness:
    def __init__(
        self,
        *,
        fail_mode: str | None = None,
        failure_behavior: str | None = None,
        status_stdout: str = "",
        status_exit: int = 0,
        scp_exit: int = 0,
    ) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory(
            prefix="clubs-release-runner-",
            dir="/tmp",
        )
        self.root = Path(self.temporary_directory.name)
        self.fake_bin = self.root / "bin"
        self.tmp = self.root / "tmp"
        self.compose_path = Path("/srv/clubs-bot-release-fixture")
        self.fake_bin.mkdir()
        self.tmp.mkdir()
        self.ssh_config = self.root / "ssh-config.json"
        self.ssh_log = self.root / "ssh.log"
        self.scp_log = self.root / "scp.log"
        self.ssh_log.write_text("", encoding="utf-8")
        self.scp_log.write_text("", encoding="utf-8")
        self.ssh_config.write_text(
            json.dumps(
                {
                    "fail_mode": fail_mode,
                    "failure_behavior": failure_behavior,
                    "sensitive_stderr": SENSITIVE_TEXT,
                    "status_exit": status_exit,
                    "status_stdout": status_stdout,
                },
                sort_keys=True,
                separators=(",", ":"),
            ),
            encoding="utf-8",
        )
        write_executable(self.fake_bin / "ssh", FAKE_SSH)
        write_executable(self.fake_bin / "scp", FAKE_SCP)
        self.env = os.environ.copy()
        self.env.update(
            {
                "APP_ENV": "stage",
                "COMPOSE_PATH": str(self.compose_path),
                "EXPECTED_REVISION": REVISION,
                "FAKE_DIGEST": DIGEST,
                "FAKE_SCP_EXIT": str(scp_exit),
                "FAKE_SCP_LOG": str(self.scp_log),
                "FAKE_SENSITIVE_STDERR": SENSITIVE_TEXT,
                "FAKE_SSH_CONFIG": str(self.ssh_config),
                "FAKE_SSH_LOG": str(self.ssh_log),
                "GITHUB_RUN_ATTEMPT": "1",
                "GITHUB_RUN_ID": "12345",
                "IMAGE_REPOSITORY": IMAGE_REPOSITORY,
                "IMAGE_TAG": "fixture",
                "PATH": f"{self.fake_bin}{os.pathsep}{os.environ['PATH']}",
                "REGISTRY_READ_TOKEN": SENSITIVE_VALUES[0],
                "REGISTRY_USERNAME": "fixture-user",
                "SSH_HOST": "stage-host.invalid",
                "SSH_PORT": "22",
                "SSH_USER": "release-user",
                "TMPDIR": str(self.tmp),
            }
        )

    def __enter__(self) -> "RunnerHarness":
        return self

    def __exit__(self, *_: object) -> None:
        self.temporary_directory.cleanup()

    def run(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["bash", str(RUNNER)],
            cwd=REPOSITORY_ROOT,
            env=self.env,
            capture_output=True,
            text=True,
            check=False,
            timeout=15,
        )

    def ssh_entries(self) -> list[dict[str, Any]]:
        return [
            json.loads(line)
            for line in self.ssh_log.read_text(encoding="utf-8").splitlines()
            if line
        ]

    def mode_counts(self) -> dict[str, int]:
        counts: dict[str, int] = {}
        for entry in self.ssh_entries():
            name = entry["mode"]
            counts[name] = counts.get(name, 0) + 1
        return counts


def status_line(
    *,
    checkpoint: str,
    result: str,
    owner_match: str = "yes",
    revision_match: str = "yes",
    digest_match: str = "yes",
    migration_evidence: str = "absent",
    app_state: str = "old_running",
    abort_permitted: str = "yes",
    resume_permitted: str = "yes",
    status_available: str = "yes",
    failure_category: str = "none",
) -> str:
    return (
        f"release-status:v=1 status_available={status_available} "
        f"owner_match={owner_match} revision_match={revision_match} digest_match={digest_match} "
        f"checkpoint={checkpoint} operation_result={result} "
        f"migration_evidence={migration_evidence} app_state={app_state} "
        f"abort_permitted={abort_permitted} resume_permitted={resume_permitted} "
        f"failure_category={failure_category}"
    )


def snapshot_tree(root: Path) -> dict[str, tuple[object, ...]]:
    """No-follow authority snapshot: metadata plus hashes, never raw contents."""

    snapshot: dict[str, tuple[object, ...]] = {}

    def visit(path: Path, relative: str) -> None:
        try:
            metadata = path.lstat()
        except FileNotFoundError:
            snapshot[relative] = ("missing",)
            return
        common = (
            metadata.st_dev,
            metadata.st_ino,
            metadata.st_nlink,
            stat.S_IMODE(metadata.st_mode),
            metadata.st_size,
            metadata.st_mtime_ns,
        )
        if stat.S_ISLNK(metadata.st_mode):
            snapshot[relative] = ("symlink", *common, os.readlink(path))
            return
        if stat.S_ISDIR(metadata.st_mode):
            snapshot[relative] = ("directory", *common, "")
            for child in sorted(path.iterdir(), key=lambda item: item.name):
                child_relative = child.name if relative == "." else f"{relative}/{child.name}"
                visit(child, child_relative)
            return
        if stat.S_ISREG(metadata.st_mode):
            snapshot[relative] = (
                "file",
                *common,
                hashlib.sha256(path.read_bytes()).hexdigest(),
            )
            return
        snapshot[relative] = ("other", *common, "")

    visit(root, ".")
    return snapshot


def snapshot_authoritative_tree(harness: RemoteHarness) -> dict[str, tuple[object, ...]]:
    """Capture lexical parents and the resolved Compose authority without logs."""

    snapshot: dict[str, tuple[object, ...]] = {}
    try:
        parts = harness.compose_path.relative_to(harness.root).parts
    except ValueError:
        parts = ()
    current = harness.root
    for index, part in enumerate(parts):
        current = current / part
        metadata = snapshot_tree(current).get(".", ("missing",))
        snapshot[f"lexical-parent-{index}"] = metadata
    resolved = harness.compose_path.resolve(strict=False)
    for relative, metadata in snapshot_tree(resolved).items():
        snapshot[f"compose/{relative}"] = metadata
    return snapshot


def assert_untrusted_status_read_only(
    testcase: unittest.TestCase,
    harness: RemoteHarness,
) -> subprocess.CompletedProcess[str]:
    harness.clear_command_logs()
    before_tree = snapshot_authoritative_tree(harness)
    before_docker = harness.docker_state()

    result = harness.status("prepare")

    after_tree = snapshot_authoritative_tree(harness)
    after_docker = harness.docker_state()
    testcase.assertEqual(0, result.returncode, result.stderr)
    testcase.assertEqual(before_tree, after_tree)
    testcase.assertEqual("", result.stderr)
    testcase.assertIn("status_available=no", result.stdout)
    testcase.assertIn("failure_category=untrusted_state_root", result.stdout)
    testcase.assertIn("abort_permitted=no", result.stdout)
    testcase.assertIn("resume_permitted=no", result.stdout)
    write_counts = harness.status_filesystem_write_counts()
    testcase.assertEqual({category: 0 for category in write_counts}, write_counts)
    testcase.assertEqual([], harness.lifecycle_commands())
    testcase.assertEqual(
        before_docker["migration_invocations"],
        after_docker["migration_invocations"],
    )
    testcase.assertEqual(before_docker["start_invocations"], after_docker["start_invocations"])
    testcase.assertEqual(before_docker["lifecycle_counts"], after_docker["lifecycle_counts"])
    config = json.loads(harness.findmnt_config.read_text(encoding="utf-8"))
    raw_mount_values = {
        str(record.get(field, ""))
        for record in [config.get("default", {}), *config.get("mounts", [])]
        for field in ("source", "fsroot", "target")
        if len(str(record.get(field, ""))) > 3
    }
    for forbidden in SENSITIVE_VALUES + (str(harness.compose_path), *sorted(raw_mount_values)):
        testcase.assertNotIn(forbidden, result.stdout + result.stderr)
    for raw_error in ("no such file", "permission denied", "findmnt", "stat:"):
        testcase.assertNotIn(raw_error, (result.stdout + result.stderr).lower())
    return result


class CheckpointFailureMatrixTest(unittest.TestCase):
    def assert_failed_at(
        self,
        harness: RemoteHarness,
        result: subprocess.CompletedProcess[str],
        checkpoint: str,
    ) -> None:
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertEqual(checkpoint, harness.checkpoint())
        record = harness.result()
        self.assertEqual("remote_failure", record["result"])
        self.assertEqual(checkpoint, record["checkpoint_after"])
        self.assertRegex(record["failure_category"], r"^[a-z0-9_]+$")
        self.assertLessEqual((harness.result_dir / f"{OWNER}.result").stat().st_size, 2048)

    def test_required_checkpoint_failure_matrix(self) -> None:
        cases = (
            "before-maintenance-creation",
            "after-maintenance-creation",
            "after-prior-state-capture",
            "after-candidate-override-publication",
            "after-stop-intent-checkpoint",
            "during-app-stop",
            "after-app-disappearance-before-quiesced-ack",
            "before-migration",
            "during-migration",
            "after-migration",
            "during-candidate-start",
            "before-successful-cleanup",
        )
        for case_name in cases:
            with self.subTest(checkpoint_failure=case_name), RemoteHarness() as harness:
                if case_name == "before-maintenance-creation":
                    harness.update_docker_state(candidate_revision="0" * 40)
                    result = harness.operation("prepare")
                    expected = "none"
                elif case_name == "after-maintenance-creation":
                    harness.configure_mv_failure("/checkpoint", match_at=2)
                    result = harness.operation("prepare")
                    expected = "maintenance_prepared"
                elif case_name == "after-prior-state-capture":
                    harness.progress_to("prior_state_captured")
                    harness.configure_mv_failure("/docker-compose.override.yml")
                    result = harness.operation("publish")
                    expected = "prior_state_captured"
                elif case_name == "after-candidate-override-publication":
                    harness.progress_to("candidate_override_published")
                    harness.configure_mv_failure("/checkpoint")
                    result = harness.operation("quiesce")
                    expected = "candidate_override_published"
                elif case_name == "after-stop-intent-checkpoint":
                    harness.progress_to("candidate_override_published")
                    harness.update_docker_state(
                        ps_sequence=["old_running", "old_running", "ambiguous"]
                    )
                    result = harness.operation("quiesce")
                    expected = "app_stop_intent"
                elif case_name == "during-app-stop":
                    harness.progress_to("candidate_override_published")
                    harness.fail_docker("compose_stop", 61)
                    result = harness.operation("quiesce")
                    expected = "app_stop_intent"
                elif case_name == "after-app-disappearance-before-quiesced-ack":
                    harness.progress_to("candidate_override_published")
                    harness.configure_mv_failure("/checkpoint", match_at=2)
                    result = harness.operation("quiesce")
                    expected = "app_stop_intent"
                    self.assertEqual("absent", harness.docker_state()["app_state"])
                elif case_name == "before-migration":
                    harness.progress_to("app_quiesced")
                    harness.update_docker_state(migration_exists=True)
                    result = harness.operation("migrate")
                    expected = "app_quiesced"
                    self.assertEqual(0, harness.docker_state()["migration_invocations"])
                elif case_name == "during-migration":
                    harness.progress_to("app_quiesced")
                    harness.fail_docker("compose_run", 62)
                    result = harness.operation("migrate")
                    expected = "migration_started"
                    self.assertEqual(1, harness.docker_state()["migration_invocations"])
                elif case_name == "after-migration":
                    harness.progress_to("migration_completed")
                    harness.configure_mv_failure("/checkpoint")
                    result = harness.operation("start")
                    expected = "migration_completed"
                    self.assertEqual(0, harness.docker_state()["start_invocations"])
                elif case_name == "during-candidate-start":
                    harness.progress_to("migration_completed")
                    harness.fail_docker("compose_up", 63)
                    result = harness.operation("start")
                    expected = "candidate_start_begun"
                    self.assertEqual(1, harness.docker_state()["start_invocations"])
                elif case_name == "before-successful-cleanup":
                    harness.progress_to("candidate_healthy")
                    harness.configure_mv_failure(".finalizing")
                    result = harness.operation("cleanup")
                    expected = "cleanup_started"
                else:  # pragma: no cover - closed tuple above
                    self.fail(case_name)
                self.assert_failed_at(harness, result, expected)


class AbortAndResumeTest(unittest.TestCase):
    def assert_no_container_or_database_mutation(self, harness: RemoteHarness) -> None:
        self.assertEqual([], harness.lifecycle_commands())
        state = harness.docker_state()
        self.assertEqual(0, state["migration_invocations"])
        self.assertEqual(0, state["start_invocations"])
        self.assertEqual(0, state["migration_removals"])

    def test_prepared_state_abort_is_safe_and_idempotent(self) -> None:
        with RemoteHarness() as harness:
            harness.configure_mv_failure("/checkpoint", match_at=2)
            failed_prepare = harness.operation("prepare")
            self.assertNotEqual(0, failed_prepare.returncode)
            self.assertEqual("maintenance_prepared", harness.checkpoint())
            harness.clear_mv_failure()
            harness.clear_command_logs()

            first = harness.operation("abort")
            self.assertEqual(0, first.returncode, first.stderr)
            self.assertEqual("abort_completed", harness.checkpoint())
            self.assertEqual(self.prior_bytes(harness), (harness.compose_path / "docker-compose.override.yml").read_bytes())
            self.assert_no_container_or_database_mutation(harness)

            harness.clear_command_logs()
            completed_path = harness.result_dir / f"{OWNER}.completed"
            completed_before = completed_path.read_bytes()
            override_before = (harness.compose_path / "docker-compose.override.yml").read_bytes()
            unrelated = harness.root / "unrelated-operator-file"
            unrelated.write_text("unchanged", encoding="utf-8")
            second = harness.operation("abort")
            self.assertEqual(0, second.returncode, second.stderr)
            self.assertEqual("release-operation:v=1 result=already_clean\n", second.stdout)
            self.assertEqual(completed_before, completed_path.read_bytes())
            self.assertEqual(
                override_before,
                (harness.compose_path / "docker-compose.override.yml").read_bytes(),
            )
            self.assertEqual("unchanged", unrelated.read_text(encoding="utf-8"))
            self.assertIsNone(harness.active_state_dir)
            self.assert_no_container_or_database_mutation(harness)

    @staticmethod
    def prior_bytes(harness: RemoteHarness) -> bytes:
        return harness.prior_override_bytes

    def test_published_override_abort_restores_exact_prior_state(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("candidate_override_published")
            self.assertEqual(
                canonical_override(DIGEST, REVISION),
                (harness.compose_path / "docker-compose.override.yml").read_bytes(),
            )
            harness.clear_command_logs()
            result = harness.operation("abort")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                harness.prior_override_bytes,
                (harness.compose_path / "docker-compose.override.yml").read_bytes(),
            )
            self.assertEqual("abort_completed", harness.checkpoint())
            self.assert_no_container_or_database_mutation(harness)

    def test_abort_rejects_forbidden_state_matrix(self) -> None:
        cases = (
            "app-absent",
            "app-quiesced",
            "migration-evidence",
            "migration-container",
            "changed-override",
            "changed-prior-evidence",
            "unmanaged-prior",
        )
        for case_name in cases:
            with self.subTest(abort_guard=case_name), RemoteHarness() as harness:
                if case_name == "unmanaged-prior":
                    override = harness.compose_path / "docker-compose.override.yml"
                    override.write_text("services:\n  db:\n    image: unmanaged\n", encoding="utf-8")
                    override.chmod(0o600)
                    result = harness.operation("prepare")
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual("none", harness.checkpoint())
                    self.assert_no_container_or_database_mutation(harness)
                    continue

                harness.progress_to(
                    "app_quiesced" if case_name == "app-quiesced" else "candidate_override_published"
                )
                if case_name == "app-absent":
                    harness.update_docker_state(app_state="absent")
                elif case_name == "migration-evidence":
                    harness.write_state_value("migration_image_digest", DIGEST)
                elif case_name == "migration-container":
                    harness.update_docker_state(migration_exists=True)
                elif case_name == "changed-override":
                    override = harness.compose_path / "docker-compose.override.yml"
                    override.write_text("malformed changed override\n", encoding="utf-8")
                    override.chmod(0o600)
                elif case_name == "changed-prior-evidence":
                    prior = harness.lock_dir / "prior-override"
                    prior.write_text("malformed changed prior evidence\n", encoding="utf-8")
                    prior.chmod(0o600)
                harness.clear_command_logs()
                result = harness.operation("abort")
                self.assertNotEqual(0, result.returncode)
                self.assert_no_container_or_database_mutation(harness)

    def test_abort_removes_only_allowlisted_state(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("candidate_override_published")
            unrelated = harness.lock_dir / "unrelated-sentinel"
            unrelated.write_text("must survive", encoding="utf-8")
            unrelated.chmod(0o600)
            harness.clear_command_logs()
            result = harness.operation("abort")
            self.assertNotEqual(0, result.returncode)
            state_dir = harness.active_state_dir
            self.assertIsNotNone(state_dir)
            assert state_dir is not None
            surviving = state_dir / unrelated.name
            self.assertTrue(surviving.is_file())
            self.assertEqual("must survive", surviving.read_text(encoding="utf-8"))
            self.assertTrue((state_dir / "owner").is_file())
            self.assertTrue((state_dir / "checkpoint").is_file())
            self.assertEqual(
                canonical_override(DIGEST, REVISION),
                (harness.compose_path / "docker-compose.override.yml").read_bytes(),
            )
            self.assert_no_container_or_database_mutation(harness)

    def test_abort_after_candidate_override_write_before_checkpoint_is_safe(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("prior_state_captured")
            harness.configure_mv_failure("/checkpoint")
            publish = harness.operation("publish")
            self.assertNotEqual(0, publish.returncode)
            self.assertEqual("prior_state_captured", harness.checkpoint())
            self.assertEqual(
                canonical_override(DIGEST, REVISION),
                (harness.compose_path / "docker-compose.override.yml").read_bytes(),
            )
            harness.clear_mv_failure()
            harness.clear_command_logs()
            aborted = harness.operation("abort")
            self.assertEqual(0, aborted.returncode, aborted.stderr)
            self.assertEqual("abort_completed", harness.checkpoint())
            self.assertEqual(
                harness.prior_override_bytes,
                (harness.compose_path / "docker-compose.override.yml").read_bytes(),
            )
            self.assert_no_container_or_database_mutation(harness)

    def test_abort_rejects_tampered_prior_before_intent_and_status_denies(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("candidate_override_published")
            prior = harness.lock_dir / "prior-override"
            prior.write_text("malformed changed prior evidence\n", encoding="utf-8")
            prior.chmod(0o600)
            checkpoint_before = harness.checkpoint()

            before_abort = harness.status("publish")
            self.assertEqual(0, before_abort.returncode)
            self.assertIn("abort_permitted=no", before_abort.stdout)

            harness.clear_command_logs()
            aborted = harness.operation("abort")
            self.assertNotEqual(0, aborted.returncode)
            self.assertEqual(checkpoint_before, harness.checkpoint())
            self.assertEqual("prior_state_invalid", harness.result()["failure_category"])
            self.assertEqual(
                canonical_override(DIGEST, REVISION),
                (harness.compose_path / "docker-compose.override.yml").read_bytes(),
            )
            after_abort = harness.status("abort")
            self.assertEqual(0, after_abort.returncode)
            self.assertIn("abort_permitted=no", after_abort.stdout)
            self.assertIn("resume_permitted=no", after_abort.stdout)
            self.assert_no_container_or_database_mutation(harness)

    def test_resume_rejects_candidate_without_effective_compose_proof(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("prior_state_captured")
            harness.update_docker_state(configured_digest=False)

            published = harness.operation("publish")
            self.assertNotEqual(0, published.returncode)
            self.assertEqual("prior_state_captured", harness.checkpoint())
            self.assertEqual(
                canonical_override(DIGEST, REVISION),
                (harness.compose_path / "docker-compose.override.yml").read_bytes(),
            )
            status = harness.status("publish")
            self.assertEqual(0, status.returncode)
            self.assertIn("abort_permitted=yes", status.stdout)
            self.assertIn("resume_permitted=no", status.stdout)

            harness.clear_command_logs()
            resumed = harness.resume("quiesce")
            self.assertNotEqual(0, resumed.returncode)
            self.assertEqual("prior_state_captured", harness.checkpoint())
            self.assertEqual("override_invalid", harness.result()["failure_category"])
            self.assertEqual([], harness.lifecycle_commands())
            self.assert_no_container_or_database_mutation(harness)

    def test_abort_rejects_normal_release_completion(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("candidate_healthy")
            harness.assert_success(harness.operation("cleanup"))
            self.assertEqual("cleanup_completed", harness.checkpoint())
            state_before = harness.docker_state()

            harness.clear_command_logs()
            aborted = harness.operation("abort")
            self.assertNotEqual(0, aborted.returncode)
            self.assertEqual("cleanup_completed", harness.checkpoint())
            state_after = harness.docker_state()
            self.assertEqual([], harness.lifecycle_commands())
            self.assertEqual(state_before["migration_invocations"], state_after["migration_invocations"])
            self.assertEqual(state_before["start_invocations"], state_after["start_invocations"])
            self.assertEqual(state_before["migration_removals"], state_after["migration_removals"])

    def test_resume_quiesce_continues_once_for_unchanged_old_app(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("candidate_override_published")
            harness.clear_command_logs()
            first = harness.resume("quiesce")
            self.assertEqual(0, first.returncode, first.stderr)
            self.assertEqual("app_quiesced", harness.checkpoint())
            state = harness.docker_state()
            self.assertEqual({"stop": 1, "rm": 1}, state["lifecycle_counts"])
            self.assertEqual(0, state["migration_invocations"])
            self.assertTrue(
                any(
                    command[-1] == OLD_CONTAINER_ID
                    and "com.docker.compose.oneoff" in " ".join(command)
                    for command in harness.docker_commands()
                )
            )

            harness.clear_command_logs()
            second = harness.resume("quiesce")
            self.assertEqual(0, second.returncode, second.stderr)
            self.assertEqual([], harness.lifecycle_commands())
            self.assertEqual({"stop": 1, "rm": 1}, harness.docker_state()["lifecycle_counts"])

    def test_resume_quiesce_reconciles_exact_absence_without_lifecycle(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("candidate_override_published")
            harness.update_docker_state(app_state="absent")
            harness.clear_command_logs()
            result = harness.resume("quiesce")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("app_quiesced", harness.checkpoint())
            self.assertEqual([], harness.lifecycle_commands())

    def test_resume_quiesce_rejects_replaced_or_ambiguous_identity(self) -> None:
        for app_state in ("replaced", "ambiguous"):
            with self.subTest(app_state=app_state), RemoteHarness() as harness:
                harness.progress_to("candidate_override_published")
                harness.update_docker_state(app_state=app_state)
                harness.clear_command_logs()
                result = harness.resume("quiesce")
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("candidate_override_published", harness.checkpoint())
                self.assertEqual([], harness.lifecycle_commands())
                self.assertEqual("app_identity_mismatch", harness.result()["failure_category"])

    def test_migration_failure_is_fail_closed_and_never_reinvoked(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("app_quiesced")
            harness.fail_docker("compose_run", 64)
            first = harness.resume("migrate")
            self.assertNotEqual(0, first.returncode)
            self.assertEqual("migration_started", harness.checkpoint())
            self.assertEqual(1, harness.docker_state()["migration_invocations"])
            self.assertEqual("absent", harness.docker_state()["app_state"])
            self.assertEqual(
                canonical_override(DIGEST, REVISION),
                (harness.compose_path / "docker-compose.override.yml").read_bytes(),
            )

            harness.clear_docker_failures()
            second = harness.resume("migrate")
            self.assertNotEqual(0, second.returncode)
            self.assertEqual(1, harness.docker_state()["migration_invocations"])
            self.assertEqual(0, harness.docker_state()["start_invocations"])

    def test_resume_start_and_cleanup_remain_migration_gated(self) -> None:
        with self.subTest(case="retained-oneoff-production-incident"), RemoteHarness() as harness:
            harness.progress_to("migration_completed")
            migrated_state = harness.docker_state()
            self.assertEqual("absent", migrated_state["app_state"])
            self.assertTrue(migrated_state["migration_exists"])
            self.assertEqual(1, migrated_state["migration_invocations"])
            self.assertEqual(0, migrated_state["start_invocations"])
            self.assertEqual(0, migrated_state["migration_removals"])

            harness.clear_command_logs()
            status = harness.status("migrate")
            self.assertEqual(0, status.returncode, status.stderr)
            self.assertIn("checkpoint=migration_completed", status.stdout)
            self.assertIn("migration_evidence=present", status.stdout)
            self.assertIn("app_state=absent", status.stdout)
            self.assertIn("resume_permitted=yes", status.stdout)
            self.assertEqual([], harness.lifecycle_commands())

            migrated_again = harness.resume("migrate")
            self.assertEqual(0, migrated_again.returncode, migrated_again.stderr)
            self.assertEqual(1, harness.docker_state()["migration_invocations"])

            harness.clear_command_logs()
            started = harness.resume("start")
            self.assertEqual(0, started.returncode, started.stderr)
            self.assertEqual("candidate_healthy", harness.checkpoint())
            started_state = harness.docker_state()
            self.assertEqual(1, started_state["migration_invocations"])
            self.assertEqual(1, started_state["start_invocations"])
            self.assertTrue(started_state["migration_exists"])
            self.assertEqual(0, started_state["migration_removals"])
            docker_commands = harness.docker_commands()
            self.assertTrue(
                any(
                    command[-1] == MIGRATION_CONTAINER_ID
                    and "com.docker.compose.oneoff" in " ".join(command)
                    for command in docker_commands
                )
            )
            self.assertFalse(
                any(
                    command[-1] == MIGRATION_CONTAINER_ID
                    and "{{.State.Running}}" in command
                    for command in docker_commands
                )
            )
            health_urls = [
                command[-1]
                for command in docker_commands
                if command[:1] == ["compose"] and "exec" in command
            ]
            self.assertEqual(
                ["http://127.0.0.1:8080/ready", "http://127.0.0.1:8080/health"],
                health_urls,
            )
            for forbidden in SENSITIVE_VALUES:
                self.assertNotIn(forbidden, started.stdout + started.stderr)

            harness.clear_command_logs()
            running_status = harness.status("start")
            self.assertEqual(0, running_status.returncode, running_status.stderr)
            self.assertIn("migration_evidence=present", running_status.stdout)
            self.assertIn("app_state=candidate_running", running_status.stdout)
            self.assertEqual([], harness.lifecycle_commands())

            started_again = harness.resume("start")
            self.assertEqual(0, started_again.returncode, started_again.stderr)
            self.assertEqual(1, harness.docker_state()["start_invocations"])
            cleaned = harness.resume("cleanup")
            self.assertEqual(0, cleaned.returncode, cleaned.stderr)
            self.assertEqual("cleanup_completed", harness.checkpoint())
            self.assertFalse(harness.docker_state()["migration_exists"])
            self.assertEqual(1, harness.docker_state()["migration_removals"])
            cleaned_again = harness.operation("cleanup")
            self.assertEqual(0, cleaned_again.returncode, cleaned_again.stderr)
            self.assertEqual("release-operation:v=1 result=already_clean\n", cleaned_again.stdout)
            self.assertEqual(1, harness.docker_state()["migration_removals"])

        identity_failures = (
            ("multiple-ordinary-containers", {"app_state": "ambiguous"}),
            (
                "duplicate-ordinary-container-id",
                {
                    "compose_ps_ids": [
                        MIGRATION_CONTAINER_ID,
                        CANDIDATE_CONTAINER_ID,
                        CANDIDATE_CONTAINER_ID,
                    ]
                },
            ),
            (
                "malformed-container-id",
                {"compose_ps_ids": [MIGRATION_CONTAINER_ID, "A" * 64]},
            ),
            ("absent-oneoff-label", {"migration_oneoff_label": "<no value>"}),
            ("unknown-oneoff-label", {"migration_oneoff_label": "unknown"}),
        )
        for case_name, docker_updates in identity_failures:
            with self.subTest(case=case_name), RemoteHarness() as harness:
                harness.progress_to("migration_completed")
                harness.update_docker_state(**docker_updates)
                harness.clear_command_logs()

                result = harness.resume("start")

                self.assertNotEqual(0, result.returncode)
                self.assertEqual("migration_completed", harness.checkpoint())
                self.assertEqual("app_identity_mismatch", harness.result()["failure_category"])
                state = harness.docker_state()
                self.assertEqual(1, state["migration_invocations"])
                self.assertEqual(0, state["start_invocations"])
                self.assertTrue(state["migration_exists"])
                self.assertEqual(0, state["migration_removals"])
                self.assertEqual([], harness.lifecycle_commands())
                for forbidden in SENSITIVE_VALUES:
                    self.assertNotIn(forbidden, result.stdout + result.stderr)

        with self.subTest(case="oneoff-label-inspect-failure"), RemoteHarness() as harness:
            harness.progress_to("migration_completed")
            harness.fail_docker("inspect_oneoff")
            harness.clear_command_logs()

            result = harness.resume("start")

            self.assertNotEqual(0, result.returncode)
            self.assertEqual("migration_completed", harness.checkpoint())
            self.assertEqual("app_identity_mismatch", harness.result()["failure_category"])
            state = harness.docker_state()
            self.assertEqual(1, state["migration_invocations"])
            self.assertEqual(0, state["start_invocations"])
            self.assertTrue(state["migration_exists"])
            self.assertEqual(0, state["migration_removals"])
            self.assertEqual([], harness.lifecycle_commands())
            for forbidden in SENSITIVE_VALUES:
                self.assertNotIn(forbidden, result.stdout + result.stderr)

    def test_cleanup_rejects_changed_migration_image_correlation(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("candidate_healthy")
            harness.write_state_value("migration_image_id", REPLACED_IMAGE_ID)
            result = harness.operation("cleanup")
            self.assertNotEqual(0, result.returncode)
            self.assertIsNotNone(harness.active_state_dir)
            self.assertNotEqual("cleanup_completed", harness.checkpoint())

    def test_cleanup_unknown_state_entry_rejects_before_any_removal(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("candidate_healthy")
            unknown = harness.lock_dir / "operator-owned-unknown"
            unknown.write_text("must survive", encoding="utf-8")
            unknown.chmod(0o600)
            state_before = snapshot_tree(harness.lock_dir)
            harness.clear_command_logs()

            result = harness.operation("cleanup")

            self.assertNotEqual(0, result.returncode)
            self.assertEqual(state_before, snapshot_tree(harness.lock_dir))
            self.assertTrue(harness.docker_state()["migration_exists"])
            self.assertEqual(0, harness.docker_state()["migration_removals"])
            self.assertEqual(
                canonical_override(DIGEST, REVISION),
                (harness.compose_path / "docker-compose.override.yml").read_bytes(),
            )
            self.assertEqual([], harness.lifecycle_commands())

    def test_cleanup_resumes_allowlisted_disposal_after_filesystem_failure(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("candidate_healthy")
            harness.configure_rm_failure("/owner")

            first = harness.operation("cleanup")

            self.assertNotEqual(0, first.returncode)
            for forbidden in SENSITIVE_VALUES:
                self.assertNotIn(forbidden, first.stdout + first.stderr)
            self.assertTrue(harness.disposal_dir.is_dir())
            self.assertTrue((harness.disposal_dir / "owner").is_file())
            self.assertEqual("cleanup_completed", harness.checkpoint())
            self.assertFalse(harness.docker_state()["migration_exists"])
            self.assertEqual(1, harness.docker_state()["migration_removals"])

            harness.clear_rm_failure()
            second = harness.operation("cleanup")

            self.assertEqual(0, second.returncode, second.stderr)
            self.assertEqual("release-operation:v=1 result=already_clean\n", second.stdout)
            self.assertFalse(harness.disposal_dir.exists())
            self.assertEqual(1, harness.docker_state()["migration_removals"])


class PowerLossExactlyOnceTest(unittest.TestCase):
    def assert_no_retry_or_rollback(self, harness: RemoteHarness) -> None:
        state = harness.docker_state()
        self.assertEqual(1, state["migration_invocations"])
        self.assertEqual(0, state["start_invocations"])
        self.assertEqual("absent", state["app_state"])
        self.assertEqual({"stop": 1, "rm": 1}, state["lifecycle_counts"])

    def assert_cold_migrate_rejected(self, harness: RemoteHarness) -> None:
        harness.simulate_process_reboot()
        second = harness.operation("migrate")
        self.assertNotEqual(0, second.returncode)
        self.assert_no_retry_or_rollback(harness)

    def test_migration_success_first_durable_evidence_write_failure_is_fail_closed(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("app_quiesced")
            harness.configure_mv_failure("/migration_image_digest")

            first = harness.operation("migrate")

            self.assertNotEqual(0, first.returncode)
            self.assertIn("migration-safe:v=1 event=completed applied=0", first.stderr)
            self.assertEqual("migration_started", harness.physical_checkpoint())
            self.assertEqual("started", harness.migration_ledger()["state"])
            self.assertFalse(harness.migration_outcome_exists())
            harness.clear_mv_failure()
            self.assert_cold_migrate_rejected(harness)
            status = harness.status("migrate")
            self.assertIn(
                "migration_evidence=migration_outcome_requires_incident_reconciliation",
                status.stdout,
            )
            self.assertIn("resume_permitted=no", status.stdout)

    def test_migration_success_completion_ledger_write_failure_is_fail_closed(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("app_quiesced")
            harness.configure_mv_failure(".ledger", match_at=2)

            first = harness.operation("migrate")

            self.assertNotEqual(0, first.returncode)
            self.assertIn("migration-safe:v=1 event=completed applied=0", first.stderr)
            self.assertEqual("migration_started", harness.physical_checkpoint())
            self.assertEqual("started", harness.migration_ledger()["state"])
            self.assertTrue(harness.migration_outcome_exists())
            harness.clear_mv_failure()
            self.assert_cold_migrate_rejected(harness)

    def test_migration_success_completion_parent_directory_fsync_failure_is_fail_closed(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("app_quiesced")
            harness.configure_sync_failure(
                kind="directory",
                suffix=".migration-ledgers",
                match_at=3,
            )

            first = harness.operation("migrate")

            self.assertNotEqual(0, first.returncode)
            self.assertIn("migration-safe:v=1 event=completed applied=0", first.stderr)
            self.assertEqual(1, harness.docker_state()["migration_invocations"])
            self.assertEqual("migration_started", harness.physical_checkpoint())
            self.assertEqual("started", harness.migration_ledger()["state"])
            self.assertEqual(0, harness.docker_state()["start_invocations"])
            self.assertEqual("absent", harness.docker_state()["app_state"])
            harness.clear_sync_failure()
            self.assert_cold_migrate_rejected(harness)
            status = harness.status("migrate")
            self.assertIn(
                "migration_evidence=migration_outcome_requires_incident_reconciliation",
                status.stdout,
            )

    def test_completed_ledger_survives_completion_checkpoint_fsync_failure(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("app_quiesced")
            harness.configure_sync_failure(
                kind="file",
                contains="/checkpoint.tmp.",
                match_at=2,
            )

            first = harness.operation("migrate")

            self.assertNotEqual(0, first.returncode)
            self.assertEqual(1, harness.docker_state()["migration_invocations"])
            self.assertEqual("migration_started", harness.physical_checkpoint())
            self.assertEqual("completed", harness.migration_ledger()["state"])
            self.assertTrue(harness.migration_outcome_exists())
            harness.clear_sync_failure()
            harness.simulate_process_reboot()
            status = harness.status("migrate")
            self.assertIn("checkpoint=migration_completed", status.stdout)
            self.assertIn("resume_permitted=yes", status.stdout)
            resumed = harness.resume("start")
            harness.assert_success(resumed)
            self.assertEqual("candidate_healthy", harness.physical_checkpoint())
            self.assertEqual(1, harness.docker_state()["migration_invocations"])
            self.assertEqual(1, harness.docker_state()["start_invocations"])

    def test_completed_ledger_survives_migration_container_removal_failure(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("migration_completed")
            harness.assert_success(harness.operation("start"))
            harness.fail_docker("docker_rm")

            first = harness.operation("cleanup")

            self.assertNotEqual(0, first.returncode)
            self.assertEqual("completed", harness.migration_ledger()["state"])
            self.assertEqual(1, harness.docker_state()["migration_invocations"])
            self.assertEqual(0, harness.docker_state()["migration_removals"])
            harness.clear_docker_failures()
            harness.simulate_process_reboot()
            rejected = harness.operation("migrate")
            self.assertNotEqual(0, rejected.returncode)
            self.assertEqual(1, harness.docker_state()["migration_invocations"])
            resumed = harness.resume("cleanup")
            harness.assert_success(resumed)
            self.assertEqual(1, harness.docker_state()["migration_removals"])

    def test_completed_ledger_survives_terminal_release_record_failure(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("migration_completed")
            harness.assert_success(harness.operation("start"))
            harness.configure_mv_failure(".completed")

            first = harness.operation("cleanup")

            self.assertNotEqual(0, first.returncode)
            self.assertEqual(1, harness.docker_state()["migration_removals"])
            self.assertEqual("completed", harness.migration_ledger()["state"])
            self.assertEqual(1, harness.docker_state()["migration_invocations"])
            harness.clear_mv_failure()
            harness.simulate_process_reboot()
            status = harness.status("cleanup")
            self.assertIn("checkpoint=cleanup_started", status.stdout)
            self.assertIn("resume_permitted=yes", status.stdout)
            rejected = harness.operation("migrate")
            self.assertNotEqual(0, rejected.returncode)
            self.assertEqual(1, harness.docker_state()["migration_invocations"])

    def test_cold_process_reentry_discards_volatile_files_but_keeps_authority(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("migration_completed")
            disposable = harness.volatile_root / "disposable.tmp"
            disposable.write_text("not-authority", encoding="utf-8")
            before = harness.migration_ledger().copy()

            harness.simulate_process_reboot()

            self.assertFalse(disposable.exists())
            self.assertEqual(before, harness.migration_ledger())
            rejected = harness.operation("migrate")
            self.assertEqual(0, rejected.returncode)
            self.assertEqual(1, harness.docker_state()["migration_invocations"])
            status = harness.status("migrate")
            self.assertIn("checkpoint=migration_completed", status.stdout)
            self.assertIn("revision_match=yes", status.stdout)
            self.assertIn("digest_match=yes", status.stdout)

    def test_completed_same_candidate_blocks_ordinary_rerun_but_future_candidate_is_allowed(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("migration_completed")
            harness.assert_success(harness.operation("start"))
            harness.assert_success(harness.operation("cleanup"))
            before_invocations = harness.docker_state()["migration_invocations"]

            same_candidate = harness.operation("prepare")

            self.assertNotEqual(0, same_candidate.returncode)
            self.assertEqual(before_invocations, harness.docker_state()["migration_invocations"])
            self.assertFalse(harness.active_state_dir)

            future_owner = "33333-1"
            future_revision = "5" * 40
            future_digest = f"{IMAGE_REPOSITORY}@sha256:{'6' * 64}"
            future_image_id = f"sha256:{'7' * 64}"
            state = harness.docker_state()
            state.update(
                {
                    "app_state": "old_running",
                    "old_container_id": CANDIDATE_CONTAINER_ID,
                    "old_digest": DIGEST,
                    "old_image_id": CANDIDATE_IMAGE_ID,
                    "old_revision": REVISION,
                    "candidate_image_id": future_image_id,
                    "candidate_revision": future_revision,
                    "digest": future_digest,
                }
            )
            harness._write_docker_state(state)
            future_prepare = harness.run(
                "prepare",
                str(harness.compose_path),
                future_digest,
                future_revision,
                owner=future_owner,
            )

            harness.assert_success(future_prepare)
            self.assertEqual(before_invocations, harness.docker_state()["migration_invocations"])
            self.assertEqual(
                future_owner,
                (harness.active_state_dir / "owner").read_text(encoding="utf-8"),
            )

    def test_durability_failures_never_false_advance_checkpoint(self) -> None:
        cases = ("file-fsync", "rename", "parent-directory-fsync", "directory-create-sync")
        for case_name in cases:
            with self.subTest(durability_failure=case_name), RemoteHarness() as harness:
                if case_name == "directory-create-sync":
                    harness.progress_to("app_quiesced")
                    harness.configure_sync_failure(
                        kind="directory",
                        suffix=".migration-ledgers",
                    )
                    result = harness.operation("migrate")
                    expected = "app_quiesced"
                else:
                    harness.progress_to("candidate_override_published")
                    if case_name == "file-fsync":
                        harness.configure_sync_failure(
                            kind="file",
                            contains="/checkpoint.tmp.",
                        )
                    elif case_name == "rename":
                        harness.configure_mv_failure("/checkpoint")
                    else:
                        harness.configure_sync_failure(
                            kind="directory",
                            suffix=".lock",
                        )
                    result = harness.operation("quiesce")
                    expected = "candidate_override_published"
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(expected, harness.physical_checkpoint())
                self.assertEqual(0, harness.docker_state()["migration_invocations"])
                if expected == "candidate_override_published":
                    self.assertEqual([], harness.lifecycle_commands())


class ActiveCandidateAnchorTest(unittest.TestCase):
    def test_anchor_update_failures_preserve_previous_authority_until_durable_replace(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("candidate_healthy")
            harness.assert_success(harness.operation("cleanup"))
            previous_anchor = harness.active_anchor.read_bytes()
            previous_ledger = harness.ledger_dir / f"{OWNER}.ledger"
            self.assertTrue(previous_ledger.is_file())

            future_owner = "34000-1"
            future_revision = "5" * 40
            future_digest = f"{IMAGE_REPOSITORY}@sha256:{'6' * 64}"
            future_image_id = f"sha256:{'7' * 64}"
            future_container_id = "6" * 64
            state = harness.docker_state()
            state.update(
                {
                    "app_state": "old_running",
                    "old_container_id": CANDIDATE_CONTAINER_ID,
                    "old_digest": DIGEST,
                    "old_image_id": CANDIDATE_IMAGE_ID,
                    "old_revision": REVISION,
                    "started_at": state["candidate_started_at"],
                    "candidate_container_id": future_container_id,
                    "candidate_image_id": future_image_id,
                    "candidate_revision": future_revision,
                    "digest": future_digest,
                    "migration_exists": False,
                    "migration_running": False,
                }
            )
            harness._write_docker_state(state)
            harness.env["FAKE_OWNER"] = future_owner

            def future_operation(name: str) -> subprocess.CompletedProcess[str]:
                return harness.run(
                    name,
                    str(harness.compose_path),
                    future_digest,
                    future_revision,
                    owner=future_owner,
                )

            for operation in ("prepare", "publish", "quiesce", "migrate", "start"):
                harness.assert_success(future_operation(operation))
            self.assertEqual(2, harness.docker_state()["migration_invocations"])

            failure_configurations = (
                ("file-fsync", lambda: harness.configure_sync_failure(
                    kind="file",
                    contains="active-candidate.anchor.tmp.",
                )),
                ("rename", lambda: harness.configure_mv_failure("active-candidate.anchor")),
                ("parent-sync", lambda: harness.configure_sync_failure(
                    kind="directory",
                    suffix=str(harness.state_parent),
                )),
            )
            for failure_name, configure_failure in failure_configurations:
                with self.subTest(anchor_failure=failure_name):
                    configure_failure()
                    failed = future_operation("cleanup")
                    self.assertNotEqual(0, failed.returncode)
                    self.assertEqual(previous_anchor, harness.active_anchor.read_bytes())
                    self.assertTrue(previous_ledger.is_file())
                    self.assertEqual(2, harness.docker_state()["migration_invocations"])
                    self.assertEqual(2, harness.docker_state()["start_invocations"])
                    self.assertEqual("candidate_running", harness.docker_state()["app_state"])
                    harness.clear_sync_failure()
                    harness.clear_mv_failure()

            harness.assert_success(future_operation("cleanup"))
            self.assertNotEqual(previous_anchor, harness.active_anchor.read_bytes())
            anchor = parse_record(harness.active_anchor)
            self.assertEqual(future_revision, anchor["expected_revision"])
            self.assertEqual(future_digest, anchor["image_digest"])
            self.assertEqual(f"{future_owner}.ledger", anchor["migration_ledger_key"])
            harness.simulate_process_reboot()
            same_candidate = future_operation("prepare")
            self.assertNotEqual(0, same_candidate.returncode)
            self.assertEqual(2, harness.docker_state()["migration_invocations"])

            old = int(time.time()) - 31 * 24 * 60 * 60
            os.utime(previous_ledger, (old - 2, old - 2))
            os.utime(harness.ledger_dir / f"{OWNER}.outcome", (old - 2, old - 2))
            os.utime(harness.result_dir / f"{OWNER}.completed", (old - 2, old - 2))
            for index in range(32):
                harness.write_terminal_abort_artifact(
                    f"35000-{index + 1}",
                    mtime=old + 1000,
                )
            retained = harness.run(
                "retention",
                str(harness.compose_path),
                future_digest,
                future_revision,
                owner=future_owner,
                timeout=300,
            )
            harness.assert_success(retained)
            self.assertFalse(previous_ledger.exists())
            self.assertTrue(
                (harness.ledger_dir / f"{future_owner}.ledger").is_file()
            )

    def test_malformed_or_symlinked_anchor_blocks_pruning(self) -> None:
        for variant in ("malformed", "symlink"):
            with self.subTest(anchor=variant), RemoteHarness() as harness:
                harness.progress_to("candidate_healthy")
                harness.assert_success(harness.operation("cleanup"))
                unrelated = harness.state_parent / "unrelated.keep"
                unrelated.write_text("keep", encoding="utf-8")
                if variant == "malformed":
                    harness.active_anchor.write_text("anchor_version=1\n", encoding="utf-8")
                    harness.active_anchor.chmod(0o600)
                else:
                    held = harness.state_parent / "anchor-held"
                    harness.active_anchor.rename(held)
                    harness.active_anchor.symlink_to(held)
                result = harness.run(
                    "retention",
                    str(harness.compose_path),
                    DIGEST,
                    REVISION,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("keep", unrelated.read_text(encoding="utf-8"))


class RetentionPolicyTest(unittest.TestCase):
    retention_seconds = 30 * 24 * 60 * 60

    def run_retention(self, harness: RemoteHarness) -> subprocess.CompletedProcess[str]:
        return harness.run(
            "retention",
            str(harness.compose_path),
            DIGEST,
            REVISION,
            timeout=300,
        )

    def test_old_terminal_artifacts_pruned_at_count_and_age_boundary(self) -> None:
        with RemoteHarness() as harness:
            old = int(time.time()) - self.retention_seconds - 1000
            owners = [f"20000-{index + 1}" for index in range(34)]
            for index, artifact_owner in enumerate(owners):
                harness.write_terminal_abort_artifact(
                    artifact_owner,
                    mtime=old + index,
                    helper=index == 0,
                )
            unrelated = harness.volatile_root / "operator-note.txt"
            unrelated.write_text("unrelated", encoding="utf-8")
            before_unrelated = unrelated.read_bytes()

            first = self.run_retention(harness)

            harness.assert_success(first)
            for artifact_owner in owners[:2]:
                self.assertFalse((harness.result_dir / f"{artifact_owner}.result").exists())
                self.assertFalse((harness.result_dir / f"{artifact_owner}.completed").exists())
            for artifact_owner in owners[2:]:
                self.assertTrue((harness.result_dir / f"{artifact_owner}.completed").is_file())
            self.assertFalse(
                (harness.volatile_root / f"clubs-bot-release-{owners[0]}.sh").exists()
            )
            self.assertEqual(before_unrelated, unrelated.read_bytes())
            paths_after_first = sorted(
                str(path.relative_to(harness.state_root))
                for path in harness.state_root.rglob("*")
            )

            second = self.run_retention(harness)

            harness.assert_success(second)
            self.assertEqual(
                paths_after_first,
                sorted(
                    str(path.relative_to(harness.state_root))
                    for path in harness.state_root.rglob("*")
                ),
            )
            self.assertEqual(before_unrelated, unrelated.read_bytes())

    def test_recent_terminal_and_incomplete_or_current_artifacts_are_preserved(self) -> None:
        with RemoteHarness() as harness:
            now = int(time.time())
            recent_owners = [f"21000-{index + 1}" for index in range(33)]
            for index, artifact_owner in enumerate(recent_owners):
                harness.write_terminal_abort_artifact(
                    artifact_owner,
                    mtime=now - 100 + index,
                )
            old = now - self.retention_seconds - 1000
            incomplete_owner = "22000-1"
            harness.write_terminal_abort_artifact(
                incomplete_owner,
                mtime=old - 2,
                incomplete=True,
            )
            harness.write_terminal_abort_artifact(OWNER, mtime=old - 3)

            result = self.run_retention(harness)

            harness.assert_success(result)
            for artifact_owner in recent_owners:
                self.assertTrue((harness.result_dir / f"{artifact_owner}.completed").is_file())
            self.assertTrue((harness.result_dir / f"{incomplete_owner}.result").is_file())
            self.assertTrue((harness.result_dir / f"{incomplete_owner}.completed").is_file())
            self.assertTrue((harness.result_dir / f"{OWNER}.completed").is_file())

    def test_active_candidate_anchor_beats_clock_count_and_timestamp_ties(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("candidate_healthy")
            harness.assert_success(harness.operation("cleanup"))
            self.assertTrue(harness.active_anchor.is_file())
            now = int(time.time())
            old = now - self.retention_seconds - 2000
            tied_epoch = 1_700_000_000
            tied_mtime = old
            active_ledger = parse_record(harness.ledger_dir / f"{OWNER}.ledger")
            active_ledger["completed_epoch"] = str(tied_epoch)
            harness._write_record(
                harness.ledger_dir / f"{OWNER}.ledger",
                active_ledger,
                tied_mtime,
            )
            for active_path in (
                harness.ledger_dir / f"{OWNER}.outcome",
                harness.result_dir / f"{OWNER}.result",
                harness.result_dir / f"{OWNER}.completed",
            ):
                os.utime(active_path, (tied_mtime, tied_mtime))
            for index in range(32):
                harness.write_terminal_abort_artifact(
                    f"23000-{index + 1}",
                    mtime=old + 1000,
                )
            tied_non_authoritative_owner = "99999-1"
            harness.write_terminal_migration_artifact(
                tied_non_authoritative_owner,
                mtime=tied_mtime,
                completed_epoch=tied_epoch,
                revision_digit="1",
                digest_digit="2",
            )
            anchor_before = harness.active_anchor.read_bytes()

            result = self.run_retention(harness)

            harness.assert_success(result)
            self.assertFalse(
                (harness.ledger_dir / f"{tied_non_authoritative_owner}.ledger").exists()
            )
            self.assertFalse(
                (harness.ledger_dir / f"{tied_non_authoritative_owner}.outcome").exists()
            )
            self.assertTrue((harness.ledger_dir / f"{OWNER}.ledger").is_file())
            self.assertTrue((harness.ledger_dir / f"{OWNER}.outcome").is_file())
            self.assertTrue((harness.result_dir / f"{OWNER}.completed").is_file())
            self.assertEqual(
                str(tied_epoch),
                parse_record(harness.ledger_dir / f"{OWNER}.ledger")["completed_epoch"],
            )
            self.assertEqual(
                tied_mtime,
                int((harness.result_dir / f"{OWNER}.completed").stat().st_mtime),
            )
            self.assertEqual(anchor_before, harness.active_anchor.read_bytes())
            harness.simulate_process_reboot()
            same_candidate = harness.operation("prepare")
            self.assertNotEqual(0, same_candidate.returncode)
            self.assertEqual(1, harness.docker_state()["migration_invocations"])

    def test_symlink_or_malformed_terminal_artifact_is_rejected_without_broad_cleanup(self) -> None:
        variants = ("symlink-helper", "malformed-receipt")
        for variant in variants:
            with self.subTest(retention_rejection=variant), RemoteHarness() as harness:
                now = int(time.time())
                old = now - self.retention_seconds - 2000
                for index in range(32):
                    harness.write_terminal_abort_artifact(
                        f"25000-{index + 1}",
                        mtime=old + 1000 + index,
                    )
                rejected_owner = "26000-1"
                harness.write_terminal_abort_artifact(rejected_owner, mtime=old)
                unrelated = harness.volatile_root / "unrelated.keep"
                unrelated.write_text("keep", encoding="utf-8")
                if variant == "symlink-helper":
                    helper = harness.volatile_root / f"clubs-bot-release-{rejected_owner}.sh"
                    helper.symlink_to(unrelated)
                else:
                    malformed = harness.result_dir / f"{rejected_owner}.completed"
                    malformed.write_text("malformed=true\n", encoding="utf-8")
                    malformed.chmod(0o600)

                result = self.run_retention(harness)

                self.assertNotEqual(0, result.returncode)
                self.assertTrue((harness.result_dir / f"{rejected_owner}.completed").exists())
                self.assertEqual(b"keep", unrelated.read_bytes())


class PersistentRootAndBindingTest(unittest.TestCase):
    def assert_no_mutating_effects(self, harness: RemoteHarness) -> None:
        self.assertEqual([], harness.lifecycle_commands())
        self.assertEqual(0, harness.docker_state()["migration_invocations"])
        self.assertEqual(0, harness.docker_state()["start_invocations"])

    def test_volatile_paths_reject_before_authority(self) -> None:
        with RemoteHarness() as volatile:
            volatile.clear_command_logs()
            for environment in ("stage", "prod"):
                with self.subTest(environment=environment, root="tmp"):
                    result = volatile.preflight(environment=environment, owner="51000-1")
                    self.assertNotEqual(0, result.returncode)
                    self.assertFalse(
                        (volatile.compose_path / ".clubs-bot-release-state").exists()
                    )
            for rejected_path in ("/run", "/dev/shm"):
                with self.subTest(root=rejected_path):
                    result = volatile.run(
                        "preflight",
                        rejected_path,
                        IMAGE_REPOSITORY,
                        "candidate",
                        REVISION,
                        "fixture-bot",
                        environment="stage",
                        owner="51000-2",
                        input_text="fixture-registry-token\n",
                    )
                    self.assertNotEqual(0, result.returncode)
            self.assert_no_mutating_effects(volatile)

    def test_mount_detector_accepts_every_allowlisted_filesystem_from_one_coherent_record(
        self,
    ) -> None:
        expected_prefix = [
            "--noheadings",
            "--pairs",
            "--output",
            "FSTYPE,SOURCE,FSROOT,TARGET",
            "--target",
        ]
        for filesystem_type in ("ext2", "ext3", "ext4", "xfs", "btrfs", "zfs", "f2fs"):
            with self.subTest(accepted_filesystem=filesystem_type), RemoteHarness(
                app_env="stage",
                filesystem_type=filesystem_type,
                auto_preflight=False,
            ) as harness:
                result = harness.preflight()
                harness.assert_success(result)
                binding = parse_record(harness.application_binding)
                self.assertEqual("3", binding["binding_version"])
                self.assertEqual("2", binding["mount_fingerprint_version"])
                self.assertRegex(binding["mount_fingerprint"], r"^mount-v2:[0-9a-f]{64}$")
                commands = harness.findmnt_commands()
                self.assertTrue(commands)
                self.assertTrue(
                    all(command[:5] == expected_prefix and len(command) == 6 for command in commands),
                    commands,
                )
                # Preflight intentionally validates the Compose root twice at two
                # trust boundaries. Each identity read is one coherent command;
                # the fake rejects the former four-call field-by-field protocol.
                compose_identity_reads = [
                    command for command in commands if command[5] == str(harness.compose_path)
                ]
                self.assertEqual(2, len(compose_identity_reads), commands)
                fingerprint = binding["mount_fingerprint"]

                harness.clear_command_logs()
                status = harness.status("preflight")
                self.assertEqual(0, status.returncode, status.stderr)
                self.assertIn("status_available=yes", status.stdout)
                self.assertEqual(fingerprint, parse_record(harness.application_binding)["mount_fingerprint"])
                self.assertTrue(
                    all(
                        command[:5] == expected_prefix and len(command) == 6
                        for command in harness.findmnt_commands()
                    )
                )
                self.assertEqual(0, harness.docker_state()["migration_invocations"])
                self.assertEqual(0, harness.docker_state()["start_invocations"])

    def test_mount_detector_failures_and_unsupported_types_reject_before_authority(self) -> None:
        for filesystem_type in (
            "tmpfs",
            "ramfs",
            "devtmpfs",
            "overlay",
            "unknownfs",
        ):
            with self.subTest(filesystem_type=filesystem_type), RemoteHarness(
                app_env="stage",
                filesystem_type=filesystem_type,
                auto_preflight=False,
            ) as unsupported:
                unsupported.clear_command_logs()
                result = unsupported.preflight()
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(unsupported.state_parent.exists())
                self.assert_no_mutating_effects(unsupported)

        for behavior in (
            "failure",
            "empty",
            "multiple",
            "extra-fields",
            "missing-fstype",
            "missing-source",
            "missing-fsroot",
            "missing-target",
            "duplicate-field",
            "reordered-fields",
            "malformed-escaping",
            "malformed-pairs",
        ):
            with self.subTest(findmnt_behavior=behavior), RemoteHarness(
                app_env="stage",
                auto_preflight=False,
            ) as malformed:
                malformed.configure_findmnt_behavior(behavior)
                malformed.clear_command_logs()
                result = malformed.preflight()
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(malformed.state_parent.exists())
                self.assert_no_mutating_effects(malformed)

    def test_mount_fingerprint_is_collision_safe_and_old_binding_format_rejects(self) -> None:
        fingerprints: list[str] = []
        collision_records = (
            ("device|/branch", "/leaf"),
            ("device", "/branch|/leaf"),
        )
        for source, filesystem_root in collision_records:
            with RemoteHarness(app_env="stage", auto_preflight=False) as harness:
                harness.configure_mount_identity(
                    harness.compose_path,
                    filesystem_type="ext4",
                    source=source,
                    filesystem_root=filesystem_root,
                    mount_target=Path("/approved-target"),
                )
                result = harness.preflight()
                harness.assert_success(result)
                fingerprints.append(parse_record(harness.application_binding)["mount_fingerprint"])
                self.assertNotIn(source, result.stdout + result.stderr)
                self.assertNotIn(filesystem_root, result.stdout + result.stderr)
        self.assertEqual(2, len(set(fingerprints)))

        with RemoteHarness(app_env="stage") as legacy:
            current = parse_record(legacy.application_binding)
            legacy.application_binding.write_text(
                "\n".join(
                    (
                        "binding_version=2",
                        f'environment={current["environment"]}',
                        f'compose_path_hash={current["compose_path_hash"]}',
                        f'mount_fingerprint={current["mount_fingerprint"].removeprefix("mount-v2:")}',
                        f'compose_project={current["compose_project"]}',
                        f'compose_service={current["compose_service"]}',
                    )
                ),
                encoding="utf-8",
            )
            legacy.application_binding.chmod(0o600)
            rejected = legacy.operation("prepare")
            self.assertNotEqual(0, rejected.returncode)
            self.assert_no_mutating_effects(legacy)

    def test_mixed_mount_snapshots_cannot_form_application_authority(self) -> None:
        with RemoteHarness(app_env="stage", auto_preflight=False) as harness:
            harness.configure_alternate_mount_identity()
            harness.configure_findmnt_behavior("mixed-snapshots")
            harness.clear_command_logs()

            result = harness.preflight()

            self.assertNotEqual(0, result.returncode)
            self.assertFalse(harness.application_binding.exists())
            self.assertFalse(harness.state_root.exists())
            commands = harness.findmnt_commands()
            self.assertGreaterEqual(len(commands), 2)
            self.assertTrue(
                all(
                    command[:5]
                    == [
                        "--noheadings",
                        "--pairs",
                        "--output",
                        "FSTYPE,SOURCE,FSROOT,TARGET",
                        "--target",
                    ]
                    and len(command) == 6
                    for command in commands
                ),
                commands,
            )
            self.assert_no_mutating_effects(harness)

    def test_symlinked_root_rejects_and_supported_persistent_fixture_is_accepted(self) -> None:
        with RemoteHarness(app_env="stage", auto_preflight=False) as supported:
            result = supported.preflight()
            supported.assert_success(result)
            self.assertEqual("stage", parse_record(supported.application_binding)["environment"])
            self.assertEqual(0o700, mode(supported.state_parent))
            self.assertEqual(0o700, mode(supported.state_root))

        with RemoteHarness(app_env="stage", auto_preflight=False) as symlinked:
            real_root = symlinked.root / "real-compose"
            symlinked.compose_path.rename(real_root)
            symlinked.compose_path.symlink_to(real_root, target_is_directory=True)
            symlinked.clear_command_logs()
            result = symlinked.preflight()
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((real_root / ".clubs-bot-release-state").exists())
            self.assert_no_mutating_effects(symlinked)

    def test_one_compose_root_binds_exactly_one_environment(self) -> None:
        for first_environment, rejected_environment in (("stage", "prod"), ("prod", "stage")):
            with self.subTest(first=first_environment), RemoteHarness(
                app_env=first_environment
            ) as harness:
                before_override = (harness.compose_path / "docker-compose.override.yml").read_bytes()
                harness.clear_command_logs()
                first_prepare = harness.run(
                    "prepare",
                    str(harness.compose_path),
                    DIGEST,
                    REVISION,
                )
                harness.assert_success(first_prepare)
                conflicting_owner = harness.run(
                    "prepare",
                    str(harness.compose_path),
                    DIGEST,
                    REVISION,
                    owner="52000-9",
                )
                self.assertNotEqual(0, conflicting_owner.returncode)
                rejected = harness.preflight(
                    environment=rejected_environment,
                    owner="52000-1",
                )
                self.assertNotEqual(0, rejected.returncode)
                self.assertEqual(
                    first_environment,
                    parse_record(harness.application_binding)["environment"],
                )
                self.assertFalse((harness.state_parent / rejected_environment).exists())
                self.assertEqual(
                    before_override,
                    (harness.compose_path / "docker-compose.override.yml").read_bytes(),
                )
                self.assert_no_mutating_effects(harness)

        with RemoteHarness(app_env="stage") as stage_root, RemoteHarness(app_env="prod") as prod_root:
            self.assertEqual("stage", parse_record(stage_root.application_binding)["environment"])
            self.assertEqual("prod", parse_record(prod_root.application_binding)["environment"])

    def test_concurrent_stage_prod_binding_has_one_winner_and_survives_restart(self) -> None:
        with RemoteHarness(app_env="stage", auto_preflight=False) as harness:
            harness.env["FAKE_FLOCK_REAL"] = "yes"

            def command(environment: str, owner: str) -> list[str]:
                return [
                    "bash",
                    str(REMOTE_HELPER),
                    "preflight",
                    owner,
                    environment,
                    str(harness.compose_path),
                    IMAGE_REPOSITORY,
                    "candidate",
                    REVISION,
                    "fixture-bot",
                ]

            processes = [
                subprocess.Popen(
                    command("stage", "53000-1"),
                    cwd=REPOSITORY_ROOT,
                    env=harness.env,
                    stdin=subprocess.PIPE,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                ),
                subprocess.Popen(
                    command("prod", "53000-2"),
                    cwd=REPOSITORY_ROOT,
                    env=harness.env,
                    stdin=subprocess.PIPE,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                ),
            ]
            results = [process.communicate("fixture-registry-token\n", timeout=60) for process in processes]
            return_codes = [process.returncode for process in processes]
            self.assertEqual(1, return_codes.count(0), (return_codes, results))
            binding = parse_record(harness.application_binding)
            winner = binding["environment"]
            self.assertIn(winner, {"stage", "prod"})
            self.assertTrue((harness.state_parent / winner).is_dir())
            self.assertFalse((harness.state_parent / ({"stage", "prod"} - {winner}).pop()).exists())
            binding_before = harness.application_binding.read_bytes()
            same_environment = harness.preflight(environment=winner, owner="53000-3")
            harness.assert_success(same_environment)
            self.assertEqual(binding_before, harness.application_binding.read_bytes())

    def test_binding_parent_sync_failure_and_malformed_binding_fail_closed(self) -> None:
        with RemoteHarness(app_env="stage", auto_preflight=False) as harness:
            harness.state_parent.mkdir(mode=0o700)
            application_lock = harness.state_parent / "application.lock"
            application_lock.write_bytes(b"")
            application_lock.chmod(0o600)
            harness.configure_sync_failure(
                kind="directory",
                suffix=".clubs-bot-release-state",
            )
            failed = harness.preflight()
            self.assertNotEqual(0, failed.returncode)
            self.assertFalse(harness.application_binding.exists())
            self.assertFalse(harness.state_root.exists())
            self.assert_no_mutating_effects(harness)

        for variant in ("malformed", "symlink"):
            with self.subTest(binding=variant), RemoteHarness(app_env="stage") as harness:
                before_override = (harness.compose_path / "docker-compose.override.yml").read_bytes()
                harness.clear_command_logs()
                if variant == "malformed":
                    harness.application_binding.write_text("binding_version=1\n", encoding="utf-8")
                    harness.application_binding.chmod(0o600)
                else:
                    held = harness.state_parent / "binding-held"
                    harness.application_binding.rename(held)
                    harness.application_binding.symlink_to(held)
                failed = harness.run(
                    "prepare",
                    str(harness.compose_path),
                    DIGEST,
                    REVISION,
                )
                self.assertNotEqual(0, failed.returncode)
                self.assertEqual(
                    before_override,
                    (harness.compose_path / "docker-compose.override.yml").read_bytes(),
                )
                self.assert_no_mutating_effects(harness)

    def test_binding_file_fsync_failure_has_no_false_authority(self) -> None:
        with RemoteHarness(app_env="stage", auto_preflight=False) as harness:
            override = harness.compose_path / "docker-compose.override.yml"
            before_override = override.read_bytes()
            harness.configure_sync_failure(
                kind="file",
                contains="application.binding.tmp.",
            )

            failed = harness.preflight()

            self.assertNotEqual(0, failed.returncode)
            self.assertFalse(harness.application_binding.exists())
            self.assertFalse(harness.state_root.exists())
            self.assertEqual([], list(harness.state_parent.glob("application.binding.tmp.*")))
            self.assertEqual(before_override, override.read_bytes())
            self.assert_no_mutating_effects(harness)

            harness.clear_sync_failure()
            harness.simulate_process_reboot()
            fresh = harness.preflight()
            harness.assert_success(fresh)
            self.assertTrue(harness.application_binding.is_file())
            self.assertEqual(0, harness.docker_state()["migration_invocations"])

    def test_binding_rename_failure_has_no_false_authority(self) -> None:
        with RemoteHarness(app_env="stage", auto_preflight=False) as harness:
            override = harness.compose_path / "docker-compose.override.yml"
            before_override = override.read_bytes()
            harness.configure_mv_failure("application.binding")

            failed = harness.preflight()

            self.assertNotEqual(0, failed.returncode)
            self.assertFalse(harness.application_binding.exists())
            self.assertFalse(harness.state_root.exists())
            self.assertEqual([], list(harness.state_parent.glob("application.binding.tmp.*")))
            self.assertEqual(before_override, override.read_bytes())
            self.assert_no_mutating_effects(harness)

            harness.clear_mv_failure()
            harness.simulate_process_reboot()
            fresh = harness.preflight()
            harness.assert_success(fresh)
            self.assertTrue(harness.application_binding.is_file())
            self.assertEqual(0, harness.docker_state()["migration_invocations"])

    def test_nested_authoritative_mounts_reject_mutation_status_and_cold_reentry(self) -> None:
        with RemoteHarness(app_env="stage", auto_preflight=False) as shared:
            override = shared.compose_path / "docker-compose.override.yml"
            before_override = override.read_bytes()
            shared.configure_mount_identity(
                shared.state_parent,
                filesystem_type="tmpfs",
                source="volatile-state",
            )
            failed = shared.preflight()

            self.assertNotEqual(0, failed.returncode)
            self.assertFalse(shared.application_binding.exists())
            self.assertFalse(shared.state_root.exists())
            self.assertEqual(before_override, override.read_bytes())
            self.assert_no_mutating_effects(shared)
            assert_untrusted_status_read_only(self, shared)
            shared.simulate_process_reboot()
            second = shared.preflight()
            self.assertNotEqual(0, second.returncode)
            self.assertFalse(shared.application_binding.exists())
            self.assertEqual(0, shared.docker_state()["migration_invocations"])

        for variant in (
            "shared-root",
            "environment-root",
            "environment-root-same-fstype",
            "ledger-root",
            "result-root",
            "anchor-file",
        ):
            with self.subTest(nested_mount=variant), RemoteHarness(app_env="stage") as harness:
                override = harness.compose_path / "docker-compose.override.yml"
                before_override = override.read_bytes()
                if variant == "shared-root":
                    nested_path = harness.state_parent
                elif variant in {"environment-root", "environment-root-same-fstype"}:
                    nested_path = harness.state_root
                elif variant == "ledger-root":
                    harness.ledger_dir.mkdir(mode=0o700)
                    nested_path = harness.ledger_dir
                elif variant == "result-root":
                    nested_path = harness.result_dir
                else:
                    harness.active_anchor.write_text("untrusted-anchor\n", encoding="utf-8")
                    harness.active_anchor.chmod(0o600)
                    nested_path = harness.active_anchor
                harness.configure_mount_identity(
                    nested_path,
                    filesystem_type=(
                        "ext4" if variant == "environment-root-same-fstype" else "tmpfs"
                    ),
                    source=f"volatile-{variant}",
                )
                before_tree = snapshot_tree(harness.compose_path)
                harness.clear_command_logs()

                failed = harness.operation("prepare")

                self.assertNotEqual(0, failed.returncode)
                self.assertEqual(before_tree, snapshot_tree(harness.compose_path))
                self.assertEqual(before_override, override.read_bytes())
                self.assert_no_mutating_effects(harness)
                assert_untrusted_status_read_only(self, harness)
                self.assert_no_mutating_effects(harness)

    def test_intermediate_parent_symlink_rejects_mutation_and_status(self) -> None:
        for depth in ("direct-parent", "nested-parent"):
            with self.subTest(symlink_depth=depth), RemoteHarness(
                app_env="stage",
                auto_preflight=False,
            ) as harness:
                real_parent = harness.root / f"real-{depth}"
                real_parent.mkdir()
                real_application = real_parent / "application"
                harness.compose_path.rename(real_application)
                if depth == "direct-parent":
                    linked_parent = harness.root / "link-parent"
                else:
                    trusted_parent = harness.root / "trusted-parent"
                    trusted_parent.mkdir()
                    linked_parent = trusted_parent / "link-parent"
                linked_parent.symlink_to(real_parent, target_is_directory=True)
                harness.compose_path = linked_parent / "application"
                harness.state_parent = harness.compose_path / ".clubs-bot-release-state"
                harness.state_root = harness.state_parent / "stage"
                before_tree = snapshot_tree(real_application)
                before_override = (real_application / "docker-compose.override.yml").read_bytes()
                harness.clear_command_logs()

                failed = harness.preflight()

                self.assertNotEqual(0, failed.returncode)
                self.assertEqual(before_tree, snapshot_tree(real_application))
                self.assertFalse(harness.state_parent.exists())
                self.assertEqual(
                    before_override,
                    (real_application / "docker-compose.override.yml").read_bytes(),
                )
                self.assert_no_mutating_effects(harness)
                assert_untrusted_status_read_only(self, harness)
                self.assert_no_mutating_effects(harness)


class StatusAndSecurityTest(unittest.TestCase):
    def assert_untrusted_status(self, harness: RemoteHarness) -> None:
        assert_untrusted_status_read_only(self, harness)

    def test_status_write_audit_detects_every_forbidden_category(self) -> None:
        with RemoteHarness() as harness:
            harness.clear_command_logs()
            environment = harness.env.copy()
            environment.update(
                {
                    "BASH_ENV": str(harness.status_write_audit),
                    "FAKE_STATUS_WRITE_AUDIT_ENABLED": "yes",
                    "FAKE_AUDIT_CALIBRATION_ROOT": str(harness.root / "audit-calibration"),
                }
            )
            calibration = subprocess.run(
                [
                    "bash",
                    "-c",
                    """
mkdir "$FAKE_AUDIT_CALIBRATION_ROOT"
touch "$FAKE_AUDIT_CALIBRATION_ROOT/a"
chmod 600 "$FAKE_AUDIT_CALIBRATION_ROOT/a"
mv "$FAKE_AUDIT_CALIBRATION_ROOT/a" "$FAKE_AUDIT_CALIBRATION_ROOT/b"
truncate -s 0 "$FAKE_AUDIT_CALIBRATION_ROOT/b"
sync "$FAKE_AUDIT_CALIBRATION_ROOT/b"
: >"$FAKE_AUDIT_CALIBRATION_ROOT/c"
rm "$FAKE_AUDIT_CALIBRATION_ROOT/b" "$FAKE_AUDIT_CALIBRATION_ROOT/c"
rmdir "$FAKE_AUDIT_CALIBRATION_ROOT"
prune_terminal_artifacts() { :; }
prune_terminal_artifacts
""",
                ],
                cwd=REPOSITORY_ROOT,
                env=environment,
                capture_output=True,
                text=True,
                check=False,
                timeout=20,
            )
            self.assertEqual(0, calibration.returncode, calibration.stderr)
            counts = harness.status_filesystem_write_counts()
            for category in counts:
                self.assertGreater(counts[category], 0, (category, counts))
            self.assertFalse((harness.root / "audit-calibration").exists())

    def test_status_rejects_every_untrusted_root_chain_without_writes(self) -> None:
        variants = (
            "shared-root-symlink",
            "environment-root-symlink",
            "compose-root-symlink",
            "shared-root-mode",
            "environment-root-mode",
            "malformed-binding",
            "wrong-environment-binding",
            "symlinked-binding",
            "unsupported-filesystem",
            "mount-detector-failure",
        )
        for variant in variants:
            with self.subTest(root_chain=variant), RemoteHarness(app_env="stage") as harness:
                if variant == "shared-root-symlink":
                    held = harness.compose_path / "release-state-held"
                    harness.state_parent.rename(held)
                    harness.state_parent.symlink_to(held, target_is_directory=True)
                elif variant == "environment-root-symlink":
                    held = harness.state_parent / "stage-held"
                    harness.state_root.rename(held)
                    harness.state_root.symlink_to(held, target_is_directory=True)
                elif variant == "compose-root-symlink":
                    held = harness.root / "compose-held"
                    harness.compose_path.rename(held)
                    harness.compose_path.symlink_to(held, target_is_directory=True)
                elif variant == "shared-root-mode":
                    harness.state_parent.chmod(0o755)
                elif variant == "environment-root-mode":
                    harness.state_root.chmod(0o755)
                elif variant == "malformed-binding":
                    harness.application_binding.write_text("malformed=true\n", encoding="utf-8")
                    harness.application_binding.chmod(0o600)
                elif variant == "wrong-environment-binding":
                    fields = parse_record(harness.application_binding)
                    fields["environment"] = "prod"
                    harness.application_binding.write_text(
                        "\n".join(f"{key}={value}" for key, value in fields.items()),
                        encoding="utf-8",
                    )
                    harness.application_binding.chmod(0o600)
                elif variant == "symlinked-binding":
                    held = harness.state_parent / "binding-held"
                    harness.application_binding.rename(held)
                    harness.application_binding.symlink_to(held)
                elif variant == "unsupported-filesystem":
                    harness.configure_mount_identity(
                        harness.compose_path,
                        filesystem_type="tmpfs",
                    )
                elif variant == "mount-detector-failure":
                    harness.configure_findmnt_behavior("failure")
                self.assert_untrusted_status(harness)

    def test_valid_persistent_root_status_is_truthful_and_read_only(self) -> None:
        with RemoteHarness(app_env="stage") as harness:
            harness.clear_command_logs()
            before = snapshot_authoritative_tree(harness)
            result = harness.status("preflight")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(before, snapshot_authoritative_tree(harness))
            self.assertIn("status_available=yes", result.stdout)
            self.assertIn("owner_match=yes", result.stdout)
            self.assertIn("failure_category=none", result.stdout)
            write_counts = harness.status_filesystem_write_counts()
            self.assertEqual({category: 0 for category in write_counts}, write_counts)
            self.assertEqual([], harness.lifecycle_commands())

    def test_authoritative_stage_root_is_compose_scoped_and_not_volatile(self) -> None:
        with RemoteHarness(app_env="stage", auto_preflight=False) as harness:
            stage_owner = "44444-1"
            result = subprocess.run(
                [
                    "bash",
                    str(REMOTE_HELPER),
                    "preflight",
                    stage_owner,
                    "stage",
                    str(harness.compose_path),
                    IMAGE_REPOSITORY,
                    "candidate",
                    REVISION,
                    "fixture-bot",
                ],
                cwd=REPOSITORY_ROOT,
                env=harness.env,
                input="fixture-registry-token\n",
                capture_output=True,
                text=True,
                check=False,
                timeout=20,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            durable_root = harness.compose_path / ".clubs-bot-release-state" / "stage"
            self.assertTrue(durable_root.is_dir() and not durable_root.is_symlink())
            self.assertEqual(0o700, mode(durable_root))
            self.assertTrue(
                (durable_root / "clubs-bot-schema-stage.results" / f"{stage_owner}.result").is_file()
            )
            self.assertFalse((harness.volatile_root / "clubs-bot-schema-stage.results").exists())

    def test_state_and_result_permissions_are_restricted(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("migration_completed")
            self.assertEqual(0o700, mode(harness.state_root))
            self.assertEqual(0o700, mode(harness.lock_dir))
            self.assertEqual(0o700, mode(harness.result_dir))
            self.assertEqual(0o700, mode(harness.ledger_dir))
            operation_lock = harness.result_dir / "operation.lock"
            self.assertEqual(0o600, mode(operation_lock))
            for path in harness.lock_dir.iterdir():
                self.assertTrue(path.is_file() and not path.is_symlink(), path)
                self.assertEqual(0o600, mode(path), path)
                self.assertLessEqual(path.stat().st_size, 4096, path)
            self.assertEqual(0o600, mode(harness.result_dir / f"{OWNER}.result"))
            self.assertEqual(0o600, mode(harness.ledger_dir / f"{OWNER}.ledger"))
            self.assertEqual(0o600, mode(harness.ledger_dir / f"{OWNER}.outcome"))
            self.assertEqual(0o600, mode(harness.compose_path / "docker-compose.override.yml"))

    def test_status_is_read_only_bounded_and_reports_permissions(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("candidate_override_published")
            before = snapshot_tree(harness.state_root)
            harness.clear_command_logs()
            result = harness.status("publish")
            after = snapshot_tree(harness.state_root)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(before, after)
            self.assertEqual("", result.stderr)
            self.assertRegex(
                result.stdout,
                r"^release-status:v=1 status_available=yes owner_match=yes revision_match=yes digest_match=yes "
                r"checkpoint=candidate_override_published operation_result=success migration_evidence=absent "
                r"app_state=old_running abort_permitted=yes resume_permitted=yes failure_category=none\n$",
            )
            for command in harness.lifecycle_commands():
                self.fail(f"status executed lifecycle command: {command}")
            flock_calls = harness.flock_log.read_text(encoding="utf-8").splitlines()
            self.assertEqual(["-s -n 6", "-s -n 8"], flock_calls)
            for forbidden in SENSITIVE_VALUES + (str(harness.compose_path), OLD_CONTAINER_ID, CANDIDATE_CONTAINER_ID):
                self.assertNotIn(forbidden, result.stdout + result.stderr)

    def test_status_reports_no_result_for_not_yet_invoked_operation(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("candidate_override_published")
            before = snapshot_tree(harness.state_root)

            result = harness.status("quiesce")

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(before, snapshot_tree(harness.state_root))
            self.assertIn("checkpoint=candidate_override_published", result.stdout)
            self.assertIn("operation_result=unavailable", result.stdout)
            self.assertIn("abort_permitted=yes", result.stdout)
            self.assertIn("resume_permitted=yes", result.stdout)

    def test_status_fails_closed_when_shared_snapshot_lock_is_unavailable(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("candidate_override_published")
            harness.clear_command_logs()
            harness.env["FAKE_FLOCK_FAIL"] = "yes"

            result = harness.status("publish")

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("operation_result=unavailable", result.stdout)
            self.assertIn("app_state=unknown", result.stdout)
            self.assertIn("abort_permitted=no", result.stdout)
            self.assertIn("resume_permitted=no", result.stdout)
            self.assertEqual(["-s -n 6"], harness.flock_log.read_text(encoding="utf-8").splitlines())
            self.assertEqual([], harness.lifecycle_commands())

    def test_status_fails_closed_and_does_not_recreate_missing_snapshot_lock(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("candidate_override_published")
            operation_lock = harness.result_dir / "operation.lock"
            operation_lock.unlink()
            harness.clear_command_logs()

            result = harness.status("publish")

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertFalse(operation_lock.exists())
            self.assertIn("operation_result=unavailable", result.stdout)
            self.assertIn("app_state=unknown", result.stdout)
            self.assertIn("abort_permitted=no", result.stdout)
            self.assertIn("resume_permitted=no", result.stdout)
            self.assertEqual(["-s -n 6"], harness.flock_log.read_text(encoding="utf-8").splitlines())
            self.assertEqual([], harness.lifecycle_commands())

    def test_status_fails_closed_and_does_not_recreate_missing_result_directory(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("candidate_override_published")
            moved_result_dir = harness.root / "operator-held-results"
            harness.result_dir.rename(moved_result_dir)
            harness.clear_command_logs()

            result = harness.status("publish")

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertFalse(harness.result_dir.exists())
            self.assertTrue(moved_result_dir.is_dir())
            self.assertIn("operation_result=unavailable", result.stdout)
            self.assertIn("app_state=unknown", result.stdout)
            self.assertIn("abort_permitted=no", result.stdout)
            self.assertIn("resume_permitted=no", result.stdout)
            self.assertEqual(["-s -n 6"], harness.flock_log.read_text(encoding="utf-8").splitlines())
            self.assertEqual([], harness.lifecycle_commands())

    def test_unexpected_migration_evidence_disables_abort_and_resume(self) -> None:
        for evidence_kind in ("state-file", "migration-container"):
            with self.subTest(evidence=evidence_kind), RemoteHarness() as harness:
                harness.progress_to("candidate_override_published")
                if evidence_kind == "state-file":
                    harness.write_state_value("migration_image_digest", DIGEST)
                else:
                    harness.update_docker_state(migration_exists=True)
                result = harness.status("publish")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn("migration_evidence=present", result.stdout)
                self.assertIn("abort_permitted=no", result.stdout)
                self.assertIn("resume_permitted=no", result.stdout)
                harness.clear_command_logs()
                resumed = harness.resume("quiesce")
                self.assertNotEqual(0, resumed.returncode)
                self.assertEqual("candidate_override_published", harness.checkpoint())
                self.assertEqual([], harness.lifecycle_commands())

    def test_wrong_same_owner_digest_cannot_replace_prior_durable_result(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("prior_state_captured")
            result_path = harness.result_dir / f"{OWNER}.result"
            before = result_path.read_bytes()
            wrong_digest = f"{IMAGE_REPOSITORY}@sha256:{'6' * 64}"
            attempted = harness.run(
                "publish",
                str(harness.compose_path),
                wrong_digest,
                REVISION,
            )
            self.assertNotEqual(0, attempted.returncode)
            self.assertEqual(before, result_path.read_bytes())

    def test_status_rejects_wrong_identity_and_malformed_result(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("candidate_override_published")
            wrong_owner = harness.status("publish", owner="999-9")
            self.assertEqual(0, wrong_owner.returncode)
            self.assertIn("owner_match=no", wrong_owner.stdout)
            self.assertIn("abort_permitted=no", wrong_owner.stdout)
            self.assertIn("resume_permitted=no", wrong_owner.stdout)

            wrong_revision = harness.status("publish", revision="9" * 40)
            self.assertEqual(0, wrong_revision.returncode)
            self.assertIn("revision_match=no", wrong_revision.stdout)
            self.assertIn("abort_permitted=no", wrong_revision.stdout)

            wrong_digest = harness.status(
                "publish",
                digest=f"{IMAGE_REPOSITORY}@sha256:{'6' * 64}",
            )
            self.assertEqual(0, wrong_digest.returncode)
            self.assertIn("digest_match=no", wrong_digest.stdout)
            self.assertIn("resume_permitted=no", wrong_digest.stdout)

            record = harness.result_dir / f"{OWNER}.result"
            record.write_text("malformed-result-record\n", encoding="utf-8")
            record.chmod(0o600)
            malformed = harness.status("publish")
            self.assertEqual(0, malformed.returncode)
            self.assertIn("operation_result=malformed", malformed.stdout)
            self.assertIn("abort_permitted=no", malformed.stdout)
            self.assertIn("resume_permitted=no", malformed.stdout)

    def test_remote_failure_records_and_outputs_exclude_untrusted_data(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("app_quiesced")
            harness.update_docker_state(
                migration_log=(
                    "migration-safe:v=1 event=started\n"
                    f"{SENSITIVE_TEXT}\n"
                    "migration-safe:v=1 event=completed applied=1\n"
                )
            )
            result = harness.operation("migrate")
            self.assertNotEqual(0, result.returncode)
            combined = result.stdout + result.stderr
            records = b"".join(
                path.read_bytes()
                for path in harness.state_root.rglob("*")
                if path.is_file()
            ).decode("utf-8", errors="replace")
            for forbidden in SENSITIVE_VALUES + (
                str(harness.compose_path),
                OLD_CONTAINER_ID,
                MIGRATION_CONTAINER_ID,
                CANDIDATE_CONTAINER_ID,
            ):
                self.assertNotIn(forbidden, combined)
                self.assertNotIn(forbidden, records)
            self.assertIn("raw output suppressed", result.stderr)

    def test_atomic_filesystem_failure_suppresses_arbitrary_child_stderr(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("prior_state_captured")
            harness.clear_command_logs()
            harness.configure_mv_failure("/docker-compose.override.yml")
            result_path = harness.result_dir / f"{OWNER}.result"

            result = harness.operation("publish")

            self.assertNotEqual(0, result.returncode)
            combined = result.stdout + result.stderr
            for forbidden in SENSITIVE_VALUES:
                self.assertNotIn(forbidden, combined)
            record = harness.result()
            self.assertEqual("publish", record["requested_operation"])
            self.assertEqual("prior_state_captured", record["checkpoint_before"])
            self.assertEqual("prior_state_captured", record["checkpoint_after"])
            self.assertEqual("remote_failure", record["result"])
            self.assertEqual("override_invalid", record["failure_category"])
            self.assertEqual(OWNER, record["owner"])
            self.assertEqual(REVISION, record["expected_revision"])
            self.assertEqual(DIGEST, record["image_digest"])
            self.assertEqual(
                hashlib.sha256(str(harness.compose_path).encode()).hexdigest(),
                record["compose_path_hash"],
            )
            result_renames = [
                (source, target)
                for source, target in harness.rename_entries()
                if target == result_path
            ]
            self.assertEqual(2, len(result_renames), result_renames)
            self.assertTrue(
                all(".result.tmp." in source.name for source, _ in result_renames),
                result_renames,
            )
            terminal_inode = result_path.stat().st_ino
            terminal_hash = hashlib.sha256(result_path.read_bytes()).hexdigest()
            self.assertEqual({result_path}, set(harness.result_dir.glob(f"{OWNER}.result*")))
            self.assertEqual(
                [],
                list(harness.volatile_root.glob(f".clubs-release-previous.{OWNER}.*")),
            )
            self.assertEqual(terminal_inode, result_path.stat().st_ino)
            self.assertEqual(terminal_hash, hashlib.sha256(result_path.read_bytes()).hexdigest())

    def test_remote_child_exit_255_is_recorded_as_child_failure(self) -> None:
        with RemoteHarness() as harness:
            harness.progress_to("candidate_override_published")
            harness.clear_command_logs()
            harness.fail_docker("compose_stop", 255)
            result = harness.operation("quiesce")
            self.assertEqual(255, result.returncode)
            record = harness.result()
            self.assertEqual("remote_failure", record["result"])
            self.assertEqual("child_exit_255", record["failure_category"])
            self.assertEqual("app_stop_intent", record["checkpoint_after"])
            stop_commands = [
                command for command in harness.lifecycle_commands() if "stop" in command
            ]
            self.assertEqual(1, len(stop_commands), stop_commands)
            self.assertEqual(0, harness.docker_state()["migration_invocations"])
            self.assertEqual(0, harness.docker_state()["start_invocations"])
            for forbidden in SENSITIVE_VALUES:
                self.assertNotIn(forbidden, result.stdout + result.stderr)

        with self.subTest(terminal_result_persistence="rename"), RemoteHarness() as harness:
            harness.progress_to("candidate_override_published")
            harness.clear_command_logs()
            harness.configure_mv_failure(".result", match_at=2)
            harness.fail_docker("compose_stop", 61)
            result_path = harness.result_dir / f"{OWNER}.result"

            result = harness.operation("quiesce")

            self.assertEqual(61, result.returncode)
            self.assertNotIn("result=success", result.stdout)
            self.assertEqual(
                {
                    "result": "incomplete_unknown",
                    "failure_category": "operation_in_progress",
                    "checkpoint_after": "unavailable",
                },
                {
                    key: harness.result()[key]
                    for key in ("result", "failure_category", "checkpoint_after")
                },
            )
            result_renames = [
                (source, target)
                for source, target in harness.rename_entries()
                if target == result_path
            ]
            self.assertEqual(2, len(result_renames), result_renames)
            stop_commands = [
                command for command in harness.lifecycle_commands() if "stop" in command
            ]
            self.assertEqual(1, len(stop_commands), stop_commands)
            self.assertEqual(0, harness.docker_state()["migration_invocations"])
            self.assertEqual(0, harness.docker_state()["start_invocations"])
            self.assertEqual({result_path}, set(harness.result_dir.glob(f"{OWNER}.result*")))
            self.assertEqual(
                [],
                list(harness.volatile_root.glob(f".clubs-release-previous.{OWNER}.*")),
            )
            for forbidden in SENSITIVE_VALUES:
                self.assertNotIn(forbidden, result.stdout + result.stderr)


class RunnerClassificationTest(unittest.TestCase):
    def assert_redacted(self, result: subprocess.CompletedProcess[str]) -> None:
        combined = result.stdout + result.stderr
        for forbidden in SENSITIVE_VALUES:
            self.assertNotIn(forbidden, combined)

    def assert_one_mutation_and_one_status(self, harness: RunnerHarness, mode_name: str) -> None:
        counts = harness.mode_counts()
        self.assertEqual(1, counts.get(mode_name), counts)
        self.assertEqual(1, counts.get("status"), counts)
        self.assertFalse(set(counts).intersection({"abort", "resume"}), counts)

    def test_runner_rejects_volatile_compose_root_before_upload_or_ssh(self) -> None:
        with RunnerHarness() as harness:
            harness.env["COMPOSE_PATH"] = "/tmp/clubs-bot-compose"
            result = harness.run()
            self.assertEqual(2, result.returncode)
            self.assertEqual([], harness.ssh_entries())
            self.assertEqual("", harness.scp_log.read_text(encoding="utf-8"))
            self.assert_redacted(result)

    def test_success_path_executes_each_mutating_remote_operation_once(self) -> None:
        with RunnerHarness() as harness:
            result = harness.run()
            self.assertEqual(0, result.returncode, result.stderr)
            counts = harness.mode_counts()
            for expected_mode in (
                "preflight",
                "prepare",
                "publish",
                "quiesce",
                "migrate",
                "start",
                "cleanup",
                "helper-cleanup",
            ):
                self.assertEqual(1, counts.get(expected_mode), counts)
            self.assertNotIn("status", counts)
            self.assertNotIn("abort", counts)
            self.assertNotIn("resume", counts)
            self.assertTrue(all(not entry["registry_token_present"] for entry in harness.ssh_entries()))
            self.assert_redacted(result)

    def test_cleanup_acknowledgement_loss_retains_current_helper_for_pruning(self) -> None:
        line = status_line(
            checkpoint="cleanup_completed",
            result="success",
            migration_evidence="present",
            app_state="unknown",
            abort_permitted="no",
            resume_permitted="no",
        )
        with RunnerHarness(
            fail_mode="cleanup",
            failure_behavior="exit255",
            status_stdout=line,
        ) as harness:
            result = harness.run()
            self.assertEqual(1, result.returncode)
            self.assertIn("outcome=completed_but_acknowledgement_lost", result.stderr)
            counts = harness.mode_counts()
            self.assertEqual(1, counts.get("cleanup"), counts)
            self.assertEqual(1, counts.get("status"), counts)
            self.assertNotIn("helper-cleanup", counts)
            self.assert_redacted(result)

    def test_remote_exit_one_is_confirmed_without_retry(self) -> None:
        with RunnerHarness(fail_mode="prepare", failure_behavior="exit1") as harness:
            result = harness.run()
            self.assertEqual(1, result.returncode)
            self.assertIn("outcome=confirmed_remote_failure operation=prepare", result.stderr)
            counts = harness.mode_counts()
            self.assertEqual(1, counts.get("prepare"), counts)
            self.assertNotIn("status", counts)
            self.assert_redacted(result)

    def test_remote_child_exit_255_is_distinguished_by_durable_result(self) -> None:
        line = status_line(checkpoint="app_stop_intent", result="remote_failure")
        with RunnerHarness(
            fail_mode="quiesce",
            failure_behavior="exit255",
            status_stdout=line,
        ) as harness:
            result = harness.run()
            self.assertEqual(1, result.returncode)
            self.assertIn("outcome=confirmed_remote_failure operation=quiesce", result.stderr)
            self.assert_one_mutation_and_one_status(harness, "quiesce")
            self.assert_redacted(result)

    def test_transport_loss_after_incomplete_result_is_fail_closed(self) -> None:
        line = status_line(
            checkpoint="candidate_override_published",
            result="incomplete_unknown",
        )
        with RunnerHarness(
            fail_mode="quiesce",
            failure_behavior="exit255",
            status_stdout=line,
        ) as harness:
            result = harness.run()
            self.assertEqual(1, result.returncode)
            self.assertIn("outcome=transport_loss_with_durable_checkpoint", result.stderr)
            self.assertIn("recovery=explicit-abort", result.stderr)
            self.assert_one_mutation_and_one_status(harness, "quiesce")
            self.assert_redacted(result)

    def test_transport_loss_before_result_record_uses_prior_checkpoint(self) -> None:
        line = status_line(
            checkpoint="candidate_override_published",
            result="unavailable",
        )
        with RunnerHarness(
            fail_mode="quiesce",
            failure_behavior="exit255",
            status_stdout=line,
        ) as harness:
            result = harness.run()
            self.assertEqual(1, result.returncode)
            self.assertIn("outcome=transport_loss_with_durable_checkpoint", result.stderr)
            self.assertIn("recovery=explicit-abort", result.stderr)
            self.assert_one_mutation_and_one_status(harness, "quiesce")
            self.assert_redacted(result)

    def test_lost_acknowledgement_after_remote_success_is_classified(self) -> None:
        line = status_line(
            checkpoint="app_quiesced",
            result="success",
            app_state="absent",
            abort_permitted="no",
        )
        with RunnerHarness(
            fail_mode="quiesce",
            failure_behavior="exit255",
            status_stdout=line,
        ) as harness:
            result = harness.run()
            self.assertEqual(1, result.returncode)
            self.assertIn("outcome=completed_but_acknowledgement_lost", result.stderr)
            self.assertIn("recovery=explicit-resume-migrate", result.stderr)
            self.assert_one_mutation_and_one_status(harness, "quiesce")
            self.assert_redacted(result)

    def test_status_query_unavailable_stops_with_one_attempt(self) -> None:
        with RunnerHarness(
            fail_mode="publish",
            failure_behavior="exit255",
            status_exit=255,
        ) as harness:
            result = harness.run()
            self.assertEqual(1, result.returncode)
            self.assertIn("outcome=status_unavailable operation=publish", result.stderr)
            self.assert_one_mutation_and_one_status(harness, "publish")
            self.assert_redacted(result)

    def test_malformed_status_and_malformed_acknowledgement_fail_closed(self) -> None:
        variants = (
            ("malformed status output", "exit255"),
            (status_line(checkpoint="app_stop_intent", result="malformed"), "malformed_ack"),
        )
        for response, behavior in variants:
            with self.subTest(behavior=behavior), RunnerHarness(
                fail_mode="prepare",
                failure_behavior=behavior,
                status_stdout=response,
            ) as harness:
                result = harness.run()
                self.assertEqual(1, result.returncode)
                self.assertIn("outcome=status_unavailable operation=prepare", result.stderr)
                self.assert_one_mutation_and_one_status(harness, "prepare")
                self.assert_redacted(result)

    def test_wrong_owner_revision_or_digest_status_fails_closed(self) -> None:
        mismatch_lines = (
            status_line(checkpoint="app_stop_intent", result="remote_failure", owner_match="no"),
            status_line(checkpoint="app_stop_intent", result="remote_failure", revision_match="no"),
            status_line(checkpoint="app_stop_intent", result="remote_failure", digest_match="no"),
        )
        for line in mismatch_lines:
            with self.subTest(status=line), RunnerHarness(
                fail_mode="prepare",
                failure_behavior="exit255",
                status_stdout=line,
            ) as harness:
                result = harness.run()
                self.assertEqual(1, result.returncode)
                self.assertIn("outcome=status_unavailable operation=prepare", result.stderr)
                self.assert_one_mutation_and_one_status(harness, "prepare")
                self.assert_redacted(result)

    def test_scp_failure_is_redacted_and_does_not_attempt_ssh(self) -> None:
        with RunnerHarness(scp_exit=255) as harness:
            result = harness.run()
            self.assertEqual(1, result.returncode)
            self.assertIn("outcome=status_unavailable operation=upload", result.stderr)
            self.assertEqual([], harness.ssh_entries())
            self.assert_redacted(result)


STRICT_CI_ARGUMENT = "--strict-ci"
EXPECTED_TEST_COUNT = 73


def run_strict_ci_suite() -> int:
    suite = unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__])
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    outcome_counts = {
        "testsRun": result.testsRun,
        "failures": len(result.failures),
        "errors": len(result.errors),
        "skipped": len(result.skipped),
        "expectedFailures": len(result.expectedFailures),
        "unexpectedSuccesses": len(result.unexpectedSuccesses),
    }
    perfect = outcome_counts == {
        "testsRun": EXPECTED_TEST_COUNT,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "expectedFailures": 0,
        "unexpectedSuccesses": 0,
    }
    if not perfect:
        print(
            "release-state-suite: strict result rejected "
            + " ".join(f"{key}={value}" for key, value in outcome_counts.items()),
            file=sys.stderr,
        )
        return 1
    print(
        "release-state-suite: 73/73 passed; failures=0 errors=0 skipped=0 "
        "expectedFailures=0 unexpectedSuccesses=0"
    )
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == [STRICT_CI_ARGUMENT]:
        raise SystemExit(run_strict_ci_suite())
    if STRICT_CI_ARGUMENT in sys.argv[1:]:
        print(
            f"usage: {Path(sys.argv[0]).name} [{STRICT_CI_ARGUMENT}]",
            file=sys.stderr,
        )
        raise SystemExit(2)
    unittest.main()
