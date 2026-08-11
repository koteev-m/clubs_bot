# Устойчивость к сбоям БД

Централизованный helper `withRetriedTx` обеспечивает повторное выполнение транзакций, метрики и простой circuit breaker.

## Настройки retry/backoff
* `DB_TX_MAX_RETRIES` — максимальное число повторов (по умолчанию 3).
* `DB_TX_BASE_BACKOFF_MS` — базовая задержка перед первым ретраем (по умолчанию 500 мс).
* `DB_TX_MAX_BACKOFF_MS` — потолок задержки (по умолчанию 15 000 мс).
* `DB_TX_JITTER_MS` — добавочный джиттер [0..jitter] мс, чтобы разнести одновременные ретраи (по умолчанию 100 мс).

Хелпер использует экспоненциальный backoff с джиттером и прекращает попытки после исчерпания лимита.

## Классификация ошибок
Retryable:
* PostgreSQL `SQLState` 40001 (serialization failure) и 40P01 (deadlock detected).
* Подключенческие/временные ошибки (`SQLTransientException`, `SQLTransientConnectionException`, `SQLState` класса 08).

Not retryable:
* Ошибки ограничений (`SQLState` 23xxx, например 23505 unique_violation).
* Бизнес-исключения и прочие runtime-ошибки.

Дополнительно для бизнес-кода доступны помощники:
* `isUniqueViolation()` — проверяет наличие `SQLState=23505` в стеке причин.
* `isRetryLimitExceeded()` — возвращает `true`, если в стеке есть `SQLState` 40001 или 40P01 (serialization/deadlock), что удобно, когда все ретраи уже исчерпаны.

## Circuit breaker
При серии connection-ошибок helper открывает breaker и быстро отвечает `DatabaseUnavailableException` без попытки создания новых транзакций.

Параметры:
* `DB_BREAKER_THRESHOLD` — сколько connection-фейлов подряд нужно для открытия (по умолчанию 5).
* `DB_BREAKER_WINDOW_SECONDS` — окно для подсчёта фейлов (по умолчанию 30 секунд).
* `DB_BREAKER_OPEN_SECONDS` — сколько держать breaker открытым перед автосбросом (по умолчанию 20 секунд).

## Метрики (Micrometer/Prometheus)
* `db.tx.retries{reason="deadlock|serialization|connection"}` — счётчик ретраев.
* `db.tx.failures{reason="deadlock|serialization|connection|constraint|other"}` — счётчик падений.
* `db.tx.duration{readOnly="true|false"}` — длительность транзакций.
* `db.breaker.opened` — сколько раз circuit breaker переходил в состояние open (открывался) после серии connection-ошибок.
* `db.migrations.validate.success|failure`, `db.migrations.migrate.success|failure`, `db.migrations.pending` — метрики для валидации/применения Flyway миграций (см. раздел ниже).

Порог для логирования медленных транзакций задаётся через `DB_SLOW_QUERY_MS` (по умолчанию 200 мс). Значение > 0 включает `WARN` при превышении порога; `DB_SLOW_QUERY_MS=0` отключает предупреждения, оставляя только метрику `db.tx.duration`.

При старте приложение логирует эффективные значения retry/backoff/slow-query/breaker конфигурации, чтобы упростить диагностику окружения.

## Migration metrics
- В core-слое используется `NoOpDbMigrationMetrics` по умолчанию; Micrometer-биндинг `MicrometerDbMigrationMetrics` подключается в `MetricsPlugin` (модуль `app-bot`).
- Метрики `db.migrations.validate.success|failure` инкрементируются при `validate`, `db.migrations.migrate.success|failure` — при `migrate-and-validate`; gauge `db.migrations.pending` сохраняет последнее известное количество отложенных миграций.
- `db.migrations.migrate.success` аккумулирует общее число применённых миграций (`migrationsExecuted`), при `appliedCount=0` счётчик не меняется.
- В prod/stage приложение лишь валидирует схему. Миграции выполняются внутри полного quiesced release через
  `.github/workflows/deploy-ssh.yml` либо вручную подтверждённый `.github/workflows/db-migrate.yml`; оба workflow
  до Flyway закрепляют verified schema-compatible digest в managed Compose override и останавливают app/workers.
  Flyway запускается отдельным `/opt/app/bin/app-bot-migrate` в one-off Compose container из этого exact digest,
  наследуя DB environment/network сервиса `app`; runner checkout и отдельные runner DB secrets не используются.
  После успешного exit, validate и zero-pending check lock записывает migration digest/image ID, а application
  запускается только при совпадении обоих значений. Обычный app startup остаётся validate-only.
- Public `app-bot-migrate` удаляет JVM option injection variables и через `exec` запускает private
  `app-bot-migrate-java`, который использует отдельный packaged Logback contract: root/Flyway выключены, pgjdbc JUL сброшен,
  а наружу выходят только фиксированные `migration-safe:v=1` start/completion/failure events с allowlisted
  phase/category. Throwable/message, JDBC URL, DB identity/credentials, query parameters и stack trace не выводятся.
  Remote helper сохраняет полный output как mode `0600` внутри maintenance lock, но в CI реконструирует только
  allowlisted события. Raw stream обязан содержать ровно `started` и один соответствующий exit-status terminal
  event; unknown/malformed/duplicate/out-of-order/control-byte output остаётся fail-closed. Этот контракт защищён
  container sentinel tests и не является обещанием безопасности произвольной будущей library configuration.

## Использование
* Новый API: `withRetriedTx(name = "label", readOnly = true) { /* Exposed DSL */ }` — helper сам открывает транзакцию и применяет retry/backoff.
* Для совместимости оставлен `withTxRetry { ... }`, но предпочтительно переходить на `withRetriedTx` без вложенного `transaction { ... }`.
* При открытом breaker helper быстро выбрасывает `DatabaseUnavailableException` (стоит маппить в 503, если наверху уже есть соответствующий слой).

## Flyway migration parity policy (Postgres vs H2)
- `common` миграции считаются обязательной частью любой среды и должны применяться вместе с вендорными миграциями.
- Политика parity для тестов: **Postgres — источник истины для критичных инвариантов** (partial unique, блокировки/конкурентность, строгие DB-ограничения).
- H2 остаётся быстрым контуром для unit/smoke сценариев, но не рассматривается как полноценная замена Postgres по конкурентному поведению и вендорным особенностям.
- Для инвариантов уровня продовой консистентности используются интеграционные тесты на Testcontainers Postgres (`@Tag("it")`).
