#!/usr/bin/env bash
set -euo pipefail
umask 077

private_root_fd=""
known_hosts_fd=""
status_stdout_fd=""
status_stderr_fd=""
pending_signal_status=0
ssh_pid=""

# No shell, SSH, utility, or secret-bearing diagnostic is public. The only
# output surface is the bounded protocol emitted explicitly below.
exec 2>/dev/null

private_fs() {
  python3 - "$@" <<'PRIVATE_FS'
import os
import stat
import sys


def metadata(value):
    if stat.S_ISDIR(value.st_mode):
        kind = "directory"
    elif stat.S_ISREG(value.st_mode):
        kind = "regular"
    else:
        kind = "other"
    return (
        f"{value.st_dev}:{value.st_ino}:{value.st_uid}:"
        f"{stat.S_IMODE(value.st_mode):o}:{value.st_nlink}:{kind}"
    )


operation = sys.argv[1]
if operation == "fd-metadata":
    print(metadata(os.fstat(int(sys.argv[2]))))
elif operation == "rewind":
    os.lseek(int(sys.argv[2]), 0, os.SEEK_SET)
elif operation == "scrub":
    descriptor = int(sys.argv[2])
    value = os.fstat(descriptor)
    if (
        not stat.S_ISREG(value.st_mode)
        or value.st_uid != os.geteuid()
        or value.st_nlink != 0
    ):
        raise RuntimeError("unsafe anonymous private file")
    os.ftruncate(descriptor, 0)
    os.lseek(descriptor, 0, os.SEEK_SET)
else:
    raise RuntimeError("unknown private filesystem operation")
PRIVATE_FS
}

