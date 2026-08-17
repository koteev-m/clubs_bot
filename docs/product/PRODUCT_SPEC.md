# Нормализованная продуктовая спецификация

Статус документа: первая базовая линия для review. Строки `SOURCE` нормализуют, но не расширяют [CONCEPT_SOURCE.md](CONCEPT_SOURCE.md). Строки `DECISION_REQUIRED` фиксируют невыбранный вариант или контракт. Repository-only требования, которых source не устанавливает, не становятся source promises: они зарегистрированы как конфликт и зависят от `DEC-026`. Ни один тип не означает, что capability реализован. Неурегулированные варианты вынесены в [OPEN_DECISIONS.md](OPEN_DECISIONS.md).

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

## 26. Что спецификация сознательно не обещает

- Конкретную AI-модель, поставщика, точность или экономический эффект.
- Принятые публичные названия, MVP-состав ролей и multi-club launch policy до решений пользователя.
- Repository-only additions (включая playlists/favourites, exports/auto-reports и cloning/templates) не считаются source promises до решения `DEC-026`; их нельзя ни автоматически принять, ни автоматически удалить из target scope.
- Availability любого Telegram capability без повторной официальной проверки непосредственно перед реализацией/release.
