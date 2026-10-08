# CLB-191 local isolated semantic feasibility candidate

**LOCAL/TEST ONLY — synthetic bytes; no stage entrypoint or private-data authority.**
Current result: `CLB191_PROTOTYPE_PARTIAL`. No local commit, production acceptance,
transaction readiness or native positive acceptance is claimed.

Base: `d1238c722a384c60e5df66c1818786e11565fda0`. The production files, workflow,
runtime manifest and package targets are unchanged. This directory is deliberately
outside all production source closures. `helper.py` embeds the exact five source
hashes from that base; changed/missing/symlinked sources refuse before execution.

## What executes

`run-local.py --image sha256:<explicit-local-image-id>` exports only the five
pinned source files to a disposable directory. It sends three internally generated
synthetic requests to separate containers: equivalent removal, equivalent explicit
replacement, and a real ambient/dotenv priority mismatch. It accepts only exact
bounded public result strings. All other output is suppressed and fails closed.
Exit 0 requires both equivalent results AND the distinct semantic `different`
result; generic runtime refusal cannot satisfy the negative semantic case.

Inside the container, `helper.py` is executed with `python3.12 -I -S -B -c`.
As in the existing bootstrap, all source bytes are verified before compiling or
executing captured modules; those modules are not registered as filesystem imports.
The unchanged production Runtime verifies Linux/x86_64, isolation, root-owned
paths, all 191 file hashes and four aliases, interpreter, loaded modules/caches,
procfs mappings and held file descriptors. Both synthetic canonical/private roots
and the Ruby/Psych loaded closure are checked before the first stdin read.
The full guard is rechecked after real planner execution; held descriptors close
before any successful result. No runtime fallback exists.

The real planner uses Psych, Compose 5.1.1 `config --format json`, typed full-model
comparison, explicit-variable projection when needed, and resolved-model reuse.
Its existing sealed memfds retain input bytes through all normalizer children.
Only `Plan.public()` leaves the helper; neither model, candidate nor input hash is
reported. Request deadline is 30 seconds; host capture deadline is 45 seconds
with the existing bounded capture/child cleanup mechanism. Launcher removes only
its randomly named containers and verifies their absence.

## Input/output and trust

```text
trusted LOCAL test launcher (only built-in synthetic fixtures)
  -> supervisor-owned FIFO: ordered JSON, max 196608 bytes, EOF required
  -> exact source verification -> unchanged runtime guard -> input read/parse
  -> production planner -> sealed 0600 memfds -> real Psych / Compose
  -> bounded public equivalence / different / refusal; container cleanup
```

Fields, in exact order: `format` (integer 1), `base`, `dotenv`, `override`
(nonempty UTF-8 strings, each <=65536 bytes), `interpolation` (<=256 string pairs).
Duplicate/missing/reordered/extra fields refuse. Existing planner restrictions
add their own aggregate bounds, environment-control exclusions and YAML subset.
No request may select runtime, executable, source path, project name, temporary
root or canonical root. Inherited stdin must be a FIFO owned by UID 0 (Docker
supervisor) or the executing UID; regular files/sockets refuse. It is borrowed,
not closed by the read function; container process exit closes it. EOF terminates
one request, not a reusable session. No private transport/authentication is added.

The test supervisor/kernel and reviewed launcher source remain trusted. Container
isolation does **not** attest that trust and is not equivalent to accepted
root-installed stage authority. Hashes are content identity, not a new signature.
A compromised supervisor is outside this local experiment's threat model.

Future stage-side capture must retain authorization, principal and canonical
root/binding validation, shared locks, no-follow open/read identity, bounds and
capture lifetime. The isolated side can potentially verify its own runtime and
perform normalization over an already authorized snapshot. Its canonical path
context must preserve the accepted model semantics without mounting live secrets.
The wire schema above is only an interface experiment; a future interface also
needs authenticated request/result binding and explicit context identity.

**CONTRACT_DECISION_REQUIRED for stage adoption:** identify the trusted capture
runtime and supervisor; approve the source/runtime installation and ownership
anchor; specify authenticated bounded snapshot handoff, canonical context,
lifetime/cleanup and result authority. Current `diagnose()` cannot simply have its
runtime-before-capture gate replaced by a container. No such change is made here.
This does not block synthetic local feasibility work. Host OpenSSL/OS patching
remains a separate task; `TRANSACTION_READINESS=NO`.

## Reproduction and runtime identity

Use the existing [Linux harness](../README.md) and its frozen public input lock,
not latest dependencies. Current production manifest SHA-256:
`7ec2c9354972024933677eaf752768820d17577f6fd5fd3491c2d7ce85b7b342`.
Python 3.12.3, Ruby 3.2.3, bundled Psych 5.0.1, Compose 5.1.1, whose exact digest is
`2ac954c9d506b912a12477d72f01601dc72ec918c429c7bae48fd707bdf0f3e5`.
The native-adapter has separate minimal-rootfs/cache pins; it is studied/retested,
not silently substituted for the production manifest.

