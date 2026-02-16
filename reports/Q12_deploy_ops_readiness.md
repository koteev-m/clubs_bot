# Q12 — Deploy & Ops Readiness Audit

## Scope
Аудит выполнен **без изменений кода**.
Проверены:
- Dockerfile / docker-compose / CI-deploy pipeline;
- соответствие `.env.example` и runtime-конфигурации (без раскрытия секретов);
- Flyway-миграции (авто-применение, validate-only, rollback-подход);
- health/readiness/liveness;
- логирование и ротация;
- масштабирование и statefulness;
- аварийный запуск / fallback (в т.ч. без mini app).

---

## 1) Что есть

## 1.1 Docker / контейнеризация
- Есть multi-stage `Dockerfile` (builder + runtime), pinned base images по digest.
- Runtime образ запускается от non-root пользователя `10001:10001`.
- Встроен `HEALTHCHECK` на `/health`.
- В `docker-compose.yml` есть сервисы `db`, `app`, `caddy`, healthcheck для DB и app.

## 1.2 Конфигурация и env
- `.env.example` покрывает большинство operational переменных: DB, Flyway, webhook secret, rate limits, Hikari, workers, observability.
- В коде DB-конфиг централизован (`DbConfig.fromEnv()` требует `DATABASE_URL/USER/PASSWORD`).
- Hikari-параметры централизованы и валидируются диапазонами с логированием clamp-ов.

## 1.3 Flyway и запуск миграций
- На старте приложения миграции выполняются централизованно через `installMigrationsAndDatabase()` + `MigrationRunner`.
- `MigrationRunner` поддерживает режимы `validate`, `migrate-and-validate`, `off`.
- Для prod/stage effective mode принудительно `validate` (даже если запросили migrate), при pending миграциях стартап фейлится.
- Есть отдельный CI workflow `db-migrate.yml` для controlled миграций (`FLYWAY_MODE=migrate-and-validate`).
- Есть отдельный CLI entrypoint `MigrateMain` для миграций.

## 1.4 Health / readiness / observability
- Есть health/readiness роуты в `ObservabilityRoutes`:
  - `/health` проверяет доступность DB (`SELECT 1`) с таймаутом;
  - `/ready` зависит от флага `MigrationState.migrationsApplied`.
- Есть `/metrics` (Prometheus scrape) через `installMetrics()`.
- В CI есть container smoke, который проверяет `/health` и `/ready`.

## 1.5 Логирование и ротация
- JSON-логирование через logback + `maskedMsg` converter и sensitive turbo filter.
- В prod включается file appender с time-based rolling и `maxHistory=30` дней.

## 1.6 Базовый deploy/ops контур
- Есть SSH deploy workflow (`deploy-ssh.yml`) c проверкой `/ready` после `docker compose up`.
- Есть smoke-процедуры: Makefile + `scripts/smoke.sh`.
- Supply-chain и pinned actions policy документированы и частично автоматизированы guard-джобой.

## 1.7 Аварийный fallback без mini app
- Есть документированный fallback UX через Telegram-команды (`/qr`, `/my`, `/ask`, `/cancel`) в `docs/p2.4-discovery-bot-fallbacks.md`.
- Это покрывает базовые user/ops-сценарии при деградации mini app.

---

## 2) Чего нет / риски

## P0
1. **Риск несогласованности readiness в runtime:**
   - `/ready` и `/health` определены в `ObservabilityRoutes`, но в `Application.module()` явно не вызываются `healthRoute()/readinessRoute()`;
   - одновременно в `Application.module()` есть только простой `get("/health") { "OK" }`.
   - При этом deploy/smoke ожидают рабочий `/ready`.

2. **Отсутствует формализованный rollback playbook для схемы + приложения как единой процедуры**
   (есть DR/PITR общая политика, но нет пошагового «release rollback» runbook с decision-tree по миграциям несовместимости).

## P1
1. **Нет helm/k8s deployment manifests в репозитории**
   (есть security рекомендации и k8s snippets в docs, но не готовый production manifest/chart).

2. **Stateful-in-memory компоненты затрудняют горизонтальное масштабирование без внешней координации:**
   - in-memory hold/idempotency/session/token stores;
   - при multi-replica возможны несогласованные локальные состояния между pod/instance.

3. **Rollback deploy-стратегия в `deploy-ssh.yml` ограничена**
   - есть readiness-check после up,
   - но нет автоматического rollback на предыдущий image tag при fail readiness.

4. **Отсутствуют k8s-native probes/manifests в кодовой поставке**
   - есть Docker healthcheck и CI smoke,
   - но нет декларативных liveness/readiness probes для кластера (если целевой runtime — Kubernetes).

5. **Секрет-менеджмент описан через env, но нет встроенной политики ротации/провайдера секретов в репозитории**
   (Vault/SSM/Secrets Manager integration и rotation jobs находятся вне кода).

---

## 3) MVP-рекомендации для прод-запуска

## 3.1 Обязательный минимум до go-live
1. Закрепить **единый источник truth для health/readiness**:
   - гарантировать подключение `/health` и `/ready` из `ObservabilityRoutes` в runtime;
   - убрать дублирующиеся/упрощённые health-роуты, которые могут маскировать проблемы БД/миграций.

2. Зафиксировать **release rollback runbook** (MVP документ):
   - app rollback (N-1 image);
   - DB rollback policy (обычно forward-fix + PITR fallback);
   - критерии «когда делаем PITR vs когда forward-fix migration».

3. Ввести **операционный чек-лист релиза**:
   - preflight (DB connectivity, pending migrations, secrets present);
   - post-deploy checks (`/health`, `/ready`, critical smoke);
   - критерии автоматической/ручной отмены релиза.

## 3.2 Масштабирование и state
1. Для горизонтального скейла вынести критичный state из памяти в внешнее хранилище:
   - HOLD/idempotency/session/token stores в Redis/DB;
   - единые TTL/purge политики на shared storage.

2. Зафиксировать mode:
   - либо singleton instance (как ограничение архитектуры),
   - либо multi-instance с shared-state (целевой прод режим).

## 3.3 Инфраструктурный слой
1. Если target = Kubernetes: добавить минимальный chart/manifests:
   - Deployment, Service, Ingress;
   - readiness/liveness probes;
   - resource requests/limits;
   - securityContext из `docs/runtime-security.md`.

2. Для SSH deploy добавить auto-rollback шаг:
   - при fail `/ready` откат на previous known-good tag + логирование причины.

## 3.4 Secrets и config governance
1. Оставить `.env.example` как контракт, но хранить секреты только в secret manager.
2. Добавить runbook ротации для:
   - `TELEGRAM_BOT_TOKEN` / `BOT_TOKEN`;
   - `WEBHOOK_SECRET_TOKEN`;
   - `DATABASE_PASSWORD`;
   - `QR_SECRET` (+ процедура dual-secret ротации).

## 3.5 Аварийный запуск / деградация
1. Оформить «degraded mode» как официальный SOP:
   - mini app down => командный fallback (уже описан в docs);
   - кто и как переключает коммуникацию для staff/guests;
   - какие метрики/alerts подтверждают восстановление.

---

## 4) Итог
Репозиторий уже содержит сильный базис для prod-эксплуатации (контейнеризация, Flyway policy, health/metrics заготовки, smoke/deploy pipelines, security docs). Критичные пробелы для уверенного прод-запуска: единообразный readiness wiring, формальный rollback runbook и стратегия работы со state при горизонтальном масштабировании.
