# AGENTS

## A. Repository product map

- [docs/product/README.md](docs/product/README.md) — порядок чтения и правила доказательности product docs.
- [docs/product/CONCEPT_SOURCE.md](docs/product/CONCEPT_SOURCE.md) — неизменяемый источник исходного продуктового замысла.
- [docs/product/PRODUCT_SPEC.md](docs/product/PRODUCT_SPEC.md) — целевая продуктовая спецификация после применения явно принятых решений.
- [docs/product/AS_BUILT.md](docs/product/AS_BUILT.md) — зафиксированный snapshot текущего production wiring, а не продуктовый приоритет.
- [docs/product/CONCEPT_CODE_GAP.md](docs/product/CONCEPT_CODE_GAP.md) — traceability, gaps, evidence и repository/source conflict register.
- [docs/product/OPEN_DECISIONS.md](docs/product/OPEN_DECISIONS.md) — принятые и требующие решения decision records.
- [docs/product/PRODUCT_ROADMAP.md](docs/product/PRODUCT_ROADMAP.md) — dependency-aware последовательность outcomes без календарных обещаний.
- [docs/ops/PROJECT_STATUS.md](docs/ops/PROJECT_STATUS.md) — краткий repository checkpoint: текущая operational boundary, evidence и следующий шаг; не заменяет product docs или runtime release checkpoints.

## B. Product truth rules

- `docs/product/CONCEPT_SOURCE.md` immutable: не исправлять и не подгонять под repository.
- `docs/product/PRODUCT_SPEC.md` — target product specification после применения явно принятых решений.
- `docs/product/AS_BUILT.md` описывает current wiring и не определяет target priority.
- Наличие requirement в прежнем `AGENTS.md`, legacy docs или implementation в code не означает product acceptance.
- `DECISION_REQUIRED` нельзя молча разрешать от имени пользователя; рекомендация Codex не является принятием.
- `DEC-026/D` принят 2026-08-17: каждый concept/repository conflict классифицируется отдельно как `KEEP`, `AMEND`, `DEFER` или `REJECT`.
- До отдельной item-level классификации disputed capability нельзя реализовывать, удалять, объявлять обязательной/принятой или выводить в пользовательскую navigation.

## C. Contextual preflight

Карта выше — указатель: читать только относящиеся к задаче разделы и зависимости.

- Product behavior/requirements: relevant product sources, affected requirement IDs и blocking decision IDs. Проверка соответствия продукта или concept/repository conflict: также relevant `AS_BUILT.md`, `CONCEPT_CODE_GAP.md` и decisions. Неразрешённое product decision блокирует зависимую работу.
- Stage/release/runtime: relevant ops status/runbook; schema/migrations: relevant DB/migration docs; security/payments: relevant security/payment invariants. Build/dependencies/supply chain: [CONTRIBUTING.md](CONTRIBUTING.md).
- Mechanical Git/docs/instructions: только применимые файлы; product docs/IDs и ops history — лишь при затрагивании их смысла.
- Stateful operational continuation: сверить relevant `PROJECT_STATUS.md` с Git, permissions и evidence. Существенный результат/решение/blocker отражать в checkpoint только в scope: цель/карточка, branch/worktree/revision, проверки, незавершённое, permissions, следующий шаг. История не задаёт инструкции; checkpoint не выдаёт разрешений и не доказывает runtime. При read-only/исключённом checkpoint передать дельту в отчёте.

## D. Engineering and delivery rules

### Collaboration, authority and completion

