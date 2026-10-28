# Нормализованная продуктовая спецификация

Статус документа: target product specification после применения явно принятых решений к первой базовой линии для review. Строки `SOURCE` нормализуют, но не расширяют immutable [CONCEPT_SOURCE.md](CONCEPT_SOURCE.md). Строки `DECISION_REQUIRED` фиксируют невыбранный вариант или контракт. `DEC-026/D` принят пользователем 2026-08-17: repository-only capabilities не становятся target requirements автоматически; до отдельной item-level классификации `KEEP`, `AMEND`, `DEFER` или `REJECT` unresolved repository-only capability не является target promise и не может появляться в target navigation. Ни один тип не означает, что capability реализован. Неурегулированные варианты вынесены в [OPEN_DECISIONS.md](OPEN_DECISIONS.md).

Каждая из 170 строк имеет собственный bounded `Source locator`. Для строки `SOURCE` указанные диапазоны подтверждают её продуктовый смысл; для строки `DECISION_REQUIRED` locator указывает точное место source, создающее контекст или tension, но не выдаёт предлагаемый вариант решения за текст source.

## 1. Эпистемическая рамка

### 1.1. Факты source

- `SOURCE`: продукт задуман как единая Telegram-система «бот + Mini App» для клубной сети и клубных операций.
- `SOURCE`: iBota описан как сквозной помощник для всех ролей, но с ограничением role/scope и обязательным подтверждением критических действий.
- `SOURCE`: source содержит платформенные утверждения Telegram. Они не превращаются в бессрочные требования платформы и проверяются отдельно.

### 1.2. Оценки source

- `SOURCE`, оценка: Guest Mode назван наиболее сильным UX-усилением для вызова iBota в рабочих и гостевых чатах.
- `SOURCE`, оценка: streaming draft улучшает ощущение живого ответа, но не заменяет финальное сообщение и подтверждение операции.

### 1.3. Гипотезы source

- `SOURCE`, гипотеза: AI-форма может сократить время операций промо/Host/менеджера в `2–4 раза`. Это не доказанный KPI и не обещание результата.
- `SOURCE`, гипотеза: AI-сводка ночи/сети может повысить управляемость и дисциплину данных. Критерий проверки source не задаёт.

## 2. Платформенные утверждения

Проверка выполнена 2026-08-17 только по официальным материалам Telegram. `CURRENT_VERIFICATION_REQUIRED` означает, что утверждение надо перепроверять перед проектированием/релизом, даже если оно подтверждено на дату этого аудита.

