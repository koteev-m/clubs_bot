# CLB-91 — clean shared-host first-install candidate

**LOCAL ONLY — CLB_AEZA_FIRST_INSTALL_P2_FIXED_LOCAL.** Both P2 fixes are verified;
overall initial-install acceptance remains blocked: a healthy initial application/FSM
baseline is not yet demonstrated. No server execution,
publication, restore or operational migration is authorized by this document.

`AEZA_REINSTALL=NO_GO`; `STAGE_TRUST=UNVERIFIED`; `TRANSACTION_READINESS=NO`.

## Identity and preserved work

Actual cwd: `/Users/maksimmartynov/.codex/worktrees/clb-miniapp-main-smoke/clubs_bot`.
HEAD: `58234a609604194f7b8e0bf4762adbea11776ee4` (detached); origin:
`https://github.com/koteev-m/clubs_bot`. `PROJECT_IDENTITY_OK`; visible active repo/task
instructions belong to clubs_bot; active foreign instructions=0. No ref, branch,
worktree, index, release helper, runtime launcher or existing validation file was changed.
No subagents, SSH, AEZA, real database, external backup disk, credentials or GitHub action.

The pre-existing 11-file validation candidate is preserved byte-for-byte, including
`docs/ops/PROJECT_STATUS.md`. This document supplies the checkpoint delta because that
file is part of the protected candidate. Initial SHA-256 manifest:
`/private/tmp/clb91-initial-preserved.json`. The original paths are:

- `Dockerfile`, `app-bot/build.gradle.kts`, `docs/dr.md`, `docs/ops/PROJECT_STATUS.md`;
- `app-bot/src/main/dist/bin/app-bot-validate-schema`;
- `app-bot/src/main/kotlin/com/example/bot/tools/BootstrapValidateMain.kt`;
- `app-bot/src/main/resources/bootstrap-validation-logback.xml`;
- `app-bot/src/test/kotlin/com/example/bot/tools/BootstrapValidateMainTest.kt`;
- `app-bot/src/test/kotlin/com/example/bot/tools/BootstrapValidatePostgresIT.kt`;
- `docs/ops/CLB-91-bootstrap-schema-validation.md`;
- `scripts/tests/test_bootstrap_schema_launcher.py`.

New files only:

- [Compose template](../../scripts/deploy/aeza-initial/docker-compose.yml).
- [Runtime contract template](../../scripts/deploy/aeza-initial/runtime.env.example).
- [Initial database role SQL](../../scripts/deploy/aeza-initial/10-clubs-role.sql).
- [Bounded first-install recipe](../../scripts/deploy/aeza-initial/first-install.py).
- [Contract tests](../../scripts/tests/test_aeza_first_install.py).
- [Opt-in synthetic rehearsal](../../scripts/tests/rehearse_aeza_first_install.py).
- This report/runbook.

## Current shared flock addition (CLB-91)

The [shared staging flock runbook](CLB-91-shared-staging-flock.md) adds the
operator-provisioned `/run/lock/shared-staging-docker.lock` around each phase,
including coordinated read-only probes. Successful phases release it before
manual waiting; future separately approved Docker-based restore work must also
honor the same lock. Transport/process ambiguity retains attempt markers and
requires reconciliation without automatic Docker stop or replay. Bundle the
reviewed sibling `shared_staging_flock.py` with `aeza-initial/`.
The historical local validation and unresolved app-baseline acceptance below
remain historical evidence; flock tests do not change their status.

## Host and configuration contract

The host operator owns Caddy, TLS, ports 80/443, OS/firewall, persistent storage,
Docker/Compose installation and privileged setup. Caddy's approved routes remain:
`staging.hookahtootah.club -> 127.0.0.1:8080` and
`stage.nightconcierge.help -> 127.0.0.1:8081`. This bundle contains no Caddy service,
configuration, host mutation, public DB port, production domain or shared application volume.
No route is created or activated by the recipe. The operator must keep incoming application
traffic closed until separately approved activation; startup does not register a webhook.

