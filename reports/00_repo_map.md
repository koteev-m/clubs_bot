# 00 — Repo Map

## 1) Top-level map (ключевые зоны)
- Backend monorepo на Gradle Kotlin DSL с модулями `app-bot`, `core-*`, `tools:perf` (settings.gradle.kts:3,37-45).
- Mini App вынесен в отдельный `miniapp/` (Node/Vite), но артефакты подмешиваются в backend-ресурсы через Gradle task `copyMiniAppDist` (app-bot/build.gradle.kts:134-153).
- Локальный запуск и инфраструктура: `docker-compose.yml`, `Dockerfile`, `Caddyfile`, `Makefile` (docker-compose.yml:1-106; Makefile:1-28).
- Конфигурация окружения документирована в `.env.example` (DB/Flyway/Webhook/Telegram/Hikari/Tracing и др.) (.env.example:1-239).

## 2) Build system и модули
- Root build: Kotlin/JVM + detekt + ktlint + versions plugin (build.gradle.kts:21-27).
- Включены модули: `app-bot`, `core-domain`, `core-data`, `core-telemetry`, `core-security`, `core-testing`, `tools:perf` (settings.gradle.kts:37-45).
- Глобально включены и настроены detekt/ktlint для subprojects + агрегаторы `staticCheck`/`formatAll` (build.gradle.kts:162-258).
- Отдельный quality task `dependencyGuard` проверяет legacy stdlib, рассинхрон Ktor, динамические/SNAPSHOT зависимости (build.gradle.kts:70-159).

## 3) Entry points и слои
- Ktor entrypoint: `com.example.bot.ApplicationKt.module` через `application.conf` (app-bot/src/main/resources/application.conf:10-12; app-bot/src/main/kotlin/com/example/bot/Application.kt:150-170).
- Application layer устанавливает плагины (security, metrics, migrations, web UI), затем DI/Koin и роуты (app-bot/src/main/kotlin/com/example/bot/Application.kt:151-207).
- CLI entrypoint для миграций: `com.example.bot.tools.MigrateMainKt` task `runMigrations` (app-bot/build.gradle.kts:168-173).
- `app-bot` зависит от `coreDomain/coreData/coreTelemetry/coreSecurity` (app-bot/build.gradle.kts:75-80).

## 4) Database / migrations / schema
- Flyway plugin подключён в `core-data`; миграции выбираются по вендору (`h2` / `postgresql`) + `common` (core-data/build.gradle.kts:8-52,56-66).
- Для flyway task есть pre-flight валидация `DATABASE_URL/USER/PASSWORD` (core-data/build.gradle.kts:70-89).
- Миграции расположены в `core-data/src/main/resources/db/migration/{common,h2,postgresql}` (core-data/build.gradle.kts:47-52).
- Runtime DB/Flyway env-переменные описаны в `.env.example` (.env.example:39-55).

## 5) CI/CD и автоматизация
- GitHub Actions присутствуют (build/tests/lint/static-check/security/docker/release/smoke/db-migrate и др.) (`.github/workflows/*`).
- Базовый CI build запускает lint+detekt, dependencyGuard, test, build (build.yml:52-66).
- Tests workflow делит unit/integration, поднимает Postgres service и запускает `-PrunIT=true` (tests.yml:16-19,255-307).
- Static check workflow отдельно публикует detekt SARIF (static-check.yml:1-34).

## 6) Frontend Mini App
- `miniapp/package.json` содержит команды `build/lint/test/e2e/typecheck/check` (miniapp/package.json:9-19).
- Mini App дистрибутив (`miniapp/dist`) копируется в ресурсы backend при сборке (app-bot/build.gradle.kts:134-153).

## 7) Infra / runtime
- Docker Compose поднимает `db` (Postgres 16), `app`, `caddy`; app получает DB/Flyway/Telegram/Webhook env (docker-compose.yml:3-101).
- Makefile предоставляет базовые локальные команды (`up/down/logs/health/smoke`) (Makefile:3-28).
