# CLB-191 native test integration

This continues the local prototype documented in [README.md](README.md). The four
original files are preserved byte-for-byte, including `--log-driver=none`.
Their historical PARTIAL/no-commit report describes the preceding local phase,
not a prohibition on this explicitly authorized integration/publication phase.

The existing manual `amd64-runtime-prototype` job first reconstructs official,
frozen inputs with `native-adapter/ci/prepare-native-inputs.py`. After the existing
adapter tests, `native-ci.py` consumes that same job's successful preparation
record and **reference_image**, not the adapter's different minimal rootfs cache
and not a Mac image ID. It checks source/input locks, manifest/package identities,
run SHA/ID/attempt, host/runner/daemon architecture, image ID/architecture, guest
Rosetta/QEMU absence, all 191 runtime files and four aliases. Full helper execution
additionally retains production root/path/maps/module/Ruby/descriptor guards.

Three full executable requests must produce exact `remove`, `explicit` and
`different` results. Generic refusal cannot satisfy any of these checks. The
existing component suite must run 8 tests without skips; the existing Linux
context suite must run 34 tests without skips, including the real strace audit of
sealed capture inputs versus original pathname reads. Neither the helper nor the
normalizer is mocked. Context trace bytes remain in disposable tmpfs only.

Every worker is non-root, networkless, read-only, capabilities dropped, with
no-new-privileges, default seccomp, PID/memory bounds and disabled Docker logging.
Only an owned exported source subset is mounted read-only. Synthetic canonical
`/opt/clubs-bot-stage` is an empty owned tmpfs, never a stage-host mount. No socket,
credentials, host HOME or production checkout is mounted. The existing adapter's
namespace authority is not inherited. A shell only merges stderr into bounded
capture so any unexpected output invalidates the exact success frame; neither
channel is relayed to job logs. Failed checks exit nonzero without continuation.

Exactly one additional artifact is allowed:
`clb191-isolated-helper/result.json`, maximum 4096 bytes, exact keys/types/enums,
fixed helper/manifest hashes, bounded GitHub identities, image SHA and seven
fixed step statuses. A PASS requires all seven PASS. Duplicate keys, extra fields,
extra files, symlinks, oversized data and forged PASS with unfinished steps refuse.
The existing aggregate 1 MiB artifact cap is unchanged. No fixture, candidate,
canary, raw stdout/stderr, full trace, image or rootfs is uploaded. The existing
always-on evidence check/upload cannot turn a failed execution step green.

Portable checks:

```sh
python3 -I -S -B scripts/tests/linux-semantic/isolated-helper/test-native-ci.py
python3 -B scripts/tests/linux-semantic/isolated-helper/test-helper.py
python3 -B scripts/tests/test_amd64_ci_harness.py
python3 -I -S -B scripts/tests/linux-semantic/native-adapter/ci/test-recipe.py
```

`test_amd64_ci_harness.py` runs the new portable negative controls in the existing
quality gate. Its unchanged prefix hash protects unit/integration jobs and global
workflow trigger/concurrency settings. Native positive remains UNVERIFIED until
terminal CI evidence on the exact published commit is reviewed. Local Rosetta
maps/strace refusals remain failures/limitations, never native PASS. No stage,
private input, host package, production runtime contract or deployment authority
is supplied. TRANSACTION_READINESS=NO.
