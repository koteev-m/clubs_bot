# Disaster Recovery (DR) и база данных

## Цель и охват
- Цели: RPO ≤ 15 минут (WAL-архив), RTO ≤ 1 час (автоматизированное развертывание + проверка схемы).
- Среды: prod обязательно; stage используется как «канареечная» среда для проверки миграций и процедур восстановления перед продом.

## Бэкапы и PITR
- Хранилище: управляемый PostgreSQL (Cloud SQL/RDS/аналог) c ночными snapshot’ами и WAL-архивированием в объектное хранилище (S3/GCS bucket под управлением infra/platform-команды).
- Retention: nightly snapshots ≥ 14 дней, WAL архивы ≥ 7 дней.
- Процедура PITR:
  1. Определить целевое время восстановления T (до инцидента).
  2. Создать новый кластер/БД из последнего full snapshot’а.
  3. Включить восстановление WAL до времени T (PITR) и дождаться окончания реигрыша журналов.
  4. Проверить консистентность состояния (инварианты приложения, версии схемы, критичные таблицы).
  5. При необходимости выполнить миграции до нужной версии (обычно WAL покрывает актуальное состояние).
  6. Переключить трафик на восстановленную БД, исходный инстанс оставить для forensics.
- Ответственность: конфигурацию бэкапов/WAL поддерживает платформа/infra-команда; доступы/учётки для восстановления хранятся в секрете провайдера и не коммитятся в репозиторий.

## Пробный restore-тест (fire drill)
- Периодичность: не реже одного раза в квартал или после крупных релизов схемы.
- Шаги:
  1. Взять последний prod-снапшот и WAL архивы за выбранный период.
  2. Развернуть отдельный тестовый кластер/БД в изолированном проекте/namespace (без сетевого доступа из prod).
  3. Применить актуальные миграции через Gradle/CLI (режим `migrate-and-validate`), убедиться, что схема соответствует текущей версии приложения.
  4. Поднять приложение против восстановленной БД с безопасными подменами интеграций: отключённые внешние вебхуки/платежи/уведомления, заменённые токены/секреты.
  5. Прогнать smoke-тест: ключевые сценарии UI/бота (логин, чек-ин/бронирование, отправка уведомлений в песочницу).
  6. Зафиксировать результат: общее время от старта упражнения до успешного smoke, любые проблемы (несоответствие схемы, недостающие миграции, конфиг).
- Ответственность: инициирует платформа/infra-команда совместно с владельцами приложения; отчёты складываются во внутренний wiki/Confluence/Notion.

## Политика миграций (Flyway)
- Окружение определяется по `APP_ENV` (или `APP_PROFILE`, если `APP_ENV` не задан): `prod`/`production`, `stage`/`staging`, `dev`, `local`; остальные значения считаются непроизводственными.
- Режим задаётся `FLYWAY_MODE`: `validate`, `migrate-and-validate`, `off`. Значения по умолчанию: prod/stage → `validate`, dev/local → `migrate-and-validate`. Legacy-флаг `FLYWAY_VALIDATE_ONLY=true` принудительно ставит `validate`.
- В prod/stage приложение на старте только валидирует схему (`flyway.validate()`), не вызывает `migrate()` и падает при наличии pending миграций — деплой не должен поднимать устаревшую схему.
- Out-of-order (`FLYWAY_OUT_OF_ORDER=true`) разрешается только для dev/local; в prod/stage флаг игнорируется.
- Миграции prod/stage выполняются только внутри полного quiesced release из `.github/workflows/deploy-ssh.yml` либо ручного `.github/workflows/db-migrate.yml`. Оба workflow используют один environment concurrency lock и один orchestrator: verified new image загружается и закрепляется в managed Compose override заранее, app/workers останавливаются и удаляются, затем `/opt/app/bin/app-bot-migrate` выполняется в one-off container сервиса `app` из exact verified digest. Container наследует DB environment/network Compose и не использует checkout migrations либо отдельные GitHub runner DB secrets. Фаза `migrated` записывается только после exit 0, validate и zero pending; новый app стартует только при совпадении recorded migration digest/image ID. Standalone migration и release-tag trigger для `db-migrate` запрещены.
- Если runner аварийно завершился после quiesce, remote maintenance lock и phase (`quiesced` либо `migrating`) остаются как fail-closed guard.
  Оператор проверяет lock owner, Flyway history, migration container и app state до manual cleanup. После успешного релиза
  managed `docker-compose.override.yml` сохраняет verified digest для последующих обычных Compose-команд. После
  применения V056 запуск pre-V056 image запрещён; восстановление — schema-compatible forward-fix либо PITR.
- Вспомогательные переменные: `FLYWAY_LOCATIONS` (список путей через запятую, вендорные `classpath:db/migration/<vendor>` подставляются автоматически), `FLYWAY_BASELINE_ON_MIGRATE` (по умолчанию `true`), `FLYWAY_ENABLED` (по умолчанию `true`), `FLYWAY_SCHEMAS` (при необходимости явных схем).
- CI release log содержит Git revision, verified digest и только allowlisted `migration-safe:v=1` lifecycle events без JDBC endpoint, DB identity/credentials, SQL и exception text. Public image wrapper удаляет JVM option injection variables перед private Java launcher; raw stream обязан состоять ровно из canonical `started` и одного соответствующего exit-status terminal event. Любая неизвестная, malformed, дублированная или переставленная строка сохраняет maintenance mode и блокирует release. Источник истины при разборе — реконструированные safe events вместе с maintenance phase/digest/image-ID evidence и ограниченной проверкой `flyway_schema_history` на remote host. Полный container output сохраняется только mode `0600` внутри retained maintenance lock и никогда не пересылается в GitHub Actions; после успешного cleanup он удаляется. Не включать raw Flyway/JDBC logging для диагностики.

## Пул подключений (Hikari)
- Значения берутся из env; невалидные/вне диапазона значения логируются и заменяются на дефолт.
- Таблица настроек:

| Env | Назначение | Дефолт | Рекомендации (dev/local) | Рекомендации (prod/stage) |
| --- | --- | --- | --- | --- |
| `HIKARI_MAX_POOL_SIZE` | Максимум подключений в пуле (Int, 1–50) | 20 | 5–10 | 10–30 (в зависимости от нагрузки/лимитов БД) |
| `HIKARI_MIN_IDLE` | Минимум idle соединений (Int, 0–max) | 2 | 1–2 | 2–10 |
| `HIKARI_CONN_TIMEOUT_MS` | Таймаут установления соединения, мс (1_000–120_000) | 5_000 | 3_000–10_000 | 5_000–20_000 |
| `HIKARI_VALIDATION_TIMEOUT_MS` | Таймаут проверки соединения, мс (500–60_000) | 2_000 | 1_000–5_000 | 2_000–10_000 |
| `HIKARI_LEAK_DETECTION_MS` | Leak detection, мс (0–600_000; 0 = off) | 10_000 | 0 (обычно off) или 5_000–15_000 при отладке | 10_000–60_000 при расследованиях/профилировании |

- Неверные или выходящие за диапазон значения логируются и откатываются к дефолтам; `HIKARI_MIN_IDLE` дополнительно клампится до `HIKARI_MAX_POOL_SIZE`. `HIKARI_LEAK_DETECTION_MS=0` отключает leak detection.
- Параметры `APP_ENV`/`FLYWAY_MODE` и таблица выше применяются и для локальной разработки: при отсутствии переменных сохраняется дефолтное поведение (пул 20/2/5s/2s/10s, автоприменение миграций в dev/local).