Compose project is exactly `clubs-bot-stage`, services `db` and `app`, internal app port 8080,
only published port `127.0.0.1:8081:8080`. `pgdata`, `database` and `application` use automatic
project-scoped names; there are no global container names or external networks/volumes.
DB joins only the internal `database` network; app joins it and its own application bridge.
The application bridge permits egress on a future live installation. Local rehearsal removes
that bridge and all published ports and proves an internal-only network; flags alone are
not treated as an isolation boundary.

The new root must be an operator-provisioned, persistent, canonical non-symlink directory,
owned by its authorized operator and not writable by other users. Put only the exact reviewed
`docker-compose.yml`, `10-clubs-role.sql`, a private `.env` (0600), and `secrets/` (0700)
containing `postgres_password` (0600) in it. Run the recipe from the reviewed bundle **outside**
that root. Other root entries, implicit Compose overrides, old release state and old incident
locks refuse admission. Do not copy files from an old server root. Do not deploy the repository's
legacy top-level Compose file; it has different ports/container names/ingress assumptions.

Root pathname admission follows the existing release helper's `validate_compose_path`:
an absolute canonical path with only ASCII letters, digits, `.`, `_`, `-` and `/`;
no empty, `.` or `..` components, repeated/trailing separators, whitespace, Unicode,
shell metacharacters or symlinks anywhere in the path chain. `/` alone is refused.
The CLI checks the original argument before `Path` can normalize it; invalid spelling
refuses with `root_path` before any Engine call or attempt marker. Ownership, directory,
file-mode and exact-template checks still apply independently.

All values in `runtime.env.example` are required. Values are literal, single-line, unquoted;
no interpolation, shell expansion, duplicate keys or unknown keys. Configuration errors refuse.
Only synthetic values are used in tests. Actual secrets must be supplied separately by the
operator; never print `docker compose config`, inspect output, `.env` or application logs to a
public channel. Use `config --quiet` for ordinary validation.

- `CLB_PLATFORM`: independently established `linux/amd64` or `linux/arm64` for the final host.
- `CLB_APP_IMAGE`: immutable `ghcr.io/koteev-m/clubs_bot/...@sha256:<64 hex>` reference.
  Preload an independently verified signed/provenance-checked image with the validator and normal
  launchers. No pull/build/registry operation is implemented. Exact platform, digest, non-root
  image user and `org.opencontainers.image.revision == CLB_REVISION` (40 hex) are checked.
- `CLB_PG_IMAGE`: official `postgres@sha256:<64 hex>`, preloaded for the same platform.
  `PG_MAJOR=16` is required; no tag or automatic architecture fallback. Image provenance/security
  verification is an operator prerequisite; a label alone does not authenticate an image.
- `CLB_DB_PASSWORD`: Clubs application role; at least 32 characters. The distinct admin password
  is supplied only to DB through `secrets/postgres_password`, never to app.
- `CLB_BOT_TOKEN`, `CLB_WEBHOOK_SECRET`, `CLB_OWNER_ID`, `CLB_QR_SECRET`, `CLB_PHONE_KEY`:
  explicit runtime inputs. Token shape, positive owner ID, secret lengths and webhook alphabet
  are checked; validity of real credentials is not tested. The two app token aliases and two
  webhook aliases use the same source value. Local tests use fabricated strings, not live tokens.

On a genuinely empty owned volume, official PostgreSQL initialization creates only the database
and a `clubs` login with NOSUPERUSER/NOCREATEDB/NOCREATEROLE/NOREPLICATION. The role owns `public`;
PUBLIC loses database access/schema CREATE. It needs ownership of restored application objects
for later approved FSM migrations. DB admin is isolated from app; the app role retains the schema
ownership required by the existing migration launcher. This is not a new split-role migration
architecture. The SQL suppresses statement/error-statement logging while setting the role password.

No application tables or Flyway history are installed by the recipe. A compatible, stable database
must be supplied in a **separately authorized** preparation/restore step, including correct object
ownership. Empty DB, pending/mismatched/failed/future history, baseline history or bad credentials
must refuse validation. Never use migrate/baseline/repair/clean or replay historical migrations to
make this first-install gate pass. Prior user-supplied restored-schema compatibility evidence
(81 tables, 60 history rows, zero failed) is retained evidence only; it was not repeated here.