| Source claim | Классификация | Текущее состояние на 2026-08-17 |
|---|---|---|
| Bot API 10.0 добавил Guest Mode, `guest_message`, `guest_query_id`, `answerGuestQuery`. | `SOURCE_PLATFORM_CLAIM` | Подтверждено официальными [Bot API](https://core.telegram.org/bots/api) и [Bot Features](https://core.telegram.org/bots/features); `CURRENT_VERIFICATION_REQUIRED`. |
| Guest bot получает вызов/reply context, не историю/участников, даёт один ответ; можно упомянуть до трёх guest bots. | `SOURCE_PLATFORM_CLAIM` | Подтверждено разделом Guest Bots в [Bot Features](https://core.telegram.org/bots/features); `CURRENT_VERIFICATION_REQUIRED`. |
| `sendMessageDraft` даёт временный streaming preview около 30 секунд и требует финальный `sendMessage`. | `SOURCE_PLATFORM_CLAIM` | Подтверждено [Bot API](https://core.telegram.org/bots/api#sendmessagedraft); `CURRENT_VERIFICATION_REQUIRED`. |
| Обычный broadcast ограничен примерно 30 msg/s; paid mode — до 1000 msg/s и 0.1 Stars за успешно отправленное сообщение сверх free amount, с дополнительными eligibility conditions. | `SOURCE_PLATFORM_CLAIM` | Подтверждено официальным [Bots FAQ](https://core.telegram.org/bots/faq#broadcasting-to-users) и параметром `allow_paid_broadcast` в [Bot API](https://core.telegram.org/bots/api); `CURRENT_VERIFICATION_REQUIRED`. |
| Bot-to-bot требует dedup, rate limit, depth/timeouts. | `SOURCE_PLATFORM_CLAIM` | Подтверждено [Bot Features](https://core.telegram.org/bots/features#bot-to-bot-communication); `CURRENT_VERIFICATION_REQUIRED`. |
| Connected business bots получают business updates и могут действовать через `business_connection_id` в пределах прав. | `SOURCE_PLATFORM_CLAIM` | Подтверждено [Bot Features](https://core.telegram.org/bots/features#secretary-bots) и [Bot API](https://core.telegram.org/bots/api); `CURRENT_VERIFICATION_REQUIRED`. |
| Local Bot API позволяет upload до 2000 MB, в отличие от hosted API. | `SOURCE_PLATFORM_CLAIM` | Подтверждено разделом [Using a Local Bot API Server](https://core.telegram.org/bots/api#using-a-local-bot-api-server); `CURRENT_VERIFICATION_REQUIRED`. |

В source ссылка/дата проверки остаются неизменными; эта таблица — отдельная текущая проверка, а не правка source.

## 3. Product identity и network model

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `PROD-001` | Система должна объединять в Telegram bot и Mini App: брони, гостевые списки, вход, столы, финансы, лояльность, музыку, коммуникации, аналитику, безопасность и degraded operations. | `SOURCE` | `CONCEPT_SOURCE.md:L32-L46 — §0 «Что это за приложение и зачем оно нужно»` | `RBAC-001`, `NET-001` |
| `PROD-002` | iBota должна быть сквозным слоем доступа к разрешённым действиям, а не отдельным несвязанным продуктом. | `SOURCE` | `CONCEPT_SOURCE.md:L48-L54 — §0 «Главное изменение v2.0»; CONCEPT_SOURCE.md:L629-L642 — §18.1–18.2 «Где живёт и как отвечает iBota»` | `RBAC-003`, `AISAFE-001` |
| `PROD-003` | Нужно выбрать единый public product name и assistant name для guest/staff surfaces. | `DECISION_REQUIRED` | `CONCEPT_SOURCE.md:L3-L4 — вводный блок «Telegram Club OS / iBota»; CONCEPT_SOURCE.md:L48-L54 — §0 «Главное изменение v2.0»` | `UX-001`, `IBCHAT-001` |
| `NET-001` | Модель должна поддерживать несколько клубов и выбор клуба гостем. | `SOURCE` | `CONCEPT_SOURCE.md:L32-L45 — §0 «Telegram Club OS»; CONCEPT_SOURCE.md:L147-L156 — §3.1 «Что видит гость»` | `CAT-001`, `RBAC-002` |
| `NET-002` | Данные и действия должны разделяться на уровень конкретного клуба и уровень сети. | `SOURCE` | `CONCEPT_SOURCE.md:L73-L84 — §1.2–1.3 «Scope доступа / принцип iBota»` | `RBAC-002`, `ANL-004` |
| `NET-003` | Owner/сеть должен видеть межклубные сравнения и trends. | `SOURCE` | `CONCEPT_SOURCE.md:L524-L529 — §13.1 «Owner/сеть: сравнение клубов и тренды»` | `ANL-004` |

## 4. Role/scope model и critical actions

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `RBAC-001` | Role catalogue: Гость, глобальный Промоутер, Host/Менеджер входа, Менеджер зала, Менеджер клуба, Админ клуба, Финансовый менеджер клуба, DJ, Owner; Главный админ и Главный менеджер сети — опциональны. | `SOURCE` | `CONCEPT_SOURCE.md:L60-L71 — §1.1 «Роли»` | `NET-002` |
| `RBAC-002` | Scope catalogue: `GLOBAL` для сети и `CLUB` для конкретного клуба. | `SOURCE` | `CONCEPT_SOURCE.md:L73-L76 — §1.2 «Scope доступа»` | `NET-001` |
| `RBAC-003` | iBota определяет роль пользователя, проверяет `GLOBAL`/`CLUB` scope и показывает только разрешённые действия и данные. | `SOURCE` | `CONCEPT_SOURCE.md:L78-L94 — §1.3 «Помогает всем, но делает только разрешённое»` | `RBAC-001`, `RBAC-002` |
| `RBAC-004` | Бронь, отмена, посадка, депозит, доплата, закрытие смены и корректировка требуют явного подтверждения кнопкой/формой. | `SOURCE` | `CONCEPT_SOURCE.md:L96-L101 — §1.4 «Критические операции требуют подтверждения»` | `AISAFE-002` |
| `RBAC-005` | Денежные операции, корректировки, закрытие смены и смена ролей должны логироваться; опасные изменения требуют причины. | `SOURCE` | `CONCEPT_SOURCE.md:L617-L619 — §17.2 «Аудит опасных действий»` | `SEC-003` |
| `RBAC-006` | Guest, staff и management surfaces должны показывать только разрешённые роли и scope capabilities. | `SOURCE` | `CONCEPT_SOURCE.md:L78-L84 — §1.3 «Роль и scope»; CONCEPT_SOURCE.md:L209-L223 — §5.1 «Общая навигация Mini App»` | `UX-002`, `UX-003` |

## 5. Календарь и операционная ночь

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `CAL-001` | Операционная ночь — интервал в TZ клуба, способный пересекать календарные сутки и сохраняющий business date ночи начала. | `SOURCE` | `CONCEPT_SOURCE.md:L107-L112 — §2.1 «Операционная ночь»` | `NET-002` |
| `CAL-002` | Клуб настраивает рабочие дни недели и время начала/окончания. | `SOURCE` | `CONCEPT_SOURCE.md:L114-L118 — §2.2 «Настройки календаря»` | `CAL-001` |
| `CAL-003` | Календарь учитывает исключения, дополнительные/праздничные ночи и TZ клуба. | `SOURCE` | `CONCEPT_SOURCE.md:L114-L121 — §2.2 «Настройки календаря»` | `CAL-001` |
| `CAL-004` | Guest surfaces показывают только рабочие ночи и ближайшую доступную ночь. | `SOURCE` | `CONCEPT_SOURCE.md:L114-L128 — §2.2–2.3 «Рабочий календарь и ближайшая ночь»` | `CAL-002`, `CAL-003` |
| `CAL-005` | Booking cut-off равен «конец ночи минус настраиваемое значение», source default — 2 часа. | `SOURCE` | `CONCEPT_SOURCE.md:L114-L121 — §2.2 «Cut-off бронирований»; CONCEPT_SOURCE.md:L184-L190 — §4.1 «Инварианты бронирования»` | `CAL-001`, `BKG-001` |
| `CAL-006` | Изменение календаря уведомляет подписанных гостей/гостей с бронью, промоутеров со списками и управленцев. | `SOURCE` | `CONCEPT_SOURCE.md:L135-L141 — §2.3 «Уведомления при изменениях календаря»` | `COM-001`, `GL-001`, `BKG-004` |
| `CAL-007` | iBota может отвечать о доступности и создавать предзаполненный draft изменения календаря, но применяет его только после подтверждения. | `SOURCE` | `CONCEPT_SOURCE.md:L123-L133 — §2.3 «iBota в календаре»; CONCEPT_SOURCE.md:L96-L101 — §1.4 «Подтверждение»` | `AIFORM-001`, `AISAFE-002` |
| `CAL-008` | При draft изменения времени раннего прихода iBota предупреждает о влиянии на gamification logic и требует подтверждения. | `SOURCE` | `CONCEPT_SOURCE.md:L130-L133 — §2.3 «Изменение раннего прихода»` | `LOY-002`, `AISAFE-002` |

## 6. Club catalogue и content

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `CAT-001` | Гость видит список доступных клубов. | `SOURCE` | `CONCEPT_SOURCE.md:L147-L150 — §3.1 «Список клубов»` | `NET-001` |
| `CAT-002` | Карточка клуба содержит описание, адрес/маршрут, афиши/события, фотоотчёты и музыку DJ. | `SOURCE` | `CONCEPT_SOURCE.md:L149-L155 — §3.1 «Карточка клуба»` | `MUS-001`, `COM-002` |
| `CAT-003` | Карточка предоставляет CTA «Забронировать стол» и «Задать вопрос». | `SOURCE` | `CONCEPT_SOURCE.md:L149-L156 — §3.1 «CTA карточки клуба»` | `BKG-001`, `SUP-005` |
| `CAT-004` | iBota-консьерж уточняет запрос, предлагает 2–3 подходящих клуба/зоны и открывает правильную ночь/схему/фильтр. | `SOURCE` | `CONCEPT_SOURCE.md:L158-L166 — §3.2 «iBota как консьерж»` | `CAL-004`, `BKG-002`, `AISAFE-001` |

## 7. Guest booking и HOLD lifecycle

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `BKG-001` | Booking flow начинается выбором клуба и рабочей ночи. | `SOURCE` | `CONCEPT_SOURCE.md:L172-L176 — §4.1 «Основной поток бронирования»` | `CAT-001`, `CAL-004` |
| `BKG-002` | Гость открывает схему, выбирает стол, после чего создаётся HOLD. | `SOURCE` | `CONCEPT_SOURCE.md:L174-L176 — §4.1 «Ночь, схема, стол, HOLD»` | `HOLD-001`, `ONB-004` |
| `BKG-003` | До подтверждения гость вводит число гостей, видит расчёт депозита, передаёт Telegram username/опциональный телефон и принимает правила. | `SOURCE` | `CONCEPT_SOURCE.md:L177-L181 — §4.1 «Данные и подтверждение брони»` | `DEP-002`, `SEC-001` |
| `BKG-004` | Явное подтверждение создаёт бронь и выдаёт подтверждение вместе с Night Pass. | `SOURCE` | `CONCEPT_SOURCE.md:L180-L182 — §4.1 «Подтверждение и Night Pass»` | `RBAC-004`, `PASS-001` |
| `BKG-005` | Гость может отменить бронь до последнего момента. | `SOURCE` | `CONCEPT_SOURCE.md:L184-L190 — §4.1 «Инварианты бронирования»` | `HOLD-004` |
| `BKG-006` | Бронь удерживается 30 минут после заявленного времени прихода, затем lifecycle может перевести её в no-show/released. | `SOURCE` | `CONCEPT_SOURCE.md:L184-L190 — §4.1 «30 минут после времени прихода»; CONCEPT_SOURCE.md:L317-L327 — §7.1 «Освобождение / no-show»` | `TOPS-005`, `CAL-001` |
| `BKG-007` | Только менеджер может продлить post-arrival удержание. | `SOURCE` | `CONCEPT_SOURCE.md:L184-L190 — §4.1 «Продление менеджером»` | `RBAC-001`, `RBAC-002`, `BKG-006` |
| `BKG-008` | iBota преобразует свободный текст в booking draft, но не подтверждает бронь, не меняет сумму и не продлевает HOLD самостоятельно. | `SOURCE` | `CONCEPT_SOURCE.md:L192-L203 — §4.2 «iBota в бронировании и границы»` | `AIFORM-001`, `AISAFE-002` |
| `BKG-009` | iBota может напомнить гостю о существующей брони в пределах разрешённого guest context. | `SOURCE` | `CONCEPT_SOURCE.md:L644-L647 — §18.3 «iBota для гостя»` | `IBCHAT-002`, `COM-007` |
| `HOLD-001` | Выбор стола должен создавать HOLD конкретного стола/ночи на ограниченный TTL. | `SOURCE` | `CONCEPT_SOURCE.md:L174-L176 — §4.1 «Выбор стола и HOLD»; CONCEPT_SOURCE.md:L199-L203 — §4.2 «HOLD TTL по правилам»` | `BKG-002` |
| `HOLD-002` | У пользователя не может быть одновременно двух активных HOLD. | `SOURCE` | `CONCEPT_SOURCE.md:L184-L190 — §4.1 «Нельзя два HOLD одновременно»` | `SEC-006` |
| `HOLD-003` | HOLD должен истекать по правилам и не может удерживаться бесконечно. | `SOURCE` | `CONCEPT_SOURCE.md:L199-L203 — §4.2 «HOLD не держится бесконечно»` | `CAL-005` |
| `HOLD-004` | Lifecycle должен явно различать HOLD, подтверждение, отмену, post-arrival удержание, no-show и освобождение. | `SOURCE` | `CONCEPT_SOURCE.md:L172-L190 — §4.1 «Booking/HOLD lifecycle»; CONCEPT_SOURCE.md:L317-L327 — §7.1 «Освобождение / no-show»` | `BKG-004`, `BKG-005`, `BKG-006` |

## 8. Mini App navigation и surfaces

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `UX-001` | Canonical guest navigation включает: Клубы, Календарь, Схема/Столы, Мои брони, Пропуск, Мои ночи, Музыка, Вопросы и доступ к iBota. | `SOURCE` | `CONCEPT_SOURCE.md:L209-L223 — §5.1 «Общая навигация Mini App»; CONCEPT_SOURCE.md:L633-L637 — §18.1 «Где живёт iBota»` | соответствующие guest domains |
| `UX-002` | Host/вход получает role-specific surfaces для scanner, поиска и входных операций. | `SOURCE` | `CONCEPT_SOURCE.md:L209-L223 — §5.1 «Role-specific sections»; CONCEPT_SOURCE.md:L305-L311 — §6.4 «Host/вход»` | `CHK-005`, `CHK-006` |
| `UX-003` | Персоналу и управленцам открываются role-specific sections/navigation и operational actions/checklists для входа, зала, stop-sales/Undo и закрытия финансовой смены. | `SOURCE` | `CONCEPT_SOURCE.md:L209-L223 — §5.1 «Role-specific sections»; CONCEPT_SOURCE.md:L543-L548 — §14.1 «Чек-листы по ролям»` | `RBAC-001`, `RBAC-002` |
| `UX-004` | В Mini App присутствует заметная кнопка iBota, открывающая chat panel. | `SOURCE` | `CONCEPT_SOURCE.md:L225-L233 — §5.2 «iBota внутри Mini App»; CONCEPT_SOURCE.md:L633-L637 — §18.1 «Где живёт iBota»` | `IBAPP-001` |
| `UX-005` | iBota объясняет текущий экран, предзаполняет форму из текста и сообщает следующий незавершённый шаг. | `SOURCE` | `CONCEPT_SOURCE.md:L225-L233 — §5.2 «iBota внутри Mini App»` | `AIFORM-001`, `IBAPP-002` |
| `UX-006` | При отказе rich surface продукт должен предлагать bounded chat/list fallback для критического действия. | `SOURCE` | `CONCEPT_SOURCE.md:L588-L598 — §16 «Работа при сбоях»` | `DEG-001`, `DEG-003` |

## 9. Guest lists, invitations и Night Pass

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `GL-001` | Промоутер создаёт GuestList массовой вставкой строк ФИО и добавлением по одному. | `SOURCE` | `CONCEPT_SOURCE.md:L248-L259 — §6.1 «Список ФИО»` | `RBAC-001`, `RBAC-002`, `CAL-004` |
| `GL-002` | Host ищет ФИО/запись в списке и отмечает ARRIVED; отсутствие отметки означает, что гость не пришёл. | `SOURCE` | `CONCEPT_SOURCE.md:L250-L259 — §6.1 «Host ищет и отмечает пришёл»` | `CHK-001` |
| `GL-003` | Промоутер может создать персональную invitation link/QR, ведущую гостя в bot и к Night Pass. | `SOURCE` | `CONCEPT_SOURCE.md:L261-L268 — §6.1 «Приглашение по QR/ссылке»` | `PASS-001`, `CHK-005` |
| `GL-004` | После разбора списка iBota предлагает сгенерировать внутренние/внешние приглашения; точный смысл каналов и delivery contract требуют решения. | `SOURCE` + `DECISION_REQUIRED` | `CONCEPT_SOURCE.md:L294-L303 — §6.4 «iBota промоутеру»` | `GL-003`, `AIFORM-002` |
| `GL-005` | iBota разбирает список, нормализует формат, удаляет дубли, подсвечивает ошибки и создаёт только подтверждаемый draft. | `SOURCE` | `CONCEPT_SOURCE.md:L294-L303 — §6.4 «Разбор списка в draft»` | `AIFORM-002`, `AISAFE-002` |
| `GL-006` | Outcome списка сохраняет attribution к промоутеру и различает приглашённого, пришедшего и посаженного. | `SOURCE` | `CONCEPT_SOURCE.md:L248-L268 — §6.1 «Списки и приглашения»; CONCEPT_SOURCE.md:L621-L625 — §17.3 «ARRIVED/SEATED anti-fraud»` | `SEC-004`, `ANL-001` |
| `GL-007` | iBota может объяснить промоутеру его quality outcome и предложить направления улучшения без изменения фактических данных. | `SOURCE` | `CONCEPT_SOURCE.md:L524-L537 — §13 «Role analytics»; CONCEPT_SOURCE.md:L644-L647 — §18.3 «iBota для промо»` | `ANL-001`, `AIAN-001` |
| `PASS-001` | На пользователя и операционную ночь существует один Night Pass; guest UI не показывает два QR. | `SOURCE` | `CONCEPT_SOURCE.md:L270-L283 — §6.2 «Один Night Pass для всего»` | `CAL-001`, `SEC-006` |
| `PASS-002` | Night Pass является общим ключом check-in, раннего прихода/штампа, розыгрышей и опциональной привязки к столу. | `SOURCE` | `CONCEPT_SOURCE.md:L270-L283 — §6.2 «Night Pass как общий ключ»` | `CHK-007`, `LOY-001`, `TOPS-002` |
| `PASS-003` | QR/код должен быть «живым» и защищённым от простой пересылки в рамках явно выбранной rotation/validation policy. | `SOURCE` + `DECISION_REQUIRED` по формату | `CONCEPT_SOURCE.md:L285-L290 — §6.3 «Живой QR и защита от повторов»` | `SEC-006` |
| `PASS-004` | Один scan должен связать guest-list/booking/table context без создания параллельных посещений. | `SOURCE` | `CONCEPT_SOURCE.md:L174-L182 — §4.1 «Booking выдаёт Night Pass»; CONCEPT_SOURCE.md:L280-L290 — §6.2–6.3 «Связь контекстов и антидубль»` | `CHK-001`, `CHK-002` |

## 10. Check-in и QR

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `CHK-001` | Для пользователя допускается один check-in на операционную ночь. | `SOURCE` | `CONCEPT_SOURCE.md:L270-L290 — §6.2–6.3 «Один Night Pass / повторный scan»; CONCEPT_SOURCE.md:L621-L625 — §17.3 «Один приход»` | `PASS-001`, `CAL-001` |
| `CHK-002` | Повторный scan не создаёт вторую отметку; точный repeat-response contract является техническим решением. | `SOURCE` | `CONCEPT_SOURCE.md:L285-L290 — §6.3 «Повторный scan не создаёт отметку»` | `SEC-006` |
| `CHK-003` | Вход различает arrival и denied outcomes для операционки, финансов и anti-fraud; точная status taxonomy и обязательность причины source не определяет. | `SOURCE` + `DECISION_REQUIRED` | `CONCEPT_SOURCE.md:L364-L369 — §8.2 «Люди и отказы»; CONCEPT_SOURCE.md:L543-L548 — §14.1 «Вход: скан/поиск/отказы»; CONCEPT_SOURCE.md:L621-L625 — §17.3 «No-show/denied»` | `GL-006`, `FIN-003`, `SEC-005` |
| `CHK-004` | iBota помогает Host выполнить быстрый поиск, предлагает возможные причины расхождения и безопасный script ответа гостю, не принимая решение о входе. | `SOURCE` | `CONCEPT_SOURCE.md:L305-L311 — §6.4 «iBota Host/входу»` | `CHK-006`, `AISAFE-001` |
| `CHK-005` | Host сканирует единый Night Pass, а система определяет связанный source context. | `SOURCE` | `CONCEPT_SOURCE.md:L261-L283 — §6.1–6.2 «Host сканирует Night Pass»` | `PASS-004` |
| `CHK-006` | При недоступности scanner доступны поиск и ручной журнал. | `SOURCE` | `CONCEPT_SOURCE.md:L590-L598 — §16 «Scanner fallback»` | `DEG-002` |
| `CHK-007` | Посещение, ранний приход, штамп и eligibility начисляются только вследствие принятого check-in. | `SOURCE` | `CONCEPT_SOURCE.md:L395-L421 — §9.1–9.3 «Посещение только после check-in»` | `LOY-001` |

## 11. Table operations и денежные операции

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `TOPS-001` | Staff видит актуальные статусы свободных/занятых столов. | `SOURCE` | `CONCEPT_SOURCE.md:L317-L320 — §7.1 «Статусы столов»` | `CAL-001`, `BKG-004` |
| `TOPS-002` | Посадка поддерживает режимы «депозит», «по счёту» и «от клуба» и при необходимости связывает Night Pass. | `SOURCE` | `CONCEPT_SOURCE.md:L317-L324 — §7.1 «Посадка и депозит»; CONCEPT_SOURCE.md:L280-L283 — §6.2 «Привязка Night Pass к столу»` | `PASS-002`, `DEP-002` |
| `TOPS-003` | После role/scope check iBota может ответить менеджеру, какие столы, включая VIP, сейчас свободны. | `SOURCE` | `CONCEPT_SOURCE.md:L88-L94 — §1.3 «Пример VIP столов»; CONCEPT_SOURCE.md:L644-L650 — §18.3 «iBota менеджеру зала»` | `TOPS-001`, `AISAFE-001` |
| `TOPS-004` | Менеджер освобождает стол действием «гости ушли». | `SOURCE` | `CONCEPT_SOURCE.md:L317-L327 — §7.1 «Освобождение стола»` | `TOPS-001` |
| `TOPS-005` | Lifecycle поддерживает no-show для неподтверждённого прихода. | `SOURCE` | `CONCEPT_SOURCE.md:L317-L327 — §7.1 «No-show»` | `BKG-006` |
| `TOPS-006` | Менеджер может остановить продажи до конца ночи и отменить остановку через Undo; это не ручное открытие финансовой смены. | `SOURCE` | `CONCEPT_SOURCE.md:L343-L352 — §7.3 «Stop-sales и Undo»` | `CAL-001`, `FIN-001` |
| `TOPS-007` | iBota предзаполняет формы посадки/освобождения/stop-sales, но действие выполняется только после проверки и подтверждения менеджером. | `SOURCE` | `CONCEPT_SOURCE.md:L329-L352 — §7.2–7.3 «Предзаполненные операции столов»; CONCEPT_SOURCE.md:L676-L680 — §19 «Текст → форма → подтвердить»` | `RBAC-004`, `AIFORM-001` |
| `DEP-001` | Депозит, доплата и охрана фиксируются как отдельные операции. Repository invariant дополнительно запрещает перезапись итоговой суммы; его precedence относительно source регулирует `DEC-026`. | `SOURCE` + repository conflict | `CONCEPT_SOURCE.md:L317-L324 — §7.1 «Депозит, доплата, охрана»; CONCEPT_SOURCE.md:L329-L341 — §7.2 «Отдельные заполненные операции»` | `RBAC-005`, `DEC-026` |
| `DEP-002` | Посадка может создать первичную депозитную операцию. | `SOURCE` | `CONCEPT_SOURCE.md:L317-L324 — §7.1 «Внесение депозита»; CONCEPT_SOURCE.md:L329-L336 — §7.2 «Форма посадки с депозитом»` | `TOPS-002` |
| `DEP-003` | Последующая доплата создаётся отдельной операцией. | `SOURCE` | `CONCEPT_SOURCE.md:L321-L324 — §7.1 «Доплаты»; CONCEPT_SOURCE.md:L338-L341 — §7.2 «Доплата»` | `DEP-001` |
| `DEP-004` | «Личная охрана» — отдельная услуга/операция. | `SOURCE` | `CONCEPT_SOURCE.md:L321-L324 — §7.1 «Личная охрана»; CONCEPT_SOURCE.md:L338-L341 — §7.2 «Услуга: охрана»` | `DEP-001` |
| `DEP-005` | Распределение депозита поддерживает настроенные статьи, включая бар, шары и 50/50. | `SOURCE` | `CONCEPT_SOURCE.md:L317-L324 — §7.1 «Распределение депозита»` | `ONB-006` |
| `DEP-006` | После закрытия смены денежные операции заморожены; исключение возможно только по супер-праву, с причиной и аудитом. | `SOURCE` | `CONCEPT_SOURCE.md:L358-L362 — §8.1 «Freeze после закрытия»; CONCEPT_SOURCE.md:L617-L619 — §17.2 «Причина и audit»; CONCEPT_SOURCE.md:L655-L662 — §18.4 «Super-right boundary»` | `FIN-008`, `RBAC-005` |
| `DEP-007` | iBota может подготовить сумму/распределение/услугу, но не проводит их без подтверждения. | `SOURCE` | `CONCEPT_SOURCE.md:L329-L341 — §7.2 «iBota готовит денежные формы»; CONCEPT_SOURCE.md:L655-L662 — §18.4 «Без проводки без подтверждения»` | `AISAFE-002` |

## 12. Financial shift

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `FIN-001` | Финансовая смена открывается автоматически по операционной ночи. | `SOURCE` | `CONCEPT_SOURCE.md:L358-L362 — §8.1 «Смена открывается автоматически»` | `CAL-001` |
| `FIN-002` | Закрыть смену может только финансовый менеджер соответствующего клуба. | `SOURCE` | `CONCEPT_SOURCE.md:L358-L362 — §8.1 «Закрывает финансовый менеджер»` | `RBAC-001`, `RBAC-002` |
| `FIN-003` | В смене фиксируются женщины, мужчины и отказы. | `SOURCE` | `CONCEPT_SOURCE.md:L364-L369 — §8.2 «Люди»` | `CHK-003` |
| `FIN-004` | Типы браслетов настраиваются; source defaults включают бесплатный, платный, стол, VIP, backstage и допускают DJ. | `SOURCE` | `CONCEPT_SOURCE.md:L370-L374 — §8.2 «Типы браслетов»` | `ONB-006` |
| `FIN-005` | Статьи выручки настраиваются и имеют флаг «в общую/не в общую». | `SOURCE` | `CONCEPT_SOURCE.md:L375-L379 — §8.2 «Статьи выручки»` | `ONB-006` |
| `FIN-006` | Депозитные карты учитываются отдельной метрикой и по умолчанию не входят в общую выручку. | `SOURCE` | `CONCEPT_SOURCE.md:L375-L379 — §8.2 «Депозитные карты»` | `FIN-005` |
| `FIN-007` | Перед закрытием система показывает сверку и расхождение с операциями столов. | `SOURCE` | `CONCEPT_SOURCE.md:L381-L383 — §8.2 «Сверка со столами»` | `DEP-001` |
| `FIN-008` | После закрытия данные заморожены; корректировка требует супер-роли, причины и аудита. | `SOURCE` | `CONCEPT_SOURCE.md:L358-L362 — §8.1 «Freeze»; CONCEPT_SOURCE.md:L617-L619 — §17.2 «Причина и audit»; CONCEPT_SOURCE.md:L655-L662 — §18.4 «Super-right»` | `RBAC-005`, `DEP-006` |
| `FIN-009` | iBota помогает с вводом, подсвечивает аномалии и готовит итоговый отчёт, но не закрывает смену самостоятельно. | `SOURCE` | `CONCEPT_SOURCE.md:L385-L389 — §8.3 «iBota для финансиста»; CONCEPT_SOURCE.md:L96-L101 — §1.4 «Подтверждение закрытия»` | `AIAN-001`, `AISAFE-002` |

## 13. Loyalty и gamification

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `LOY-001` | Штамп посещения начисляется по клубу только после check-in и один раз за ночь. | `SOURCE` | `CONCEPT_SOURCE.md:L395-L401 — §9.1 «Штамп только после check-in»; CONCEPT_SOURCE.md:L621-L625 — §17.3 «Один штамп»` | `CHK-001`, `CHK-007` |
| `LOY-002` | Ранний приход определяется настраиваемым временем клуба/ночи. | `SOURCE` | `CONCEPT_SOURCE.md:L395-L401 — §9.1 «Ранний приход по правилу клуба»; CONCEPT_SOURCE.md:L412-L421 — §9.3 «Правило раннего прихода этой ночи»` | `CAL-001` |
| `LOY-003` | Бейджи имеют настраиваемые пороги посещений/ранних/активностей и русские названия. | `SOURCE` | `CONCEPT_SOURCE.md:L395-L410 — §9.1–9.2 «Бейджи и русские названия»; CONCEPT_SOURCE.md:L423-L426 — §9.3 «Пороги badge ladder»` | `LOY-001`, `LOY-002` |
| `LOY-004` | Розыгрыши можно включать и выключать. | `SOURCE` | `CONCEPT_SOURCE.md:L395-L401 — §9.1 «Розыгрыши on/off»` | `ONB-007` |
| `LOY-005` | Розыгрыш имеет настраиваемые период и условия; закрытый каталог типов условий source не задаёт. | `SOURCE` + `DECISION_REQUIRED` | `CONCEPT_SOURCE.md:L395-L410 — §9.1–9.2 «Период, условия и guest progress»` | `LOY-001` |
| `LOY-006` | Table loyalty является накопительной и настраиваемой по клубу. | `SOURCE` | `CONCEPT_SOURCE.md:L395-L401 — §9.1 «Лояльность по столам»` | `DEP-001` |
| `LOY-007` | «Мои ночи» показывает штампы, русские бейджи, активные условия розыгрышей и table-loyalty progress. | `SOURCE` | `CONCEPT_SOURCE.md:L403-L410 — §9.2 «Мои ночи»` | `UX-001` |
| `LOY-008` | iBota объясняет начисление по фактическому check-in/правилу и создаёт подтверждаемый draft badge ladder. | `SOURCE` | `CONCEPT_SOURCE.md:L412-L426 — §9.3 «iBota в геймификации»` | `AIAN-001`, `AIFORM-001` |

## 14. Support/questions

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `SUP-001` | Guest question categories: адрес, правила/дресс-код, списки/вход, бронь/депозит, потеря вещи, жалоба/сервис, другое. | `SOURCE` | `CONCEPT_SOURCE.md:L432-L442 — §10.1 «Категории вопросов»` | `CAT-002` |
| `SUP-002` | Source перечисляет ticket statuses `NEW`, `IN_PROGRESS`, `WAITING`, `RESOLVED`, `CLOSED`, но не задаёт порядок или граф переходов; lifecycle policy требует `DEC-025`. | `SOURCE` + `DECISION_REQUIRED` | `CONCEPT_SOURCE.md:L444-L449 — §10.2 «Inbox и перечисленные статусы»` | `RBAC-001`, `RBAC-002`, `DEC-025` |
| `SUP-003` | Для жалобы iBota предлагает структурированную форму «что случилось / когда / кто / контакт». | `SOURCE` | `CONCEPT_SOURCE.md:L451-L455 — §10.3 «Форма жалобы»` | `SUP-001`, `AIFORM-002` |
| `SUP-004` | Администратор отвечает гостю из internal inbox. | `SOURCE` | `CONCEPT_SOURCE.md:L444-L449 — §10.2 «Ответ гостю из панели»` | `RBAC-001`, `RBAC-002`, `COM-007` |
| `SUP-005` | CTA «Задать вопрос» доступен в guest journey. | `SOURCE` | `CONCEPT_SOURCE.md:L149-L156 — §3.1 «CTA карточки»; CONCEPT_SOURCE.md:L432-L442 — §10.1 «Кнопка всегда доступна»` | `CAT-003`, `UX-001` |
| `SUP-006` | iBota классифицирует, предлагает подтверждаемый draft ответа и структурирует жалобу, не отправляя ответ за администратора без подтверждения. | `SOURCE` | `CONCEPT_SOURCE.md:L451-L455 — §10.3 «Классификация и draft ответа»` | `AIFORM-001`, `AISAFE-002` |

## 15. DJ/music/files/interactions

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `MUS-001` | DJ загружает track/set файлами и задаёт обложку, описание, tags, клуб и ночь. | `SOURCE` | `CONCEPT_SOURCE.md:L461-L465 — §11.1 «Файлы и metadata DJ»` | `RBAC-001`, `CAL-001` |
| `MUS-002` | Публикация поддерживает включаемую moderation policy. | `SOURCE` | `CONCEPT_SOURCE.md:L461-L465 — §11.1 «Модерация»` | `RBAC-001`, `RBAC-002` |
| `MUS-003` | Ночь может иметь «главный трек ночи». | `SOURCE` | `CONCEPT_SOURCE.md:L466-L470 — §11.1 «Главный трек ночи»` | `CAL-001` |
| `MUS-004` | Guest interaction включает голосования и реакции. | `SOURCE` | `CONCEPT_SOURCE.md:L466-L470 — §11.1 «Голосования/реакции»` | `UX-001` |
| `MUS-005` | Гость может поддержать DJ донатом/чаевыми в выбранной payment policy. | `SOURCE` + `DECISION_REQUIRED` по payment boundary | `CONCEPT_SOURCE.md:L466-L470 — §11.1 «Поддержка DJ»` | `RBAC-004` |
| `MUS-006` | DJ получает статистику music content и interactions в пределах своей роли. | `SOURCE` | `CONCEPT_SOURCE.md:L461-L470 — §11.1 «Статистика для DJ»; CONCEPT_SOURCE.md:L644-L653 — §18.3 «iBota для DJ»` | `RBAC-001`, `RBAC-002` |
| `MUS-007` | Для слишком большого файла продукт предлагает части/укороченную версию либо осознанно включённый режим большего лимита. | `SOURCE` | `CONCEPT_SOURCE.md:L472-L478 — §11.2 «Ограничения файлов»` | platform verification |
| `MUS-008` | iBota готовит metadata, анонс и draft интерактива, но публикация/рассылка требуют подтверждения. | `SOURCE` | `CONCEPT_SOURCE.md:L480-L484 — §11.3 «iBota для DJ»; CONCEPT_SOURCE.md:L655-L660 — §18.4 «Предпросмотр и подтверждение»` | `COM-002`, `AISAFE-004` |

## 16. Communications и broadcasts

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `COM-001` | Система отправляет уведомления о добавлении/отмене/изменении ночи релевантным сегментам. | `SOURCE` | `CONCEPT_SOURCE.md:L135-L141 — §2.3 «Calendar audiences»; CONCEPT_SOURCE.md:L490-L497 — §12.1 «Типы коммуникаций»` | `CAL-006` |
| `COM-002` | Content communications включают афиши, анонсы, фотоотчёты, DJ releases и промо-акции. | `SOURCE` | `CONCEPT_SOURCE.md:L490-L497 — §12.1 «Content communications»` | `CAT-002`, `MUS-001` |
| `COM-003` | Гость управляет подписками. | `SOURCE` | `CONCEPT_SOURCE.md:L499-L503 — §12.2 «Настройки подписок»` | `SEC-001` |
| `COM-004` | Отправка соблюдает frequency limits и quiet hours. | `SOURCE` | `CONCEPT_SOURCE.md:L499-L503 — §12.2 «Частота и тихие часы»` | platform limits |
| `COM-005` | Обычная массовая отправка throttled; paid acceleration включается Owner осознанно и только после проверки platform eligibility/cost. | `SOURCE` | `CONCEPT_SOURCE.md:L505-L511 — §12.3 «Mass/paid broadcast»` | `RBAC-004` |
| `COM-006` | iBota предлагает сегмент и варианты текста, проверяет ограничения и показывает Telegram preview до подтверждения. | `SOURCE` | `CONCEPT_SOURCE.md:L513-L518 — §12.4 «iBota для коммуникаций»; CONCEPT_SOURCE.md:L655-L660 — §18.4 «Preview + confirmation»` | `AISAFE-004` |
| `COM-007` | Служебные уведомления staff и guest lifecycle notifications имеют отдельную адресацию и не раскрывают лишние данные. | `SOURCE` | `CONCEPT_SOURCE.md:L490-L497 — §12.1 «Служебные уведомления»; CONCEPT_SOURCE.md:L604-L615 — §17.1 «Public/private data boundary»` | `RBAC-001`, `RBAC-002`, `SEC-001` |

## 17. Analytics/reports

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `ANL-001` | Промоутер видит только свои списки, arrived/not-arrived и quality outcome. | `SOURCE` | `CONCEPT_SOURCE.md:L524-L529 — §13.1 «Промо: свои списки и качество»` | `GL-006`, `RBAC-001`, `RBAC-002` |
| `ANL-002` | Менеджер клуба видит night summary, no-show и table utilization своего клуба. | `SOURCE` | `CONCEPT_SOURCE.md:L524-L529 — §13.1 «Менеджер клуба: сводка/no-show/столы»` | `TOPS-001`, `RBAC-001`, `RBAC-002` |
| `ANL-003` | Финансист видит финансы и reconciliation своего клуба. | `SOURCE` | `CONCEPT_SOURCE.md:L524-L529 — §13.1 «Финансист: финансы/сверка»` | `FIN-007`, `RBAC-001`, `RBAC-002` |
| `ANL-004` | Owner/сеть видит сравнение клубов и trends. | `SOURCE` | `CONCEPT_SOURCE.md:L524-L529 — §13.1 «Owner: сравнение и trends»` | `NET-003`, `RBAC-001`, `RBAC-002` |
| `ANL-005` | iBota отвечает на natural-language analytic queries только по данным, разрешённым роли и scope. | `SOURCE` | `CONCEPT_SOURCE.md:L78-L94 — §1.3 «Role/scope»; CONCEPT_SOURCE.md:L531-L537 — §13.2 «Аналитик по запросу»` | `AIAN-001`, `AISAFE-001` |
| `ANL-006` | В Owner/network context iBota может подсвечивать риски и alerts, но source не задаёт их каталог, thresholds или автоматические действия. | `SOURCE` + `DECISION_REQUIRED` | `CONCEPT_SOURCE.md:L644-L653 — §18.3 «Owner: риски и алерты»` | `ANL-004`, `AIAN-001` |

## 18. Role checklists и operating procedures

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `OPS-001` | Role checklists охватывают вход, зал, менеджера клуба и финансы с действиями, перечисленными в source. | `SOURCE` | `CONCEPT_SOURCE.md:L543-L548 — §14.1 «Чек-листы по ролям»` | `CHK-005`, `TOPS-006`, `FIN-002` |
| `OPS-002` | Сотрудник получает короткую пошаговую инструкцию для role-specific операции. | `SOURCE` | `CONCEPT_SOURCE.md:L550-L555 — §14.2 «Короткая инструкция по шагам»` | `RBAC-003` |
| `OPS-003` | iBota-инструктаж не заменяет authorization, confirmation и audit operation. | `SOURCE`, нормализация safety boundary | `CONCEPT_SOURCE.md:L96-L101 — §1.4 «Confirmation»; CONCEPT_SOURCE.md:L550-L555 — §14.2 «Инструктаж»; CONCEPT_SOURCE.md:L617-L619 — §17.2 «Audit»` | `AISAFE-001`, `AISAFE-002` |
| `OPS-004` | Source называет «золотые правила», но не задаёт их содержание, владельца, версионирование или enforcement; это остаётся `DECISION_REQUIRED`. | `DECISION_REQUIRED` | `CONCEPT_SOURCE.md:L541-L548 — §14 «Золотые правила / чек-листы»` | `OPS-001` |
| `OPS-005` | Для менеджера клуба iBota объединяет сводку ночи, stop-sales/Undo, помощь с конфликтами и контроль promo outcomes в пределах club scope. | `SOURCE` | `CONCEPT_SOURCE.md:L531-L537 — §13.2 «Сводка»; CONCEPT_SOURCE.md:L644-L650 — §18.3 «iBota менеджеру клуба»` | `ANL-002`, `TOPS-006`, `SEC-004` |

## 19. Club onboarding без code changes

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `ONB-001` | Owner добавляет новый клуб через единый master без изменения кода. | `SOURCE` | `CONCEPT_SOURCE.md:L559-L565 — §15.1 «Новый клуб без изменения кода»` | `RBAC-001`, `RBAC-002`, `NET-001` |
| `ONB-002` | Master настраивает profile и contacts. | `SOURCE` | `CONCEPT_SOURCE.md:L565-L568 — §15.1 «Профиль и контакты»` | `CAT-002` |
| `ONB-003` | Master настраивает weekly calendar, exceptions, holidays и TZ. | `SOURCE` | `CONCEPT_SOURCE.md:L565-L569 — §15.1 «Календарь master»; CONCEPT_SOURCE.md:L114-L121 — §2.2 «Настройки календаря, включая TZ клуба»` | `CAL-002`, `CAL-003` |
| `ONB-004` | Master создаёт zones/tables, загружает hall plan и связывает hotspots со столами. | `SOURCE` | `CONCEPT_SOURCE.md:L565-L570 — §15.1 «Зоны, столы, схема, hotspots»` | `BKG-002`, `TOPS-001` |
| `ONB-005` | Master настраивает booking/HOLD/cut-off/arrival-by rules. | `SOURCE` | `CONCEPT_SOURCE.md:L565-L572 — §15.1 «Booking/HOLD/cut-off/arrival-by»` | `CAL-005`, `HOLD-001`, `BKG-006` |
| `ONB-006` | Master настраивает bracelet/revenue categories, total flags и deposit allocation. | `SOURCE` | `CONCEPT_SOURCE.md:L317-L324 — §7.1 «Распределение депозита по настройкам»; CONCEPT_SOURCE.md:L565-L573 — §15.1 «Финансы master»` | `FIN-004`, `FIN-005`, `DEP-005` |
| `ONB-007` | Master включает/выключает gamification, music, raffles и table loyalty. | `SOURCE` | `CONCEPT_SOURCE.md:L565-L574 — §15.1 «Переключатели модулей»` | `LOY-005`, `LOY-006`, `MUS-001` |
| `ONB-008` | Master назначает personnel roles/scopes. | `SOURCE` | `CONCEPT_SOURCE.md:L60-L76 — §1.1–1.2 «Роли/scopes»; CONCEPT_SOURCE.md:L565-L574 — §15.1 «Назначение персонала»` | `RBAC-001`, `RBAC-002` |
| `ONB-009` | iBota предзаполняет master, подсвечивает обязательные поля и ждёт подтверждения Owner. | `SOURCE` | `CONCEPT_SOURCE.md:L576-L584 — §15.2 «iBota как мастер настройки»` | `AIFORM-002`, `AISAFE-002` |

## 20. Failure/degraded modes

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `DEG-001` | При отказе Mini App critical map/table action деградирует до списка. | `SOURCE` | `CONCEPT_SOURCE.md:L590-L594 — §16.1 «Mini App → список столов»` | `UX-006`, `TOPS-001` |
| `DEG-002` | При отказе scanner вход деградирует до поиска/ручного журнала. | `SOURCE` | `CONCEPT_SOURCE.md:L590-L594 — §16.1 «Scanner → поиск/журнал»` | `CHK-006` |
| `DEG-003` | При замедлении bot доступны упрощённые bounded actions. | `SOURCE` | `CONCEPT_SOURCE.md:L590-L594 — §16.1 «Bot → упрощённые действия»` | `UX-006` |
| `DEG-004` | iBota выдаёт краткий инцидент-протокол, но не скрывает факт деградации и не обходит permissions. | `SOURCE` | `CONCEPT_SOURCE.md:L596-L598 — §16.2 «Инцидент-помощник»; CONCEPT_SOURCE.md:L78-L94 — §1.3 «Permissions»` | `OPS-002`, `AISAFE-001` |

## 21. Security, audit и fraud prevention

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `SEC-001` | В публичном/неизвестном chat context iBota отвечает только общими фактами/ссылками и не раскрывает PII. | `SOURCE` | `CONCEPT_SOURCE.md:L604-L615 — §17.1 «Без PII в публичном context»` | `GM-002` |
| `SEC-002` | Персональные данные и admin actions доступны только в личном bot chat или Mini App после authorization. | `SOURCE` | `CONCEPT_SOURCE.md:L604-L615 — §17.1 «Personal/admin only private»` | `RBAC-001`, `RBAC-002` |
| `SEC-003` | Денежные операции, корректировки, закрытие смены и role changes требуют audit trail и причины. | `SOURCE` | `CONCEPT_SOURCE.md:L617-L619 — §17.2 «Audit и причина»` | `RBAC-005` |
| `SEC-004` | Promoter quality измеряется ARRIVED/SEATED outcome, а не числом вписанных имён. | `SOURCE` | `CONCEPT_SOURCE.md:L621-L625 — §17.3 «ARRIVED/SEATED quality»` | `GL-006`, `ANL-001` |
| `SEC-005` | Fraud controls включают лимиты и flags по no-show/denied. | `SOURCE` | `CONCEPT_SOURCE.md:L621-L625 — §17.3 «No-show/denied flags»` | `CHK-003`, `TOPS-005` |
| `SEC-006` | Защита от дублей обеспечивает один приход, один штамп и один бонус на допустимую единицу учёта. | `SOURCE` | `CONCEPT_SOURCE.md:L621-L625 — §17.3 «Один приход/штамп/бонус»` | `CHK-001`, `LOY-001` |

## 22. Сквозной контракт iBota

### 22.1. iBota в private bot chat

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `IBCHAT-001` | В private bot chat существует явный entry «iBota». | `SOURCE` | `CONCEPT_SOURCE.md:L633-L637 — §18.1 «iBota внутри бота»` | `PROD-002` |
| `IBCHAT-002` | iBota маршрутизирует guest/staff request к разрешённому capability или безопасной справке. | `SOURCE` | `CONCEPT_SOURCE.md:L78-L94 — §1.3 «Role/scope»; CONCEPT_SOURCE.md:L644-L653 — §18.3 «Capabilities по ролям»` | `RBAC-003`, `AISAFE-001` |
| `IBCHAT-003` | Streaming draft допустим как preview; финальное сообщение/форма фиксирует результат или подтверждение. | `SOURCE` | `CONCEPT_SOURCE.md:L639-L642 — §18.2 «Draft stream и финальная операция»` | platform verification, `AISAFE-002` |

### 22.2. iBota в Mini App

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `IBAPP-001` | Mini App имеет явную кнопку iBota и chat panel. | `SOURCE` | `CONCEPT_SOURCE.md:L225-L233 — §5.2 «Кнопка/chat-panel»; CONCEPT_SOURCE.md:L633-L637 — §18.1 «iBota в Mini App»` | `UX-004` |
| `IBAPP-002` | Panel объясняет текущий экран, использует данные формы для draft и подсказывает следующий шаг; exact context-minimization contract требует отдельного технического решения. | `SOURCE` | `CONCEPT_SOURCE.md:L225-L233 — §5.2 «Объяснение экрана и следующий шаг»` | `UX-005`, `SEC-001` |
| `IBAPP-003` | Panel создаёт form draft, а финальное действие выполняется только после подтверждения пользователя; точная business-validation/commit integration является техническим решением, не source promise. | `SOURCE` + `DECISION_REQUIRED` | `CONCEPT_SOURCE.md:L225-L233 — §5.2 «Заполнение формы»; CONCEPT_SOURCE.md:L639-L642 — §18.2 «Финальное подтверждение»` | `AIFORM-001`, `AISAFE-002` |

### 22.3. Guest Mode

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `GM-001` | После явного platform enablement iBota может принять guest call в чате, где bot не состоит, и дать один ответ по полученному context. | `SOURCE` | `CONCEPT_SOURCE.md:L12-L13 — «Факты Telegram / Guest Mode»; CONCEPT_SOURCE.md:L235-L242 — §5.3 «iBota в любом чате»` | platform verification |
| `GM-002` | Guest Mode не считается authenticated private session и не раскрывает историю, участников, PII или admin data/action. | `SOURCE` | `CONCEPT_SOURCE.md:L12-L13 — «Guest Mode context limits»; CONCEPT_SOURCE.md:L604-L615 — §17.1 «Public/private policy»` | `SEC-001`, `SEC-002` |
| `GM-003` | Guest Mode сценарии охватывают общий guest concierge и безопасные staff questions, но любое персональное/критическое продолжение переводится в private bot/Mini App. | `SOURCE` | `CONCEPT_SOURCE.md:L20-L23 — «Оценки и critical confirmation»; CONCEPT_SOURCE.md:L235-L242 — §5.3 «Guest/staff scenarios»; CONCEPT_SOURCE.md:L604-L615 — §17.1 «PII boundary»` | `IBCHAT-002`, `AISAFE-002` |

### 22.4. AI form filling и confirmation

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `AIFORM-001` | Свободный текст преобразуется в структурированный draft существующей формы, а не напрямую в mutation. | `SOURCE` | `CONCEPT_SOURCE.md:L192-L197 — §4.2 «Текст → booking draft»; CONCEPT_SOURCE.md:L676-L680 — §19 «Текст → форма → подтвердить»` | `IBAPP-003` |
| `AIFORM-002` | Draft показывает распознанные значения, обязательные поля и найденные дубли/ошибки до подтверждения; отдельный confidence contract source не задаёт. | `SOURCE` | `CONCEPT_SOURCE.md:L294-L303 — §6.4 «Дубли/ошибки/draft»; CONCEPT_SOURCE.md:L576-L584 — §15.2 «Обязательные поля master»` | `AIFORM-001` |
| `AIFORM-003` | Пользователь проверяет draft и явно подтверждает его в bot/Mini App. | `SOURCE` | `CONCEPT_SOURCE.md:L96-L101 — §1.4 «Critical confirmation»; CONCEPT_SOURCE.md:L639-L642 — §18.2 «Финальная операция»` | `RBAC-004` |
| `AIFORM-004` | iBota не подтверждает бронь без кнопки, не меняет сумму, не удерживает HOLD бесконечно и не выполняет финансовые проводки без подтверждения. | `SOURCE` | `CONCEPT_SOURCE.md:L199-L203 — §4.2 «Границы iBota в бронировании»; CONCEPT_SOURCE.md:L655-L662 — §18.4 «Границы iBota»` | `AISAFE-002` |

### 22.5. Role-scoped AI analytics

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `AIAN-001` | AI analytics использует только данные, доступные роли и scope пользователя. | `SOURCE` | `CONCEPT_SOURCE.md:L78-L94 — §1.3 «Role/scope»; CONCEPT_SOURCE.md:L524-L537 — §13 «Role analytics и запросы»` | `RBAC-003`, `ANL-005` |
| `AIAN-002` | Поддерживаются natural-language summaries/comparisons по доступным operational data. | `SOURCE` | `CONCEPT_SOURCE.md:L524-L537 — §13 «Сводки и сравнения»; CONCEPT_SOURCE.md:L644-L653 — §18.3 «Analytics по ролям»` | `ANL-001`–`ANL-004` |
| `AIAN-003` | Нужно решить, должен ли AI summary явно отделять factual source data от interpretation и каким способом. Это предлагаемая grounding/explanation policy, а не принятый source contract; гипотеза `2–4 раза` отдельно остаётся недоказанным KPI. | `DECISION_REQUIRED` | `CONCEPT_SOURCE.md:L25-L28 — «Гипотезы, не KPI»; CONCEPT_SOURCE.md:L531-L537 — §13.2 «AI analytics examples»; CONCEPT_SOURCE.md:L655-L662 — §18.4 «Границы iBota»` | `AISAFE-001`, `DEC-027` |

### 22.6. AI safety и critical-action confirmation

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `AISAFE-001` | Перед чтением/предложением действия iBota проверяет identity, role, club/network scope и context sensitivity. | `SOURCE` | `CONCEPT_SOURCE.md:L78-L94 — §1.3 «Identity/role/scope»; CONCEPT_SOURCE.md:L604-L615 — §17.1 «Context sensitivity»` | `RBAC-003` |
| `AISAFE-002` | Бронь, отмена, посадка, депозит, доплата, закрытие смены и корректировка выполняются только через явное подтверждение обычного service path. | `SOURCE` | `CONCEPT_SOURCE.md:L96-L101 — §1.4 «Critical confirmation»; CONCEPT_SOURCE.md:L639-L642 — §18.2 «Финальная операция»; CONCEPT_SOURCE.md:L655-L662 — §18.4 «Границы»` | `RBAC-004`, `AIFORM-003` |
| `AISAFE-003` | После закрытия смены iBota не обходит freeze; super-right/reason/audit обязательны. | `SOURCE` | `CONCEPT_SOURCE.md:L358-L362 — §8.1 «Freeze»; CONCEPT_SOURCE.md:L617-L619 — §17.2 «Причина/audit»; CONCEPT_SOURCE.md:L655-L662 — §18.4 «Super-right»` | `FIN-008` |
| `AISAFE-004` | Массовая рассылка требует segment preview, content preview и confirmation. | `SOURCE` | `CONCEPT_SOURCE.md:L513-L518 — §12.4 «Preview»; CONCEPT_SOURCE.md:L655-L660 — §18.4 «Broadcast confirmation»` | `COM-006` |
| `AISAFE-005` | iBota не выдаёт bonus вручную без подтверждаемого основания. | `SOURCE` | `CONCEPT_SOURCE.md:L621-L625 — §17.3 «Один бонус»; CONCEPT_SOURCE.md:L655-L662 — §18.4 «Основание bonus»` | `LOY-001`, `SEC-006` |

### 22.7. Bot-to-bot readiness

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `BTB-001` | Multi-bot/agent interaction — опциональное будущее capability, не обязательство первого release. | `SOURCE` | `CONCEPT_SOURCE.md:L664-L667 — §18.5 «Bot-to-bot опционально»` | platform verification |
| `BTB-002` | При включении bot-to-bot обязательны dedup, rate limit, maximum depth и timeout. | `SOURCE` | `CONCEPT_SOURCE.md:L664-L667 — §18.5 «Loop prevention»` | `SEC-006` |
| `BTB-003` | Архитектура может оставаться single-bot, пока отдельное решение о multi-bot не принято. | `SOURCE` | `CONCEPT_SOURCE.md:L664-L667 — §18.5 «Single-bot baseline»` | `BTB-001` |

### 22.8. Business-connected-bot readiness

| Requirement ID | Нормализованное требование | Тип | Source locator | Dependencies |
|---|---|---|---|---|
| `BUS-001` | Connected business bot — опциональный отдельный режим для промо/клубных account workflows. | `SOURCE` | `CONCEPT_SOURCE.md:L669-L672 — §18.6 «Business connected bots опционально»` | platform verification |
| `BUS-002` | Режим включается осознанно с отдельной access policy и message templates. | `SOURCE` | `CONCEPT_SOURCE.md:L669-L672 — §18.6 «Отдельный осознанный режим»` | `RBAC-003`, `SEC-002` |
| `BUS-003` | Действие от business account не расширяет club/network scope и не обходит confirmation/audit. | `SOURCE`, нормализация safety boundary | `CONCEPT_SOURCE.md:L78-L101 — §1.3–1.4 «Scope и confirmation»; CONCEPT_SOURCE.md:L669-L672 — §18.6 «Access policy»` | `AISAFE-001`, `AISAFE-002` |

## 23. Dependency spine

Критический порядок зависимостей:

`Product identity / role decisions → RBAC + club scope → operational night → catalogue/navigation → booking/HOLD or guest-list invitation → Night Pass/check-in → table ledger → financial freeze → loyalty/analytics → communications/AI`.

iBota не является способом обойти этот порядок: она может появляться только поверх существующего авторизованного end-to-end capability. Guest Mode, bot-to-bot и business connection дополнительно зависят от текущей платформенной проверки и явного product enablement.

## 24. Critical-action register

| Action | Basis | Confirmation | Reason/audit | Freeze/extra boundary |
|---|---|---|---|---|
| Create/cancel booking, seat | `SOURCE` | Явная кнопка/форма | Audit для staff override | Role + club/night context |
| Free/no-show | `DECISION_REQUIRED` для confirmation policy | Требование source не определено | Требование source не определено | Действие остаётся role/night-scoped |
| Initial deposit, top-up, security, correction | `SOURCE` для раздельных операций; no-overwrite — repository requirement под `DEC-026` | Явная кнопка/форма | Причина и audit | Только отдельная operation; closed shift freeze |
| Stop table sales / Undo | `SOURCE` action; controls — `DECISION_REQUIRED` | Явная кнопка в source, дополнительный confirm не определён | Audit policy требует решения | Только выбранная operational night |
| Close shift | `SOURCE` | Явная итоговая форма | Причина/итоговый audit | Только financial manager выбранного клуба |
| Closed-shift correction | `SOURCE` | Отдельное подтверждение | Обязательные super-role, причина, audit | Никогда не скрытая AI mutation |
| Role/scope change | `SOURCE` для reason/audit; confirm — `DECISION_REQUIRED` | Явное подтверждение предлагается, но не принято | Причина и audit | Нельзя расширять собственные права |
| Broadcast | `SOURCE` | Segment + content preview + confirmation | Campaign audit | Quiet hours/rate/cost policy |
| Badge/bonus/manual reward | `SOURCE` boundary; manual-flow details — `DECISION_REQUIRED` | Основание обязательно; форма подтверждения требует решения | Dedup/audit | Не выдаётся iBota произвольно |

## 25. Coverage control

170 unique requirement IDs с 170 индивидуальными bounded source locators покрывают source без использования итогового блока 19 как второго набора дублирующих обещаний:

| Source section | Нормализованное покрытие |
|---|---|
| Факты / оценки / гипотезы | §§ 1–2; platform claims отделены от current verification, гипотеза `2–4 раза` не стала KPI |
| 0. Product definition | `PROD-*`, `NET-*` и domain IDs |
| 1. RBAC | `RBAC-*`, `AISAFE-*` |
| 2. Calendar | `CAL-*` |
| 3. Catalogue | `CAT-*` |
| 4. Booking | `BKG-*`, `HOLD-*` |
| 5. Mini App / Guest Mode | `UX-*`, `IBAPP-*`, `GM-*` |
| 6. Guest lists / entrance | `GL-*`, `PASS-*`, `CHK-*` |
| 7. Table operations | `TOPS-*`, `DEP-*` |
| 8. Financial shift | `FIN-*` |
| 9. Gamification | `LOY-*` |
| 10. Support | `SUP-*` |
| 11. Music | `MUS-*` |
| 12. Communications | `COM-*` |
| 13. Analytics | `ANL-*`, `AIAN-*` |
| 14. Procedures | `OPS-*` |
| 15. Onboarding | `ONB-*` |
| 16. Degraded modes | `DEG-*` |
| 17. Security / fraud | `SEC-*`, `AISAFE-*` |
| 18. Cross-cutting iBota | domain AI rows plus `IBCHAT-*`, `IBAPP-*`, `GM-*`, `AIFORM-*`, `AIAN-*`, `AISAFE-*`, `BTB-*`, `BUS-*` |
| 19. Summary | Cross-check of the same capabilities; no additional promise inferred |

## 26. Accepted decision overlays

Этот слой является частью target specification, но не частью immutable source requirement set: 170 source rows выше, их IDs, wording, Type, Source locator и Dependencies не изменяются. Overlay records не получают `CONCEPT_SOURCE` locators; их authority — только принятый item-level record в [OPEN_DECISIONS.md](OPEN_DECISIONS.md). `AMEND` означает active accepted extension в указанной будущей phase, а не соответствие current code или готовность к implementation. `DEFER` означает accepted defer classification, а не deletion/rejection. Параметры, прямо оставленные unresolved, нельзя молча выбирать при реализации.

| Decision | Classification | Accepted target boundary | Phase | Authority |
|---|---|---|---|---|
| DEC-003 | `ACCEPTED_DECISION` — pilot launch boundary | Первый usable release запускается для одного real pilot club, выбранного configuration/data без hardcode; multi-club data model, `clubId` и `CLUB/GLOBAL scope` сохраняются, а network analytics, полноценный multi-club onboarding и межклубный UX не входят в первый release | Pilot release; first slice | [DEC-003](OPEN_DECISIONS.md#dec-003--один-клуб-или-сеть-в-mvp) |
| DEC-004 | `ACCEPTED_DECISION` — first-release role boundary | Первый release включает Гостя, Host/Менеджера входа, Менеджера клуба, Админа клуба и Owner; target distinction Менеджера зала сохраняется через mapping `DEC-005`; Promoter, Финансовый менеджер, DJ, Главный админ и Главный менеджер сети сохраняются в target model, но не входят в release; permission matrix этим решением не определяется | Pilot release; role slices | [DEC-004](OPEN_DECISIONS.md#dec-004--mvp-role-set) |
| DEC-005 | `ACCEPTED_DECISION` — role/RBAC mapping | `GUEST`, `ENTRY_MANAGER`, объединённый для Менеджера зала/клуба `MANAGER`, `CLUB_ADMIN`, `OWNER`; первые три staff roles ограничены CLUB scope, `OWNER` сохраняет GLOBAL scope, а полномочия задаются role + scope + explicit permission; critical actions требуют confirmation/audit, existing `GLOBAL_ADMIN`/`HEAD_MANAGER`/`PROMOTER` не удаляются и не входят в новые first-release flows | Pilot release; RBAC foundation | [DEC-005](OPEN_DECISIONS.md#dec-005--различия-host-entrance-manager-floor-manager-club-manager-и-admin) |
| DEC-007 | `ACCEPTED_DECISION` — canonical guest navigation boundary | Future canonical guest home — новый minimal role-aware shell с target IA Клубы/Календарь/Схема или Столы/Мои брони/Пропуск/Мои ночи/Музыка/Вопросы; section активируется только после accepted dependencies, complete wiring, RBAC и staging smoke; first support slice остаётся private `/start → /ask`, не зависит от Mini App redesign и не активирует iBota; final labels зависят от `DEC-001`, cutover — от `DEC-017` | Guest shell after first slice; private support slice | [DEC-007](OPEN_DECISIONS.md#dec-007--canonical-guest-homenavigation) |
| DEC-017 | `ACCEPTED_DECISION` — Mini App implementation strategy | Новый role-aware shell становится canonical; current React можно переиспользовать выборочно только после проверки requirements/API/RBAC/packaging; static `/app` — temporary fallback, не target; итог — один `/app`, один build/package/serve pipeline и один source of assets; navigation после wiring/RBAC/tests/smoke, explicit cutover перед legacy removal; private support flow и bounded staff inbox не зависят от полного redesign | Canonical Mini App cutover; bounded support surface | [DEC-017](OPEN_DECISIONS.md#dec-017--retainreplace-current-mini-app-implementations) |
| DEC-025 | `ACCEPTED_DECISION` — support taxonomy/RBAC/lifecycle | Guest выбирает одну из семи accepted categories; inbox/view/reply/status доступны только `MANAGER`/`CLUB_ADMIN` в matching CLUB scope через отдельные explicit permissions, `ENTRY_MANAGER` denied, `OWNER` не получает operational reply автоматически, guest видит только own thread; lifecycle `NEW → IN_PROGRESS → RESOLVED → CLOSED` с explicit take/resolve/close, first reply starts work, guest message reopens only `RESOLVED`, `CLOSED` terminal, `WAITING` deferred; staff reply/status/close/delivery result audited without message-body duplication; SLA/priority/escalation/automation/reopen/AI excluded | Private Support Loop | [DEC-025](OPEN_DECISIONS.md#dec-025--support-taxonomy-and-lifecycle) |
| DEC-028 | `AMEND` — active accepted extension | Reminder перед canonical expiry сохраняется, но выбор HOLD expiry, arrival-retention completion или обоих событий, а также trigger/timing/recipients/channel/repeats/anti-spam остаётся unresolved до canonical booking lifecycle; не first slice и не navigation до этого решения | Booking lifecycle phase | [DEC-028](OPEN_DECISIONS.md#dec-028--reminder-скоро-слетит) |
| DEC-029 | `AMEND` — active accepted extension | staff-only walk-in seating создаёт table session без fictitious booking; только authorized staff, свободный стол текущей operational night, stop-sales/blocks, explicit deposit/bill/club mode, confirmation/audit/ledger; optional Night Pass не позволяет обойти check-in/stamp/loyalty; не guest section и не first slice | Table operations / finance configuration phase | [DEC-029](OPEN_DECISIONS.md#dec-029--spontaneous-tables-staff-only-walk-in-seating) |
| DEC-030 | `DEFER` — accepted defer classification | Deferred hypothesis, not current target promise, MVP or navigation; no implementation requirement until revisit after canonical Night Pass/check-in, loyalty ledger, reward catalogue, anti-fraud and audit, with upgrade subject, eligibility, selection, budget, validity, staff confirmation and abuse prevention separately defined | Loyalty phase, deferred after prerequisites | [DEC-030](OPEN_DECISIONS.md#dec-030--mystery-upgrade) |
| DEC-031 | `AMEND` — active accepted extension | Club-configured canonical allocation categories; every deposit/top-up operation may split across active categories with exact totals and historical category snapshot; append-only correcting operation requires reason/confirmation/audit; security stays separate, revenue articles are a different taxonomy, guest cannot see internal allocation; initial catalogue unresolved and not first slice | Table operations / finance configuration phase | [DEC-031](OPEN_DECISIONS.md#dec-031--richer-allocation-categories) |
| DEC-032 | `AMEND` — active accepted extension | After read-only catalogue, private favourites may bookmark only published tracks/sets without bypassing moderation/access and without exposing user lists; after accepted DJ role/authoring, authorized DJ/admin may create rights-aware moderated curated playlists, and each playlist is associated with club, event or operational night; no public user playlists, collaboration, social catalogue or accepted battles/stems; no first-slice/navigation before music phase | Music phase | [DEC-032](OPEN_DECISIONS.md#dec-032--playlistsfavourites) |
| DEC-033 | `AMEND` — active accepted extension | Channel posts target only pre-connected allowlisted club/network channels under separate communications permission, preview and explicit confirmation; arbitrary text IDs have no authority, PII/staff-only content is forbidden, actor/target/confirmed content/result are audited, edit/delete is separately confirmed; distinct from personal/segmented delivery, no automatic scheduling, not first slice | Communications phase | [DEC-033](OPEN_DECISIONS.md#dec-033--channel-posts) |
| DEC-034 | `AMEND` — active accepted extension | Authorized on-demand exports derive from canonical reports with fixed filters/period/scope/timezone and metadata, frozen financial or explicit non-final semantics, PII minimization/permission and sensitive access audit; formats remain an implementation-slice choice. Scheduled reports follow canonical metrics and accepted delivery with allowlists, test/enablement/log/bounded retry/dedup; AI narrative cannot replace numbers and remains blocked by accepted AI-grounding contract; not first slice/navigation | Analytics/communications phase | [DEC-034](OPEN_DECISIONS.md#dec-034--exportsauto-reports) |
| DEC-035 | `AMEND` — active accepted extension | Versioned allowlisted configuration templates are created and published only by Owner or an authorized GLOBAL role; templates and explicit-category create-from-existing snapshots produce validated, previewed, confirmed, audited drafts with new independent IDs and no live link; secrets, credentials/keys, staff/roles, guests/PII, booking/entrance/session/financial/audit/idempotency/incident/delivery runtime state never copy; content/media is explicit and rights-checked; no first slice or guest navigation | Onboarding/network-scaling phase | [DEC-035](OPEN_DECISIONS.md#dec-035--cloningtemplates) |
| DEC-036 | `ACCEPTED PRODUCT SLICE` | Target status: `ACCEPTED_NOT_IMPLEMENTED`. Private Support Loop for one data/config-selected pilot club: minimal idempotent fresh-user identity, private `/ask`, production club and guest-selected category, durable ticket/thread, explicitly permissioned club-scoped staff inbox/detail/reply/status, truthful Telegram delivery, exact `DEC-025` lifecycle/audit and bounded exclusions. Canonical acceptance and implementation contract is in the slice specification | First product slice | [DEC-036](OPEN_DECISIONS.md#dec-036--first-product-slice-private-support-loop) · [Private Support Loop](slices/PRIVATE_SUPPORT_LOOP.md) |
| DEC-039 | `ACCEPTED_DECISION` — platform identity | Кабинет системного владельца требует подписанной Telegram identity и точного совпадения с положительным protected configured owner ID; прежний OWNER/GLOBAL другого пользователя не предоставляет доступ к этому кабинету и сохраняет прежние legacy semantics. Только владелец платформы сможет назначать владельцев клубов; будущий владелец клиента ограничен разрешёнными клубами по DEC-040/041 и не становится GLOBAL owner. Scope реализации DEC-039 — read-only identity check и два экрана; новые приглашения/заявки приняты отдельно и ещё не реализованы | Platform cabinet identity; club-owner assignment remains future implementation | [DEC-039](OPEN_DECISIONS.md#dec-039--telegram-identity-владельца-платформы) |

### Уточнение прежних overlays от 2026-10-27

`DEC-040/041/042` уточняют строки `DEC-003/005/007/017/039` выше: один operational pilot не ограничивает аккаунт одним клубом; GLOBAL относится к сохранённым legacy назначениям, не к новому клиенту; «Клубы» в целевом guest entry ограничены одним клиентом; полный UI redesign не prerequisite кабинета. Рабочий platform-owner identity check и explicit guest cutover сохраняются. Исходные 170 source rows не меняются; при расхождении применяются эти явно принятые дополнения. Остальные решения и границы Private Support Loop не расширяются.

<a id="client-onboarding-contract"></a>
### Клиенты, заявки и брендированные боты — принято 2026-10-27

Authority: `DEC-040`–`DEC-042`. На дату принятия 2026-10-27 все новые требования ниже — **`ACCEPTED_NOT_IMPLEMENTED`**; рабочий platform identity является только переиспользуемой основой. Это отдельный accepted overlay, не дополнительные immutable-source rows. Принятые позже технические детали А/Б — [§29](#client-onboarding-ab-technical-contract), bounded local results — [AS_BUILT](AS_BUILT.md#client-onboarding-a-local); полный onboarding/multi-bot outcome не объявляется выполненным.

| ID | Принятое требование | Authority |
|---|---|---|
| `CLNT-001` | Пользователь, клиент, клуб и подключение бота различны. Каждый клуб принадлежит одному клиенту; у клиента один клуб или сеть. Нет лимита «аккаунт = один клуб». | DEC-040 |
| `CLNT-002` | Клиентское членство не даёт сотруднику все клубы. Доступ определяется человеком, клиентом, разрешённым клубом и действием; platform owner отдельно. Legacy GLOBAL назначения не переосмысливаются и не выдаются новому клиенту. | DEC-040; DEC-005/039 |
| `CLNT-003` | Подтверждённое приглашение открывает кабинет клиента, но не club-creation grant. Отзыв приглашения, доступа и удаление данных — разные действия. | DEC-041 |
| `CLNT-004` | Каждый клуб требует своей заявки и одобрения только platform owner после коммерческого согласования. Черновик → отправлена → согласование → одобрена / отклонена / отменена; один счёт может покрыть несколько отдельных разрешений, без автоматического биллинга. | DEC-041 |
| `CLNT-005` | Одобрение разрешает ровно один конкретный клуб. Повторы/конкуренция не создают дубль. Создание даёт непубличный черновик; профиль и подключение бота не означают публикацию. Отказ новой заявки не отключает существующие клубы; отзыв доступа не удаляет данные автоматически. | DEC-041 |
| `CLNT-006` | «Мои клубы»: свои разрешённые клубы, прогресс настройки, заявки, «Подключить заведение» и явный рабочий контекст. Повторный вход восстанавливает сохранённую настройку; следующий клуб требует новой заявки, а не приглашения. Платформа видит клиентов, заявки/решения и связанные клубы. | DEC-041 |
| `CLNT-007` | Guest entry открывает клуб или разрешённую витрину одной сети/клиента. Публичность разных клубов не раскрывает клиентам чужую историю гостей, обращения, брони и финансы. Общий каталог независимых клиентов не стандартный вход. | DEC-040/042 |
| `CLNT-008` | Общая серверная часть/обновления обслуживают брендированные боты: один клуб либо явно заданные клубы одного клиента на бот; несколько брендов могут иметь разные боты. NightConcierge остаётся платформенным входом, старые guest flows сохраняются до проверенного перехода. Managed Bots — предпочтительный механизм с подтверждением и владением пользователя, без гарантии username. | DEC-042 |
| `CLNT-009` | Доверенный bot context определяет initData validation и outbound connection. Сервер проверяет бот → клиент → клуб → действие; URL/username/подставленный ID не authority. Изоляция охватывает API, файлы, уведомления, очереди и кэши; токены/чувствительные данные не раскрываются. | DEC-040/042 |
| `CLNT-010` | Приёмка: A подключает два клуба по двум одобрениям, B изолирован, повторы безопасны; брендированные входы/доставка не смешиваются. Дополнительные данные синтетические, operational pilot может быть одним реальным клубом. Полный мастер, аналитика сети и автоматические платежи не обязательны для ближайшего outcome. | DEC-040/041/042 |

#### Матрица полномочий нового пути

| Субъект | Разрешено в принятой границе | Не следует из этой authority |
|---|---|---|
| Системный владелец платформы | После exact DEC-039 identity: управление доступом клиентов, просмотр заявок/связанных клубов, решение по каждому подключению | Вход от имени клиента; автоматическое право всех операционных действий клуба |
| Подтверждённый владелец клиента | «Мои клубы», заявки своего клиента; создание конкретного клуба только по своему отдельному одобрению; настройка разрешённых клубов и назначение сотрудников в их пределах | Самоодобрение; неограниченное создание; чужие клиенты; legacy GLOBAL OWNER |
| Сотрудник | Только разрешённые клубы и конкретные explicit permissions | Все клубы клиента; коммерческое одобрение; platform cabinet |
| Гость | Публичный клуб/разрешённая витрина сети и собственные операции в этом контексте | Черновик, чужие private records или перенос закрытой истории между клиентами |
| Legacy OWNER/GLOBAL | Существующая semantics сохраняется в legacy endpoints | Platform identity; новый клиентский доступ или одобрение заявки по одному названию роли |

#### UX без полного перепроектирования

1. Platform owner приглашает человека в клиентский кабинет. Текст прежнего draft «доступ для создания собственного клуба» заменяется смыслом «доступ к кабинету и подаче заявок»; приглашение само не создаёт клуб и не выдаёт разрешение на него.
2. Подтверждённый пользователь открывает **«Мои клубы»**, даже если клубов пока нет. Пустое состояние ведёт в **«Подключить заведение»** — форму заявки, не форму создания клуба.
3. Заявка сохраняется черновиком, отправляется и отображает согласование/решение. До одобрения рабочее создание клуба отсутствует и отклоняется сервером. Отказ/отмена показываются в заявке; действующие клубы доступны по прежним разрешениям.
4. В **«Управлении платформой»** нужны списки клиентов/заявок и карточка решения: конкретный клиент и заведение, состояние, коммерческий контекст без автоматического биллинга, решение и связь с созданным клубом. Карточка не подменяет оперативный кабинет клиента.
5. По одобренной заявке появляется действие создания **одного непубличного клуба**. После успеха повтор ведёт к этому же клубу. Карточка предлагает продолжить профиль/настройку; название, готовый профиль и одобрение не делают клуб доступным гостям.
6. Повторный вход возвращает сохранённые клубы, заявки и прогресс. При нескольких клубах явно показывается выбранный рабочий контекст. Через полгода «Подключить заведение» начинает новую заявку без повторного приглашения.
7. Карточка клуба показывает состояние брендированного подключения отдельно от настройки и публичности. Предлагаемые UI labels: «Не подключён», «Ожидает подтверждения в Telegram», «Подключён», «Требует внимания»; это presentation proposal, не утверждённая token state machine. До готового wiring не показывать подключение как работающее.

Существующие layout, typography, карточки, Back/navigation и platform auth переиспользуются после проверки API/permissions; существующий preview не доказывает backend. Guest cutover и publication остаются отдельной реализацией с проверками.

#### Критерии приёмки целевой модели

| ID | Проверка | Ожидаемый результат |
|---|---|---|
| `CLNT-AC-01` | Приглашение подтверждено; заявки ещё нет | Кабинет доступен, создание запрещено; GLOBAL роль не появилась |
| `CLNT-AC-02` | Все состояния заявки; попытка самоодобрения/чужого решения | Принятый путь виден; решение только exact platform owner; подробные cancellation/reconsideration rules утверждены до реализации |
| `CLNT-AC-03` | Одобренная заявка, повтор/конкурентное создание и сбой сохранения | Ровно один связанный клуб; нет частично выданных прав/потраченного разрешения без согласованного результата; recovery/atomicity contract проверен |
| `CLNT-AC-04` | Создан черновик, заполнен профиль; прямые guest URLs/API/files | Клуб и связанные данные непубличны до отдельной публикации, не только скрыты в списке |
| `CLNT-AC-05` | A получает два клуба по двум одобрениям; повторный вход и новая заявка | Один кабинет, сохранённый профиль/настройка, явный выбор клуба; повторная регистрация не нужна |
| `CLNT-AC-06` | B подставляет client/club/record IDs A; сотрудник A1 пробует A2 | Нет чтения/изменения private данных без соответствующего назначения, даже внутри одного клиента |
| `CLNT-AC-07` | Отказ/отмена новой заявки; отзыв конкретного доступа | Работающие клубы не отключаются из-за отказа; отозванный доступ не действует, данные не удаляются автоматически |
| `CLNT-AC-08` | Guest посещает публичные A/B, затем их владельцы читают историю | Видна только разрешённая история своего клиентского/клубного контекста |
| `CLNT-AC-09` | Подмена bot context, initData другого бота, callback/deep link чужого клуба | Fail-closed, без перебора токенов; platform-owner identity не ослаблена |
| `CLNT-AC-10` | Одинаковый update_id/chatId у двух ботов, retries и сохранённая delivery | Дедупликация и доставка различают подключения; ответ/уведомление идёт правильным ботом |
| `CLNT-AC-11` | Повторные reads через API/files/cache/queues и revoked access | Изоляция проверяется вне UI, устаревший контекст не выдаёт права, sensitive data не раскрываются |
| `CLNT-AC-12` | Один бот для сети A; отдельные боты брендов A и B; старый NightConcierge flow | Сеть ограничена A; независимые клиенты не объединяются; старый вход работает до отдельного cutover |

Дополнительные клиенты/клубы/пользователи в этих проверках синтетические. Ни один критерий пока не объявлен PASS. Этапы и одна первая задача — в [roadmap](PRODUCT_ROADMAP.md#client-delivery-sequence); существенные технические выборы собраны [одним блоком](PRODUCT_ROADMAP.md#client-open-design).

## 27. Что спецификация сознательно не обещает

- Конкретную AI-модель, поставщика, точность или экономический эффект.
- Public product/assistant names остаются pending до явного решения пользователя; first-release role set и pilot launch policy определены только accepted overlays `DEC-003`–`DEC-005` и не меняют immutable source rows.
- Repository-only additions не становятся target promises автоматически по `DEC-026/D`. Initial eight conflicts имеют только boundaries из accepted overlays выше; все остальные disputed additions требуют собственной item-level classification.
- Availability любого Telegram capability без повторной официальной проверки непосредственно перед реализацией/release.

<a id="client-context-technical-contract"></a>
## 28. Принятый технический контракт: клиентский контекст и чтение клубов

**Статус: ACCEPTED — прямое решение владельца, 2026-10-27.** Владелец утвердил узкий контракт четырёх таблиц, `client.club.read`, двух GET endpoints, одного production adapter платформенного бота и отсутствия автоматического mapping/GLOBAL grants; разрешил локальную реализацию, тесты и связанные документы. Это техническое уточнение `DEC-039`–`DEC-042`, без нового DEC/CLB. Приглашения, заявки, UI, Managed Bots и активация клиентов не входят в разрешение. Публикация и runtime-действия не разрешены. Локальное implementation evidence и ограничения фиксируются отдельно в [AS_BUILT](AS_BUILT.md#client-read-local) и [checkpoint](../ops/PROJECT_STATUS.md), а не следуют из статуса принятия.

**Пользовательский результат:** подписанный Telegram-пользователь может прочитать собственные клиентские пространства и только явно разрешённые клубы. Будущие «Мои клубы» получают имена и доступный контекст; список не даёт прав на настройку, создание или операционные данные. Владелец с grants на A1/A2 видит оба, сотрудник с grant на A1 — только A1. Все реальные записи остаются прежними: первая реализация создаёт новую схему пустой и проверяет её только синтетическими fixtures.

#### 1. Данные: четыре additive-таблицы и одно действие

Переиспользуются `users` (одна application identity по подписанному Telegram ID), `clubs` (существующие операционные клубы), каталог `permissions(code)` и тип `PermissionCode`. **Новые grants не записываются в `user_roles` или `user_role_permissions`.** Не создаются второй каталог ролей, `CLIENT_OWNER` role enum, наследование прав сети или универсальная CRM. «Владелец» и «сотрудник» в примерах — продуктовые персоны; для этого read slice их различает только набор явных grants.

Все ID ниже — положительные `BIGINT` / Kotlin `Long`, `CHECK (> 0)` на новых ID/FK-полях, без `toInt()`. SQL `clubs.id` уже BIGINT (PostgreSQL/H2 V1), хотя `data/db/Tables.kt` использует `IntIdTable`: новый repository читает `clubs.id` через Long-проекцию/типизированный SQL, не меняя существующую ORM-модель или тип существующего API. Во внешнем **новом** API ID — десятичные строки, чтобы не терять точность в JavaScript.

| Таблица | Поля и обязательные ограничения | Зачем / reuse |
|---|---|---|
| `clients` | `id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY`; `display_name VARCHAR(255) NOT NULL`, `CHECK (char_length(trim(display_name)) BETWEEN 1 AND 255)`; `is_active BOOLEAN NOT NULL DEFAULT FALSE`; `created_at`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Отдельное пространство бизнеса; ни user, ни club его не заменяют. Название не уникально и не идентификатор доверия. |
| `client_memberships` | `client_id BIGINT NOT NULL`, `user_id BIGINT NOT NULL`, `is_active BOOLEAN NOT NULL DEFAULT FALSE`, timestamps как выше; PK `(client_id,user_id)`; FK `client_id → clients(id)` ON DELETE RESTRICT; FK `user_id → users(id)` ON DELETE CASCADE; индекс `(user_id,client_id)` | Членство открывает только собственный кабинет/имя клиента. Пользователь может состоять в нескольких клиентах, у каждого клиента много пользователей. Не означает доступ ко всем клубам. |
| `client_clubs` | `club_id BIGINT PRIMARY KEY`, `client_id BIGINT NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`; FK `club_id → clubs(id)` ON DELETE CASCADE; FK `client_id → clients(id)` ON DELETE RESTRICT; UNIQUE `(client_id,club_id)` | PK гарантирует не более одного клиента для клуба. Отдельный mapping позволяет оставить существующие клубы unmapped без nullable/backfill-изменений старой таблицы. UNIQUE нужен для composite FK grants. |
| `client_club_grants` | `client_id`, `user_id`, `club_id BIGINT NOT NULL`; `permission_code TEXT NOT NULL`; `is_active BOOLEAN NOT NULL DEFAULT FALSE`; timestamps как у membership; PK `(client_id,user_id,club_id,permission_code)`; FK `(client_id,user_id) → client_memberships(client_id,user_id)` ON DELETE CASCADE; FK `(client_id,club_id) → client_clubs(client_id,club_id)` ON DELETE CASCADE; FK `permission_code → permissions(code)` ON DELETE RESTRICT; CHECK `permission_code = 'client.club.read'`; индекс `(user_id,client_id,club_id)` | **Одна строка — точное клубное назначение на одно действие.** Composite FKs не позволяют соединить membership A и club mapping B. Отдельная assignment-таблица без роли/других атрибутов сейчас не нужна. |

У всех FK — ON UPDATE RESTRICT; нет deferrable constraints, partial unique или cross-table CHECK. NOT NULL обязателен для всех компонентов ключей. Добавляется ровно одна запись в существующий каталог permissions: `client.club.read` — чтение минимального club context только через новые endpoints. Это не grant и не `support.view`; наличие кода в каталоге никого не авторизует. Существующий набор permissions/roles сохраняется. Добавление иных действий в новый grant store потребует отдельного расширения DB allowlist и predicate, а не wildcard или role inheritance.

`is_active=false` клиента скрывает его и все его клубы в новых reads; неактивная membership скрывает этот клиент только для данного пользователя; неактивный grant скрывает только соответствующий клуб/действие. **Отзыв для read slice = деактивация**, а не удаление membership/клуба/данных. GET не меняет timestamps и не активирует записи. Интерфейса/endpoint выдачи, отзыва или повторной активации нет; будущая административная операция потребует собственного контракта и audit. FK CASCADE касается только удаления производных memberships/mappings/grants, если соответствующий user/club уже удаляется отдельно разрешённой legacy операцией; это сохраняет возможность прежнего delete и не делает revoke удалением. Удаление самого клиента с зависимостями запрещено FK; нового delete API нет.

Принятый target требует владельца-клиента для каждого нового клуба, но на переходной схеме **существующий unmapped club разрешён как legacy запись и отсутствует в новом контексте**. Будущая одобренная операция создания обязана записывать mapping атомарно; она не входит в этот slice. `clubs.is_active` не используется как permission или новая публикационная state machine: явно разрешённая минимальная карточка mapped клуба доступна в кабинете независимо от этого legacy flag, без доступа к guest/private endpoints и без утверждения «это опубликованный клуб/черновик».

#### 2. Доверенное подключение и свежая identity

Внутренние типы (не wire API): `TrustedBotConnection(connectionKey, audience, tokenProvider)` и `VerifiedClientIdentity(telegramUserId, connectionKey, audience)`. `audience` — закрытый вариант `Platform` либо `Client(clientId, allowedClubIds)`. Конфигурация создаётся серверным wiring; положительные IDs проверяются, пустой allowlist у Client не разрешает ни одного клуба. `tokenProvider` — непрозрачная ссылка на credential provider, не поле сериализуемого principal; секреты не попадают в `toString`, ошибки, API и logs. Тип verified identity создаётся только после validation в текущем запросе.

В первой реализации `Application` передаёт **один фиксированный platform connection** в регистрацию `/api/client`: `connectionKey = "platform"`, audience `Platform`, token provider из существующего `AppConfig.bot.token`. Не используется fallback выбора через `BOT_TOKEN`/query/header, нет registry API и вызова Telegram/getMe. Platform connection не привязано к одному клиенту: допустимые клиенты определяются membership пользователя; сам platform entry не даёт GLOBAL authority. `Client` audience реализуется только как тестируемая restriction policy и синтетическая DI fixture, не как второй production endpoint/бот.

Тонкий namespace adapter `withClientContextAuth(connection)` состоит из раннего application hook для `/api/client` и route guard, требующего созданную этим hook identity. Он переиспользует **тот же `InitDataValidator.validate`**, включая HMAC fix #550, существующие freshness/skew defaults и constant-time comparison. Это адаптация контекста/HTTP envelope, не второй HMAC алгоритм. Hook выполняется в `ApplicationCallPipeline.Monitoring` до routing/query decoding; namespace определяется серверной регистрацией и сегментами path, не request IDs. Он охватывает весь `/api/client` namespace; остальные маршруты не меняются. Route guard не валидирует подпись повторно и не восстанавливает identity из legacy attributes. На новых endpoints:

1. С начала запроса выставляются no-store headers. До routing/query decoding используется существующий принцип `rejectMiniAppInitDataQuery`: просмотр raw query keys, включая encoded/malformed keys, с отказом `401 unauthorized` при forbidden transport или malformed query key. Значения raw query не декодируются. Для нового namespace отказ идёт через `respondError`; можно выделить общий predicate/helper, но прежние callers/ответы остаются без изменений.
2. Connection берётся только из серверного route wiring. При отсутствующем/некорректном token configuration — fail-closed startup; unexpected provider failure в запросе — `500 internal_error`, без fallback на другой token. Request `botId`, username, `clientId`, `clubId`, Host и forwarded headers не выбирают connection.
3. Принимается ровно один непустой канонический header `X-Telegram-Init-Data`. Повторные значения или присутствие альтернативного `X-Telegram-InitData` дают `401 unauthorized`; body/query transport не применяется. Это явный контракт **новых** endpoints; aliases/body compatibility других маршрутов не меняются.
4. Подпись проверяется один раз токеном фиксированного connection; подписанный `user.id` должен быть положительным. Неверная/просроченная подпись, другой synthetic bot token, неподписанный header/body дают `401`. Ни token trial loop, ни доверия уже существующему `MiniAppUserKey` нет.
5. После успеха validation, **ещё до routing decoding**, любой оставшийся непустой raw query отклоняется как `400 validation_error` без декодирования key/value. Поэтому `?x=%GG` без/с неверной подписью даёт 401, с валидной подписью — 400; ни routing parser, ни DB не вызываются. Одиночный завершающий `?` без query bytes эквивалентен отсутствию query. Ранний отказ завершает pipeline.
6. Только для прошедшего все шаги запроса создаётся новый request-local `VerifiedClientIdentity`. Новый handler читает только его и передаёт в новый read repository; не использует `rbacContext`, `TelegramPrincipal` из legacy fallback, `roles`, `clubIds`, `ClubScopeResolver` или клиентский «selected context» как authority.

`configureSecurity()` сейчас допускает `X-Telegram-Id` fallback, а `RbacPlugin` может сохранить успешную resolution до route auth. Эта legacy semantics **не исправляется глобально** данной задачей. Новая route group не вложена в `authorize/clubScoped` и не потребляет сохранённую resolution. Даже при заранее заполненном legacy контексте B и валидной подписи A результат обязан соответствовать A; без подписи результат 401. `X-Telegram-Id`/`X-Telegram-Username`/`X-Club-Id` игнорируются новыми handlers. Ошибка/отсутствие legacy resolution не заменяет проверку и не предоставляет доступ.

`/api/platform/access`, `OWNER_TELEGRAM_ID`, существующие Mini App response defaults и HMAC алгоритм остаются без изменения semantics. System owner и legacy OWNER не получают клиентские записи без membership/grant; platform cabinet сохраняет свой отдельный endpoint/identity gate.

#### 3. Точный предикат и актуальность

Для подписанного principal `p`, клиента `c`, клуба `k` и действия `a` доступ разрешён **только одновременно** при:

```text
p получен fresh validation выбранного сервером connection
u.telegram_user_id = p.telegramUserId, u существует
c.id = m.client_id = mapping.client_id = g.client_id
u.id = m.user_id = g.user_id
k.id = mapping.club_id = g.club_id
c.is_active AND m.is_active AND g.is_active
g.permission_code = a = 'client.club.read'
audience = Platform
  OR (audience = Client(c.id, allowedClubIds) AND k.id IN allowedClubIds)
```

Роль из `user_roles`, роль платформы и grant другого действия не являются альтернативными ветками. Membership lookup для показа собственного клиентского имени использует только fresh identity, существующего user, активные client/membership и restriction по audience.clientId; клубы добавляются исключительно по полному predicate. Поэтому активное членство без grants возвращает клиента с `clubs: []`, без чужих названий/счётчиков. У Client audience все отображаемые клубы дополнительно проходят allowlist.

Для ответа каждого endpoint новый client repository выполняет **один параметризованный SELECT**, который в одной read-only `READ COMMITTED` transaction связывает `users`, memberships, clients, mapping, grants и club projection. В list используется authorized-club subquery/LEFT JOIN после фильтрации membership; detail фильтруется полным predicate сразу, без предварительного existence lookup. Не допускается сначала выбрать клуб, затем разрешить его по отдельному cached/role check; не допускаются N+1 lookup/автопровижининг пользователя. SQL/policy получает действие как server constant, не из request. Пользователь без application row также получает пустой список.

Результат соответствует committed snapshot **начала SELECT**. Отзыв, committed до следующего SELECT, обязательно действует в следующем ответе; отзыв, committed после начала уже выполняющегося SELECT, не гарантирует изъятия этого in-flight ответа. Это принятая граница snapshot, без обещания линейной актуальности до последнего сетевого байта. При отказе/timeout БД — 500 без выдачи старого результата; cancellation пробрасывается. Никакого cross-request auth/data cache, TTL grants или сохранения authority в browser/localStorage. Общие flags `RBAC_ENABLED` не отключают этот predicate. Семантика одного SELECT проверена по [PostgreSQL 16: Read Committed](https://www.postgresql.org/docs/16/transaction-iso.html#XACT-READ-COMMITTED); cross-table связи задаются [FK/UNIQUE](https://www.postgresql.org/docs/16/ddl-constraints.html#DDL-CONSTRAINTS-FK), а не CHECK с внешним lookup.

#### 4. Новый read API

| Endpoint | Успех / граница |
|---|---|
| `GET /api/client/context` | 200: `clients` — все активные memberships подписанного пользователя в допустимой audience, каждый с массивом только разрешённых clubs. Без pagination/тихого truncation на этом этапе; порядок client.id, затем club.id по числовому значению. |
| `GET /api/client/clubs/{clubId}` | 200: одна минимальная разрешённая club card; путь содержит только club ID, client определяется из mapping. Валидный, но чужой/unmapped/revoked/missing club — одинаковый 404 `not_found`. |

Query-параметры не предусмотрены: после auth ранний namespace hook отвергает любые непустые raw query bytes, кроме уже отвергнутого credential transport/malformed key, с `400 validation_error` без echo ключей/значений. Body не читается и не используется. `clubId` должен быть канонической десятичной строкой `[1-9][0-9]*` в диапазоне positive signed Long; malformed/overflow ID после auth даёт `400 validation_error`, не lookup. Ни path ID, ни возвращаемые IDs не являются токенами доступа.

Обязательные success поля: client `{id, name, clubs}`; club `{id, name, permissions}`. Detail добавляет `clientId`. `permissions` в этой версии ровно `["client.club.read"]` для каждой показанной карточки; это не права старых APIs. Не выдаются Telegram/user IDs, membership/grant IDs, роли, бот/credentials, чужие counts, guest history, files, progress/publication/bot status (их надёжной модели ещё нет). Отсутствующие capabilities не имитируются `ready=true`, `canCreate=true` или неработающими actions.

Примеры синтетических ответов:

```json
{"clients":[]}
```

200 одинаков для никогда не зарегистрированного подписанного Telegram user, существующего user без memberships и user с единственной отозванной membership. Не раскрывается, какой из этих случаев произошёл.

```json
{"clients":[{"id":"101","name":"Клиент A","clubs":[{"id":"1001","name":"A1","permissions":["client.club.read"]},{"id":"1002","name":"A2","permissions":["client.club.read"]}]}]}
```

Это владелец с двумя **явными** grants, а не наследование по названию роли. Сотрудник с grant только A1:

```json
{"clients":[{"id":"101","name":"Клиент A","clubs":[{"id":"1001","name":"A1","permissions":["client.club.read"]}]}]}
```

Активная membership без клубных grants:

```json
{"clients":[{"id":"101","name":"Клиент A","clubs":[]}]}
```

Разрешённый `GET /api/client/clubs/1001`:

```json
{"clientId":"101","id":"1001","name":"A1","permissions":["client.club.read"]}
```

Попытка сотрудника A1 прочитать A2, пользователя B прочитать A1, запрос unmapped/несуществующего клуба или detail после отзыва membership — один 404 через проектный `ApiError`/`respondError`:

```json
{"code":"not_found","message":null,"requestId":"req-synthetic","status":404,"details":null}
```

Nullable поля могут опускаться действующим serializer; `requestId` — текущий call ID или null, не идентификатор чужого объекта. По тому же envelope: 401 `unauthorized` для auth/transport, 400 `validation_error` для формата/query после auth, 500 `internal_error` для provider/DB failure. Причина отсутствия доступа/SQL/credential не выдаётся; нет отдельного 403, позволяющего различить существующий чужой объект. Это контракт нового namespace, не глобальная унификация старых 401 (`InitDataAuth` исторически имеет другой shape).

На всех success/error ответах: `Cache-Control: no-store`, `Vary: X-Telegram-Init-Data` через `ensureMiniAppNoStoreHeaders`; отсутствие ETag/Last-Modified/304, даже при условных request headers. Existing generic rate/body limits и безопасный error envelope сохраняются. GET не создаёт/не обновляет users, clients, mappings, memberships, grants, заявки или клубы; не вызывает `ensureMinimalIdentity` и не пишет domain audit. Существующая инфраструктурная telemetry допустима без credentials/PII.

Будущий экран «Мои клубы» загружает context заново при входе/возврате, явно выбирает одну из полученных карточек и перед открытием перечитывает detail. На 401 очищает локальный контекст; на 404 очищает выбор и перечитывает список. Пустой список показывает нейтральное отсутствие доступных пространств; пустые clubs — отсутствие разрешённых клубов. **Кнопки приглашения, заявки, создания, настройки и подключения бота этим API не включаются.** Клиентское сохранение выбранного ID — только удобство навигации, не permission. UI в первой задаче не реализуется.

#### 5. Additive migration и граница legacy

Порядок paired PostgreSQL/H2 Flyway migration `V061__client_context.sql`: clients → memberships → client_clubs → добавить `client.club.read` в permissions → client_club_grants/индексы. Применить NOT NULL/PK/UNIQUE/FKs/CHECK к пустым новым таблицам; проверить metadata и отказ на schema drift, без ослабляющего `IF NOT EXISTS`. Вставка каталожного кода не выдаёт grant. Номер 061 выбран после inventory до V060; существующие миграции/checksums не редактируются. H2 повторяет доступные structural constraints; production-инварианты и concurrency доказываются на Postgres по [migration parity policy](../runtime-db-resiliency.md#flyway-migration-parity-policy-postgres-vs-h2).

Нет backfill по OWNER, username, `OWNER_TELEGRAM_ID`, `adminChatId` или «первому клубу». Existing users/clubs/roles/permissions/grants сохраняются; FK на новые таблицы не добавляются в старые таблицы. Empty/unmapped state легален: старые endpoints работают по прежнему контракту, новые не возвращают unmapped clubs. CASCADE на legacy user/club FK удаляет только новые зависимые access rows при отдельно выполненном parent delete; иначе новый FK RESTRICT неожиданно изменил бы старый delete API. Old-code compatibility проверяется на расширенной схеме, но она не разрешает runtime rollback вопреки действующим release/migration guards.

Новые записи не расширяют legacy доступ: `ExposedUserRoleRepository` и support permission repository читают только старые `user_roles`/`user_role_permissions`; их query/wiring/cache не получают client grants и не вызываются как authority нового namespace. Пользователь без прежних staff ролей после выдачи synthetic client grant не получает `/api/admin/clubs`, support staff mutations, OWNER/GLOBAL или `/api/platform/access`. У пользователя с прежними legacy правами они сохраняются, включая прежние широкие grants; новая membership не «исправляет» и не отзывает их. Поэтому этот результат **не доказывает изоляцию всего приложения для включения клиентов**.

До активации полного клиентского сценария обязательны отдельные проверки/containment старых API, fallback/header identity, файлов, private guest history, уведомлений, очередей/кэшей, callback/deep links, draft visibility и branded ingress/outbound. Нельзя обойти их тем, что новый список отфильтрован. Client activation не выполняется в данной задаче.

Будущий mapping — отдельно разрешённая процедура: идентифицировать конкретный бизнес/клубы и Telegram-пользователей, согласовать независимое client space и явные grants, проверить legacy права и полную boundary, затем в одной аудируемой transaction создать клиента/memberships/mappings/grants с явным подтверждением. Только уникальный mapping, без reparent существующего club или автоматического повышения роли. Повторная выдача/реактивация, перенос клуба между клиентами и live backfill не входят в первую задачу. Это описание следующей authority boundary, не готовая команда для базы.

#### 6. Матрица проверок и законченный outcome

Таблица задаёт обязательный verification contract. Фактические команды, результаты и ограничения локальной проверки находятся в [checkpoint](../ops/PROJECT_STATUS.md); эта таблица сама по себе не является свидетельством выполнения. Fixtures: A=`101`, B=`102`; A1=`1001`, A2=`1002`, B1=`2001`; ownerA имеет active membership A и два read grants, staffA1 — membership A и один, ownerB — membership B и один. Два synthetic bot tokens известны только fixture; production token не читается. Дополнительные synthetic user/client/club IDs проверяют multiple memberships и BIGINT выше `Int.MAX_VALUE`/JS safe integer.

| Проверка | Unit / policy | Ktor с реальным auth/wiring | PostgreSQL IT / persistence |
|---|---|---|---|
| A1/A2/B1 и staff A1 | Predicate: точный user/client/club/action; Platform не bypass | context/detail ровно ожидаемые JSON, foreign detail 404 | Real joins с разными users/clients; order и уникальность результатов |
| Membership без grants, unknown user, revoked membership | Membership не даёт клубы | 200 empty/no clubs согласно примерам; GET не provisions | До/после GET rows и sequences не меняются; false membership исключает все её grants |
| Mixed assignments | A1 permission + иной club/user/client/действие не складываются | Legacy cached roles/clubIds B не меняют ответ signed A | Composite FK отвергает membership A + mapping B; active grant A1 и inactive A2 не дают A2; другой action запрещён CHECK |
| Отзыв grant/client и другие memberships | false на каждом уровне deny; отзыв A не выключает membership B | Следующий запрос после commit уже не видит отозванное | Синхронизированные два соединения: commit до SELECT и после snapshot; rollback не отзывает; без sleeps как доказательства |
| Spoof headers и IDs | Request values не вызывают resolver/token lookup | X-Telegram-Id без initData →401; signed A + spoof B → только A; X-Club-Id игнорируется; query bot/client/club →400 после auth | SQL binds из fresh signed ID и path; no existence lookup leakage |
| Другой бот / audience | Synthetic Platform и Client(A,{A1}); для Client A2/B1 denied даже при grants | Production path проверяет только platform token; signature другого bot →401, lookup/send counters подтверждают отсутствие token trial/Telegram вызова | Membership/grant остаются обязательными даже при trusted audience |
| Transport / freshness / HMAC #550 | Empty/negative user, expiry/future skew, signature field включена в HMAC | Canonical single header; duplicate/alias/body-only/credential query/malformed key →401; `?x=%GG` без/с неверной подписью →401, с валидной →400 до routing/DB; valid header; fresh attr нельзя заменить старым MiniAppUserKey | Невалидный auth не вызывает новый client repository |
| Data constraints | Long/string codec, positive IDs | malformed/overflow path →400; missing/foreign одинаковы 404 | Duplicate PK/club mapping, orphan FK, NULL, bad permission/blank client name отвергаются; одна identity во многих клубах/клиентах разрешена; Long без narrowing |
| Совместимость DELETE | Cascade не означает revoke/delete клуба | Новые read grants не блокируют старую разрешённую delete операцию | Parent delete в disposable fixture удаляет только новый access tail; деактивация ничего не удаляет; client с зависимостями RESTRICT |
| Сохранность legacy authority | Ни одного моста к Role.OWNER/GLOBAL/старым permissions | Full application fixture: новые grants не открывают admin/support/platform; прежние разрешения/отказы и старые auth response shapes неизменны | Existing users/clubs/user_roles/user_role_permissions byte/value equivalent после additive migration; нет backfill/grants |
| Platform owner auth | Точное signed user.id и positive configured owner ID | Existing platform-owner tests: missing/invalid config denied; signed owner allowed; legacy OWNER другого user denied; HMAC #550 regression сохраняется | Read-only access не создаёт memberships/grants |
| Restart / failures / caching | Нет singleton cache authority; CancellationException rethrow | Второй application instance с тем же DB даёт тот же context; изменения после restart видны; 500 без старого payload; no-store/Vary во всех ответах, no 304 | Два независимых процесса с одной synthetic DB сохраняют grants/context; DB failure не возвращает cached данные; GET не изменяет записи |
| Миграция | Paired schema definitions согласованы | Startup нового приложения на extended schema; без external calls | Fresh DB и upgrade populated fixture; все constraints + повторный Flyway validate; invalid drift fail; прежние reads/mutations на extended schema |
| No secret leakage | Principal/connection representations без token | Capture responses/logs/errors с synthetic canaries; нет initData/token/Idempotency-Key/чужих названий | Ошибки constraints/SQL наружу не попадают |

**Один законченный implementation outcome:** четыре additive-таблицы, один permission code без grants, два read endpoints и namespace-local trusted identity + exact access query, с единственным platform adapter и синтетическим proof A1/A2/B1. Новые grants не влияют на legacy RBAC; текущий owner entry работает без изменения контракта. Все строки матрицы имеют PASS либо устраняемый в scope failure до handoff. Targeted module tests, Ktor test host, Postgres IT и H2 migration parity, affected detekt/ktlint обязательны; межмодульный auth/data/API change требует полного JVM test gate по repository DoD. Документы получают bounded implementation evidence, независимое focused review и исправления findings.

**Exclusions:** реальные memberships/grants/mapping и credentials; любые mutation/admin APIs, UI, invitation/application/approval/club creation/publish, draft state machine, SDK upgrade, webhook/outbound multi-bot refactor, полный legacy isolation audit/cutover, commit/publication/deploy/runtime. Законченный локальный outcome не называется клиентским onboarding или готовностью запуска клиента. После его отдельной реализации и проверки — один handoff; дальнейшие права не возникают автоматически.

**Существенные альтернативы:** запись grants в существующий `user_roles` отвергнута принятым контрактом из-за расширения legacy authority; отдельные capability grants сохраняют старый role catalogue. Nullable `clubs.client_id` с глобальным NOT NULL/backfill не рекомендуется: side mapping оставляет legacy unmapped и не меняет старые queries. Унификация всех auth errors/удаление legacy X-Telegram-Id fallback не включены: новый namespace имеет строгий стандартный envelope, изменение старого внешнего контракта требует отдельного scope. За этими границами дополнительных вариантов для принятия этой первой задачи не требуется.

<a id="client-onboarding-ab-technical-contract"></a>
## 29. Принятый технический контракт А/Б: приглашение, заявка и отдельное создание клуба

**ACCEPTED, 2026-10-28.** Владелец прямо принял техническое предложение, подготовленное от `main 90b772ef117baa07b203ec6bad056873f51f8979`, с семью уточнениями текущей задачи. Это уточнение `DEC-039`–`DEC-042` и `CLNT-001`–`CLNT-010`, без нового DEC/CLB ID. §28 сохраняет контракт и исторические границы предыдущей read-задачи; его исключения mutations/UI не запрещают отдельно разрешённый этап А. Принятие target не доказывает implementation или runtime: локальная дельта — [AS_BUILT](AS_BUILT.md#client-onboarding-a-local), фактический verdict и проверки — [checkpoint](../ops/PROJECT_STATUS.md).

| Результат | Проверяемый конец | Текущая граница |
|---|---|---|
| **А** | Platform owner приглашает клиента; получатель явно подтверждает Telegram-аккаунт; платформа сверяет кандидата и выдаёт доступ; «Мои клубы» сохраняет заявку до решения платформы и восстанавливает её после входа | Разрешена локальная реализация production wiring, additive migrations, tests, UI/dist и связанные документы; без публикации/runtime |
| **Б** | Собственная approved-заявка используется ровно один раз для атомарного создания непубличного клуба, точных grants и минимального профиля; повтор ведёт к тому же клубу | Принят target-контракт, **реализация не входит в А** и требует отдельного разрешения |

Managed Bots, guest publication, перенос существующих реальных клубов, управление сотрудниками, полный мастер зала/календаря, автоматический billing и перенос владения на другой Telegram-аккаунт не входят в А. Б также не принимает автоматически многоботовый credential lifecycle или guest cutover.

### 29.1. Authority и клиентские границы

Существующие `users`, V061 `clients/client_memberships/client_clubs/client_club_grants`, оба client GET, `ApiError`, no-store и один production adapter платформенного бота переиспользуются. Новые назначения не записываются в `user_roles`; legacy `OWNER/GLOBAL` не открывает новые onboarding API.

| Субъект | Разрешение |
|---|---|
| Exact platform owner | Свежая проверенная Telegram identity, совпадающая с положительным серверным `OWNER_TELEGRAM_ID`: выдача/отзыв/явное восстановление доступа, направленные заявки, решения |
| Активный участник клиента | Собственное пространство; membership сама не предоставляет клубы или заявки |
| Участник с точным `client.application.manage` | Собственные черновики; чтение направленных заявок того же клиента; только автор изменяет/отправляет/отменяет свою заявку |
| Пользователь с точным `client.club.read` | Только соответствующий mapped-клуб при активных client/membership/grant; это не право настройки или создания |

Каждый новый запрос заново проверяет подпись, freshness и серверное подключение. Client mutation проверяет активность клиента, membership, точный action, объект и авторство. Platform mutation заново проверяет exact owner; прежний ответ `/api/platform/access` и отображённая кнопка не являются authority. Query, username, clientId, `X-Telegram-Id`, выбранный в браузере аккаунт и владение ссылкой не подтверждают полномочия. Браузер не выбирает bot token; второго production resolver или перебора токенов нет.

### 29.2. Приглашение и подтверждение человека

1. Platform owner вводит название клиентского пространства и подпись предполагаемого получателя. Одна транзакция создаёт неактивное пространство и invitation.
2. Invitation действует **семь суток**. Secret — криптографически случайный, минимум 256 бит; persistence хранит только hash. Ссылка использует отдельный `startapp=ci_<secret>`, не смешивается с guest invitations.
3. Preview/GET не создаёт `users`, membership или grants. Явный claim из прямого Mini App launch идемпотентно provisions минимального пользователя по проверенной Telegram identity в той же транзакции и фиксирует одного кандидата. Аккаунт не обязан предварительно отправлять `/start`.
4. Кандидат получает короткий одноразовый код и сообщает его отправителю **в исходном личном чате**. Платформа показывает тот же код у зафиксированного кандидата. Код служит человеку для сверки, не является credential и не принимается вместо platform-owner authority.
5. Только отдельное авторизованное действие platform owner до expiry активирует client, membership и ровно `client.application.manage`, переводит invitation в accepted и пишет audit. Клуб и клубные grants на этом шаге не создаются.

| Invitation state | Значение и допустимое продолжение |
|---|---|
| `issued` | Ожидает явного claim |
| `claimed` | Кандидат зафиксирован, код доступен для сверки; доступа ещё нет |
| `accepted` | Платформа завершила выдачу доступа; прежняя ссылка проверяет текущую membership и ничего не восстанавливает |
| `revoked` | Ссылка отозвана; secret и код больше не действуют |
| Effective `expired` | У issued/claimed истекло время; GET вычисляет состояние без записи, confirm запрещён |

Чужой аккаунт не заменяет зафиксированного кандидата и не получает его/клиентские private-данные. Для ошибочного получателя нужны revoke и reissue. Claim/confirm/revoke сериализуются; повтор не создаёт вторую identity/membership/grant или второе бизнес-событие. Effective expiry проверяется и на mutation, а не только при отрисовке.

Raw secret возвращается только в первоначальном успешном ответе issue/reissue. Идемпотентный повтор возвращает ту же invitation без восстановления secret; при потерянном ответе нужен явный reissue. Reissue отзывает прежнюю запись и создаёт новую для того же клиента с `replaces_invitation_id`; прежняя ссылка больше не действует. Accepted invitation не перевыпускается для восстановления доступа.

Сервер не отправляет приглашение автоматически: platform owner копирует ссылку и передаёт её сам. Raw secret, initData и raw idempotency key не сохраняются в наших логах, telemetry, audit metadata или необязательных клиентских хранилищах. `startapp` может попасть в URL и подписанную initData; приложение очищает адрес/необязательный SDK cache, но **не обещает отсутствие этого значения внутри инфраструктуры Telegram**.

### 29.3. Отзыв и восстановление доступа

До accepted отзывается invitation. После accepted отдельная platform операция деактивирует membership, `client.application.manage` и club grants этого пользователя **только в данном клиенте**. Пространство, заявки и клубы сохраняются; другие участники/пространства не меняются. Отказ в новой заявке не отзывает прежние клубные grants.

Restore — отдельная platform mutation с актуальной версией и **явным списком возвращаемых прежних scopes**: membership permission и пары club/action. Нельзя включить все клубы клиента по membership, восстановить чужие/не существовавшие grants или перенести членство на другой Telegram-аккаунт. Пустой явный список может восстановить только membership; новые права из него не следуют. Повтор и гонка restore/revoke подчинены общей transactional/version/idempotency границе.

### 29.4. Заявка: одно заведение, один автор

Новый draft сервер связывает с выбранным активным клиентом и текущим автором; автор/клиент далее неизменяемы. Для submit обязательны `venueName` 1–255, `countryCode` из двух букв, `city` 1–128, `address` 1–512 и валидная IANA `timezone` до 64 символов; `unit` до 128 и `comment` до 2000 необязательны. Контакт — подтверждённый Telegram-аккаунт автора; обязательных телефонных/платёжных реквизитов нет.

| Переход | Authority и условия |
|---|---|
| Создать/изменить `draft` | Автор, active client/membership и exact manage |
| `draft → submitted` | Автор; полная валидная форма и явное подтверждение отправки |
| `submitted → reviewing` | Только platform owner; актуальная version |
| `reviewing → approved` | Только platform owner; manual commercial record и явное подтверждение платформы |
| `reviewing → rejected` | Только platform owner; обязательная причина для клиента |
| `draft/submitted/reviewing → cancelled` | Автор с актуальным доступом, явная отмена |
| `submitted/reviewing → cancelled` | Platform owner, обязательная причина |
| `approved → cancelled` | Только platform owner, обязательная причина, до использования одобрения в Б |

После submit поля заведения неизменяемы; сохраняются snapshot, его hash и версия. Исправление требует отмены и нового draft с `previous_application_id`. Rejected/cancelled — конечные состояния; «Подать заново» создаёт другую запись, история прежней остаётся. Approval автоматически не истекает. Второе несовместимое решение или гонка submit/cancel, approve/reject возвращает конфликт версии/перехода, а не частичный результат.

Черновик читает только автор с manage; другие managers этого клиента читают направленные заявки без права действовать от имени автора. Platform owner видит направленные заявки и дальнейшую историю, не чужие несданные drafts. Обычная membership не открывает заявки. Отзыв автора не передаёт его approval другому manager; для другого будущего владельца нужна новая связанная заявка.

Нормализованный fingerprint названия и адреса уникален внутри клиента среди submitted/reviewing/approved. Одобрение, использованное в Б, продолжает резервировать fingerprint. Разные написания одного реального заведения требуют человеческой проверки и не считаются решённой физической дедупликацией.

### 29.5. Ручное коммерческое согласование

Approve требует `commercialReference`, `commercialAgreedAt` (дата), `platformConfirmed=true`, актуальную version и привязку к immutable submitted snapshot/hash/version. Сохраняются внутренний decision actor и серверное время. Один reference может покрывать несколько отдельно одобренных заявок. Синтетические references используются только в локальных тестах; payment API, счета, проверка оплаты и автоматическое списание не реализуются.

Клиентский текст решения и внутренняя platform note разделены. Клиентский API не возвращает internal note или чужие коммерческие данные. Ошибка новой заявки не меняет существующие клубы и их доступы. В А approved-карточка показывает ровно: **«Подключение одобрено. Создание клуба будет доступно после следующего этапа настройки платформы»**. Рабочей кнопки создания клуба в А нет.

### 29.6. Additive schema, API и транзакции

Все новые resource IDs — positive BIGINT и десятичные строки в JSON; изменяемые ресурсы имеют positive BIGINT version. Времена — UTC. Парные migrations PostgreSQL/H2 идут после V061; старые migration bytes не меняются.

| Объект А | Контракт |
|---|---|
| `client_memberships.version` | Версия выдачи/отзыва/восстановления доступа |
| `client_membership_grants` | Точный composite FK к membership, PK client/user/action; только `client.application.manage`, default inactive, version/timestamps |
| `client_invitations` | Unique token hash, state/expiry/claimant, verification code только на период сверки, version, замена invitation, CHECK согласованности lifecycle; не более одного открытого invitation клиента |
| `client_club_applications` | Client/author composite FK, шесть состояний, draft-поля, submitted snapshot/hash/version, fingerprint, previous application, решение/согласование/история, запрет активного точного дубля |
| `client_commands` | Unique actor/connection/key-hash, digest операции/resource/нормализованного payload/version, ссылки на результат; без raw secrets/keys/credentials |
| `audit_log` | Transaction-local insert через то же соединение; безопасные внутренние actor/resource/client IDs, action/transition, version и correlation, без полной заметки или secret |

`C=/api/client`, `P=/api/platform/onboarding`. Действующие `GET C/context` и `GET C/clubs/{clubId}` сохраняют read-контракт.

| API А | Назначение |
|---|---|
| `POST P/invitations`; `GET P/invitations/{id}` | Issue и состояние/кандидат |
| `POST P/invitations/{id}/{confirm,revoke,reissue}` | Отдельные platform lifecycle actions |
| `POST P/clients/search`; `GET P/clients/{id}` | Ограниченные списки/карточки клиентов и доступов |
| `POST P/clients/{c}/members/{u}/{revoke,restore}` | Отдельный отзыв/явное восстановление scopes |
| `POST C/invitations/resolve`; `POST C/invitations/{id}/claim`; `GET C/invitations/{id}` | Read-only preview secret из body, явный claim, статус своего кандидата |
| `GET C/clients/{c}/onboarding` | Доступные actions и разрешённые клубы |
| `POST C/clients/{c}/applications`; `POST C/clients/{c}/applications/search` | Создание draft и авторизованный список |
| `GET/PATCH C/applications/{id}`; `POST C/applications/{id}/{submit,cancel}` | Своя карточка/draft/переход |
| `POST P/applications/search`; `GET P/applications/{id}` | Очередь и карточка направленной заявки |
| `POST P/applications/{id}/{review,approve,reject,cancel}` | Platform decision |

Read-only search POST не требует idempotency key; cursor находится в body, default limit 50/max 100. Query-параметров нового API нет. Mutations требуют `Idempotency-Key`, изменения существующего ресурса также `expectedVersion`. Responses возвращают состояние, version и доступные действия; эти actions — подсказка UI, не authority.

Каждая mutation записывает бизнес-данные, identity provisioning при необходимости, command result и обязательный audit в **одной** transaction/connection. Ошибка audit откатывает все записи; нет последовательных независимых writer-транзакций. Порядок locks согласован между claim/confirm/revoke/access/application действиями; concurrency проверяется на отдельных PostgreSQL соединениях. Bounded retry повторяет всю transaction; `CancellationException` пробрасывается. Повтор уже committed команды повторно проверяет текущий доступ и возвращает тот же ресурс без второго бизнес-события. Тот же key с другим payload даёт `409 idempotency_conflict`; потерянный ответ не запускает компенсационное удаление.

Сохраняется плоский `ApiError` и no-store/Vary. Неверная/устаревшая identity — 401; не-platform owner — 403; чужой/отсутствующий/недоступный объект неразличимы (404); ввод — 400; `version_conflict/invalid_transition/idempotency_conflict/duplicate_application` — 409 после authority check; persistence failure — безопасный 500 без SQL/credentials. Значение GET не обещает отменить уже начавшийся ответ после момента его снимка.

### 29.7. Экраны, повторный вход и account switch

Переиспользуются тёмная основа platform-owner, существующие карточки, safe areas, Back/history и фокус на заголовке. Platform screens связаны с приглашениями/кандидатом, выдачей и отзывом доступа, clients и очередью/карточкой решения. Client screens: текущий аккаунт и claim, ожидание с кодом, «Мои клубы», выбор собственного пространства, сохранение draft/submit, статус/история/допустимая отмена.

Кабинет читает свежий серверный context; global guest ClubPicker для него не используется. Нет активных пространств — честное empty state; нет clubs — разрешённая manage-кнопка «Подключить заведение». Club picker содержит только exact grants пользователя. Несохранённый ввод не объявляется сохранённым; уход с ним требует предупреждения.

При запуске, foreground/pageshow и смене аккаунта прежние private-данные скрываются, SDK credential перечитывается, запросы отменяются, старые формы/results очищаются; запоздалые ответы прежнего аккаунта отвергаются. Сохранённые IDs — только навигационное предпочтение. Если доступ отозван или identity устарела, UI показывает access lost/повторное открытие, не старые данные.

Предусмотрены loading, empty, error/retry, saving, success, validation, access lost, version conflict и **«Результат операции пока неизвестен»**. После неизвестного исхода допускается повтор той же команды с тем же key/payload либо авторизованное перечитывание; после 409 загружается актуальный ресурс. Секрет invitation не сохраняется в localStorage/sessionStorage/другом необязательном cache.

Прямой `startapp` использует canonical React client entry; `mode=client` выбирает presentation, а не права. Private `/start` сохраняет guest кнопку и добавляет «Мои клубы» по active membership **message.from.id**. Platform-owner menu сохраняется. Backend не рассылает реальные приглашения автоматически.

### 29.8. Контракт Б остаётся отдельной реализацией

`POST C/applications/{id}/club` в Б принимает expectedVersion, idempotency key и проверяет автора, active client/membership/manage и approved snapshot. Одна transaction создаёт ровно один `clubs` с `is_active=false`, `client_clubs`, `client_club_onboarding`, exact `client.club.read` и `client.club.profile.write`, consumption и audit. Unique application consumption и composite FK фиксируют client/owner/approved status; used approval нельзя отменить или освободить удалением mapping. Потерянный ответ/параллельный повтор возвращает тот же клуб при текущем доступе; любая ошибка до commit полностью откатывает результат.

Минимальный профиль Б: display name, описание до 2000 и валидная timezone; страна/город/адрес одобренного заведения без редактирования. `GET/PATCH C/clubs/{id}/profile` требует exact read/write grants, version/idempotency для записи. Статусы «Непубличный», «Бот не подключён», дата сохранения профиля независимы от approval. Publication API отсутствует.

Одного `is_active=false` недостаточно: до готовности Б обязательны targeted guards guest list/detail/events, support/ask, layout/files/ETag/304, booking/HOLD и legacy admin mutations для новых mapped private clubs, включая кэши и side effects; unmapped legacy flow сохраняется. Это будущий containment target, **не выполненный этапом А**. Перенос владения, staff management, Managed Bots и реальная активация клиентов требуют отдельного scope.

### 29.9. Verification и delivery boundary

Локальная приёмка А: meaningful Kotlin/H2 unit/migration tests; Ktor transport/identity/authority/negative isolation; PostgreSQL на CI-major для transactions/rollback/claim-confirm-revoke и submit-cancel/approve-reject concurrency; idempotency/lost response; Vitest/TypeScript/lint, Playwright переходов и отказов; build/dist/реальное packaging; affected detekt/ktlint/logs-security и полный JVM gate межмодульного изменения. Все аккаунты/данные/references синтетические.

Один независимый focused review проверяет final auth/RBAC, client boundaries, lifecycle, locks, audit, secrets и guest regression; findings исправляются, affected checks повторяются. Review evidence привязывается к **финальным bytes после форматирования и последней static правки**, не только к base SHA.

Real Telegram/device, stage/prod, deployed revision и runtime migrations проверяются только после отдельного разрешения rollout. Текущая задача запрещает commit/push/PR/merge, workflow dispatch/rerun, deploy/recovery, SSH/AEZA/Docker lifecycle, Secrets/Environment и live mutations. Следующий publication step — отдельное разрешение подготовки Draft PR; автоматического перехода к Б нет.
