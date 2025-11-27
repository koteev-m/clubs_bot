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

Browse API routes for clubs/events currently rely on in-memory repositories shipped with the app module; a production-grade database module will replace them in future iterations without changing the public API surface. Responses are JSON (UTF-8) with `Cache-Control: max-age=60, must-revalidate`, `Vary: X-Telegram-Init-Data` and stable ETags even for `304 Not Modified` replies.

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

## SEC-02 Политика логирования и приватность

### Что пишем в логи
- **Технические идентификаторы:** `request_id` (из Ktor CallId; также уходит в заголовок ответа `X-Request-Id`), `actor_id` (идентификатор актёра после успешной аутентификации/авторизации). Оба ключа лежат в **MDC** и попадают во все записи логов в рамках запроса.
- **Бизнес‑ID:** `clubId`, `listId`, `entryId`, `bookingId`, внутренний `userId`.
- **Статусы/исходы**, **тайминги/метрики** и прочую **не‑PII** диагностическую информацию.
- **Никогда** не логируем тела запросов/ответов и произвольные payload’ы.

### Что маскируем (делает `MessageMaskingConverter`, используется как `%maskedMsg` в `logback.xml`)
- **Телефоны** → маскируем всё, кроме 2–3 последних цифр. Пример: `+7 *** *** ** 67`.
- **Имена/ФИО** (`fullName`/`fio`/`name`) → оставляем первые буквы слов, остальное `*`. Пример: `И*** И*****`.
- **«Голые» токены** формата `\d{6,12}:[A-Za-z0-9_-]{30,}` → `***REDACTED***`.

### Что запрещено (TurboFilter = DENY)
Сообщения, содержащие ключи `qr`, `start_param`, `idempotencyKey`, **не доходят до аппендеров**: такие записи отсекаются `DenySensitiveTurboFilter` на ранней стадии.

### Файл‑логирование (prod)
- Консольный вывод включён всегда.
- Файловый аппендер настроен на `logs/app.log` с дневной ротацией.
- Каталог задаётся переменной окружения `LOG_DIR` (по умолчанию — `./logs`). Убедитесь, что каталог существует и доступен на запись.
- Типичный прод‑запуск:  
  ```bash
  export LOG_DIR=/var/log/app
  # дополнительные переменные окружения см. ниже
  # запуск вашего entrypoint (Docker/K8s/systemd)
  ```

Если нужно — ротацию/формат можно настраивать в logback.xml.

### Локальный смоук

1) **Тестовый смоук (без запуска сервера)**

   ```bash
   # Юнит/интеграционные тесты модуля app-bot
   ./gradlew :app-bot:test --no-daemon -S -i
   ```

   Что проверить:
   - Тесты на маскирование и TurboFilter зелёные.
   - Тест `KtorMdcTest` (если включён) подтверждает: X-Request-Id в ответе и request_id в MDC логов.

2) **Ручной смоук (опционально, если есть возможность поднять локально)**

   Запуск с in-memory H2, чтобы не требовать PostgreSQL:

   ```bash
   export TELEGRAM_BOT_TOKEN=111111:TEST_BOT_TOKEN
   export NOTIFICATIONS_ENABLED=false
   export DATABASE_URL='jdbc:h2:mem:clubsb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_UPPER=false'
   export DATABASE_USER=sa
   export DATABASE_PASSWORD=
   export FLYWAY_LOCATIONS=classpath:db/migration/h2
   export LOG_DIR=./logs

   # если настроен application plugin с рабочей точкой входа:
   ./gradlew :app-bot:run --no-daemon -S -i
   ```

   Дальше — 1–2 запроса (подойдёт даже несуществующий путь — CallLogging всё равно сработает):

   ```bash
   # с заданным X-Request-Id
   curl -i -H 'X-Request-Id: smoke-req-1' http://localhost:8080/__smoke__
   # без заголовка — сервер сам сгенерирует request_id
   curl -i http://localhost:8080/__smoke__
   ```

   В логах проверьте:
   - есть request_id=<...> (и actor_id=<...> в авторизованных ветках);
   - нет телефонных номеров/ФИО/«голых» токенов;
   - нет упоминаний qr=/start_param=/idempotencyKey=.

ℹ️ Если в модуле нет исполняемой main, можно ограничиться «тестовым смоуком» (п.1): он покрывает генерацию и прокидывание request_id через MDC, а также маскирование/запрет чувствительных полей.

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
