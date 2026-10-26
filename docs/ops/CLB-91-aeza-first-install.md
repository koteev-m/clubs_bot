# CLB-91 — clean shared-host first-install candidate

## Current stage continuation — 2026-10-26

The bounded recovery is **already complete**. The owner and
[Clubs project journal](https://docs.google.com/document/d/1I4m-kQvDjQ6xTNXh5t6pcdtrpNn9dGSVSgOGuWwwtQ4/edit) report one `recover-application` PASS,
first-install `verify` PASS and preserved PostgreSQL/resources on 2026-10-23.
A later shared-host operation activated Caddy/HTTPS; the owner opened the Mini App
through `@NightConcierge_Bot`. Do not repeat recovery, initial-install, restore,
marker creation or the historical Caddy-masked prerequisite below. Those were
conditions of the completed operation, not instructions to deactivate current ingress.

[Current checkpoint](PROJECT_STATUS.md#current-operational-state) records the bounded
2026-10-26 observations: recovered app running/healthy at revision
`77e0635a4e43df03eb1bbec7acf91bf832966aa3`, original healthy PostgreSQL 16.15,
original volume/networks, recovery receipt/baseline present, active Caddy and
HTTPS health/ready/app 200. No installer/verify command was rerun. Owner/five-chat
configuration presence is observed; real Telegram credentials/identity were not tested.
The owner accepted the VNC-authenticated replacement key in
[DEC-038](../product/OPEN_DECISIONS.md#accepted-replacement--authenticated-aeza-vnc-2026-10-26).

PRs #545–547 and #549 are merged. Current published main is
`d10c0fb53829192d2cc38cb93dfbbc754a4ab123`, immutable image
`ghcr.io/koteev-m/clubs_bot/app-bot@sha256:6ca42beb556e72317e949bd043ae7884db440536ad0e6bb454944e564fcd8c93`
([Docker Publish 40386747163](https://github.com/koteev-m/clubs_bot/actions/runs/40386747163),
15/15 post-merge workflows SUCCESS). The owner reports that the new image is **not
deployed**; this documentation reconciliation performs no stage observation. The
previously proposed `00759b8...` / `fd39b782...` remains historical; its conditional
authorization does not transfer to the current artifact. The next delivery must use the ordinary protected quiesced
release/FSM, capture the recovered app as prior baseline and preserve project/root,
DB, networks and volume. No historical incident recovery state is imported.

**Current delivery boundary:** PR #549 replaced the earlier unverified
`ssh-keyscan` trust bootstrapping with reviewed pinned SSH trust for both privileged
workflows. Live Environment pin provisioning/verification, protected deployment
principal/policy, schema and complete release admission remain unverified. No release
was dispatched or run in this documentation reconciliation. The previous target was
not preloaded at the last observation; preloading of the current image is unknown. At the last recorded observation, HTTPS HTML for `mode=platform-owner` did not
prove the new cabinet was deployed: the old backend returned 404 for `/api/platform/access`.
See [ordinary stage delivery gates](release-rollback.md#current-clb-91-stage-delivery-boundary--2026-10-26).

## Historical local preparation and recovery contract — 2026-10-22

The local-only, unverified-live and future-execution statements below describe
that preparation date. Retain the exact recovery guards/evidence as history;
this section grants no new recovery or infrastructure authority.

**LOCAL ONLY — bounded recovery implementation (2026-10-22).** The five-input runtime
candidate is preserved. `recover-application` implements one explicit continuation of the
known failed install; ordinary phases cannot replay it. Publication and live execution
require separate authorization. No SSH, server recovery, restore or migration was performed.
Healthy live baseline remains unverified.

`AEZA_REINSTALL=NO_GO`; `STAGE_TRUST=UNVERIFIED`; `TRANSACTION_READINESS=NO`.

## Runtime contract correction, 2026-10-22

Execution worktree `/private/tmp/clb91-staging-shared-flock` was clean before editing.
Local HEAD `2bd87bbbab514ca237211005dbf40e489cb7e60a` and published commit
`77e0635a4e43df03eb1bbec7acf91bf832966aa3` have the same tree
`031b0701392a6e794d066f3d8ace64ce3ad7f241`; the published tree was read through GitHub API.
No checkout/reset/rebase/ref change was needed. `PROJECT_IDENTITY_OK`; active foreign
instructions=0 separately attested. The older identity and validation sections below are history.

Root cause: `AppConfig.fromEnv()` unconditionally requires `HQ_CHAT_ID` and
`CLUB1_CHAT_ID` through `CLUB4_CHAT_ID`, even with polling and notifications disabled.
The initial Compose omitted all five. The private `.env` now requires the corresponding
`CLB_HQ_CHAT_ID` and `CLB_CLUB1_CHAT_ID` through `CLB_CLUB4_CHAT_ID`; Compose passes
them explicitly, without defaults. Admission accepts an optional sign and 1..19 ASCII
decimal digits in `[-9223372036854775808, 9223372036854775807]`, excluding zero.
Missing/empty/overflow/malformed values refuse before any Engine operation. Chat values
are not echoed in refusals. The example remains blank: operators supply confirmed values.
Zero placeholders have not been proven safe and are refused. Notification flags alone
are not a network boundary; the application has other startup/ingress workers.

Required-input review: all direct mandatory `envRequired`/numeric reads in
`AppConfig.fromEnv()` are now present; stage also receives `MINI_APP_URL`, webhook/token
aliases, DB credentials, `CORS_ALLOWED_ORIGINS`, `RBAC_ENABLED=true`, `QR_SECRET` and
`PHONE_ENCRYPTION_KEY`. DB config's required credentials are the same mapped values.
Optional thread IDs, bot username and worker defaults remain optional; disabled local
Bot API, tracing and legacy booking features do not require activation credentials.
No additional missing mandatory input was found in these startup guards. This is not
proof of a full healthy application or of credential validity/external integrations.

Logging: the FILE appender is instantiated even when its event filter denies stage
events. It attempts to open relative `logs/app.log` on the read-only container root;
failure leaves that appender unstarted. In the isolated JVM probe the exact
`FileNotFoundException: logs/app.log` occurs, CONSOLE starts, `AppConfig.fromEnv()`
accepts the rendered synthetic environment and the process exits 0. It is a nonfatal
Logback appender-initialization error, not the observed startup blocker. Logging bytes,
writable mounts and filesystem permissions are unchanged. File logging remains unavailable.

Local verification: 38/38 existing/expanded first-install and Linux process-boundary
tests passed; 2/2 opt-in real Compose/JVM tests passed. The JVM probe uses existing local
image `sha256:810731421097b8cc8d91a0a2b5d4139a1ea10c8cea3b6081c70c6d70ac53e1a1`
(linux/arm64, local candidate, not the deployed amd64 image), synthetic values, network
none, read-only root and no DB, Docker socket or real configuration mount. It calls
AppConfig and logging directly, not Application.module(). It does not certify live health.
The Linux runner uses the existing image documented in P2 evidence below. Commands:

- Linux disposable runner: `python3 -I -S -B scripts/tests/test_aeza_first_install.py FirstInstallTest SubprocessBoundaryTest -v`.
- Mac local Engine: set `CLB91_COMPOSE_TEST=1` and `CLB91_RUNTIME_IMAGE` to the local
  image above, then `python3 -I -S -B scripts/tests/test_aeza_first_install.py ComposeRuntimeTest -v`.

Tests cover all five required mappings, signed boundaries and refusal cases, actual
Compose/AppConfig agreement, unchanged DB/volume/network/port isolation, changed-binding
refusal and exact failed-install markers blocking database/application/verify replay.
The dependent shared-flock suite also passed all 23 cases: 22 in the read-only source
mount, and the stage runner case after rerunning only that case against a writable copy
of `scripts/` in disposable `/work` tmpfs. Its first failure was an unwritable fixture
directory, not a code failure; the earlier non-root launch refused before running tests.
`validate-quiesced-deployment.sh` passed in Linux/Bash 5 (Mac Bash 3.2 cannot parse the
existing `coproc`), as did Python syntax, `git diff --check`, repository-relative links
and the new anchor. Source/AST comparisons confirm unchanged phase guards, role SQL,
shared lock and logging; removing exactly the five added mappings reproduces the original
Compose bytes. No Gradle/full application/real-DB/live recovery claim is made.

## Bounded continuation of the existing failed install

The owner approved **local implementation/tests/docs only**. The incoming six-file runtime
candidate was preserved; the current worktree/HEAD remain as identified above. The owner's
live state is a stopped ordinary app, healthy restored DB, exactly `database-attempt`,
`database-ready`, `application-attempt`, `schema-compatible`, and no `baseline-observed`.
This local task performs no SSH and does not refresh that evidence. Expected live targets:

- Project/root: `clubs-bot-stage`, `/opt/clubs-bot-stage`, AEZA `178.20.209.5`.
- Stopped app: `07c8aeb68f0130583a6704ea81cceb73dc6bd1c1a79369687090df5f1b59ca6a`.
- DB: `c25a5b59d1995fca1ceb3b12add2e6dc558fb92fd975c3f27ad17689a35df7e7`;
  volume `clubs-bot-stage_pgdata`; running/healthy, restart count 0.
- Source/app revision: `77e0635a4e43df03eb1bbec7acf91bf832966aa3`.
- App: `ghcr.io/koteev-m/clubs_bot/app-bot@sha256:1113570fc72001f761aa4f93444ad2e0b65779fe533da0d8a60ca06155230a1e`.
- PG16: `postgres@sha256:f9d1e581cde3e4c8bc696b07e6f7b7aea1d41014b33a52b27990762daae61beb`.

These targets do not authorize adopting a replacement DB/app. Never delete/rewrite the
four markers, manually overwrite the original `.env`/Compose, replay database/restore/
application, fabricate a baseline, or invoke incident-specific release recovery.

### Exact implemented contract

The explicit `recover-application` phase requires `--root`, `--recovery-env`,
`--stopped-app-id` and `--database-id`. Recovery arguments are refused on other phases.
It is bounded to the old published template and the five CHAT_ID correction; there is no
new service, coordinator, general resume mechanism, retry loop or release FSM.

1. The existing host flock is acquired before installer construction and held until the
   entire phase finishes. No nested acquisition or new project lock is added. The existing
   ordering (host lock before any project operation/locks) is preserved. All commands use
   the existing bounded process runner, inherited lock descriptor and 300-second phase budget.
2. Original Compose bytes must match SHA-256
   `204807d80a65980a043ce0bf367ed2d4dc4db185a0a2877f4d855b00f7cc8fcd`;
   original role SQL must match
   `333da4091f5a3b8c20441f69ce478687f11650f5ddcae44dd4be4d00891dab21`.
   Removing exactly the five specified mappings from the corrected template must reproduce
   the published Compose hash. B0 is recomputed from actual original `.env` bytes, unchanged
   admin password (existing trailing-newline normalization), and original Compose bytes.
   All three original binding markers must equal B0; DB ID comes from `database-ready` and
   must equal the explicit CLI target. A caller-supplied binding is never accepted.
3. The target is one private, owned, mode-0600 regular env file outside project/template
   roots, using a canonical path without symlinks. Old/target env inputs are bounded to
   16 KiB each. The only added keys are the five nonzero signed-Long CHAT_ID inputs.
   Every original parsed value must stay equal, including credentials, cryptographic
   secrets, images/platform/revision and owner. Formatting/comments may differ; their
   exact old/new bytes are separately bound. Existing admin credentials/SQL are untouched.
4. Read-only inspection requires exactly the original DB and explicitly identified stopped
   app; exact project/service/oneoff/root labels, image/revision, rendered environment and
   image entrypoint/command/user; unchanged loopback port and security/mount controls.
   The original app must be exited, not running/restarting, with stable identity/state in
   two observations five seconds apart. DB must be healthy, not restarting, restart count
   zero, same ID/image/volume, with no published port. Only the two named bridge networks
   and one named local volume are admitted, with project labels and recorded DB network
   continuity. Stable DB configuration/start/mount metadata, network identities/settings
   and volume metadata are compared throughout. No other project is enumerated or mutated.
5. Before any file/Engine mutation, create/fsync the exclusive private
   `.first-install/application-recovery-attempt`. It retains **the literal old env bytes**,
   B0/B1, original marker hashes, stopped-app fingerprint, app/DB/resource identities and
   hashes of the corrected installer/templates/shared helper. This is secret-bearing private
   evidence: do not print, publish, attach to issues or include in public diagnostics.
   Original Compose is reproducible from pinned template bytes; original four marker bytes
   and inodes are retained. DB configuration is retained as a private hash, not raw inspect.
6. Recheck stopped app/resources/old evidence before each configuration replacement.
   Write/fsync pending files inside `.first-install`, atomically replace `.env` (0600) and
   `docker-compose.yml` (0644) individually, and fsync directories. There is deliberately no
   claim of a two-file atomic transaction. Any interruption leaves the exclusive attempt,
   possibly pending/mixed files; all attempted recovery replay is refused.
7. Check B1/images, recheck stopped app/DB, then run the existing launcher once:
   `compose run --rm --no-deps --pull never --entrypoint /opt/app/bin/app-bot-validate-schema app`.
   Require exit 0 and exactly `schema-validation:v=1 result=compatible\n`. Old
   `schema-compatible` remains historical B0 evidence, not a new validation result. No
   migration, baseline, repair, clean, SQL restore or DB lifecycle operation is supplied.
8. Recheck B1, stopped app and original resources again. Only then execute
   `compose up -d --no-deps --force-recreate --pull never --wait --wait-timeout 90 app`.
   Compose replaces only that confirmed stopped ordinary app. No separate rm/down/stop,
   policy edit, image pull, DB recreate or volume operation is performed.
9. Require a different app ID, exact image/revision/labels/runtime/security/network controls,
   running/healthy, restart count zero, only `127.0.0.1:8081`, and both health endpoints
   inside the container **and from the host loopback**. Recheck unchanged healthy DB/resource
   identities and B1/history, then exclusively write/fsync the real `baseline-observed`.
   This records the new result and never converts the previous failed attempt into success.
10. Normal `verify` recognizes only the exact six-marker recovery shape. It independently
    recomputes B0 from retained original bytes/current unchanged admin secret, validates the
    original marker hashes, bundle hashes and B1/current bytes, then checks resource continuity,
    exact observed baseline and both endpoints. Other normal phase guards are unchanged;
    none initiate recovery. A second recovery is refused, including after success.

### P1 network reconciliation admission — Compose 2.40.3

Independent review found that the original recovery receipt/continuity checks came too late
for network drift: `run --no-deps` can reconcile networks before the next observation.
The local P1 correction admits **only Compose 2.40.3** and checks both networks before
receipt creation, then again at the existing resource-continuity boundaries. Ordinary
`database`/`application` behavior is unchanged.

The source chain is [run.go at v2.40.3](https://github.com/docker/compose/blob/v2.40.3/pkg/compose/run.go)
(`startDependencies` calls `Create`),
[create.go at v2.40.3](https://github.com/docker/compose/blob/v2.40.3/pkg/compose/create.go)
(`prepareNetworks`, `ensureNetworks`, `resolveOrCreateNetwork`, `removeDivergedNetwork`),
[NetworkHash](https://github.com/docker/compose/blob/v2.40.3/pkg/compose/hash.go), and
[compose-go v2.9.1 NetworkConfig/IPAMConfig](https://github.com/compose-spec/compose-go/blob/v2.9.1/types/types.go),
the version pinned by Compose's go.mod. A nonempty mismatching hash can trigger network
removal/recreation; `--no-deps` does not disable it. Upstream accepts an empty hash, but
this recovery deliberately refuses empty/missing hashes because identity is unconfirmed.

Expected hashes are independently derived from the trusted, pinned template's rendered
`config --format json` model, never from inspect or from comparing the two networks.
Admission requires exact project/network names and exactly this supported network model:

- `application`: name `clubs-bot-stage_application`, empty `ipam`.
- `database`: name `clubs-bot-stage_database`, empty `ipam`, `internal: true`.

Unknown fields, topology or Compose versions refuse. The bounded serializer reproduces
Go struct order (`name`, `ipam`, then optional `internal`) and compact JSON. Empty IPAM is
an embedded struct and remains `{}` despite `omitempty`; no default `driver` is inserted.
`prepareNetworks` adds project/network/version CustomLabels, but `json:"-"` excludes them
from NetworkHash. Thus this calculation is specific to the proven model and version,
not a generic approximation based on sorted arbitrary Compose JSON.

Each inspected network must have its full 64-hex ID, exact name, bridge/internal identity,
and exactly the project/network/version/config-hash labels expected from that model.
Both IDs must be distinct and retain the existing DB/app attachment and recorded network
continuity. Wrong/nonempty, empty, missing, malformed or swapped hashes refuse **before**
receipt, configuration replacement, validator `run`, `up`, stop/rm/recreate. Historical
markers, B0/B1, healthy original DB/volume, host flock and UNKNOWN refusal remain intact.

Actual-version evidence uses the official `docker-compose-darwin-aarch64` v2.40.3 release
asset, SHA-256 `8cd7eb5f95bacb536cc407111662e2c205d67d9abfea5dcb8400be8418db60d1`,
verified against the GitHub release asset digest. It was run against a disposable loopback
HTTP Engine fixture with an empty temporary HOME and explicit DOCKER_HOST. No real Docker
socket/resources are available to that fixture. The actual binary emitted NetworkCreate
hashes matching the derived values for both old and corrected project models; matching
labels produced no network mutation request. Wrong hashes for each network produced a
DELETE request even with `--no-deps`; the fixture denied deletion and container creation.
This proves the relevant CLI/reconciliation path, not actual Engine removal or live recovery.
Local Compose 5.5.1 is used only for the pre-existing Compose/JVM checks, not as evidence
of v2.40.3 reconciliation semantics. No local/global Compose installation was replaced.

Reproduction commands (synthetic inputs only):

- Linux fixture runner: `python3 -I -S -B scripts/tests/test_aeza_first_install.py FirstInstallTest RecoveryTest SubprocessBoundaryTest -v` — 62/62 PASS.
- `CLB91_COMPOSE_2403_BINARY=/private/path/to/verified/v2.40.3 python3 -I -S -B scripts/tests/test_aeza_first_install.py Compose2403NetworkTest -v` — 3/3 PASS; exact release digest required, no real Engine.
- Existing local Compose/JVM command from the runtime section — 3/3 PASS.
- Linux `python3 -I -S -B scripts/tests/test_shared_staging_flock.py -v` — final result recorded in checkpoint.

Live hashes/version remain unobserved in this task. A failed admission is an operator
reconciliation blocker; do not repair labels, recreate networks, upgrade Compose or bypass
this predicate under the recovery permission.

### Future application order — separate authorization required

1. Review/publish the exact local candidate separately. Obtain one-use live permission
   naming this operation, corrected bundle, exact host/root/Engine, images and original IDs.
   Preserve sibling layout `bundle/shared_staging_flock.py` + `bundle/aeza-initial/`.
   Do not substitute the corrected project Compose before admission.
2. Reconfirm host identity, trusted local Engine context on AEZA, Compose **2.40.3**, shared flock, stopped app,
   healthy original DB, original four markers, unchanged restore evidence and closed ingress.
   Retained restore evidence must still cover 81 tables, Flyway 60/0, 343 constraints,
   235 indexes and 57 sequences. This entrypoint validates schema, not data row checksums.
   Caddy must remain masked/inactive. Exclude concurrent operators/writers; flock is advisory,
   not fencing of a privileged actor who ignores it. No changes to users/privileges are needed.
3. In an approved private staging directory prepare `recovery.env` from the original env,
   adding the five owner-confirmed values only. Preserve all existing secrets. No guessed or
   zero CHAT_ID; use a private editor/channel, not CLI values or printed Compose output.
4. Invoke once, from the newly reviewed bundle, with unchanged original project files:

   ```sh
   python3 -I -S -B /reviewed/bundle/aeza-initial/first-install.py recover-application \
     --root /opt/clubs-bot-stage \
     --recovery-env /approved/private/recovery.env \
     --stopped-app-id 07c8aeb68f0130583a6704ea81cceb73dc6bd1c1a79369687090df5f1b59ca6a \
     --database-id c25a5b59d1995fca1ceb3b12add2e6dc558fb92fd975c3f27ad17689a35df7e7
   ```

5. On confirmed success, run the same bundle's `verify --root /opt/clubs-bot-stage`.
   Independently confirm unchanged Caddy/neighbor state and DB evidence without printing
   private configuration. Public routing, TLS, polling/webhooks/notifications remain separate.

Stop on any mismatch, nonzero command, timeout, signal, lock loss or ambiguous Engine result.
After the attempt exists there is **no automatic retry, rollback, containment stop or reset**.
Keep all files/resources for authorized read-only reconciliation; Docker may have completed
an operation despite losing its reply. A failure before baseline leaves no success marker.
Lost acknowledgement after a durable baseline can be investigated with read-only verify;
it never permits replay. Pending/malformed/mixed state cannot be adopted by this command.
Any subsequent mutation, including stopping a failed replacement, needs a separate decision.

### Local recovery verification

The existing synthetic Engine harness covers the old-to-new transition, independent old/new
binding checks, marker bytes/inodes, unrelated secret/image/platform/DB/network/volume/port
refusals, running/wrong app, DB health/continuity, preserved foreign fixtures, interruption
at each file boundary, malformed attempt, validator refusal, Docker error/timeout/UNKNOWN,
new-app restart refusal, no replay/automatic stop, and successful fresh-instance verify.
The actual Linux flock tests observe BUSY during receipt/files/validator/start/baseline and
refuse before construction when occupied. Real Compose compares the entire rendered old/new
model, permitting only the five app environment entries. Packaged AppConfig/Logback remains
an isolated arm64 probe, not a real app start or deployed amd64 recovery.

Final command counts/results are recorded in the current checkpoint. No live restore/data
recheck, actual Docker app replacement, native amd64 run, Caddy inspection or external
integration was performed in this local task. Future admission may refuse if actual Engine
metadata differs; that is a blocker for inspection, never authority to relax the predicates.

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
- `CLB_HQ_CHAT_ID`, `CLB_CLUB1_CHAT_ID`, `CLB_CLUB2_CHAT_ID`, `CLB_CLUB3_CHAT_ID`,
  `CLB_CLUB4_CHAT_ID`: operator-confirmed nonzero signed Long values; all required,
  private, no defaults. See the current runtime correction above for exact syntax/range.

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
