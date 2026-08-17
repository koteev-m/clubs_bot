# AGENTS

## A. Repository product map

- [docs/product/README.md](docs/product/README.md) — порядок чтения и правила доказательности product docs.
- [docs/product/CONCEPT_SOURCE.md](docs/product/CONCEPT_SOURCE.md) — неизменяемый источник исходного продуктового замысла.
- [docs/product/PRODUCT_SPEC.md](docs/product/PRODUCT_SPEC.md) — целевая продуктовая спецификация после применения явно принятых решений.
- [docs/product/AS_BUILT.md](docs/product/AS_BUILT.md) — зафиксированный snapshot текущего production wiring, а не продуктовый приоритет.
- [docs/product/CONCEPT_CODE_GAP.md](docs/product/CONCEPT_CODE_GAP.md) — traceability, gaps, evidence и repository/source conflict register.
- [docs/product/OPEN_DECISIONS.md](docs/product/OPEN_DECISIONS.md) — принятые и требующие решения decision records.
- [docs/product/PRODUCT_ROADMAP.md](docs/product/PRODUCT_ROADMAP.md) — dependency-aware последовательность outcomes без календарных обещаний.

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

### Definition of Done (DoD)
- Изменение считается завершённым только если выполнены все пункты:
  1. Обновлён production-код.
  2. Добавлены/обновлены тесты под изменённое поведение.
  3. Выполнены проверки Gradle и зафиксирован результат (pass/fail с причиной).
- Минимум для локальной проверки: `./gradlew test`.
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