This run reused local reference image
`sha256:4b344db615e8e4ce08db0b5132488044c7baaee611e020fe2e43f8c4be21fc9b`.
190 production runtime files matched. The sole old Expat file was replaced in a
new disposable image using cached official `libexpat1=2.6.1-2ubuntu0.6` archive,
SHA-256 `494b8e672f722130c6bca6a7bc4cc31a43ca891a31d60d868bfdd699a3c20b13`,
matching the unchanged `amd64-inputs.json`. No dependency was downloaded/resolved
and no package manager ran on the host. Image construction extracted the DEB and
copied only `usr/lib/x86_64-linux-gnu/libexpat.so.1.9.1`; this is a semantic runtime
fixture, not an updated installed-package inventory. All 191 hashes and four
aliases then matched. Upstream signature ancestry is reused from the checked-in
locks/evidence; no fresh supplier signature verification is claimed.

Resulting local image:
`sha256:2982ea157465e5735f589429a166531e09a46e71d012adc5006d179de20a6a73`.
Build definition/log and test logs are under `/private/tmp/clb191-build` for this
session. These external artifacts may expire. Rebuild exact reference bytes via
the existing locked harness if that local image is absent; the launcher never
pulls or substitutes an image. An initial BuildKit `FROM sha256:<image-id>` attempt
was rejected because BuildKit interpreted it as a registry name; the successful
build used the already verified local reference tag with `--pull=false`, and
networkless RUN instructions. No registry mutation occurred.

```sh
python3 -B scripts/tests/linux-semantic/isolated-helper/test-helper.py
python3 -B scripts/tests/linux-semantic/isolated-helper/run-local.py \
  --image sha256:2982ea157465e5735f589429a166531e09a46e71d012adc5006d179de20a6a73
```

Launcher always uses `--pull=never`, non-root UID 1000, network none, read-only
root/export, capabilities dropped, no-new-privileges, default seccomp, memory/PID
limits, disabled Docker logging (`--log-driver=none`), owned noexec tmpfs roots. No Docker socket, host HOME, production checkout,
credentials or privileged execution is mounted. Host Docker HOME is used only by
the local CLI to reach the existing engine; it is never sent into the container.
The helper environment is exactly PATH/LC_ALL via `env -i`.

For component tests, export the candidate (never mount a checkout), use the same
harness restrictions and set `CLB191_COMPONENT_TEST=1` with tmpfs at
`/run/user/1000` and `/opt/clubs-bot-stage` (UID/GID 1000, mode 0700), plus the
existing `/work/runner-temp` test tmpfs. Run the exported `test-helper.py` with
`python3 -I -S -B`. These real planner tests call `evaluate()` explicitly and
**do not prove Runtime.open acceptance**. Existing planner/context suites require
`PATH=/usr/local/bin:/usr/bin:/bin` to find pinned Compose. The helper itself uses
an absolute Compose path and the narrower `/usr/bin:/bin` environment.

## Observed limits

Host is Darwin arm64 / Docker Desktop; guest reports x86_64 but maps Rosetta.
The full helper refuses all three requests. Existing runtime evidence records
`phase=initial guard=maps private_capture=not_started`, with integrity/modules/
aliases/descriptors passing. Do not add Rosetta to pins or bypass this guard.
Native positive helper execution, full Ruby mapping acceptance and stage
compatibility remain **NOT VERIFIED**. A native Linux amd64 disposable run is
necessary; no workflow dispatch or identity spoofing is performed.

The standalone real planner components pass under emulation. This is useful
algorithm/input-lifetime evidence, not an isolated verified-runtime end-to-end
PASS. Existing strace context and post-initial-guard tests can fail under Rosetta;
their failures are retained, not reclassified as skips or fixed by weaker guards.
A filesystem/network probe verifies read-only root/export, UID 1000, CapEff=0,
NoNewPrivs=1, Seccomp=2, unreachable external test address and absent Docker socket.
Network-none does not forbid socket creation or loopback syscalls; no broader
socket-syscall isolation is claimed.

Exact component command (from the isolated candidate worktree; export is disposable):