App starts with `FLYWAY_MODE=validate`, enabled=true, baseline=false, out-of-order=false and the
same two packaged locations as the reviewed validator. `FLYWAY_VALIDATE_ONLY` is deliberately
absent so that the existing quiesced migration launcher can later override mode. No JVM/application
code or migration policy is changed. App drops capabilities, uses a read-only root, non-root UID
10001 and bounded writable tmpfs. Notifications, ops notifications, refund worker, polling and
tracing are disabled for this initial candidate. Full runtime activation is a separate operation.
Normal application startup still contains existing seeding/worker behavior; this recipe does not
claim read-only application startup or use synthetic history to certify a real application schema.

## Bounded procedure — future separately authorized execution only

This Python recipe is ordinary operator code, **not** a secure project-scoped executor or a sudo
allowlist. It assumes an exclusive maintenance window, trusted unmodified bundle/configuration,
trusted Docker context/Engine and no concurrent actor changing DB/schema/resources. It cannot
confine a principal already holding Docker authority. Do not grant Docker-group membership,
unrestricted root/sudo, or arbitrary script/Compose execution to a deployment principal to use it.
The intended dedicated non-root deployment principal and exact restricted executor authority
remain a separate approval/provisioning decision. No such authority was created here.

Each phase has a five-minute command budget; ordinary commands timeout at 30s, validator 75s
(in addition to its own 60s watchdog), DB/app health waits at 90s with 110s caller timeout.
A failed phase stops progression. Private `.first-install/` records prevent automatic replay;
they are not FSM state, an authorization token, or proof of crash-safe privileged confinement.

The CLI's shared-lock gate requires Linux before phase admission; the command runner
also refuses unsupported process semantics with `command_platform` before spawning.
Each command runs in its own session/process group,
with stdin closed. stdout and stderr are drained concurrently through nonblocking pipes.
Each stream has a separate 1,048,576-byte limit enforced during reading: exactly that
many bytes are accepted, the next byte refuses with `output_limit`. Reads are at most
64 KiB and at most the remaining allowance plus one refusal byte. Only bounded stdout
is retained; stderr is counted and discarded. Raw child diagnostics are never printed.

Overflow, command timeout (`command_timeout`), or SIGINT/SIGTERM/SIGHUP (`interrupted`)
stop the command and trigger SIGKILL of its owned process group, followed by a bounded
five-second leader reap; cleanup failure refuses with `command_cleanup`. The same cleanup
runs on normal completion so descendants cannot simply outlive an exited leader within
that group. Linux `waitid(..., WNOWAIT)` retains leader PID ownership until group cleanup;
previous signal handlers are restored. An expired phase budget refuses before spawning.
These bounds govern the local CLI process group, not Docker Engine resources: a daemon-side
operation or a process escaping the group is not thereby contained. SIGKILL of the recipe,
host loss and unconfirmed cleanup still require the UNKNOWN-state handling below.

1. Operator prerequisites: approve exact host/root/Engine/image identities and architecture,
   establish persistent storage and exclusive access, review configuration/secrets, keep ingress
   closed, independently satisfy image/security and host trust gates, and provision the
   shared lock according to the [current contract](CLB-91-shared-staging-flock.md).
   Preserve the bundle layout: `bundle/shared_staging_flock.py` beside
   `bundle/aeza-initial/` with its four reviewed recipe/template files.
2. `python3 -I -S -B /reviewed/bundle/aeza-initial/first-install.py check --root /approved/clubs/root`:
   validate exact templates/configuration/image metadata and run Compose `config --quiet`.
3. Run the same command with `database`: require no project containers/networks/volumes,
   no colliding resource names and no prior bootstrap marker; create an exclusive attempt marker,
   then only DB. Record the real healthy DB container and project network/volume inventory.
4. **Stop for separately authorized database preparation.** Preserve `.first-install` records;
   prepare the real compatible schema/ownership without historical migration replay. No restore
   command is supplied or executed by this candidate. No concurrent writers during validation/start.
