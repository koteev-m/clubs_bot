# CLB-91 Linux semantic fixtures

This is a local test image, not a stage dependency or installation procedure.
Use the existing Docker Desktop runtime. Dependency preparation needs network;
test execution has none, no Docker socket, no credentials, a read-only root and
RAM-only owned fixture directories. No daemon/Engine calls are made by Compose.

## Historical ARM64 reference

The following recipe and original evidence apply to the pre-integration source
revision `67cabe65843fe803f53213aac4d3d5c3fdd73b58`. Export that revision for
historical ARM tests; current production sources intentionally reject aarch64.
`arm64-runtime-reference.json` preserves the exact former production manifest,
SHA-256 `d2c7794fa073ab310df46a80880a83fceb0a2eb450aae410e123266f07c83903`.
The Dockerfile and `packages.txt` remain unchanged.

The image uses the official Ubuntu 24.04 arm64 digest in `Dockerfile`. APT uses
the image's Ubuntu signing keys; exact direct versions and the full resulting
package inventory (`packages.txt`) are checked. Changed dependency resolution
fails the build instead of silently changing the tested closure.

Copy this directory into a disposable build directory outside the checkout.
Download only the official asset there:

```sh
curl --fail --location --proto '=https' --tlsv1.2 \
  https://github.com/docker/compose/releases/download/v5.1.1/docker-compose-linux-aarch64 \
  -o docker-compose-linux-aarch64
echo '4b5c42952b7dd81f508d01a771df2a9e5dbffe9b8c5c7d983e738504ad38f056  docker-compose-linux-aarch64' | shasum -a 256 -c -
docker build --pull=false -t clb91-semantic-linux:5.1.1-arm64 .
```

