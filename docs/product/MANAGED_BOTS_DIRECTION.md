# Managed Bots: направление и каталог специализаций

Дата продуктового уточнения и проверки официальных источников: **2026-10-28**.
Repository evidence: base `71d0beda4ab2897f668f63f9a67b552d4214d863` и сохранённый
локальный invitation candidate; это не новая реализация и не runtime acceptance.

## Статус и authority

- **Уже принято:** [DEC-039–DEC-042](OPEN_DECISIONS.md#dec-042--брендированные-telegram-подключения-общей-платформы)
  и [CLNT-001–CLNT-010](PRODUCT_SPEC.md#client-onboarding-contract) сохраняются.
  DEC-042 задаёт брендированный гостевой бот клуба/разрешённой сети одного клиента,
  общую серверную платформу, пользовательское подтверждение создания, доверенный
  bot context и серверную проверку полномочий. Этот документ не заменяет DEC-042.
- **Новое направление владельца:** клиент сможет подключать также специализированных
  ботов для задач заведения и сотрудников. Подключение обслуживает один клуб либо
  явно разрешённые клубы одного клиента. Несколько назначений могут быть модулями
  одного служебного бота; отдельный бот под каждую функцию не обязателен.
- **Предложения:** восемь категорий ниже, выбор первого служебного сценария,
  разбиение на ботов/модули, интерфейсы, тарифы и техническое устройство lifecycle.
  Они не становятся утверждёнными продуктами или release scope из-за этой записи.
- **Открытые решения:** существенные варианты перечислены ниже; новые DEC/CLNT IDs
  и decision records не создаются, счётчики решений не меняются.

Этот документ — единый каталог исследования. Target-contract остаётся в
[PRODUCT_SPEC](PRODUCT_SPEC.md#client-onboarding-contract), фактические ограничения —
в [AS_BUILT](AS_BUILT.md#managed-bots-direction-evidence) и
[gap register](CONCEPT_CODE_GAP.md#managed-bots-direction-gap).

## Официальные возможности Telegram

Проверены первичные страницы Telegram; никаких management methods на реальных
ботах в этой задаче не вызывали. Поддержка платформой не доказывает поддержку SDK
или готовность clubs_bot.

### Пользовательское создание и ownership

[Managed Bots / Creating Your Own Management Bot](https://core.telegram.org/bots/features#creating-your-own-management-bot)
описывает включение **Bot Management Mode** у выбранного manager-бота через BotFather.
[Sharing / Using Your Management Bot](https://core.telegram.org/bots/features#sharing-your-management-bot)
описывает ссылку с предложенными именами: пользователь может их изменить и
завершает создание в Telegram. Это не право обычного бота самостоятельно создавать
произвольные бот-аккаунты без человека.

[MTProto: Creating a managed bot](https://core.telegram.org/api/bots/managed-bots#creating-a-managed-bot):
`bots.checkUsername` и `bots.createBot` вызываются пользователем; созданный бот
принадлежит этому пользователю, manager задаётся отдельно. Имена ограничены:
username 5–32 символа из букв/цифр/underscore с окончанием `bot`, display name 1–64.
Доступность username и лимит owned bots проверяются Telegram; лимиты зависят от
пользовательской конфигурации/Premium, фиксированное число здесь не обещается.
Возможны занятое имя и отсутствие manager permission. См. также
[bots.createBot: Only users](https://core.telegram.org/method/bots.createBot).

Точная [managed bot creation request link](https://core.telegram.org/api/links#managed-bot-creation-request-links):
`https://t.me/newbot/<manager_bot_username>/<suggested_bot_username>?name=<suggested_bot_name>`.
Предложенные username/name необязательны, name кодируется для URL. Telegram-клиент
проверяет, что разрешённый manager имеет `bot_can_manage_bots`, и использует
`via_deeplink` в пользовательском MTProto flow. Deep link не подтверждает права
пользователя на client/club в clubs_bot. Это другой flow, чем invitation
`startapp=ci_<secret>`; текущие приглашения не переводятся на `start`.

### Bot API и MTProto не взаимозаменяемы

| Bot API, точный раздел | Подтверждённая возможность |
| --- | --- |
| [Update.managed_bot](https://core.telegram.org/bots/api#update), [ManagedBotUpdated](https://core.telegram.org/bots/api#managedbotupdated) | Создание либо изменение token/owner; объект содержит `user` и `bot` |
| [getManagedBotToken](https://core.telegram.org/bots/api#getmanagedbottoken) | Получить token управляемого бота; `user_id` означает ID **бота**, не создателя |
| [replaceManagedBotToken](https://core.telegram.org/bots/api#replacemanagedbottoken) | Отозвать текущий token и получить новый; также принимает ID бота |
| [getManagedBotAccessSettings](https://core.telegram.org/bots/api#getmanagedbotaccesssettings) | Прочитать `BotAccessSettings` по ID бота |
| [setManagedBotAccessSettings](https://core.telegram.org/bots/api#setmanagedbotaccesssettings) | Менять `is_access_restricted`; owner сохраняет доступ, можно добавить до 10 users, список игнорируется без restriction |

Это методы manager-бота для его managed bots, не полномочия любого bot token.
В [getMe → User](https://core.telegram.org/bots/api#user) manager capability обозначена
`can_manage_bots`; будущая проверка должна связать bot ID с доверенными credentials,
а не принимать один username как identity.
Их MTProto-аналоги перечислены отдельно в
[Managing a managed bot](https://core.telegram.org/api/bots/managed-bots#managing-a-managed-bot):
`updateManagedBot`, `bots.exportBotToken` с `revoke`, `bots.getAccessSettings`,
`bots.editAccessSettings`. Telegram access restriction не заменяет permissions
платформы и само по себе не задаёт кадровую модель клуба. Событие owner update
не означает принятия нашим продуктом автоматического переноса управления.

### Webhook и входящие обновления

[Getting updates](https://core.telegram.org/bots/api#getting-updates) описывает
webhook либо getUpdates; они взаимоисключаются для данного бота.
[setWebhook](https://core.telegram.org/bots/api#setwebhook) задаёт HTTPS-доставку,
повторы при неуспехе, `secret_token` и `allowed_updates`.
Из этих примитивов **не следует готовый multi-tenant router**: выбор доверенного
подключения, связи с клиентом и дальнейшей доставки — работа clubs_bot.

## Каталог возможных специализаций

Для **всех восьми каналов статус `PROPOSED`**: такого законченного Managed Bot
продукта сейчас нет. Здесь `PROPOSED` — локальная метка предложения, не новый
статус принятого requirement. Второй статус показывает только пригодность
существующих доменных основ: `AS_BUILT` для конкретного подключённого фрагмента,
`PARTIAL` для неполного flow, `GAP` для отсутствующей основы. Ни наличие API,
ни старый UI не доказывают готовность нового бота. Evidence E1–E8 ниже относится
к source wiring, не к наличию реальных данных на Stage.

### А. Гостевой брендированный бот

- **Пользователь / польза:** гость; единый брендированный вход своего клуба или разрешённой сети.
- **Действия:** афиша/события, столы/зоны/бронирования, приглашения, информация,
  вопросы/поддержка; персональные предложения только после согласования доступа и коммуникаций.
- **Данные / доступ:** опубликованные сведения клуба, реальные availability/booking/invitation
  records, собственные обращения; гостю не раскрываются чужие записи, private-клубы и другие клиенты.
- **Зависимости / статус:** канал `PROPOSED`; домен `PARTIAL` (E1), support имеет
  ограниченный `AS_BUILT` путь. Branded connection/guest cutover — `GAP` (E8).
- **Вариант:** отдельная identity гостевого бренда по DEC-042; функции — модули
  этого бота. Не предполагается бот на каждую функцию бронирования/поддержки.

### Б. Бот аналитики для владельца

- **Пользователь / польза:** уполномоченный владелец/руководитель; видеть состояние разрешённых заведений.
- **Действия:** дневная/недельная сводка, посещаемость, загрузка столов, бронирования/
  отмены, подтверждённые приходы, результат промоутеров; финансы только по разрешённым
  достоверным данным; сравнение своих клубов только при явном сетевом доступе.
- **Данные / доступ:** реальные booking/check-in/visit records и snapshots с периодом,
  timezone, временем обновления и caveats. Финансовые показатели требуют ledger/shift
  источника и отдельного финансового permission; membership не открывает всю сеть.
- **Зависимости / статус:** канал `PROPOSED`; домен `PARTIAL` (E2). Scheduled
  reports и network comparison не доказаны. Нулевые fallback-значения с caveats
  не должны подаваться как подтверждённые нули или полноценная аналитика.
- **Вариант:** сначала оценить модуль общего служебного бота; отдельный бот —
  предложение при подтверждённой потребности в аудитории/изоляции/уведомлениях.

### В. Бот персонала

- **Пользователь / польза:** сотрудники и менеджер смены; согласованная оперативная работа.
- **Действия:** задачи по сменам, чек-листы, уведомления, вопросы менеджеру,
  инциденты, заявки на изменения и контроль выполнения.
- **Данные / доступ:** назначения сотрудников, смены, durable tasks/incidents,
  статусы и журнал действий. Отдельные permissions на задачи/сотрудников;
  staff одного клуба не получает остальные клубы клиента.
- **Зависимости / статус:** канал `PROPOSED`; домен `PARTIAL` (E3): есть ops
  notifications и ephemeral checklist; durable task/incident/change-request loop
  в исследованном пути — `GAP`, не готовая кадровая система.
- **Вариант:** общий внутренний служебный бот с ролевыми модулями; отдельные
  identities персонала/аналитики обсуждаются после выбора первого сценария.

### Г. Бот промоутеров

- **Пользователь / польза:** назначенный промоутер; собственные списки и проверяемые результаты.
- **Действия:** списки гостей, персональные ссылки, QR-приглашения, подтверждённые
  приходы, собственная статистика и разрешённые уведомления о мероприятиях.
- **Данные / доступ:** lists/entries/invitations/check-ins и подтверждённая attribution
  промоутеру; только свои списки и разрешённые события/клубы, без чужих PII/финансов.
- **Зависимости / статус:** канал `PROPOSED`; домен `PARTIAL` (E4). Несколько
  QR/pass контрактов и неполный canonical arrived/seated outcome нельзя выдавать
  за завершённую оценку эффективности.
- **Вариант:** модуль служебного бота или отдельный бот для реально отдельной аудитории;
  это не автоматическое возвращение legacy PROMOTER в новый onboarding.

### Д. Бот обратной связи

- **Пользователь / польза:** добровольно отвечающий гость и уполномоченный менеджер;
  адресная обратная связь с разрешённой агрегацией.
- **Действия:** добровольные опросы, оценки посещений, предложения, обращения/жалобы,
  агрегированные результаты.
- **Данные / доступ:** действительное посещение при оценке визита, ответы/согласия,
  support tickets/messages; гость видит своё, менеджер — разрешённый клуб.
  Нельзя обещать анонимность без отдельного принятого контракта.
- **Зависимости / статус:** канал `PROPOSED`; домен `PARTIAL` (E5). Support не
  доказывает готовность опросов, оценок посещений или агрегированной survey analytics.
- **Вариант:** модуль гостевого бота и staff-проекция в служебном; отдельный бот
  имеет смысл только после исследования пользовательского пути.

### Е. Маркетинговый бот

- **Пользователь / польза:** уполномоченный маркетолог и согласившийся получатель;
  управляемые коммуникации о клубе.
- **Действия:** мероприятия, разрешённые рассылки, подписки, сегменты, кампании,
  статистика отклика с явно определёнными событиями.
- **Данные / доступ:** consent/preferences, разрешённые сегменты, campaign/delivery
  и response events. Их наличие нельзя выводить из списка всех Telegram users.
  Нужны отдельное communications permission, preview/confirmation и отписка.
- **Зависимости / статус:** канал `PROPOSED`; домен `GAP` для полного flow (E6).
  Существующие campaign/scheduler fragments не подключены в Application;
  готового consent/частотного контракта и доказанных response metrics нет.
- **Вариант:** staff-модуль управления кампаниями, доставка разрешённым гостевым
  подключением; отдельный sender-бот не даёт права писать неподписанным людям.

### Ж. Финансовый бот

- **Пользователь / польза:** отдельно уполномоченные финансовые сотрудники;
  сверка действительных операций и сменных отчётов.
- **Действия:** отчёт смены, браслеты/категории, выручка, депозиты, расхождения,
  scoped отчёты. Изменение денег/закрытие требуют собственного разрешения и подтверждения.
- **Данные / доступ:** ledger, сохранённые shift reports и templates с указанием
  draft/closed/finality; выручка из введённых статей не означает интеграцию с POS/банком.
  Staff/analytics permission не включает финансы автоматически.
- **Зависимости / статус:** канал `PROPOSED`; домен `PARTIAL` (E7); отдельные
  ledger/template/freeze fragments — `AS_BUILT`, но canonical finance role и
  correction-after-close workflow неполны.
- **Вариант:** защищённый модуль служебного бота; отдельная identity — обсуждаемый
  вариант разграничения аудитории, не замена серверным permissions.

### З. Музыкальный бот

- **Пользователь / польза:** гость, DJ и модератор с явно принятыми правами;
  доступный музыкальный контент и взаимодействие клуба.
- **Действия:** сеты, голосования/реакции, подборки, специальные активности.
- **Данные / доступ:** published music/assets, права на контент, реальные votes/likes;
  авторство и модерация отделены от гостевого чтения, private favourites не публичны.
- **Зависимости / статус:** канал `PROPOSED`; домен `PARTIAL` (E7). Наличие
  music APIs не закрывает DJ role, curated playlists и canonical UI;
  монетизация/спецактивности не принимаются через этот каталог.
- **Вариант:** модуль гостевого бота; отдельный музыкальный бренд — только при
  отдельной потребности и согласованном контентном/правовом контракте.

## Bounded evidence существующих основ

Пути ниже — source locators текущего base/candidate. Старые baseline gaps уточняются
локальной дельтой А/Б, но не переносятся автоматически в deployed readiness.

| ID | Проверенная цепочка / граница |
| --- | --- |
| E1 | `Application.kt:397–422` регистрирует guest gamification и promoter/list APIs; `routes/ClubsRoutes.kt::clubsRoutes`, `routes/SecuredBookingRoutes.kt::securedBookingRoutes` — каталог и booking foundations. [AS_BUILT §4](AS_BUILT.md#4-key-flow-audit) фиксирует неполный guest booking/UI flow; `Application.kt:496–503` → `routes/SupportRoutes.kt` подключает bounded support. Не полный branded guest experience. |
| E2 | `Application.kt:449–453,465–470` → `AdminAnalyticsRoutes.kt:111–151`, `OwnerHealthRoutes.kt:36–80`; `analytics/AdminAnalyticsSnapshotService.kt:107–147` читает attendance/visits и сохраняет caveats. Endpoint работает в club scope; сетевые сравнения и scheduled daily/weekly bot delivery этим не доказаны. Источники/неполнота также в gap `ANL-001–006`. |
| E3 | `Application.kt:286,521–527` → `host/ShiftChecklistService.kt:7–17,48–65`: память процесса, не durable tasks. `di/OpsNotificationsModule.kt:28–47` подключает ops publisher через один TelegramClient. Gap `OPS-001–005` не закрыт наличием checklist. |
| E4 | `Application.kt:406–422` → `routes/PromoterGuestListRoutes.kt::promoterGuestListRoutes`, `routes/PromoterInvitesRoutes.kt::promoterInvitesRoutes`; guest-list/entry/invitation/check-in repositories. [Gap GL/PASS/ANL](CONCEPT_CODE_GAP.md#1-полная-traceability-matrix) сохраняет ограничения attribution/единого pass; точные исходные locators находятся в строках `GL-003`, `PASS-001`, `ANL-001`. |
| E5 | `Application.kt:496–503` → `routes/SupportRoutes.kt`, Telegram `/ask` → support persistence/delivery. Принятый [Private Support Loop](slices/PRIVATE_SUPPORT_LOOP.md) — основа вопросов/жалоб, не доказательство отдельного survey product. |
| E6 | `routes/NotifyRoutes.kt:314–345`, `workers/CampaignScheduler.kt:38–188`, `workers/SchedulerModule.kt:87–135` — fragments; `notifyRoutes`/scheduler не подключены в исследованном `Application.kt`. [Gap COM-002–005](CONCEPT_CODE_GAP.md#1-полная-traceability-matrix) описывает отсутствующие consent/quiet hours и незапущенный flow. |
| E7 | `Application.kt:442–448` → `routes/AdminFinanceShiftRoutes.kt::adminFinanceShiftRoutes`, `routes/AdminFinanceTemplateRoutes.kt::adminFinanceTemplateRoutes` → `core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportRepositories.kt`; `Application.kt:461,477–495` → admin music/likes/battles/track APIs. [Gap FIN/MUS](CONCEPT_CODE_GAP.md#1-полная-traceability-matrix) различает конкретные `AS_BUILT` fragments и незавершённый role/UI outcome. |
| E8 | `di/OpsNotificationsModule.kt:13–26` — singleton TelegramBot/TelegramClient; `Application.kt:368–379` — platform token для client onboarding; `routes/TelegramWebhookRoutes.kt:39–79` — один secret/ingress; `core-data/src/main/kotlin/com/example/bot/data/security/webhook/WebhookSecurityTables.kt:23–53` — global update_id. `gradle/libs.versions.toml:37,77` фиксирует Pengrad 9.2.0; [ранее проверенный cached artifact](CONCEPT_CODE_GAP.md#sdk-и-telegram-managed-bots) не содержит typed managed updates. Managed token lifecycle, branded inbound/outbound и назначение purpose сейчас `GAP`. |

Все сокращённые Kotlin paths E1–E8, кроме явно указанного core-data, относительны
`app-bot/src/main/kotlin/com/example/bot/`. Новые классы/таблицы/API не создаются.

## Архитектурное направление и обязательные границы

Одна общая серверная платформа обслуживает независимых клиентов. Клиент может иметь
несколько клубов, клуб — несколько Telegram connections с конкретным назначением;
каждый бот имеет отдельную Telegram identity. Разные подключения используют только
разрешённые общие данные. Создание отдельного backend/БД на каждого бота не является
базовым решением и потребует обоснованной необходимости. Точная физическая модель,
названия сущностей и API пока не выбраны.

Владение ботом в Telegram не выдаёт все клубы клиента, platform-owner authority
или кадровый/финансовый доступ. На каждое действие сервер проверяет trusted
connection → client → разрешённый club → пользователя → конкретное permission.
Статистика, финансы, сотрудники, коммуникации и гостевые операции имеют отдельные
полномочия; их точные коды и назначения требуют будущего контракта.

Обязательные принципы будущей реализации:

- Token/initData не логируются; токены не показываются другим клиентам и не
  пересылаются в UI как способ выбора authority. Secret storage, key ownership,
  rotation и отзыв должны быть согласованы до подключения.
- Нет переноса guest PII между клиентами/ботами без соответствующего права и цели;
  файлы, caches, queues и сохранённые deliveries входят в проверку изоляции.
- Аналитика использует действительные разрешённые источники, обозначает missing,
  stale, draft/final и период; выдуманных метрик или замены неизвестного нулём нет.
- Коммуникации требуют согласий там, где они необходимы, отписки и контроля частоты;
  несанкционированные рассылки запрещены. Retries не должны дублировать действие
  или менять адресата/бота исходного intent.
- Revoke немедленно прекращает соответствующие действия, в том числе отложенные;
  отключение не разрешает удалить данные или автоматически перенести ownership.
- Опасные действия — изменение токена/управления/доступа, деньги, широкая отправка —
  требуют явного подтверждения уполномоченного владельца и аудита результата.
- Специализированный бот не означает AI-агента. AI остаётся отдельной опцией с
  принятыми data permissions, grounding, пределами действий и подтверждением.

## Существенные вопросы до реализации

1. **Продукт и роли:** первый use case/релиз, модули общего служебного бота против
   отдельных identities, тарифы/стоимость, UX, permissions и доступ к достоверным данным.
2. **Ownership:** связь user/client/club/bot; один или несколько клубов на connection;
   scope согласования и purpose/modules. Как надёжно связать пользовательское создание
   с нашим intent, проверить manager identity и не принять чужой managed_bot update.
3. **Lifecycle:** статусы pending/active/attention/revoked — пока варианты, не enum;
   хранение tokens, rotation, token/owner events, безопасное отключение и передача
   управления, гонки/reconciliation и отказ после revoke. Ранее созданные боты не
   считаются автоматически поддержанным подключением.
4. **Ingress/auth:** доверенное разграничение webhook/update для каждого бота,
   правильный initData validator без перебора tokens, обработка managed_bot у manager,
   dedup в контексте конкретного бота. Логический `(connection, update_id)` — пример
   будущего ключа, а не утверждённая migration/schema.
5. **Outbound:** нужная Telegram connection сохраняется в intent и проверяется
   перед отправкой; queues/retries/limiter/dedup не смешивают клиентов. Ротация и
   отключение должны быть согласованы с уже сохранёнными уведомлениями и audit.
6. **Технология и Telegram:** SDK upgrade или ограниченный HTTP adapter, parser/update
   coverage; ограничения пользовательского создания, manager permissions и ownership;
   обработка platform errors. Наличие официального метода не доказывает его
   доступность действующему боту. MTProto user-session платформе этой записью не разрешена.

Эти вопросы дополняют [единый блок проектирования](PRODUCT_ROADMAP.md#client-open-design),
но не закрывают существенные решения от имени владельца.

## Предложение последовательности

1. Завершить onboarding А/Б, invitation fix и отдельно разрешённую Telegram acceptance.
2. Исследовать и согласовать Managed Bots connection lifecycle, SDK и security contract.
3. Выпустить первый брендированный гостевой бот клиента в принятой границе DEC-042.
4. Оценить первый внутренний служебный бот с аналитикой **либо** оперативной информацией
   по реально доступным данным; не обещать весь каталог одновременно.
5. Добавлять другие специализации по реальной потребности и отдельному принятому scope.

Это **предложение**, не автоматическое изменение принятого roadmap, не календарный
план и не блокер приглашений. Публикация локального invitation candidate и будущий
Stage update остаются отдельными действиями; текущая задача меняет только документы.