5. Run `application`: require the exact healthy DB and unchanged configuration/inventory,
   no ordinary or one-off app, no partial attempt. Persist an exclusive attempt before launching
   the existing validator as `compose run --rm --no-deps --pull never --entrypoint
   /opt/app/bin/app-bot-validate-schema app`. Require exit 0 and exactly
   `schema-validation:v=1 result=compatible\n`; errors, timeout, empty/extra output refuse.
6. Only after that result, recheck app absence/configuration and create the **ordinary** app with
   `up -d --no-deps --pull never --wait`. Require exact image/reference/revision, Compose labels,
   oneoff=False, correct loopback binding, zero restarts, healthy state and both `/ready` and
   `/health`. Record observed container identity/start time; no old container/checkpoint is fabricated.
7. Run `verify`: compare live baseline with the observation; recheck both endpoints and metadata.
   This phase never creates a missing app or adopts a changed baseline. Host-side routing/TLS and
   application activation remain separate operator acceptance, not a successful recipe result.

Failure handling: do not delete markers, rerun an attempted phase, invoke old incident recovery,
create fake release-state, or reset the database. Validator failure leaves DB and attempt evidence,
with no app startup. A confirmed unhealthy baseline attempts to stop only the positively
identified new ordinary app, leaving it and DB for reconciliation. Nonzero Docker exit,
timeout, signal/process kill, lock identity loss, Engine loss or unconfirmed containment
does not trigger an automatic stop and may leave resources running: report state UNKNOWN, keep ingress closed,
and let the authorized operator inspect exact owned IDs before any cleanup/retry decision. The
recipe never deletes server data/volumes or runs automatic rollback. Review changes to configuration
or schema after any attempt separately; old evidence cannot be reused for new bytes.

## Relationship to ordinary release FSM

The ordinary helper must later capture the actual running app via `collect_old_app_evidence`;
its `RepoDigests[0]` must be an accepted GHCR digest, with a valid OCI revision, real container ID,
project/service, start time and restart count. The first-install gate checks these same metadata
requirements. The baseline is not declared to have completed a previous release.

Keep the final root/configuration/project identity for the first ordinary update. The existing
FSM creates its own fresh durable binding/state, captures this app as its prior baseline, quiesces
it, and uses its existing migration/health/evidence gates. There is exactly one app image in the
Compose model; the validator is a transient one-off invocation, not a second service that breaks
the FSM image-count check. Its `--no-deps` app startup and mode override remain compatible.
The old incident-specific corrected/recovery executor and its narrower captured-configuration
allowlist are not an initial-install mechanism; its historical bindings/locks are not imported.
A real first ordinary update, durable backing/principal authorization and complete FSM transition
are **NOT_RUN**; static/fixture compatibility is not runtime release acceptance.

## Verification and remaining acceptance

### P2 completion verification, 2026-10-19

This independently authorized continuation completed documentation and verification only;
the two incoming P2 Python files required no further correction. The prior reported 33/33
result could not be tied to a captured post-P2 source/input manifest, so it was not reused.
Fresh evidence is in `/private/tmp/clb91-p2-final-uz5vbcw5/`, treated only as test data.
`before.json` captures SHA-256 inputs, including tests, templates and the actual release helper;
`final-verification.json` captures final hashes, inventory and preservation checks.

| Command/check | Result |
| --- | --- |
| `python3 -I -S -B scripts/project-identity-guard.py --task-id CLB-91 --expected-head 58234a609604194f7b8e0bf4762adbea11776ee4 --scope scripts/deploy/aeza-initial --scope scripts/tests --scope docs/ops` | exit 0, PROJECT_IDENTITY_OK; active foreign instructions=0 separately attested |
| `python3 -I -S -B scripts/tests/test_aeza_first_install.py -v` in the isolated Linux container below | exit 0, 33/33 PASS, no skips; `linux-tests-final.log` |
| In-memory Python `compile(..., 'exec')` of recipe, tests and rehearsal | exit 0, 3/3 PASS; `compose-syntax.json` |
| `docker compose --project-name clubs-bot-stage --project-directory <synthetic-fixture> --env-file <synthetic-fixture>/.env -f <synthetic-fixture>/docker-compose.yml config --quiet` and `config --format json` | both exit 0; service, isolation, port, platform and validate-mode assertions PASS; `compose-syntax.json` |
| `bash scripts/validate-quiesced-deployment.sh .` | exit 0; `release-structural.log` |
| `python3 -I -S -B scripts/validate-payment-hardening.py .` | exit 0, STRUCTURAL_ONLY_NON_AUTHORITATIVE; `payment-structural.log` |
| `git diff --check`, seven-file candidate whitespace and local relative links | exit 0, PASS; `final-verification.json` |
| Protected 11-file SHA-256 manifests/copies; release FSM vs HEAD; all task-entry source bytes | PASS; only this runbook changed during this continuation |

