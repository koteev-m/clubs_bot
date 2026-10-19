# CLB-91 — safe bootstrap schema validation

Local implementation at base `58234a609604194f7b8e0bf4762adbea11776ee4`, detached HEAD in
`/Users/maksimmartynov/.codex/worktrees/clb-miniapp-main-smoke/clubs_bot`. Actual cwd was verified
without an internal directory switch. Guard: `PROJECT_IDENTITY_OK`; agent attestation: active
repo/task instructions belong to `clubs_bot`, active foreign instructions = 0. Read-only
`git ls-remote origin refs/heads/main` returned the same SHA.

## Contract

The new image entrypoint is `/opt/app/bin/app-bot-validate-schema`. Gradle `installDist` copies it
unchanged alongside the application and verifies the main class, logging resource and packaged
PostgreSQL/common SQL resources. The Docker build checks that it is executable. The application
entrypoint, quiesced migration launcher and release FSM are unchanged.

The public shell launcher uses the fixed image JVM, classpath and validation main directly.
It rejects arguments and removes JVM option environment variables, including `JAVA_TOOL_OPTIONS`,
`JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`, `JAVA_OPTS`, `DEFAULT_JVM_OPTS` and launcher options.
`JAVA_HOME`, `PATH` and classpath cannot redirect its JVM or main.

Required environment (no defaults for these controls):

| Variable | Accepted value |
| --- | --- |
| `APP_ENV` | exactly `stage` or `prod` |
| `DATABASE_URL` | `jdbc:postgresql://host[:port]/database` |
| `DATABASE_USER`, `DATABASE_PASSWORD` | nonblank separate credentials |
| `FLYWAY_ENABLED` | exactly `true` |
| `FLYWAY_MODE` | exactly `validate` |
| `FLYWAY_BASELINE_ON_MIGRATE` | exactly `false` |

Optional controls are strict: `APP_PROFILE` must equal `APP_ENV`; `FLYWAY_OUT_OF_ORDER` must be
`false`; `FLYWAY_VALIDATE_ONLY` must be `true`; `FLYWAY_SCHEMAS` is one lower-case unquoted
PostgreSQL identifier. Without a schema override, normal connection schema selection applies.
If `FLYWAY_LOCATIONS` is supplied, it must contain exactly the two full classpath locations below,
in either order, with no duplicates/whitespace. Other `FLYWAY_*` controls are rejected.
System-property application configuration is not read.

Only simple single-host URLs and simple database names are accepted. URL credentials,
fragments, multiple hosts, service files and arbitrary JDBC parameters are rejected. The only
optional caller URL parameter is a single `sslmode=disable|require|verify-ca|verify-full`.
TLS mode/certificate provisioning remains an operator responsibility; no connection to AEZA
is authorized by this document.

The launcher reuses `FlywayConfig.fromEnv` and `configureFlyway` from `FlywayFactory.kt` with:

- Fixed `classpath:db/migration/postgresql` and `classpath:db/migration/common` resources.
- Validate mode, baseline-on-migrate and out-of-order disabled, schema creation disabled,
  clean disabled, no retries, default callbacks disabled, naming/missing-location checks enabled.
