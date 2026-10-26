# CLB-198 — local CI fixture optimization

Date: 2026-10-26. Repository: `koteev-m/clubs_bot`.
Source main: `23a6f70fb04dd438a5fba269271f6e252978b15a` (PR #546 merged).
Worktree: `/Users/maksimmartynov/.codex/worktrees/clb198-ci-critical-path/clubs_bot`, detached HEAD, no task commit/publication.

## Outcome and evidence boundary

The local optimization is verified by metadata equivalence tests and complete
before/after Linux suite execution. **GitHub-hosted CI acceleration is unverified.**
The whole Lint workflow was not executed locally; local numbers below cover its
release/corrected test workers, excluding lint-core, queueing, checkout, Gradle and
artifact/cache phases. This is one whole-suite sweep per variant, not a statistical
hosted-CI performance claim. Support's small increase shows ordinary timing noise.

[Machine-readable measurements and exact method inventory](evidence/CLB-198-ci-measurements.json)
contain log hashes, reviewed source hashes, timing controls and limitations.
Raw local logs and measurement harness remain at `/private/tmp/clb198-linux-jr21mcoi`; they are not tracked.

## Observed bottlenecks

Bounded read-only inspection of [Lint run 40327578146](https://github.com/koteev-m/clubs_bot/actions/runs/40327578146)
confirmed all inspected jobs checked out the exact source SHA above and succeeded.
Release-state ran 73 methods in 1667.649 s; corrected import-root ran 16 in
1593.107 s; executor 24 in 1339.809 s; authority 10 in 1200.502 s.
Support ran 72 in only 41.208 s. Method counts do not represent equal work.

Each `AuthorityCliFixture` creates a fresh `CorrectedExecutorTest.fixture`, which
creates `RemoteHarness`, runs preflight/prepare/publish/quiesce/migrate and reproduces
the retained helper incident. Fault matrices repeat this setup for separate roots,
claims and durable state. This isolation is preserved. An instrumented complete
fixture executed 457 metadata subprocess requests. The old stat shim booted Python
for every request; the same mode/uid/nlink checks can read fresh metadata via GNU stat.
The ABBA fixture means were 33.329 s before and 30.336 s after, with
457 calls in every sample. The separate 200-call ABBA microbenchmark is in the JSON.

Readiness/retry waits, process deadlines, signal handling, real kernel flock and
concurrent claim/recovery assertions are unchanged. They are not replaced with
instant-success mocks. Synthetic Docker/transport and crypto boundaries are the
existing fixture design. The default corrected suite uses the existing synthetic
crypto boundary; an optional real reviewer is cached per process. Per-fixture
Ed25519 generation and the separate crypto gates remain unchanged; no real keys,
credentials, stage host or production daemon was used.

Lint-core took 1184.500 s in that hosted run: payment-hardening Gradle runtime about
220 s, delegated selfcheck about 909 s, then a short detekt gate and about 40 s
ktlint. Its log also records Gradle cache restore/save service errors. These are
separate costs and are not attributed to the stat optimization.

## Chosen change and alternatives

Only the disposable Linux stat fixture changes. Exact `%a`, `%Lp`, `%u`, `%h`, `%l`
and the two accepted identity-triplet formats dispatch to the existing
`FAKE_REAL_STAT` using GNU `-L`, preserving Python `os.stat` symlink-following and
fresh metadata semantics. Timestamps, arbitrary/composite formats and other argv
retain the byte-identical Python fallback, including negative timestamp rounding.
Darwin retains the original fixture. No metadata caching is introduced.

A new regression suite checks permissions/special bits, directories, symlinks,
hardlinks, missing/dangling paths, timestamp/fallback semantics, exact argv and
unhidden native failures. It runs unconditionally in the existing quality
selfcheck; the dispatch regression requires its invocation and failure propagation.
The no-argument BSD-stat regression uses one regular stdin inode, avoiding active
terminal timestamp drift; stdout/status comparison remains strict.

Release-state can be split by classes, but doing only that would leave the
26.5-minute import-root job on the hosted critical path. Authority, executor and
import-root each occupy one indivisible class in the current class map; regrouping
whole classes cannot balance them. Method sharding would require new fixture/order,
coverage and topology contracts. It is deferred while this smaller common-fixture
optimization is measured. No shard, test selector, job or aggregator changes here.

Gradle duplication was inspected: Build runs `test` and then `clean build`, Coverage
pulls test tasks through Jacoco, and static gates appear in several workflows.
The selfcheck also intentionally exercises Gradle/negative-fixture gate behavior;
payment hardening forces fresh required runtime evidence. Removing these invocations
would require separate completeness/artifact/cache proof and would not directly
remove the Python-worker bottleneck in Lint. They are preserved.

## Matched local measurements

Same existing image `sha256:38fa28be010f8c3c7575c9a78dbe9bc6eee97471f8591918c42e4cc96bdd6972`,
Linux arm64, Python 3.12.3, Bash 5.2.21, non-root uid 1000, 2 CPU/2 GiB per container,
network disabled, disposable ext4 volume removed automatically. Each variant ran
five independent workers concurrently. Baseline completed before candidate started.
Both used the same source-copy/disposable-Git procedure and retained helper blob.
No host Docker socket, secrets or server mounts were available inside the containers.
Values are wall seconds including identical container/setup overhead.

| Worker | Methods | Before, s | After, s | Reduction |
|---|---:|---:|---:|---:|
| release | 73 | 768.359 | 532.365 | 30.71% |
| authority | 10 | 642.433 | 606.508 | 5.59% |
| executor | 24 | 694.455 | 654.460 | 5.76% |
| import-root | 16 | 811.653 | 761.913 | 6.13% |
| support | 72 | 27.822 | 29.865 | -7.34% |

Maximum among these five local workers: **811.653 → 761.913 s
(6.13% reduction)**. This is not whole-Lint duration.
All **195/195 methods passed in both sweeps**, with zero skips/errors/failures,
missing IDs or duplicate IDs. Release-state's strict 73-method result and all
corrected shard inventories/results were checked against source declarations.
The AST bodies of all 73 + 122 existing methods and their subtest matrices are
unchanged. Numeric subtest counts and per-method timings are not claimed.

## Checks

- New stat equivalence/dispatch suite: Linux **9/9**, Darwin **9/9** PASS.
- Existing Lint topology/shard/negative-mutation suite: Linux **17/17** PASS.
- `ruby scripts/validate-lint-workflow.rb .github/workflows/lint.yml` PASS.
- `bash scripts/validate-quiesced-deployment.sh .` PASS on Linux Bash 5.
- `ruby scripts/validate-corrected-stage-workflow.rb .` PASS.
- `python3 -B scripts/run-corrected-stage-shard.py --check` PASS: 10/24/16/72,
  exhaustive and disjoint 122-method partition.
- Python syntax, exact existing method-body/ID comparison and `git diff --check` PASS.
- Independent read-only source/test review: actionable P0/P1/P2 = **0**.

Suite commands on the matched Linux environment:

```sh
python3 -B scripts/tests/test_quiesced_release_state.py --strict-ci
python3 -B scripts/run-corrected-stage-shard.py authority
python3 -B scripts/run-corrected-stage-shard.py executor
python3 -B scripts/run-corrected-stage-shard.py import-root
python3 -B scripts/run-corrected-stage-shard.py support
python3 -B scripts/tests/test_stat_fixture.py
python3 -B scripts/tests/test_stat_fixture.py --benchmark
python3 -B scripts/tests/test_lint_sharding.py
```

System Darwin Bash 3.2 rejects the unchanged release runner during shell validation;
the supported Linux Bash 5 validation passes. No production guard was relaxed for
Darwin or tmpfs: initial local fixture attempts correctly refused those environments.
Full JVM/browser suites and the entire delegated Gradle selfcheck were not repeated;
Kotlin/runtime/workflows and their required gates are unchanged.

## Preserved contracts and remaining boundary

Required job names/topology, `lint`'s always/fail-closed aggregator, exact-candidate
non-cancelling release-state job, all four corrected workers, mapping/discovery,
action pins, permissions, timeouts, security scans, .trivyignore and payment/logging
requirements are byte-identical. There is no new conditional skip or continue-on-error.
Production executors, deployment contracts, DB and migrations are unchanged.

Hosted Ubuntu x86_64 timing, Gradle/cache service behavior, runner contention and
whole-workflow critical-path movement remain unverified. Single local sweeps do
not establish a hosted p95 improvement. GNU stat semantics are protected by the
new mandatory regressions; unsupported hosts retain Python or fail the checks.

CLB-198 was absent from checked pre-existing local history/refs/docs/worktrees and
bounded GitHub issue/PR/branch searches. Existing dirty primary files and other
worktrees were not modified. No commit, push, PR, dispatch/rerun, SSH or deployment.

Next action: separately authorize publication of this reviewed local candidate
for one comparable GitHub-hosted Lint measurement.
