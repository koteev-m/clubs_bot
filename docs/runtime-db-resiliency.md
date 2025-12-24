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
* Подключенческие/временные ошибки (`SQLTransientException`, `SQLTransientConnectionException`, `PSQLException.isTransient == true`, `SQLState` класса 08).

Not retryable:
* Ошибки ограничений (`SQLState` 23xxx, например 23505 unique_violation).
* Бизнес-исключения и прочие runtime-ошибки.

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
* `db.breaker.opened` — сколько раз открывался circuit breaker.

## Использование
* Новый API: `withRetriedTx(name = "label", readOnly = true) { /* Exposed DSL */ }` — helper сам открывает транзакцию и применяет retry/backoff.
* Для совместимости оставлен `withTxRetry { ... }`, но предпочтительно переходить на `withRetriedTx` без вложенного `transaction { ... }`.
* При открытом breaker helper быстро выбрасывает `DatabaseUnavailableException` (стоит маппить в 503, если наверху уже есть соответствующий слой).