- Empty ignore patterns, including rejection of future migrations. Flyway's normal default
  ignores future migrations; see its [official parameter reference](https://github.com/flyway/flywaydb.org/blob/c1980f3cdd905f7db78f9640bd9f34d7f316df7c/documentation/configuration/parameters/ignoreMigrationPatterns.md).
- The existing `MigrationRunner` validation contract: successful `validateWithResult()` and zero
  pending migrations. Additional evidence requires a nonempty matching validated count and only
  `SUCCESS` SQL entries with a version, checksum and installation timestamp. Empty, missing,
  failed, future, ignored, deleted, baseline or otherwise incompatible history is rejected.

No `migrate`, `baseline`, `repair` or `clean` call exists in the new production path. No Ktor,
Telegram, seeding, worker, pool, application plugin or payment startup is invoked.
Connections enforce server `default_transaction_read_only=on`, JDBC read-only mode, 5-second
connect/login and 15-second socket/statement timeouts. A 60-second process watchdog terminates
stalled validation.

The process suppresses library stdout/stderr and JUL before Flyway initialization. Its saved result
stream emits exactly one fixed line, without throwable inspection or raw diagnostics:

```text
schema-validation:v=1 result=compatible
schema-validation:v=1 result=rejected
```

Only the first line is successful (exit 0). Rejection exits 1; cancellation/interruption exits 130.
JVM launch failure also remains nonzero; callers must require both the exact compatible line and
exit 0. Missing output cannot establish success. Use the public boundary with no JVM arguments.

## Evidence and limitations

Verdict (2026-10-19): **CLB_AEZA_VALIDATE_ONLY_LOCAL_READY**. All changes remain local and
uncommitted on the original detached HEAD. Changed files:

- `app-bot/src/main/kotlin/com/example/bot/tools/BootstrapValidateMain.kt`
- `app-bot/src/main/dist/bin/app-bot-validate-schema`
- `app-bot/src/main/resources/bootstrap-validation-logback.xml`
- `app-bot/src/test/kotlin/com/example/bot/tools/BootstrapValidateMainTest.kt`
- `app-bot/src/test/kotlin/com/example/bot/tools/BootstrapValidatePostgresIT.kt`
- `scripts/tests/test_bootstrap_schema_launcher.py`
- `app-bot/build.gradle.kts`, `Dockerfile`, `docs/dr.md`, this report and `docs/ops/PROJECT_STATUS.md`.

Commands ran from the verified actual cwd. Local Gradle/Docker access was granted through the
execution sandbox; no remote/runtime permission was used. Final checks:

| Command | Exact result |
| --- | --- |
| `python3 -I -S -B scripts/project-identity-guard.py --task-id CLB-91 --expected-head 58234a609604194f7b8e0bf4762adbea11776ee4 --scope app-bot --scope Dockerfile --scope scripts/tests --scope docs` | exit 0, `PROJECT_IDENTITY_OK` |
| `./gradlew --no-daemon :app-bot:test --tests 'com.example.bot.tools.BootstrapValidateMainTest' --tests 'com.example.bot.tools.QuiescedMigrateMainTest' :app-bot:installDist` | exit 0; 8 new + 17 retained tests PASS; 24 tasks (7 executed, 17 up-to-date) |
| `./gradlew --no-daemon --no-configuration-cache test detektGate ktlintCheck :app-bot:checkLogsPolicy :app-bot:installDist --continue` | exit 0; BUILD SUCCESSFUL, 1m36s; 116 tasks (25 executed, 91 up-to-date). XML: 1182 tests, 1181 passed, 1 skipped, failures/errors 0 |
| `./gradlew --no-daemon --no-configuration-cache :app-bot:test -PrunIT=true --tests 'com.example.bot.tools.BootstrapValidatePostgresIT'` | exit 0; 1/1 PASS, no skips; BUILD SUCCESSFUL, 28s; 21 tasks (2 executed, 19 up-to-date) |
| `python3 -I -S -B scripts/validate-payment-hardening.py --run-required-runtime .` | exit 0; 9/9 PASS, no skips; BUILD SUCCESSFUL, 1m8s, 21 executed tasks; `payment-hardening-runtime: PASS` |
| `./gradlew --no-daemon --no-configuration-cache verifyResolvedProductionDependencyGraph :app-bot:installDist --dependency-verification=strict --console=plain` | exit 0; BUILD SUCCESSFUL, 8s; 27 tasks (8 executed, 1 from cache, 18 up-to-date) |
| `docker buildx build --load -t clubs-bot:clb91-bootstrap-validation-local --progress plain .` | exit 0; actual production Dockerfile; embedded installDist BUILD SUCCESSFUL, 2m55s, 19 executed tasks |
| `python3 -I -S -B scripts/tests/test_bootstrap_schema_launcher.py --image clubs-bot:clb91-bootstrap-validation-local` | exit 0; `bootstrap-launcher-contract: 14/14 PASS`; each child exit 1, exact rejected line, empty stderr |
| `bash scripts/validate-quiesced-deployment.sh .` | exit 0; `quiesced-deployment-contract: OK` |
| `python3 -I -S -B scripts/validate-payment-hardening.py .` | exit 0; structural contract OK (not substituted for the runtime check above) |
| `git diff --check`, affected local documentation links, new Python AST, `sh -n app-bot/src/main/dist/bin/app-bot-validate-schema` | PASS |

The single existing skipped test is `GuestFlowStartTest / start command sends menu with four buttons`,
already configured `enabled=false` in unchanged source. No gate, baseline or existing test was weakened.
Initial import/configuration-cache/magic-number/fixture-URL/nesting failures were corrected locally.
An overly broad module formatter initially changed 176 previously clean tracked source files;
all of those formatter-only edits were restored byte-for-byte from the original HEAD. No unrelated
source diff remains. Final gates ran on the restored source and the corrected new files.

The image is local `linux/arm64`, runs as `10001:10001`, and retains `/opt/app/bin/app-bot` as
its normal entrypoint. Docker-reported local image ID:
`sha256:810731421097b8cc8d91a0a2b5d4139a1ea10c8cea3b6081c70c6d70ac53e1a1`.
Image regression containers used `--pull=never --network=none --read-only --cap-drop=ALL
--security-opt=no-new-privileges`, without host mounts or real credentials.

Local supplementary evidence: `/private/tmp/clb91-bootstrap-quality.log`,
`/private/tmp/clb91-bootstrap-full-tests.json`, `/private/tmp/clb91-bootstrap-postgres.log`,
`/private/tmp/clb91-bootstrap-postgres-final.xml`,
`/private/tmp/clb91-bootstrap-required-runtime.log` and
`/private/tmp/clb91-bootstrap-dependency-package.log`. These are temporary local evidence,
not a deployment record or durable release authorization.

The PostgreSQL 16 integration fixture creates only synthetic history and a sentinel table.
History checksums are resolved from packaged resources; no application migration is executed.
Before/after full `pg_dump` comparisons cover schema, history and sentinel data, excluding only
randomized client `\\restrict`/`\\unrestrict` tokens. The fixture also verifies that a persistent
write through the validation connection is refused with SQLSTATE `25006` and that SELECT-only
history access suffices. Positive and negative child JVM runs check exact output and termination
with invalid synthetic integration tokens present.
The final PostgreSQL server was 16.14. Ten in-process scenarios and eight child-JVM scenarios
each preserved the full dump: matching history; pending, checksum mismatch, failed, baseline,
future, empty and missing history; plus SELECT-only success and insufficient-privilege refusal
in process. No application migration was executed by this fixture.

Flyway verifies migration evidence, names/types/versions/checksums and history consistency; it
does not reconstruct the schema to discover arbitrary manual table/column drift. A forged matching
history is outside this check's assurance. Synthetic matching history deliberately does not prove
that the real restored schema or an existing published application image is compatible.
Concurrent DDL/history writers are outside this local proof; later compatibility verification needs
a separately authorized stable isolated restore and independently trusted image.

Focused source/security self-review found no remaining actionable defect in this diff. The
quiesced launcher, its logging resource, existing Flyway config/factory/runner, deployment scripts
and workflows compare unchanged with HEAD. Unverified: real backup/image compatibility, AEZA,
stage/prod, Linux amd64 execution, TLS/certificate provisioning, concurrent database writers and
an actual forced 60-second watchdog expiry. Existing full PostgreSQL IT/release-FSM selfcheck
suites were not rerun; the new isolated PG IT and required payment runtime gate were executed.

No AEZA/SSH, external backup disk, real Clubs database, Telegram/payment endpoint, GitHub
Secrets/Environment, publication, dispatch, deployment or operational database migration was
accessed/performed. Existing unit gates use their disposable local database fixtures.

`AEZA_REINSTALL=NO_GO`; `STAGE_TRUST=UNVERIFIED`; `TRANSACTION_READINESS=NO`.

One next action: review the local CLB-91 diff before considering any separately authorized use.