Checksum source: [official Compose 5.1.1 checksums](https://github.com/docker/compose/releases/download/v5.1.1/checksums.txt).
The runtime manifest pins actual Linux executable, Python source/cache,
Ruby/Psych and shared-library bytes. These are Linux identities, not Darwin
hashes or a version-string approval. The reference closure is arm64 Python
3.12.3, Ruby 3.2.3, bundled Psych 5.0.1 (`--disable-gems`), Compose 5.1.1.
The installed standalone Psych gem is not used by the private adapter.

Export the exact candidate with `git archive` to another disposable directory.
Mount that export at `/source:ro` (never mount the checkout, home or socket).
Run with these options; the image has the required fixture mount points:

```sh
docker run --rm --read-only --network=none --cap-drop=ALL \
  --security-opt=no-new-privileges \
  --tmpfs /work/runner-temp:uid=1000,gid=1000,mode=0700,exec,nosuid,nodev \
  --tmpfs /run/user/1000:uid=1000,gid=1000,mode=0700 \
  --tmpfs /opt/clubs-bot-stage:uid=1000,gid=1000,mode=0755 \
  --mount type=bind,src=ABSOLUTE_EXPORTED_CANDIDATE,dst=/source,readonly \
  clb91-semantic-linux:5.1.1-arm64 /bin/sh -c '
    cp -R /source /work/runner-temp/repo &&
    git -c init.templateDir= init -q /work/runner-temp/repo &&
    git -C /work/runner-temp/repo add -- . &&
    python3 -B /work/runner-temp/repo/scripts/tests/test_stage_compose_env_file_plan.py &&
    python3 -B /work/runner-temp/repo/scripts/tests/test_stage_compose_env_file_context.py &&
    python3 -B /work/runner-temp/repo/scripts/tests/test_stage_compose_env_semantic.py'
```

The capture integration fixture substitutes only the mount fingerprint because
Docker overlay/tmpfs are deliberately outside the production backing allowlist.
FD metadata, no-follow opens, reads, locks, sealed memfd, real config subprocess,
HMAC and the consumer execute normally. This is fault injection, not proof of
stage backing identity. The context suite additionally uses actual `strace` file
syscalls to audit absence of original-content reads after capture. A writable
overlay root changed root ctime during testing and correctly failed the existing
guard; the immutable-root fixture avoids that environmental side effect without
relaxing the production predicate.

The owned `/work/runner-temp` test mount explicitly permits the synthetic SSH
test executable; it remains `nosuid,nodev`, nonroot and networkless. The fixture
checks this before invoking the runner so `execvp` cannot fall through a noexec
test double to the system SSH client. Private runtime/target mounts need no exec.

On other toolchains run only the portable request/source/protocol selectors:

```sh
python3 -B scripts/tests/test_stage_compose_env_semantic.py ProtocolTest SourceTest
```

These selectors are not Linux semantic proof. Missing/unsupported runtime, or
any failed Linux test, cannot be replaced by mocks or reported as semantic PASS.

## Historical amd64 local prototype — blocked on emulation, not stage-ready

The original experiment left the ARM64 materials, production `scripts/deploy`
and workflows unchanged. The subsequent test-only CI wrapper is described below. `amd64-runtime-candidate.json` is a **test candidate**, never an
approved stage profile. This experiment uses the source tree of
`8cc5389c61ac097c589de99fa474d63f7d050d13` (the same tree as merged
`6a193fe96d591918ec59cd9a865698f7f0450ff1`). No stage access or dispatch occurred.

### Inputs and trust

`amd64-inputs.json` records the exact official Ubuntu amd64 base digest,
61 downloaded package versions/SHA-256/archive paths, 15 package-index hashes
and their signed InRelease hashes, and Compose assets. Base-image packages are
anchored by the image digest; the complete installed inventory is separately
recorded in `amd64-packages.txt`. Initial resolution used the official Ubuntu
repositories once, before freezing these inputs. Four InRelease signatures
verified against Ubuntu Archive key
`F6ECB3762474EDA9D21B7022871920D1991BC93C`; each uncompressed Packages index
matched its signed hash, and each downloaded `.deb` matched its Packages entry.
The signing keyring came from the pinned official base, not from stage.

Compose is the official `v5.1.1/docker-compose-linux-x86_64`, SHA-256
`2ac954c9d506b912a12477d72f01601dc72ec918c429c7bae48fd707bdf0f3e5`.
The release checksums, authenticated release-API digests and provenance subject
agree. Source commit is `b043368028e9fcb4545fa340c8ad635c370825da`.
The published Sigstore bundle verified with the existing pinned Cosign image,
including certificate/transparency checks and subject digest; no insecure-ignore
flags were used. Expected signer is the Docker reusable build workflow
`https://github.com/docker/github-builder/.github/workflows/bake.yml@refs/tags/v1.4.0`,
OIDC issuer `https://token.actions.githubusercontent.com`.
Official sources: [Compose assets](https://github.com/docker/compose/releases/tag/v5.1.1),
[Ubuntu archive verification](https://documentation.ubuntu.com/security/software-integrity/archive-verification/).
These are independent upstream inputs, not an endorsement of inventory hashes.

For reproduction, download only the locked package URLs into a private
`DOWNLOADS/debs` directory and the four locked Compose assets into `DOWNLOADS`.
Verify the Sigstore bundle **before executing Compose** (network here is only
for dependency trust preparation; no credentials/home/socket are mounted):

```sh
docker run --rm --read-only --cap-drop=ALL --security-opt=no-new-privileges \
  --tmpfs /tmp:mode=1777 --env HOME=/tmp \
  --mount type=bind,src=ABSOLUTE_DOWNLOADS,dst=/artifacts,readonly \
  gcr.io/projectsigstore/cosign@sha256:b03690aa52bfe94054187142fba24dc54137650682810633901767d8a3e15b31 \
  verify-blob-attestation --new-bundle-format --type slsaprovenance1 \
  --bundle /artifacts/docker-compose-linux-x86_64.sigstore.json \
  --certificate-identity https://github.com/docker/github-builder/.github/workflows/bake.yml@refs/tags/v1.4.0 \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  /artifacts/docker-compose-linux-x86_64
python3 -B scripts/tests/linux-semantic/prepare-amd64-context.py ABSOLUTE_DOWNLOADS NEW_CONTEXT
docker build --platform linux/amd64 --pull=false --no-cache --network=none \
  -f NEW_CONTEXT/Dockerfile -t clb91-semantic-amd64-prototype:unique-a NEW_CONTEXT
docker build --platform linux/amd64 --pull=false --no-cache --network=none \
  -f NEW_CONTEXT/Dockerfile -t clb91-semantic-amd64-prototype:unique-b NEW_CONTEXT
```

Use fresh tags; never replace another task's images. The context helper checks
every locked checksum before copying, checks copied bytes again, and refuses an
existing output directory. It does not perform signature verification itself.
Unavailable exact artifacts/signatures are a refusal, not permission to resolve
new versions. The build installs only local `.deb` files under `--network=none`;
an initial attempt with APT `--no-download` failed on its local-file handling
(`Pathname to install is not absolute`). Removing that flag, while retaining
the network ban and every checksum, produced the two successful clean builds.

### Candidate derivation and disposable source changes

In each immutable networkless image, run `python3 -I -S -B /derive.py /reference.json`
with read-only mounts of `derive-amd64-manifest.py` and the **unchanged ARM64**
archived `arm64-runtime-reference.json` manifest (originally production). The script translates only the architecture-specific paths
in that fixed 191-file closure and hashes the verified installed artifacts.
Missing/renamed files refuse; no discovery, pruning or runtime auto-approval.
It retains Python source/cache/extension files, Ruby/Psych, loader/shared-library
files, `/etc/ld.so.cache` and all four aliases. Then compare both outputs against
`amd64-runtime-candidate.json`, and `/toolchain-packages.txt` against
`amd64-packages.txt`. This seed-derived closure still requires the real runtime
gate; hashing a list alone does not prove completeness.

Only a disposable `git archive` outside the checkout was adapted:

```diff
--- a/scripts/deploy/stage-compose-env-semantic-operation.py
+++ b/scripts/deploy/stage-compose-env-semantic-operation.py
@@
-        self.observe('platform', lambda: self.check(sys.platform == 'linux' and platform.machine() == 'aarch64'
+        self.observe('platform', lambda: self.check(sys.platform == 'linux' and platform.machine() == 'x86_64'
--- a/scripts/deploy/stage-compose-env-file-plan.py
+++ b/scripts/deploy/stage-compose-env-file-plan.py
@@
-LINUX_RUBY_LOAD = "$LOAD_PATH.replace(['/usr/lib/ruby/3.2.0', '/usr/lib/aarch64-linux-gnu/ruby/3.2.0']);\n"
+LINUX_RUBY_LOAD = "$LOAD_PATH.replace(['/usr/lib/ruby/3.2.0', '/usr/lib/x86_64-linux-gnu/ruby/3.2.0']);\n"
```

The third and only other disposable change replaces
`scripts/deploy/stage-compose-env-semantic-runtime.json` with the test candidate.
No tests, source-integrity checks, maps/alias/integrity guards or expected outcomes
were edited. Synthetic Git source-capture fixtures bind the adapted bytes as usual.
Use the same nonroot/tmpfs/read-only/networkless test options above, additionally
`--platform linux/amd64` and the amd64 image. Only mount the disposable export.

### Observed results, 2026-09-21 UTC

Docker Desktop 29.3.1 on ARM64 executed amd64 **under Rosetta emulation**, not on
a native x86_64 kernel/userspace combination. Verified tools reported Python
3.12.3, Ruby 3.2.3, bundled Psych 5.0.1 and Compose 5.1.1. Installed package
`ruby-psych=5.0.2-2build2` is not the bundled Psych loaded with `--disable-gems`.

Two successful `--no-cache --network=none` builds from identical inputs produced
byte-identical 191-file candidate manifests and identical installed package lists:
manifest SHA-256 `93a9d29cba93770fab9cc6605709a3b159cfb7ce2c627677ff77bd9cacd62008`.
There were **zero closure-file differences**, including generated caches and
`ld.so.cache`. Image identities differ; whole-image reproducibility is not claimed:

- A: `sha256:7fac9bfeaaa66a163f206b80309432f608f079f36cddec25034b323b02612d2d`.
- B: `sha256:ea2906aaa001babad0cc45e12b70dac8961b13e4867c57f29c7c6e1bdd0c3ead`.

| Actual command after isolated export setup | Exit | Result, no skips |
| --- | --- | --- |
| `python3 -B scripts/tests/test_stage_compose_env_semantic.py BootstrapTest.test_real_runtime_build_passes_without_private_capture` | 1 | 1 test, 1 error; runtime refusal prevented an authenticated success frame |
| `python3 -B scripts/tests/test_stage_compose_env_file_plan.py` | 0 | 33/33 PASS, real Compose, including strict typed comparison/normalizer controls |
| `python3 -B scripts/tests/test_stage_compose_env_file_context.py` | 1 | 34 tests; 1 subtest failure and 1 consequent error in the same audit test |
| `python3 -B scripts/tests/test_stage_compose_env_semantic.py` | 1 | 15 tests; 6 failure records and 1 error; not a semantic PASS |

The concrete runtime blocker is `/run/rosetta/rosetta` in `/proc/self/maps`,
outside the trusted Ubuntu closure. Authenticated reports retain
`phase=initial guard=maps private_capture=not_started`; the maps guard was not
relaxed and Rosetta was not added to pins. The context audit's first failure is
undecoded `syscall_...` output with no `/proc/` paths from `strace` under this
emulation; its subsequent `UnboundLocalError` is cascading. Therefore the required
kernel read-audit, full replacement/deletion proof and positive end-to-end
runtime/capture path remain unverified. Existing source/HMAC/protocol, wrong-byte
refusal and initial cancellation/cleanup controls did execute; late-phase checks
blocked by the earlier runtime gate cannot be credited as passes.

The context-preparation helper reproduced the exact successful build context;
existing-output, corrupted-input and symlink-input controls all refused (four
controls total). Python syntax, workflow YAML/capability validators (26 workflows)
and whitespace checks passed. A redacted directory scan with pinned Gitleaks
8.28.0 returned exit 1 for two `generic-api-key` checksum matches in the new test
manifest: Python `secrets.py` and `__pycache__/secrets.cpython-312.pyc` (lines 114
and 39). Both are verified runtime-file hashes, not credentials. The existing
rule-scoped exceptions cover the production manifest only and were **not** widened.
This additional publication-check blocker is recorded, not suppressed; no Git
history/hosted scan PASS or publication readiness is claimed for these materials.

The minimal next local prerequisite is the same frozen input set on a suitable
native Linux amd64 test environment. Do not change Docker Desktop global settings,
weaken tracing/assertions, or add emulator files to production trust to obtain green.
No JVM, full delegated selfcheck or old ARM suites were repeated: their production
bytes/wiring are unchanged and these tests are an unintegrated prototype.

### Native CI wrapper and production-profile integration

The existing [Tests workflow](../../../.github/workflows/tests.yml) now includes
one `amd64-runtime-prototype` job, only on `workflow_dispatch`, using the standard
`ubuntu-24.04` x64 runner, `contents: read`, no Environment or deployment secrets,
exact `github.sha` checkout and `persist-credentials: false`. Its bounded
75-minute timeout covers the 65-minute command budget plus cleanup/artifact time. Existing triggers,
unit/integration jobs and YAML anchors are unchanged; workflow inventory is 26.
The capability validator has a runner-label rule only for this exact manual job;
its permissions still use the unchanged `contents: read` policy. Other jobs still
require `ubuntu-latest`; no self-hosted/slim/ARM runner or emulation setup is added.

After **separate publication and dispatch authorization**, this workflow can be
selected on a new feature branch without merging it first. For example (text only):

```sh
gh workflow run tests.yml --repo koteev-m/clubs_bot --ref APPROVED_FEATURE_BRANCH
```

This dispatch also runs the existing **unit-tests** and **integration-tests** jobs
(including their Gradle/SEC-02 checks and Postgres fixture); their conditions were
not narrowed. A plain feature-branch push triggers **Smoke**, which uses `**`;
Tests itself runs on PR events, pushes to main, or manual dispatch. Opening a PR
also triggers the existing PR workflows, with this manual-only job skipped.
The existing `tests-${{ github.ref }}` concurrency/cancel policy is unchanged.

[`run-amd64-ci.py`](run-amd64-ci.py) records run/attempt/checkout, native runner,
host/daemon/container architecture and image identity. Darwin/ARM host, wrong
runner architecture, wrong event or checkout refuse before semantic execution.
The runner's Python only orchestrates Docker; semantic Python/Ruby/Compose are
from the frozen image. The original six prototype files, candidate manifest and
`amd64-inputs.json` remain byte-identical.

Network preparation is separate: [`prepare-amd64-downloads.py`](prepare-amd64-downloads.py)
uses the [official Ubuntu snapshot service](https://snapshot.ubuntu.com/) at
`20260921T200000Z`. Its four InRelease files match the **existing** lock hashes.
The signing key is extracted from the exact official base and hash-checked;
base-image `gpgv` verifies the signer before signed index/package data is used.
All 15 compressed indexes are checked against their signed hashes; complete,
bounded decompression (including concatenated XZ streams) must match the locked
uncompressed hash/size. All 61 exact package versions/archive paths/SHA-256s must
match those indexes. The four Compose assets retain their fixed checksums and
independent Cosign signer/OIDC/provenance verification. No fresh dependency
resolution, mutable latest tag, stage observations or version substitution is used.
Unavailable artifacts or trust verification fail the job. Local preparation
successfully re-downloaded and verified this exact set; this is supply-chain
verification, not native semantic evidence.

The job performs **one** clean `--platform linux/amd64 --pull=false --no-cache
--network=none` build, then compares the full installed package inventory and
all 191 manifest files/four aliases byte-for-byte with the frozen candidates.
A mismatch records an expected/actual diff and fails, with no baseline update.
It archives the dispatched Git revision outside the checkout. Since production
integration, `integrated_diff` verifies that the exported runtime is byte-for-byte
the exact three native-tested adaptations of the hash-pinned ARM sources; inverse
replacement and the archived ARM manifest must recover all original hashes.
It never changes this export. The `experiment.patch` artifact is now the historical
ARM-to-production comparison, not a modification performed by the current run.
The old `adapt` function remains a historical regression oracle; no profile
selector, fallback or baseline update is added. Production checkout files are
never edited or mounted in containers.

The runtime gate and full planner/context/semantic suites execute separately on
synthetic fixtures, without changed assertions. Containers use read-only root,
`--network=none`, UID/GID 1000, `--cap-drop=ALL`, no-new-privileges and the existing
owned tmpfs fixture mounts. No host checkout, home, credentials or Docker socket
is mounted; no privileged/host-network/ptrace/seccomp exception is supplied.
The syscall audit must work under these conditions, otherwise the job fails.
An opt-in test-only flag copies bounded actual synthetic traces to `context.log`
before temporary-file cleanup, including before audit assertion failures.
No mock replaces the normalizer or syscall evidence.

Every suite exit code is retained; later evidence collection cannot turn a
failed/timeout command green. Owned timed-out/interrupted test containers are removed by CID. Cancellation
records exit 130 and stops scheduling further suites; ordinary failures retain
the remaining requested suite evidence.
The explicit artifact allowlist is ten files: identity/status JSON, preparation
and build logs, comparison text, experiment diff, runtime/planner/context/semantic
logs. Each is limited to 8 MiB, total 24 MiB; retention is three days. Oversize or
unexpected evidence fails the gate and prevents upload. Images, packages, raw
source exports and build directories are not artifacts and are never published.
The optional syscall trace has an additional 256 KiB per-call bound. Everything
is synthetic or public upstream metadata; no live snapshots are involved.

Local regression command: `python3 -B scripts/tests/test_amd64_ci_harness.py`.
These controls cover wiring, preserved old jobs, privilege/runner refusals,
locked inputs, strict comparisons, three source changes, malformed/concatenated
indexes, command failures/timeouts and artifact bounds. They are also part of the
existing quality-gate selfcheck. Its stale two-job Tests expectation is now the
exact existing three-job inventory; native job/runner/trigger mutations must fail,
and unit/integration guards remain intact. These controls do **not** prove a native
runtime PASS.

The two amd64 manifest checksum false positives now have their own exact-path,
rule-scoped `generic-api-key` AND/whole-line exceptions in `.gitleaks.toml`.
They use the same verified Python file digests from both prior builds; default
rules and the production/ARM exceptions remain. Real pinned Gitleaks regressions
exercise production, amd64 candidate and archived ARM manifests, altered digest/key/path, extra same-line token and
independent generic/provider canaries, plus complete-candidate directory/history
and shallow synthetic merge scans. Missing/unwritable reports remain failures.

The earlier Rosetta **BLOCKED** result remains historical evidence. Native Tests
[run 35679432271](https://github.com/koteev-m/clubs_bot/actions/runs/35679432271),
attempt 1, workflow_dispatch on `67cabe65843fe803f53213aac4d3d5c3fdd73b58`,
completed successfully in unit-tests, integration-tests and amd64-runtime-prototype.
The native runtime gate passed; planner 33/33, context/syscall 34/34 and semantic
15/15 passed with zero skips. Manifest/packages matched the frozen candidates;
real decoded `/proc/fd` traces excluded original-file reopens after capture.
The current local production candidate adopts exactly that manifest and the two
code substitutions above. Inputs, package inventory and candidate bytes are
unchanged. This native evidence is reused for those adaptations; local protocol,
source/pin/harness/security checks cover the integration. The available Mac uses
Rosetta for amd64: it cannot replace native positive runtime/maps/syscall evidence.
No new hosted or credentialed run is implied.

### Stage is a separate decision

Historical inventory `35646675380` established partial Ubuntu 24.04/x86_64
observations; its one-shot authorization is spent. It did not approve executable
bytes or establish equivalence. Needed host evidence remains the exact trusted
Python executable-package revision and stdlib/cache/extensions/mapped closure,
loader/cache and command aliases, plus applicability of the existing private-root,
memfd/procfs and capture predicates. Package names/version strings are insufficient.
Adding Ruby/Psych or standalone Compose requires a separately reviewed installation
and dependency-impact decision; it may change libraries and `ld.so.cache`.
No server install script, production profile selection, writer or semantic
dispatch is included. The production profile is a local reviewed-code candidate,
not approval of existing stage bytes. The SSH-agent cleanup export-name defect
is separately addressed locally by the exact upstream v0.10.0 pin; offline source
and dist-module tests use synthetic command stubs, without keys or a real agent.
No new credentialed invocation has verified that migration on GitHub/stage.