The fresh test environment is Linux aarch64, Python 3.12.3, local image
`sha256:38fa28be010f8c3c7575c9a78dbe9bc6eee97471f8591918c42e4cc96bdd6972`.
The disposable container uses `--pull never --network none --read-only --cap-drop ALL`,
`--security-opt no-new-privileges --user 1000:1000 --pids-limit 128 --memory 256m --cpus 2`,
a read-only repository mount at `/src`, and
`--tmpfs /tmp:rw,exec,nosuid,nodev,size=64m,mode=1777`. No Docker socket, database, secret
or other host resource is mounted. Exact launch command is retained in `linux-command.txt`.
The initial attempt exited 1 because the default noexec tmpfs prevented the CLI tests'
generated fake Docker executable from running (three subcase failures in one test).
This was a verification-environment error; `linux-tests.log` is retained. Enabling execution
only in this disposable fixture tmpfs produced 33/33 PASS with unchanged test/source bytes.

Negative regression evidence covers raw path parity with the actual unchanged release
helper, including spaces/Unicode, symlink leaf/parent, dot components and duplicate
separators. Real Linux subprocess tests cover the exact 1,048,577-byte stderr regression,
both stream boundaries and concurrent output, continuous overflow, timeout with open/closed
pipes, all three interruption signals, exited leaders and child/grandchild termination.
They also assert no private CLI output, restored signal handlers, no application start
after validator refusal, preserved attempt markers/retry refusal, and owned-app stop after
app-start overflow. The Engine is synthetic; these are not live Docker lifecycle proofs.

Focused self-review: no open P0/P1/P2 finding in these two fixes, their tests or this
documentation. Source inspection confirms admission occurs before normalization/spawn,
reads enforce both bounds before retaining data, and cleanup targets only the dedicated
group while retaining its leader PID. Existing release helper/FSM and all 11 Flyway
candidate files remain byte-for-byte unchanged. Source-level and structural checks do not
prove image trust or deployment success; real process tests prove only the local synthetic
Linux behavior described above. No healthy initial application, trusted GHCR artifact or
successful ordinary release is claimed.

### Earlier candidate evidence (retained, not rerun for P2)

The following results predate the P2 changes; the old 20-test result and operator archive
are historical and do not identify the current P2 candidate. Do not install that old archive.

| Command/check | Result |
| --- | --- |
| `python3 -I -S -B scripts/project-identity-guard.py --task-id CLB-91 --expected-head 58234a609604194f7b8e0bf4762adbea11776ee4 --scope scripts --scope docs/ops` | exit 0, PROJECT_IDENTITY_OK |
| `python3 -I -S -B scripts/tests/test_aeza_first_install.py -v` | 20 tests PASS, exit 0; no skips |
| `python3 -I -S -B scripts/tests/rehearse_aeza_first_install.py` | partial real Engine rehearsal; see final evidence below; healthy app/FSM baseline NOT_RUN |
| Python AST, affected relative links, new-file whitespace, `git diff --check`, 11 protected SHA-256 hashes | PASS |
| Four-file operator bundle tar: inventory, modes, byte-for-byte content verification | PASS |
| Actual `docker compose ... config --format json` / `config --quiet` | PASS; exact service/isolation/port assertions and required variable rendering |
| `bash scripts/validate-quiesced-deployment.sh .` | exit 0, quiesced-deployment-contract: OK |
| `python3 -I -S -B scripts/validate-payment-hardening.py .` | exit 0, 423 production Kotlin files; STRUCTURAL_ONLY_NON_AUTHORITATIVE; contract OK |