bootstrap_private_files() {
  local private_root="$1"
  exec python3 - "$0" "$private_root" <<'PRIVATE_BOOTSTRAP'
import os
import signal
import stat
import subprocess
import sys


CHANNEL_LOCAL_FAILURE = (
    b"release-status-channel:v=1 result=unavailable category=LOCAL_FAILURE\n"
)
CHANNEL_CLEANUP_FAILURE = (
    b"release-status-channel:v=1 result=unavailable category=LOCAL_CLEANUP_FAILURE\n"
)
PRIVATE_NAMES = (
    (".clubs-release-status-known-hosts", 10),
    (".clubs-release-status-stdout", 11),
    (".clubs-release-status-stderr", 12),
)
watched_signals = (signal.SIGHUP, signal.SIGINT, signal.SIGTERM)
pending_signal = 0
root_fd = None
opened = []
child_process = None


def record_signal(signum, _frame):
    global pending_signal
    if pending_signal != 0:
        return
    pending_signal = signum
    if child_process is not None:
        try:
            os.killpg(child_process.pid, signum)
        except ProcessLookupError:
            pass


for watched_signal in watched_signals:
    signal.signal(watched_signal, record_signal)


def same_object(left, right):
    return left.st_dev == right.st_dev and left.st_ino == right.st_ino


def cleanup_bootstrap():
    ok = True
    for item in reversed(opened):
        descriptor, name, original, linked = item
        if linked:
            try:
                if original is None:
                    original = os.stat(descriptor)
                current = os.stat(name, dir_fd=root_fd, follow_symlinks=False)
                if not same_object(current, original):
                    ok = False
                else:
                    os.unlink(name, dir_fd=root_fd)
            except FileNotFoundError:
                if os.fstat(descriptor).st_nlink != 0:
                    ok = False
            except OSError:
                ok = False
        try:
            os.ftruncate(descriptor, 0)
        except OSError:
            ok = False
        try:
            os.close(descriptor)
        except OSError:
            ok = False
    if root_fd is not None:
        try:
            os.close(root_fd)
        except OSError:
            ok = False
    return ok


def stop(category=CHANNEL_LOCAL_FAILURE, exit_status=1):
    for watched_signal in watched_signals:
        signal.signal(watched_signal, signal.SIG_IGN)
    cleanup_ok = cleanup_bootstrap()
    if not cleanup_ok:
        category = CHANNEL_CLEANUP_FAILURE
        exit_status = 1
    try:
        os.write(1, category)
    finally:
        os._exit(exit_status)


def require_safe_directory(value, *, selected=False):
    mode = stat.S_IMODE(value.st_mode)
    if not stat.S_ISDIR(value.st_mode) or mode & 0o022:
        raise RuntimeError("unsafe temporary root chain")
    if value.st_uid not in {0, os.geteuid()}:
        raise RuntimeError("untrusted temporary root owner")
    if selected and (value.st_uid != os.geteuid() or mode & 0o700 != 0o700):
        raise RuntimeError("temporary root is not runner-owned and accessible")


def open_canonical_root(path):
    if (
        not path
        or not os.path.isabs(path)
        or path != os.path.normpath(path)
        or path != os.path.realpath(path)
    ):
        raise RuntimeError("temporary root is not canonical")
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    descriptor = os.open("/", flags)
    try:
        require_safe_directory(os.fstat(descriptor))
        components = [component for component in path.split("/") if component]
        for index, component in enumerate(components):
            next_descriptor = os.open(component, flags, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = next_descriptor
            require_safe_directory(
                os.fstat(descriptor), selected=index == len(components) - 1
            )
        if not components:
            require_safe_directory(os.fstat(descriptor), selected=True)
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


try:
    if len(sys.argv) != 3 or not hasattr(os, "O_NOFOLLOW"):
        raise RuntimeError("private bootstrap contract")
    script_path = os.path.realpath(os.path.abspath(sys.argv[1]))
    root_fd = open_canonical_root(sys.argv[2])
    for name, target_fd in PRIVATE_NAMES:
        descriptor = os.open(
            name,
            os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
            0o600,
            dir_fd=root_fd,
        )
        opened.append([descriptor, name, None, True])
        value = os.fstat(descriptor)
        opened[-1][2] = value
        if (
            not stat.S_ISREG(value.st_mode)
            or stat.S_IMODE(value.st_mode) != 0o600
            or value.st_uid != os.geteuid()
            or value.st_nlink != 1
        ):
            raise RuntimeError("unsafe private file")
        os.unlink(name, dir_fd=root_fd)
        opened[-1][3] = False
        if os.fstat(descriptor).st_nlink != 0:
            raise RuntimeError("private file remained linked")
        os.dup2(descriptor, target_fd, inheritable=True)
    os.dup2(root_fd, 7, inheritable=True)
    for target_fd in (7, 10, 11, 12):
        os.set_inheritable(target_fd, True)
    environment = os.environ.copy()
    child_process = subprocess.Popen(
        ["/bin/bash", script_path, "--private-fds-v1"],
        env=environment,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        pass_fds=(7, 10, 11, 12),
        start_new_session=True,
    )
    if pending_signal:
        os.killpg(child_process.pid, pending_signal)

    # After the child inherits the anonymous objects, the supervisor retains
    # no evidence descriptor. It only mediates signals across the interpreter
    # handoff and bounds the child's already-redacted protocol output.
    for descriptor, _name, _original, _linked in opened:
        os.close(descriptor)
    opened.clear()
    os.close(root_fd)
    root_fd = None
    for target_fd in (7, 10, 11, 12):
        os.close(target_fd)

    child_output, _ = child_process.communicate()
    child_status = child_process.returncode
    signal.pthread_sigmask(signal.SIG_BLOCK, watched_signals)
    pending_at_handoff = pending_signal or next(iter(signal.sigpending()), 0)
    for watched_signal in watched_signals:
        signal.signal(watched_signal, signal.SIG_IGN)
    if pending_at_handoff:
        if child_status not in (None, 0) and child_output in {
            CHANNEL_LOCAL_FAILURE,
            CHANNEL_CLEANUP_FAILURE,
        }:
            os.write(1, child_output)
            os._exit(child_status if 0 < child_status <= 255 else 1)
        os.write(1, CHANNEL_LOCAL_FAILURE)
        os._exit(128 + int(pending_at_handoff))
    if (
        child_status is None
        or child_status < 0
        or child_status > 255
        or not child_output
        or len(child_output) > 4096
        or child_output[-1:] != b"\n"
        or any(byte != 10 and not 32 <= byte <= 126 for byte in child_output)
    ):
        os.write(1, CHANNEL_LOCAL_FAILURE)
        os._exit(1)
    os.write(1, child_output)
    os._exit(child_status)
except BaseException:
    stop()
PRIVATE_BOOTSTRAP
}

fd_reference() {
  local descriptor="$1"
  if [ -e "/proc/$$/fd/$descriptor" ]; then
    printf '/proc/%s/fd/%s\n' "$$" "$descriptor"
  elif [ -e "/dev/fd/$descriptor" ]; then
    printf '/dev/fd/%s\n' "$descriptor"
  else
    return 1
  fi
}

cleanup_private_resources() {
  local cleanup_status=0
  [ -z "$known_hosts_fd" ] || private_fs scrub "$known_hosts_fd" || cleanup_status=1
  [ -z "$status_stdout_fd" ] || private_fs scrub "$status_stdout_fd" || cleanup_status=1
  [ -z "$status_stderr_fd" ] || private_fs scrub "$status_stderr_fd" || cleanup_status=1
  [ -z "$known_hosts_fd" ] || exec 10>&-
  [ -z "$status_stdout_fd" ] || exec 11>&-
  [ -z "$status_stderr_fd" ] || exec 12>&-
  [ -z "$private_root_fd" ] || exec 7<&-
  return "$cleanup_status"
}

terminate_ssh_bounded() {
  local child_pid="$ssh_pid"
  local attempt=0
  local forced=0
  [ -n "$child_pid" ] || return 0
  if ! kill -0 "$child_pid" 2>/dev/null; then
    wait "$child_pid" 2>/dev/null || true
    ssh_pid=""
    return 0
  fi
  kill -TERM "$child_pid" 2>/dev/null || true
  while [ "$attempt" -lt 20 ]; do
    if ! kill -0 "$child_pid" 2>/dev/null; then
      wait "$child_pid" 2>/dev/null || true
      ssh_pid=""
      return 0
    fi
    sleep 0.05
    attempt=$((attempt + 1))
  done
  forced=1
  kill -KILL "$child_pid" 2>/dev/null || true
  attempt=0
  while [ "$attempt" -lt 20 ]; do
    if ! kill -0 "$child_pid" 2>/dev/null; then
      wait "$child_pid" 2>/dev/null || true
      ssh_pid=""
      return "$forced"
    fi
    sleep 0.05
    attempt=$((attempt + 1))
  done
  # Never enter an unbounded wait. Anonymous capture entries are scrubbed and
  # our descriptors are closed below; a still-live child makes the result an
  # explicit bounded cleanup failure.
  ssh_pid=""
  return 1
}

finalize() {
  local exit_status="$1"
  local result="$2"
  local category="$3"
  local official_status="${4:-}"
  local cleanup_status=0
  trap - EXIT
  trap '' HUP INT TERM
  set +e
  terminate_ssh_bounded || cleanup_status=1
  cleanup_private_resources || cleanup_status=1
  if [ "$cleanup_status" != "0" ]; then
    exit_status=1
    result=unavailable
    category=LOCAL_CLEANUP_FAILURE
    official_status=""
  fi
  [ -z "$official_status" ] || printf '%s\n' "$official_status"
  printf 'release-status-channel:v=1 result=%s category=%s\n' "$result" "$category"
  exit "$exit_status"
}

unexpected_exit() {
  local exit_status=$?
  [ "$exit_status" = "0" ] && exit_status=1
  finalize "$exit_status" unavailable LOCAL_FAILURE
}

handle_signal() {
  local signal_exit_status="$1"
  finalize "$signal_exit_status" unavailable LOCAL_FAILURE
}

record_initialization_signal() {
  local signal_exit_status="$1"
  if [ "$pending_signal_status" = "0" ]; then
    pending_signal_status="$signal_exit_status"
  fi
}
trap unexpected_exit EXIT
trap 'trap "" HUP INT TERM; handle_signal 129' HUP
trap 'trap "" HUP INT TERM; handle_signal 130' INT
trap 'trap "" HUP INT TERM; handle_signal 143' TERM

private_fd_mode=0
case "$#" in
  0) ;;
  1)
    if [ "$1" != "--private-fds-v1" ]; then
      finalize 2 unavailable INPUT_INVALID
    fi
    private_fd_mode=1
    shift
    ;;
  *) finalize 2 unavailable INPUT_INVALID ;;
