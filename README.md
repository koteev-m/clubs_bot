# Bot Monorepo

Kotlin multi-module project that powers the Telegram guest flow, admin APIs and
observability surfaces for the club booking bot. The service runs on Ktor 3,
Exposed 0.49 and Micrometer, targets JDK 21 and is packaged as a self-contained
`installDist` distribution for Docker deployments.

## Stack and modules

| Module | Description |
| --- | --- |
| `app-bot` | Ktor HTTP application with Telegram integrations, routes and health probes. |
| `core-domain` | Domain logic, availability rules, metrics helpers. |
| `core-data` | Database layer (HikariCP, Flyway migrations, Exposed DAO/DSL). |
| `core-security` | RBAC, webhook signature checks and security plugins. |
| `core-telemetry` | Micrometer registry bootstrap and shared telemetry helpers. |
| `core-testing` | Integration test harness and common fixtures. |
| `miniapp` | Static Mini App assets served via `webAppRoutes()`. |
| `tools` | Auxiliary utilities (perf harness, smoke checks). |

Related documentation:

- [Полная документация пилота QR](docs/README_pilot_QR.md)
- [Чек-лист запуска пилота](docs/CHECKLIST_pilot_QR.md)

## Quality gates

```bash
./gradlew dependencyGuard --console=plain
./gradlew ktlintFormat ktlintCheck detekt --console=plain
./gradlew :core-domain:test --console=plain
./gradlew clean build --console=plain
```

Integration tests are tagged with `@Tag("it")` and excluded unless explicitly
requested.

## Tests

- Unit tests (no Docker required):

  ```bash
  ./gradlew test --console=plain
  ```

- Integration tests (require Docker/Testcontainers):

  ```bash
  ./gradlew test -PrunIT=true --console=plain
  ```

  If Testcontainers cannot access `/var/run/docker.sock`, start Docker Desktop
  (macOS/Windows) or enable the Docker service (Linux) before re-running the
  build.

The dependency guard task validates the resolved dependency graph and fails the
build if it detects legacy Kotlin stdlib artifacts, mismatched Ktor versions or
dynamic/SNAPSHOT coordinates. Run it locally via `./gradlew dependencyGuard
--console=plain` or let CI handle it as part of the standard pipeline.

## Configuration

Copy `.env.example` to `.env` (used by Docker Compose) and provide the required
secrets. The application reads configuration from environment variables:

| Variable | Default | Description |
| --- | --- | --- |
| `APP_ENV` | `dev` | Runtime profile: `dev` enables relaxed limits, `prod` enables hardened settings. |
| `PORT` | `8080` | HTTP port for the Ktor server (also exposed in Docker). |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75 ...` | Extra JVM options consumed by the `installDist` launcher. |
| `DATABASE_URL` | – | JDBC URL for PostgreSQL (required in production). |
| `DATABASE_USER` | – | Database user for Flyway and Exposed. |
| `DATABASE_PASSWORD` | – | Database password. |
| `HEALTH_DB_TIMEOUT_MS` | `150` | Timeout for the `/health` DB probe in milliseconds. |
| `TELEGRAM_BOT_TOKEN` | – | Telegram bot token (required to send messages). |
| `WEBHOOK_SECRET_TOKEN` | – | Shared secret that signs incoming Telegram webhooks. |
| `OWNER_TELEGRAM_ID` | – | Telegram user ID that receives critical alerts. |
| `TELEGRAM_USE_POLLING` | `false` | Switch between webhook mode (`false`) and long polling demo (`true`). |
| `LOCAL_BOT_API_URL` | – | Optional base URL for a self-hosted Telegram Bot API. |
| `RULES_DEBUG` | `false` | Enables detailed DEBUG logs for `OperatingRulesResolver` (fallbacks to INFO otherwise). |
| `TELEGRAM_API_ID`/`TELEGRAM_API_HASH` | – | Required when running the optional `telegram-bot-api` container. |

## Running locally

### Gradle

```bash
export $(grep -v '^#' .env | xargs)  # optional helper when using an .env file
./gradlew :app-bot:run --console=plain
```

The application starts all routes via `Application.module()` and performs
Flyway migrations on startup. After successful migrations, `/ready` returns
`READY` while `/health` returns `OK` once the DB probe succeeds.

### Docker Compose

```bash
cp .env.example .env   # fill in the required secrets
docker compose up --build
```

The compose stack provisions PostgreSQL, builds the runtime image from the
multi-stage `Dockerfile` and runs `app-bot` as a non-root user. Health checks:

- `http://localhost:8080/health` – readiness of the DB connection
- `http://localhost:8080/ready` – application readiness (migrations applied)

The container exposes `PORT` and honours `JAVA_OPTS`. Logs are available via
`docker compose logs -f app`.

## Observability

- `GET /health` — checks database connectivity with a configurable timeout.
- `GET /ready` — returns `READY` only after Flyway migrations succeed.
- `GET /metrics` — Micrometer Prometheus endpoint with counters for availability
  rules (`rules.exception.applied`, `rules.holiday.inherited_open`,
  `rules.holiday.inherited_close`, `rules.day.open`) tagged by day-of-week and
  overnight flags.

Setting `RULES_DEBUG=true` enables verbose DEBUG traces for the
`OperatingRulesResolver`: input base/holiday/exception hours, resolved boundary
sources (`base|exception|holiday|inherited`), final open/close windows and the
`overnight` flag.

## Database migrations

Flyway migrations reside in `core-data`. Run them via Gradle:

```bash
DATABASE_URL=jdbc:postgresql://localhost:5432/postgres \
DATABASE_USER=postgres DATABASE_PASSWORD=postgres \
./gradlew flywayMigrate --console=plain
```

`MigrationState.migrationsApplied` guards the readiness probe and the Docker
entrypoint fails fast when migrations cannot be applied.

## Telegram bot

The Telegram adapter uses the pengrad client. By default the demo long-polling
listener is wired in `Application.module()` for `/demo`. Production deployments
should configure a webhook endpoint and secret.

Invoices (`SendInvoice`) remain compatible with the current pengrad release and
use `.providerToken(...)` for configuration while keeping the deprecation
suppression local to the instantiation site.

## Additional tooling

- `make up` / `make down` — wrappers for Docker Compose.
- `make health` — calls `/health` on the local deployment.
- `./gradlew staticCheck` — aggregate detekt and ktlint CLI checks.
- `./tools/perf/build/install/perf/bin/perf` — performance harness (see
  `README_pilot_QR.md`).