The tests include refusal of old state/overrides, existing/partial projects, unlabelled name
collisions, invalid secrets/DB/image/revision/platform/PG major, changed configuration/resources,
validator failure/output/timeout, start-failure containment and retry refusal. A synthetic Engine
exercises the happy-path ordering and real baseline checks. Another test executes the unchanged
FSM's actual `collect_old_app_evidence` function with synthetic inspection values: qualified
baseline accepted, absent app/missing revision/non-GHCR image rejected. This is not a full FSM run.

Real rehearsal uses unique `clb91-install-<random>` resources, PostgreSQL 16.14 ARM64 at
`postgres@sha256:16bc17c64a573ef34162af9298258d1aec548232985b33ed7b1eac33ba35c229`
and the exact existing app image ID
`sha256:810731421097b8cc8d91a0a2b5d4139a1ea10c8cea3b6081c70c6d70ac53e1a1`.
No pulls, other-project mutations, historical application migration SQL, Telegram requests,
notifications, payments or real data are involved. The only SQL beyond fresh role initialization
creates synthetic history/sentinel tables and intentionally corrupts synthetic history for negatives.
Matching 60-row history is explicitly not a complete application schema.

Final actual rehearsal: exit 0, project `clb91-install-c04b979eb14d`; evidence copy
`/private/tmp/clb91-first-install-evidence/rehearsal-final.json`. The original private report is
`/private/var/folders/47/5g492cf53xg7_jvkvsx4ccyr0000gn/T/clb91-first-rehearsal-cwbszmub/report.json`.
It records nine PASS checks and the separate healthy-baseline NOT_RUN field. These temporary
files contain local test evidence, not authorization or a durable deployment record.
Operator bundle: `/private/tmp/clb91-first-install-evidence/clb91-aeza-initial.tar.gz`;
source hashes and preservation inventory are in `manifest.json` and `preservation.json` beside it.

Observed: PG creates its isolated volume and controlled role; no published DB port; network
Internal=true. Empty schema, wrong password, disallowed mode and bad checksum reject; matching
synthetic history accepts. Full `pg_dump` comparisons (excluding randomized restrict tokens) are
unchanged across validation calls, including rejection. Validator one-offs are removed; no ordinary
app is created. Cleanup checks the unique project label of each exact container/network/volume
before removal and then asserts zero resources for that project.

Preserved development failures: sandbox Docker access needed local permission; the legacy pinned
PG image was AMD64-only locally, so target-specific immutable PG input became mandatory. An init
shell bind mount was treated as executable/noexec by the local Docker file-sharing environment and
failed with exit 126; the candidate now uses a SQL init file, tested through the official PG
entrypoint. Every failed rehearsal removed only its positively identified resources. Gates were
not weakened and no failed test is represented as PASS.

Current blocker: the local validation image's actual RepoDigest is `clubs-bot@sha256:...`, and its
OCI revision label is absent. It cannot serve as an accepted ordinary-FSM baseline. No fake GHCR
digest, revision, checkpoint or completion was created. Full app startup was not attempted against
history-only fixture data. Still unverified: full synthetic application schema plus healthy startup,
real controller success against a qualifying image, exact first ordinary release, published image
provenance/security/target architecture, final host architecture, server permissions/executor,
backing, shared Caddy/TLS routing and restored-object ownership. Prior restored-schema evidence
and prior validator quality gates do not establish any of these criteria.

No Kotlin/build dependency bytes changed. Earlier validator packaging/JVM/IT evidence is retained
in [its report](CLB-91-bootstrap-schema-validation.md), not claimed as newly executed. The earlier
task packaged the pre-P2 operator bundle; this continuation preserves that historical archive
and verifies the current source candidate separately. Full Gradle,
full release FSM suite, new image build, vulnerability scan and hosted checks were not rerun.

Official reference semantics used: [Compose service ports and one-off services](https://docs.docker.com/reference/compose-file/services/),
[internal and project-scoped networks](https://docs.docker.com/reference/compose-file/networks/),
[official PostgreSQL initialization and password files](https://hub.docker.com/_/postgres).

One next action: review this separate local candidate and the unmet healthy-baseline acceptance
before authorizing further work; server execution remains NO_GO.