esac

required_variables=(
  APP_ENV
  SSH_USER
  SSH_HOST
  SSH_PORT
  COMPOSE_PATH
  SSH_KNOWN_HOSTS
  INCIDENT_TAG
  RELEASE_OWNER
  EXPECTED_REVISION
  IMAGE_DIGEST
  REQUESTED_OPERATION
  EXPECTED_HELPER_SHA256
)
for variable_name in "${required_variables[@]}"; do
  if [ -z "${!variable_name:-}" ]; then
    finalize 2 unavailable INPUT_INVALID
  fi
done

if [[ ! "$APP_ENV" =~ ^(stage|prod)$ ]] ||
  [[ ! "$INCIDENT_TAG" =~ ^deploy-(stage|prod)-[0-9a-f]{7,40}$ ]]; then
  finalize 2 unavailable INPUT_INVALID
fi
incident_environment="${BASH_REMATCH[1]}"
if [ "$incident_environment" != "$APP_ENV" ] ||
  [[ ! "$RELEASE_OWNER" =~ ^[0-9]+-[0-9]+$ ]] ||
  [[ ! "$EXPECTED_REVISION" =~ ^[0-9a-f]{40}$ ]] ||
  [[ ! "$IMAGE_DIGEST" =~ ^ghcr\.io/koteev-m/clubs_bot/app-bot@sha256:[0-9a-f]{64}$ ]] ||
  [[ ! "$EXPECTED_HELPER_SHA256" =~ ^[0-9a-f]{64}$ ]] ||
  [[ ! "$SSH_USER" =~ ^[a-zA-Z0-9_][a-zA-Z0-9._-]*$ ]] ||
  [[ ! "$SSH_HOST" =~ ^([a-zA-Z0-9][a-zA-Z0-9._-]*|\[[0-9a-fA-F:]+\])$ ]] ||
  [[ ! "$SSH_PORT" =~ ^[0-9]{1,5}$ ]]; then
  finalize 2 unavailable INPUT_INVALID
