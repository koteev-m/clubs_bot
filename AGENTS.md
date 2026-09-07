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

## C. Codex task preflight

Перед product/code task Codex обязан:

1. Прочитать relevant product docs.
2. Назвать affected requirement IDs.
3. Назвать blocking decision IDs.
4. Проверить evidence в `AS_BUILT.md` и `CONCEPT_CODE_GAP.md`.
5. Остановиться, если outcome требует неразрешённого product decision.
6. Не подгонять source concept под current code.
7. Не считать recommendation принятым решением.

## D. Engineering and delivery rules

=== REPO DELIVERY RULES (обязательно для всех задач) ===

### Collaboration, authority and task records

- ChatGPT ведёт анализ, концепцию, архитектурные решения, постановку задач, проверку отчётов и внешний журнал; Codex исследует repository, выполняет разрешённые локальные edits/команды, проверки и обновляет затронутую repository documentation. Prompt не предполагает доступ к переписке или файлам другого клиента: он должен быть самодостаточным либо называть доступный источник.
- Источники: прямые указания пользователя, актуальные product docs и принятые решения, code/tests/config как evidence текущей реализации, официальная документация технологий и явно названные engineering assumptions. Код не отменяет требований, а журнал не доказывает runtime.
- Всегда различать продолжение существующей карточки и новую задачу; не переиспользовать занятый номер. Goal не создаётся автоматически: в отчёте фиксируется точное название карточки и решение о необходимости Goal.
- При начале или продолжении задачи читать `docs/ops/PROJECT_STATUS.md` и сверять относящиеся сведения с фактическим Git, разрешениями и доступным evidence. После существенного результата, решения, blocker либо остановки адресно обновлять checkpoint, если это разрешено scope; сохранять цель, карточку, branch/worktree/revision, проверки, незавершённые операции, границы разрешений и следующий шаг. Историю отделять от active instructions и не дублировать весь документ. Для read-only задач checkpoint не менять: передавать нужную дельту в итоговом отчёте. Checkpoint не выдаёт разрешений и не заменяет product sources, Git, tests или runtime evidence.
- Разрешения определяются целью, окружением и допустимыми действиями. Анализ или review не разрешают implementation; локальный edit не разрешает публикацию. Существенные product/data/contract/cost/access изменения требуют отдельного решения. Одноразовые разрешения на dispatch/deploy/recovery не возобновляются сменой модели, чата или review.
- После сбоя сначала классифицировать product defect, environment failure, verification error или недостаток evidence; не создавать новую карточку либо полный audit автоматически. Findings и отчёты привязывать к commit/branch, environment и evidence, отделяя concept, feature, main, deployed revision и migrations.
- Проверки можно переиспользовать только с ясной привязкой к неизменному artifact, revision и environment; повторять их при изменении, сбое, устаревании или незакрытом риске. Недоступную проверку сопровождать причиной, альтернативой и перечнем непроверенного; не ослаблять test или release gate ради успешного отчёта.
- После verdict сообщать сделанное, success либо первую ошибку с expected/actual, проверки, изменения и побочные эффекты, непроверенное и evidence, затем ровно один следующий шаг.

### Models and experimental capabilities

- Выбор модели зависит от риска и содержания: substantive code, backend/frontend changes, fixes, refactoring и meaningful tests — GPT-6 Astra, medium; содержательное review преимущественно Astra; mechanical documentation, Git integration уже проверенного кода и CI verification — GPT-5.6 Terra, medium. GPT-5.6 Sol — полноценная альтернатива при недоступности Astra, экономии либо иной обоснованной пользе.
- Сложная межмодульная работа, transactions, concurrency, security, migrations и трудная диагностика могут использовать Astra с повышенным effort только при обоснованной необходимости и поддержке клиента. Не требовать сначала дешёвую модель и не назначать автоматически max/Ultra/additional agents. После прямого решения пользователя возвращаться к экономному выбору Terra/Sol/Astra по сложности.
- Текст prompt не переключает модель исполнения. Special capabilities используются только при подтверждённой поддержке модели и клиента; не применять usage resets и не покупать credits.
- Experimental context management не включается глобально и не меняет `config.toml`; недокументированные notes/history не предполагаются доступными. Отдельная несекретная проба требует предварительного согласования и не заменяет журнал, Git, tests или checkpoint.

### Definition of Done (DoD)
- Изменение поведения завершается только если обновлены production code, tests для изменённого поведения и применимые Gradle checks с зафиксированным результатом (pass/fail с причиной). Минимум для такой локальной проверки: `./gradlew test`.
- Чистая редакция инструкций или документации не требует production-code changes, новых tests либо большой Gradle suite; вместо этого нужны проверка собственного diff, whitespace, ссылок и применимый быстрый documentation validator. Это не ослабляет обязательные code/security/release checks для затрагивающих их задач.
- Для задач по hardening и readiness дополнительно прогонять lint/static/IT набор.

### Engineering standards
- Kotlin style: соблюдаем `ktlint`; читаемый и явный код без скрытой магии.
- Static analysis: `detekt` обязателен для новых/изменённых участков.
- Коррутины: не проглатывать `CancellationException` через `catch (Throwable/Exception)`; если перехват широкого типа неизбежен — `CancellationException` обязательно rethrow.
- Единый формат API ошибок: не вводить ad-hoc структуры, использовать общий error envelope проекта.

### Security guardrails (prod/stage)
- Все security-critical проверки работают в режиме fail-closed для `prod`/`stage`.
- Запрещено логировать чувствительные данные: `initData`, любые секреты, `qrSecret`, `Idempotency-Key`.
- Использование `initData` в query-string в `prod` запрещено (допускаются только безопасные каналы передачи, принятые в проекте).
- Любые исключения из правил выше требуют явного обоснования, теста и записи в документации.

### Test requirements for risky areas
- Любые изменения в `routing`, `security`, `payments` обязаны сопровождаться:
  - тестами через Ktor test host;
  - и (где есть работа с БД/транзакциями/блокировками) интеграционными тестами на Postgres.

### Recommended commands
- Базовый прогон: `./gradlew clean test`
- Интеграционные тесты: `./gradlew test -PrunIT=true`
- Линт и статанализ: `./gradlew detekt ktlintCheck`
- Форматирование + проверки + тесты: `scripts/verify.sh`
- CI-like прогон локально: `scripts/verify.sh ci`