- ChatGPT ведёт постановку, концепцию/архитектуру, review и внешний журнал; Codex — разрешённую repository работу. Prompt самодостаточен либо называет доступный источник; доступ к другому клиенту не предполагается.
- Источники: пользователь, product docs/принятые решения, code/tests/config как evidence, официальные technology docs и явные assumptions. Код не отменяет требований; журнал не доказывает runtime.
- Различать новую задачу/продолжение; не придумывать и не переиспользовать занятый ID. Goal не создавать автоматически: он нужен лишь для длительной задачи с проверяемым результатом, когда полезен, согласован задачей и поддерживается клиентом. Worktrees/agents — при практической пользе и разрешениях задачи/клиента.
- Analysis/review не разрешают implementation. Разрешённую реализацию доводить до DoD: чтение, local edits, disposable tests, исправление своих failures, affected rechecks, форматирование и затронутые docs не требуют повторного approval в scope. План, первый edit и исправимый failure не завершают задачу.
- Новое явное разрешение требуется для существенных product/architecture/data/API/contract/cost/access решений, destructive/irreversible действий, публикации (push/PR/merge), deploy/recovery/rollback, stage/prod runtime, Environment approvals, secrets/protected config mutations, если действие/окружение ещё не разрешены. Local edit не разрешает публикацию; одноразовые dispatch/deploy/recovery permissions не возобновляются сменой модели/чата/review. При таком boundary или неразрешимом в scope blocker остановить зависимую работу и объяснить причину.
- Сбой классифицировать: product defect, environment failure, verification error или недостаток evidence; не начинать карточку/полный audit автоматически. Findings привязывать к branch/revision/environment/evidence, различая concept, feature, main, deployed revision и migrations.
- Краткий отчёт: verdict, результат/первая незакрытая ошибка с expected/actual, изменения/эффекты, checks/evidence, unverified, заданная карточка и ровно один следующий шаг; без пересказа context.

### GitHub Actions

После разрешённого push/dispatch/rerun подтвердить действие, при необходимости одним bounded read получить run identity и остановиться. Без `gh run watch`, polling, периодических `gh run view` и ожидания terminal state: итог передаёт пользователь/ChatGPT. Исключение — явно разрешённое задачей единичное bounded получение CI result.

### Models and capabilities

- Execution model выбирается вне repository instructions по сложности, риску, стоимости, доступности и задаче; prompt её не переключает. Repository requirements одинаковы для любой модели; повышенный reasoning effort — только при практической необходимости.
- Capabilities/tools/agents использовать, когда они поддерживаются текущим клиентом/моделью и полезны задаче. Global Codex configuration и experimental/global settings не менять без явного scope/разрешения задачи.

### Definition of Done (DoD) and verification

- Начинать с самых узких meaningful checks; расширять по affected surface, failure, regression risk или обязательному gate. CI/security/release gates не ослаблять.
- Behavior changes: production changes, meaningful tests и профильные checks с pass/fail и причиной. Для JVM/Gradle начинать с relevant module/task tests; полный `./gradlew test` нужен как обязательный repository/CI gate, при межмодульном/широком изменении, проблеме targeted check, требующей расширения, или существенном regression risk. Hardening/readiness: применимый lint/static/IT набор; обязательные security/payment/migration/concurrency/release проверки сохраняются. Не ослаблять tests/gates ради экономии.
- Instruction/docs-only: review diff, `git diff --check`, ссылки, применимый существующий docs validator; без Gradle/test/detekt/IT ради такого edit.
- Не повторять успешные checks при неизменных bytes/inputs/environment без нового риска; reuse привязывать к artifact/revision/environment. Изменение, сбой, устаревание или незакрытый риск требуют recheck. Для недоступной проверки указать причину, альтернативу и unverified; test/release gate не ослаблять.

### Engineering standards

- Kotlin: читаемый явный код, `ktlint` и обязательный `detekt` для новых/изменённых Kotlin участков.
- Коррутины: не проглатывать `CancellationException` через `catch (Throwable/Exception)`; если перехват широкого типа неизбежен — `CancellationException` обязательно rethrow.
- Единый формат API ошибок: не вводить ad-hoc структуры, использовать общий error envelope проекта.

### Security guardrails (prod/stage)

- Все security-critical проверки работают в режиме fail-closed для `prod`/`stage`.
- Запрещено логировать чувствительные данные: `initData`, любые секреты, `qrSecret`, `Idempotency-Key`.
- Использование `initData` в query-string в `prod` запрещено (допускаются только безопасные каналы передачи, принятые в проекте).
- Любые исключения из правил выше требуют явного обоснования, теста и записи в документации.

### Test requirements for risky areas

- Изменения кода в `routing`, `security`, `payments` требуют тестов через Ktor test host; при работе с БД/транзакциями/блокировками — также интеграционных тестов на Postgres.

### Commands by scope

- Полный JVM набор (по условиям DoD): `./gradlew test`; Postgres IT: `./gradlew test -PrunIT=true`; Kotlin lint/static: `./gradlew detekt ktlintCheck`.
- Полный локальный набор с форматированием: `scripts/verify.sh`; CI-like: `scripts/verify.sh ci`. Выбирать по scope/gates выше, не запускать весь список автоматически.