fi
ssh_port_decimal=$((10#$SSH_PORT))
if [ "$ssh_port_decimal" -lt 1 ] || [ "$ssh_port_decimal" -gt 65535 ]; then
  finalize 2 unavailable INPUT_INVALID
fi
case "$REQUESTED_OPERATION" in
  preflight|prepare|publish|quiesce|migrate|start|cleanup|abort|retention|helper-cleanup|resume-quiesce|resume-migrate|resume-start|resume-cleanup) ;;
  *)
    finalize 2 unavailable INPUT_INVALID
    ;;
esac

if [[ ! "$COMPOSE_PATH" =~ ^/([a-zA-Z0-9._-]+)(/[a-zA-Z0-9._-]+)*$ ]]; then
  finalize 2 unavailable INPUT_INVALID
fi
IFS='/' read -r -a compose_segments <<<"${COMPOSE_PATH#/}"
for compose_segment in "${compose_segments[@]}"; do
  if [ -z "$compose_segment" ] || [ "$compose_segment" = "." ] ||
    [ "$compose_segment" = ".." ]; then
    finalize 2 unavailable INPUT_INVALID
  fi
done
case "$COMPOSE_PATH" in
  /tmp|/tmp/*|/var/tmp|/var/tmp/*|/run|/run/*|/var/run|/var/run/*|/dev/shm|/dev/shm/*|/private/tmp|/private/tmp/*|/private/var/tmp|/private/var/tmp/*)
    finalize 2 unavailable INPUT_INVALID
    ;;
esac

readonly helper_path="/tmp/clubs-bot-release-${RELEASE_OWNER}.sh"
if [[ ! "$helper_path" =~ ^/tmp/clubs-bot-release-[0-9]+-[0-9]+\.sh$ ]]; then
  finalize 2 unavailable INPUT_INVALID
fi

runner_uid="$(id -u)"
if [ "$private_fd_mode" = "0" ]; then
  temporary_root="${TMPDIR:-}"
  if [ -z "$temporary_root" ]; then
    temporary_root="${RUNNER_TEMP:-}"
  fi
  if [ -z "$temporary_root" ]; then
    finalize 1 unavailable LOCAL_FAILURE
  fi
  bootstrap_private_files "$temporary_root"
  finalize 1 unavailable LOCAL_FAILURE
fi

private_root_identity="$(private_fs fd-metadata 7)" ||
  finalize 1 unavailable LOCAL_FAILURE
IFS=: read -r private_root_device private_root_inode private_root_owner \
  private_root_mode private_root_links private_root_kind <<<"$private_root_identity"
[[ "$private_root_mode" =~ ^[0-7]{3,4}$ ]] ||
  finalize 1 unavailable LOCAL_FAILURE
private_root_mode_value=$((8#$private_root_mode))
if [ "$private_root_kind" != "directory" ] ||
  [ "$private_root_owner" != "$runner_uid" ] ||
  ((private_root_mode_value & 0022)) ||
  (((private_root_mode_value & 0700) != 0700)); then
  finalize 1 unavailable LOCAL_FAILURE
fi
private_root_fd=7

private_file_identities=()
for private_descriptor in 10 11 12; do
  private_identity="$(private_fs fd-metadata "$private_descriptor")" ||
    finalize 1 unavailable LOCAL_FAILURE
  IFS=: read -r private_device private_inode private_owner private_mode \
    private_links private_kind <<<"$private_identity"
  if [ "$private_kind" != "regular" ] || [ "$private_owner" != "$runner_uid" ] ||
    [ "$private_mode" != "600" ] || [ "$private_links" != "0" ]; then
    finalize 1 unavailable LOCAL_FAILURE
  fi
  private_file_key="${private_device}:${private_inode}"
  for existing_private_key in "${private_file_identities[@]:-}"; do
    if [ -n "$existing_private_key" ] &&
      [ "$private_file_key" = "$existing_private_key" ]; then
      finalize 1 unavailable LOCAL_FAILURE
    fi
  done
  private_file_identities+=("$private_file_key")
done
known_hosts_fd=10
status_stdout_fd=11
status_stderr_fd=12

known_hosts_file="$(fd_reference "$known_hosts_fd")"
status_stdout="$(fd_reference "$status_stdout_fd")"
status_stderr="$(fd_reference "$status_stderr_fd")"
printf '%s' "$SSH_KNOWN_HOSTS" >&"$known_hosts_fd"
unset SSH_KNOWN_HOSTS
private_fs rewind "$known_hosts_fd"
known_hosts_size="$(wc -c <"$known_hosts_file" | tr -d ' ')"
if [[ ! "$known_hosts_size" =~ ^[0-9]+$ ]] || [ "$known_hosts_size" -lt 1 ] ||
  [ "$known_hosts_size" -gt 65536 ]; then
  finalize 2 unavailable KNOWN_HOSTS_INVALID
fi
private_fs rewind "$known_hosts_fd"
if ! LC_ALL=C od -An -tu1 -v "$known_hosts_file" | awk '
  {
    for (position = 1; position <= NF; position += 1) {
      byte = $position + 0
      if (byte != 9 && byte != 10 && (byte < 32 || byte > 126)) {
        invalid = 1
      }
    }
  }
  END { exit invalid ? 1 : 0 }
'; then
  finalize 2 unavailable KNOWN_HOSTS_INVALID
fi
private_fs rewind "$known_hosts_fd"
if ! LC_ALL=C awk '
  BEGIN { valid = 1; count = 0 }
  {
    count += 1
    field = 1
    if ($1 == "@cert-authority" || $1 == "@revoked") {
      field = 2
    }
    if (NF < field + 2 ||
        $(field) !~ /^([a-zA-Z0-9._*?:|=+\/\[\]-]+)(,[a-zA-Z0-9._*?:|=+\/\[\]-]+)*$/ ||
        $(field + 1) !~ /^[a-zA-Z0-9@._+-]+$/ ||
        $(field + 2) !~ /^[a-zA-Z0-9+\/]+={0,2}$/) {
      valid = 0
    }
  }
  END { exit valid && count > 0 ? 0 : 1 }
' "$known_hosts_file"; then
  finalize 2 unavailable KNOWN_HOSTS_INVALID
fi
set +e
private_fs rewind "$known_hosts_fd"
known_hosts_fingerprints="$(ssh-keygen -lf "$known_hosts_file" 2>/dev/null)"
known_hosts_key_status=$?
set -e
private_fs rewind "$known_hosts_fd"
known_hosts_lines="$(awk 'END { print NR + 0 }' "$known_hosts_file")"
known_hosts_fingerprint_lines="$(printf '%s\n' "$known_hosts_fingerprints" | awk 'NF { count += 1 } END { print count + 0 }')"
unset known_hosts_fingerprints
if [ "$known_hosts_key_status" != "0" ] ||
  [ "$known_hosts_fingerprint_lines" != "$known_hosts_lines" ]; then
  finalize 2 unavailable KNOWN_HOSTS_INVALID
fi

readonly ssh_target="${SSH_USER}@${SSH_HOST}"
command_parts=(
  bash -s --
  "$helper_path"
  "$EXPECTED_HELPER_SHA256"
  "$COMPOSE_PATH"
  "$RELEASE_OWNER"
  "$APP_ENV"
  "$EXPECTED_REVISION"
  "$IMAGE_DIGEST"
  "$REQUESTED_OPERATION"
)
printf -v quoted_command '%q ' "${command_parts[@]}"

private_fs rewind "$known_hosts_fd"
private_fs rewind "$status_stdout_fd"
private_fs rewind "$status_stderr_fd"
pending_signal_status=0
trap 'record_initialization_signal 129' HUP
trap 'record_initialization_signal 130' INT
trap 'record_initialization_signal 143' TERM
set +e
ssh -p "$SSH_PORT" \
  -F /dev/null \
  -o BatchMode=yes \
  -o StrictHostKeyChecking=yes \
  -o "UserKnownHostsFile=$known_hosts_file" \
  -o GlobalKnownHostsFile=/dev/null \
  -o KnownHostsCommand=none \
  -o VerifyHostKeyDNS=no \
  -o ProxyCommand=none \
  -o ProxyJump=none \
  -o PermitLocalCommand=no \
  -o ConnectTimeout=15 \
  -o ConnectionAttempts=1 \
  -- "$ssh_target" "$quoted_command" >&"$status_stdout_fd" 2>&"$status_stderr_fd" <<'REMOTE_STATUS' &
set -euo pipefail
umask 077
exec 2>/dev/null

helper_path="$1"
expected_helper_sha256="$2"
compose_path="$3"
release_owner="$4"
app_env="$5"
expected_revision="$6"
image_digest="$7"
requested_operation="$8"

[[ "$helper_path" =~ ^/tmp/clubs-bot-release-[0-9]+-[0-9]+\.sh$ ]] || exit 41
[[ "$release_owner" =~ ^[0-9]+-[0-9]+$ ]] || exit 41
[ "$helper_path" = "/tmp/clubs-bot-release-${release_owner}.sh" ] || exit 41
[[ "$app_env" =~ ^(stage|prod)$ ]] || exit 41
[[ "$expected_revision" =~ ^[0-9a-f]{40}$ ]] || exit 41
[[ "$image_digest" =~ ^ghcr\.io/koteev-m/clubs_bot/app-bot@sha256:[0-9a-f]{64}$ ]] || exit 41
[[ "$expected_helper_sha256" =~ ^[0-9a-f]{64}$ ]] || exit 41
[[ "$compose_path" =~ ^/([a-zA-Z0-9._-]+)(/[a-zA-Z0-9._-]+)*$ ]] || exit 41
case "$requested_operation" in
  preflight|prepare|publish|quiesce|migrate|start|cleanup|abort|retention|helper-cleanup|resume-quiesce|resume-migrate|resume-start|resume-cleanup) ;;
  *) exit 41 ;;
esac

[ -f "$helper_path" ] && [ ! -L "$helper_path" ] || exit 41
exec 9<"$helper_path" || exit 41
if [ -r /proc/self/fd/9 ]; then
  helper_fd_path=/proc/self/fd/9
elif [ -r /dev/fd/9 ]; then
  helper_fd_path=/dev/fd/9
else
  exit 41
fi
[ -f "$helper_path" ] && [ ! -L "$helper_path" ] && [ -f "$helper_fd_path" ] || exit 41
if path_metadata="$(stat -c '%a:%h:%d:%i:%s' -- "$helper_path" 2>/dev/null)" &&
  helper_metadata="$(stat -Lc '%a:%h:%d:%i:%s' -- "$helper_fd_path" 2>/dev/null)"; then
  :
elif path_metadata="$(stat -f '%Lp:%l:%d:%i:%z' "$helper_path" 2>/dev/null)" &&
  helper_metadata="$(stat -Lf '%Lp:%l:%d:%i:%z' "$helper_fd_path" 2>/dev/null)"; then
  :
else
  exit 41
fi
[ "$path_metadata" = "$helper_metadata" ] || exit 41
IFS=: read -r helper_mode helper_links helper_device helper_inode helper_size <<<"$helper_metadata"
[[ "$helper_mode" =~ ^[0-7]{3,4}$ ]] || exit 41
[ "$helper_links" = "1" ] || exit 41
[[ "$helper_size" =~ ^[0-9]+$ ]] || exit 41
helper_mode_value=$((8#$helper_mode))
if ((helper_mode_value & 07022)); then
  exit 41
fi
readonly max_helper_size=262144
if [ "$helper_size" -lt 1 ] || [ "$helper_size" -gt "$max_helper_size" ]; then
  exit 41
fi

# This is the sole read of the opened retained helper. The live descriptor is
# closed before verification, and both the digest and Bash stdin are decoded
# from this same bounded, non-exported process-local value.
helper_snapshot_b64="$(
  LC_ALL=C head -c "$((helper_size + 1))" <&9 |
    base64 | LC_ALL=C tr -d '\r\n'
)" || exit 41
exec 9<&-
readonly helper_snapshot_b64
[[ "$helper_snapshot_b64" =~ ^[a-zA-Z0-9+/]+={0,2}$ ]] || exit 41
decoded_helper_size="$(
  printf '%s' "$helper_snapshot_b64" |
    base64 -d | wc -c | tr -d ' '
)" || exit 41
[ "$decoded_helper_size" = "$helper_size" ] || exit 41
actual_helper_sha256="$(
  printf '%s' "$helper_snapshot_b64" |
    base64 -d | sha256sum 2>/dev/null
)" || exit 41
actual_helper_sha256="${actual_helper_sha256%% *}"
[ "$actual_helper_sha256" = "$expected_helper_sha256" ] || exit 41

printf '%s' "$helper_snapshot_b64" |
  base64 -d |
  bash -s -- status "$release_owner" "$app_env" "$compose_path" \
    "$expected_revision" "$image_digest" "$requested_operation"
REMOTE_STATUS
ssh_pid=$!
trap 'trap "" HUP INT TERM; handle_signal 129' HUP
trap 'trap "" HUP INT TERM; handle_signal 130' INT
trap 'trap "" HUP INT TERM; handle_signal 143' TERM
if [ "$pending_signal_status" != "0" ]; then
  handle_signal "$pending_signal_status"
fi
wait "$ssh_pid"
ssh_exit=$?
ssh_pid=""
set -e

if [ "$ssh_exit" != "0" ]; then
  if [ "$ssh_exit" = "255" ]; then
    private_fs rewind "$status_stderr_fd"
    if LC_ALL=C grep -Eiq 'permission denied|authentication failed|no supported authentication|too many authentication failures' "$status_stderr"; then
      transport_category=SSH_AUTH_FAILURE
    else
      private_fs rewind "$status_stderr_fd"
      if LC_ALL=C grep -Eiq 'timed out|operation timeout|connection timeout' "$status_stderr"; then
        transport_category=SSH_TIMEOUT
      else
        transport_category=SSH_TRANSPORT_FAILURE
      fi
    fi
  elif [[ "$ssh_exit" =~ ^([1-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-4])$ ]]; then
    transport_category=SSH_REMOTE_FAILURE
  else
    transport_category=SSH_UNKNOWN_FAILURE
  fi
  finalize 1 unavailable "$transport_category"
fi

private_fs rewind "$status_stdout_fd"
status_size="$(wc -c <"$status_stdout" | tr -d ' ')"
private_fs rewind "$status_stdout_fd"
status_newlines="$(LC_ALL=C tr -cd '\012' <"$status_stdout" | wc -c | tr -d ' ')"
private_fs rewind "$status_stdout_fd"
status_last_byte="$(tail -c 1 "$status_stdout" | od -An -tu1 -v | tr -d '[:space:]')"
if [[ ! "$status_size" =~ ^[0-9]+$ ]] || [ "$status_size" -lt 1 ] ||
  [ "$status_size" -gt 1024 ] || [ "$status_newlines" != "1" ] ||
  [ "$status_last_byte" != "10" ]; then
  finalize 1 unavailable STATUS_MALFORMED
fi
private_fs rewind "$status_stdout_fd"
if ! LC_ALL=C od -An -tu1 -v "$status_stdout" | awk '
  {
    for (position = 1; position <= NF; position += 1) {
      byte = $position + 0
      if (byte != 10 && (byte < 32 || byte > 126)) {
        invalid = 1
      }
    }
  }
  END { exit invalid ? 1 : 0 }
'; then
  finalize 1 unavailable STATUS_MALFORMED
fi
private_fs rewind "$status_stdout_fd"
IFS= read -r status_line <"$status_stdout"

readonly official_status_pattern='^release-status:v=1 status_available=(yes|no) owner_match=(yes|no) revision_match=(yes|no) digest_match=(yes|no) checkpoint=(none|maintenance_prepared|prior_state_captured|candidate_override_published|app_stop_intent|app_quiesced|migration_started|migration_completed|candidate_start_begun|candidate_healthy|cleanup_started|cleanup_completed|abort_started|abort_completed|unavailable) operation_result=(success|remote_failure|incomplete_unknown|unavailable|malformed) migration_evidence=(present|absent|unknown|migration_outcome_requires_incident_reconciliation) app_state=(old_running|absent|candidate_running|replaced|ambiguous|unknown) abort_permitted=(yes|no) resume_permitted=(yes|no) failure_category=(none|untrusted_state_root)$'
if [[ ! "$status_line" =~ $official_status_pattern ]]; then
  finalize 1 unavailable STATUS_MALFORMED
fi

status_available="${BASH_REMATCH[1]}"
owner_match="${BASH_REMATCH[2]}"
revision_match="${BASH_REMATCH[3]}"
digest_match="${BASH_REMATCH[4]}"
if [ "$status_available" = "yes" ] && [ "$owner_match" = "yes" ] &&
  [ "$revision_match" = "yes" ] && [ "$digest_match" = "yes" ]; then
  finalize 0 trusted STATUS_TRUSTED "$status_line"
fi
finalize 1 untrusted STATUS_UNTRUSTED "$status_line"