```sh
CLB191_EXPORT=$(mktemp -d /private/tmp/clb191-export.XXXXXX)
git archive HEAD | tar -x -C "$CLB191_EXPORT"
cp -R scripts/tests/linux-semantic/isolated-helper "$CLB191_EXPORT/scripts/tests/linux-semantic/"
docker run --rm --pull=never --platform linux/amd64 --log-driver=none \
  --read-only --network=none --user=1000:1000 --cap-drop=ALL \
  --security-opt=no-new-privileges --pids-limit=64 --memory=768m \
  --tmpfs /run/user/1000:uid=1000,gid=1000,mode=0700,nosuid,nodev,noexec \
  --tmpfs /opt/clubs-bot-stage:uid=1000,gid=1000,mode=0700,nosuid,nodev,noexec \
  --tmpfs /work/runner-temp:uid=1000,gid=1000,mode=0700,nosuid,nodev,noexec \
  --mount type=bind,src="$CLB191_EXPORT",dst=/source,readonly \
  --entrypoint /usr/bin/env \
  sha256:2982ea157465e5735f589429a166531e09a46e71d012adc5006d179de20a6a73 \
  -i PATH=/usr/bin:/bin LC_ALL=C TMPDIR=/work/runner-temp CLB191_COMPONENT_TEST=1 \
  /usr/bin/python3.12 -I -S -B /source/scripts/tests/linux-semantic/isolated-helper/test-helper.py
rm -r -- "$CLB191_EXPORT"
```

Focused independent read-only review found one P2 (Docker log driver), now fixed
with explicit disabled logging and a policy/canary regression. It also requested
current evidence and exact component reproduction; both are recorded here/in the
handoff. No second independent review is claimed.

## CLB-191 verification ledger (2026-10-08)

Commands below use `python3 -B`, except native-adapter controls (`-I -S -B`).
Linux rows ran in disposable containers using the exact image above; Darwin rows
are portable controls and must not be counted as native Linux acceptance.

| Command / scope | Final result |
| --- | --- |
| `test-helper.py` on Darwin | 6 controls PASS; 2 explicit Linux component SKIP |
| `test-helper.py`, Linux with `CLB191_COMPONENT_TEST=1` | 8/8 PASS, no skips, including real removal/explicit/different, FD sealing/cleanup, FIFO bounds, source drift, canary and timeout controls |
| `run-local.py --image <exact ID above>` | Exit 2, all three requests refused; cleanup confirmed absent; no semantic or native PASS |
| `scripts/tests/test_stage_compose_env_file_plan.py` | 33/33 PASS with real Compose |
| `scripts/tests/test_stage_compose_env_file_context.py` | 34 tests, 33 PASS; syscall audit FAIL plus cascading UnboundLocalError under Rosetta |
| `scripts/tests/test_stage_compose_env_semantic.py RuntimeProfileTest ProtocolTest SourceTest` | 9/9 PASS on Darwin and Linux |
| Same semantic suite plus eight runtime negative selectors | 17 tests: 15 PASS; two methods have five failed subcases because Rosetta maps fails before the later guard/probe under test |
| `BootstrapTest.test_real_runtime_build_passes_without_private_capture` | ERROR, runtime positive unavailable under Rosetta; no accepted success frame |
| `scripts/tests/test_amd64_ci_harness.py` | 10/10 PASS |
| `native-adapter/tests/test-codegen.py` | 3/3 PASS |
| `native-adapter/tests/test-driver.py` | 10 PASS; exact runtime-tar control SKIP (not supplied); 11 total |
| `native-adapter/ci/test-recipe.py` | 9/9 PASS |
| Exact 191 hashes / four aliases / isolation probe | PASS; x86_64 reported, Rosetta detected, never called native |
| Semantic workflow validator, Python syntax, diff/new-file whitespace, README link | PASS |
| Production path diff / original user checkout preservation | PASS; only these four new test files |

The eight semantic negative selectors were `test_wrong_runtime_fails_before_capture`,
`test_corrupt_cache_and_library_refuse_at_integrity_before_capture`,
`test_wrong_architecture_refuses_before_private_capture`,
`test_initial_compatibility_matrix_no_unverified_probe_or_capture`,
`test_failed_acquisition_does_not_claim_final_file_metadata_pass`,
`test_independent_mismatches_aggregate_through_production_consumer`,
`test_initial_failure_cleanup_and_cancellation_preserve_attribution`, and
`test_probe_failure_and_pre_open_capture_boundary`, all in `BootstrapTest`.

Initial planner/context runs had a harness PATH error (Compose not found); after
fixing only the disposable test environment, the above results supersede them.
The initial isolation probe attempted to stat an inaccessible container root-home
path; the corrected probe verifies no HOME environment or host HOME mount.
No production code was changed to accommodate either test setup error.

Final source-byte identities, runtime evidence and review disposition are in the
CLB-191 handoff. Native post-guard stdin admission, Ruby mappings, positive helper
end-to-end and native syscall audit remain unverified. Do not infer a complete
negative matrix from the portable ordering tests or the earlier Rosetta refusal.
