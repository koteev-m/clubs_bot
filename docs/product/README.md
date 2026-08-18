# Product rebaseline: как читать документы

Этот каталог отделяет исходный продуктовый замысел от целевого контракта и от фактически подключённого поведения repository. Первая базовая линия снята на commit `df7685facb52a6e5731a520669dfa2c73f6ccf24` 2026-08-17. Проценты готовности не используются: у требований нет согласованной весовой формулы.

## Порядок чтения

1. [PRODUCT_SPEC.md](PRODUCT_SPEC.md) — нормализованный целевой контракт с устойчивыми requirement IDs.
2. [AS_BUILT.md](AS_BUILT.md) — доказанное состояние production wiring на зафиксированном commit.
3. [CONCEPT_CODE_GAP.md](CONCEPT_CODE_GAP.md) — полная трассировка требований к коду, gaps и рекомендации.
4. [OPEN_DECISIONS.md](OPEN_DECISIONS.md) — решения, которые нельзя принять от имени пользователя.
5. [PRODUCT_ROADMAP.md](PRODUCT_ROADMAP.md) — dependency-aware последовательность, а не календарный план.
6. [CONCEPT_SOURCE.md](CONCEPT_SOURCE.md) — неизменяемый первичный источник намерения.

## Управление документами

`DEC-026`, option D, принят пользователем 2026-08-17. Принята ровно следующая governance policy:

1. [CONCEPT_SOURCE.md](CONCEPT_SOURCE.md) остаётся неизменяемым источником исходного замысла.
2. [PRODUCT_SPEC.md](PRODUCT_SPEC.md) является target product specification после применения явно принятых решений.
3. Каждое расхождение между concept, `AGENTS.md`, repository docs и code классифицируется отдельно как `KEEP`, `AMEND`, `DEFER` или `REJECT`.
4. Наличие требования в прежнем `AGENTS.md`, legacy docs или implementation в code не даёт capability автоматического product acceptance или priority.
5. До отдельного item-level решения disputed capability нельзя удалять, объявлять обязательной/принятой или выводить в пользовательскую navigation.
6. `AGENTS.md` служит короткой navigation/instruction map для repository, product docs, engineering rules и обязательных проверок, а не второй полной product spec.

Initial eight repository/source conflict items классифицированы пользователем 2026-08-18: семь `AMEND`, один `DEFER`. Classification не означает `AS_BUILT`, соответствие текущего кода или завершённую implementation. [PRODUCT_SPEC.md](PRODUCT_SPEC.md) содержит отдельный accepted-decision overlay layer, не смешанный со 170 immutable-source requirement rows.

Следующие baseline proposals не приняты `DEC-026` и остаются `DECISION_REQUIRED` там, где требуют выбора пользователя:

- решения пользователя только после их явного принятия и записи в decision log;
- [AS_BUILT.md](AS_BUILT.md) как снимок текущей реализации;
- production code и tests как доказательство исполнения;
- существующие repository documents — по granular reconciliation status из [AS_BUILT.md](AS_BUILT.md), а не с blanket-ярлыком historical.

## Статусы

| Статус | Значение |
|---|---|
| `SOURCE` | Прямо следует из неизменяемого `CONCEPT_SOURCE.md`. |
| `AS_BUILT` | Подтверждено production-кодом, фактическим wiring и runtime path. |
| `TEST_ONLY` | Поведение присутствует только в test code или test fixture. |
| `DOC_ONLY` | Утверждение существует только в старом документе/SOP. |
| `PRESENT_UNWIRED` | Код или asset существует, но не включён в production runtime path. |
| `PARTIAL` | Подтверждена только часть заявленного capability либо flow обрывается. |
| `GAP` | Требуемого поведения в relevant production path не найдено. |
| `UNKNOWN` | Repository не даёт достаточного доказательства. |
| `DECISION_REQUIRED` | Нужен явный выбор пользователя. |
| `ACCEPTED_DECISION` | Прямо принято пользователем и записано в decision record. |

Decision log содержит 35 records: 9 `ACCEPTED_DECISION` (`DEC-026`, `DEC-028`–`DEC-035`) и 26 `DECISION_REQUIRED`; `DEC-027` остаётся pending. Рекомендации имеют отдельные dispositions/defaults и не становятся принятым выбором через wording, dependency или существование кода.

## Правила доказательности

- `SOURCE` не означает `AS_BUILT`, а наличие класса, route, repository или asset само по себе не означает production availability.
- Для `AS_BUILT`, `PARTIAL` и `PRESENT_UNWIRED` указывается относительный path, ограниченный диапазон строк, symbol/route/test и объяснение фактического runtime значения.
- Для ключевого flow проверяется цепочка `entrypoint → route/router → handler/service → repository → persistence → response/UI`.
- Tests подтверждают контракт или регрессионную защиту, но не заменяют production wiring.
- Generated build output не используется как источник продукта; tracked source/assets и build packaging рассматриваются отдельно.
- Отсутствие найденного кода фиксируется как `GAP`, а не как доказательство того, что capability невозможно реализовать.
- Platform claims из source не считаются вечными фактами. В `PRODUCT_SPEC.md` они отделены как `SOURCE_PLATFORM_CLAIM`; проверенное на дату состояние либо необходимость повторной проверки отмечены отдельно.
- Каждый requirement в `PRODUCT_SPEC.md` имеет собственный bounded line locator в immutable source; family-level coverage не заменяет per-ID mapping.

## Неизменяемость source

`CONCEPT_SOURCE.md` — byte-for-byte копия attachment: SHA-256 `f44b72055694f4762ff1f1d935e9e3889e035acc598b487daf487fb42f8db027`, 36928 bytes, 702 строки. В нём нельзя исправлять формулировки, противоречия, ссылки или даты проверки. Нормализация, текущая проверка фактов и выводы аудита находятся только в соседних документах.

## Область первой базовой линии

Документы описывают repository на exact `origin/main` commit, а не развёрнутую внешнюю среду. VPS, Telegram API, реальные secrets, runtime data и deployment не проверялись. Поэтому утверждения о фактически запущенном экземпляре остаются `UNKNOWN`, если их нельзя доказать packaging и application wiring.
