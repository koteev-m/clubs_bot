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
Stage update остаются отдельными действиями; задача 2026-10-28 меняла только документы.

<a id="first-managed-bot-contract"></a>
## Первый клубный бот: техническое предложение — 2026-10-31

**PROPOSED, решения владельца перечислены в конце.** Адресное исследование на
`main 2ab62b042d3ca392733e52a1de15b0df7acce227`; без новых DEC/CLB, migrations,
token lifecycle, BotFather или реальных Telegram connections. Прямо заданный
scope: один branded guest bot → один непубличный клуб одного клиента; общая
серверная платформа. Создание бота не публикует клуб. Night Concierge сохраняет
платформенный вход и прежние гостевые сценарии до отдельно проверенного перехода.

### Проверенные Telegram primitives и SDK

Повторно прочитаны [Managed Bots](https://core.telegram.org/bots/features#managed-bots),
[Bot API](https://core.telegram.org/bots/api) и
[MTProto creation](https://core.telegram.org/api/bots/managed-bots).
Пользовательское создание и ownership подтверждены документацией; MTProto
user-session платформе не требуется и не разрешается этим предложением.

В [newbot link](https://core.telegram.org/api/links#managed-bot-creation-request-links)
нет документированного callback nonce. Bot API `ManagedBotUpdated` содержит
только `user`/`bot`; нет reason, revision или request correlation. Поле `user`
описано как создатель даже для уведомления об изменении owner. Поэтому событие
не доказывает выбранный клуб или непрерывность владения. `getManagedBotToken`
и `replaceManagedBotToken` принимают ID **бота** в `user_id`. `getMe` проверяет
bot identity, но не owner. Keyboard request имеет `request_id`, однако схемы
`ManagedBotUpdated`/`ManagedBotCreated` не обещают его эхо. Access settings
позволяют ограничить аудиторию владельцем и выбранными пользователями.
[Точные схемы](https://core.telegram.org/bots/api#managedbotupdated).

- `gradle/libs.versions.toml` закрепляет Pengrad **9.2.0**. Повторное чтение cached
  JAR подтвердило отсутствие Managed classes и поля `managed` у Update;
  SHA-256 `4f172d2e71229cb54684dbe427d30be2640f0c44f4353333e0d5e10688641f79`.
- [Официальные releases Pengrad](https://github.com/pengrad/java-telegram-bot-api/releases)
  указывают появление ManagedBotUpdated/Get/ReplaceManagedBotToken в **9.6.0**;
  текущий release **10.3.0**. Переход через 10.0 меняет Gson/OkHttp и Kotlin
  Gradle plugin/toolchain. Release notes не доказывают совместимость с ClubsBot или
  поддержку всех более новых methods в 9.6.0. Новый SDK здесь не устанавливался.

### Выбор manager и compatibility boundary

| Вариант | Последствие и статус |
| --- | --- |
| Night Concierge как manager | Меньше новых identities, но Bot Management Mode расширяет authority существующего production credential. Требует отдельного принятия; текущий token не менялся. |
| **Отдельный management bot — рекомендация** | Собственные credential/ingress и management handlers, общие backend/DB. Дополнительные provisioning и Telegram переход; новая authority требует принятия владельцем. |
| Pinned SDK upgrade | Typed updates/methods; нужны dependency verification, parser/HTTP fixtures и regression всех затронутых Telegram flows. Версия выбирается отдельным compatibility gate. |
| **Узкий HTTP/JSON adapter — рекомендация для первого этапа** | Сохраняет 9.2.0 для legacy flows; manager methods и raw managed envelope имеют собственные DTO/error/redaction tests. Нельзя сначала пропускать managed payload через старый Pengrad parser: поле теряется. Это также непринятое техническое решение. |

Manager обслуживает только onboarding/lifecycle; branded connection — только
guest purpose указанного клуба. Ни одна из них не наследует platform-owner,
GLOBAL, staff, finance или другие client permissions. Отдельные servers/DB не нужны.

### Пользовательский путь и доверенная привязка

1. В Night Concierge авторизованный клиент открывает профиль конкретного private
   клуба и инициирует подключение. Сервер заново проверяет fresh signed identity,
   active user/client/membership, exact client-club mapping и **отдельный** grant,
   предлагаемый код `client.club.bot.manage`. `profile.write`, role name и GLOBAL
   не заменяют его. Источник выдачи grant ещё должен утвердить владелец.
2. Durable intent хранит manager bot ID, creator Telegram ID и внутренний user ID,
   client/club IDs, `purpose=guest`, expiry/version/idempotency. Предложение:
   TTL 30 минут, один незавершённый intent на `(manager, creator)`; при переключении
   между двумя клиентами прежний intent нужно явно отменить или завершить.
3. UI показывает организацию/клуб и открывает официальный
   `https://t.me/newbot/<manager>/<suggested_username>?name=<suggested_name>`.
   Имя — предложение; пользователь сам создаёт принадлежащего ему бота в Telegram.
4. Только authenticated manager ingress принимает candidate из `managed_bot`:
   доверенный manager, совпадающий creator, pending intent, ещё не связанный bot ID.
   Событие не выбирает client/club по username/name/URL. Orphan/late/ambiguous
   events сохраняются как неразрешённые и ничего не активируют.
5. Вернувшись в кабинет, тот же creator явно подтверждает **конкретные** bot ID,
   username, организацию и клуб: «Подключить @… к заведению …». Сервер принимает
   opaque candidate ID + expectedVersion, повторяет все проверки и атомарно
   резервирует уникальную связь. Произвольный bot ID из frontend не принимается.
   Несколько candidates показываются явно, без выбора последнего по времени.
6. Доверенный сервер получает token через manager, проверяет `getMe.id` и
   `is_bot` против candidate, сохраняет credential отдельно от обычных проекций.
   Provisioning выполняется durable шагами, без transaction через внешний HTTP:
   restricted access → webhook/menu setup → readback → проверенный вход/ответ.
7. Для приёмки доступ разрешён только owner/tester preview. Сервер проверяет
   exact user/club и для preview; Telegram restriction — дополнительный барьер.
   Обычный гость до отдельной публикации получает нейтральное «Клуб пока недоступен»;
   private profile не выдаётся. Booking, рассылки и публикация не включаются.
8. «Подключён» означает завершённый provisioning и проверенный правильный
   inbound/outbound/initData path. Наличие token или `setWebhook ok` недостаточно.

### Предлагаемые API, persistence и точки изменения

Предлагаемая UI-проекция: `not_connected` / `pending_confirmation` / `connected` /
`attention` → «Не подключён» / «Ожидает подтверждения» / «Подключён» /
«Требует внимания». Pending/provisioning остаётся неприсоединённым состоянием;
revoke хранится в истории и отображается как attention с причиной, без ложного success.
Названия ниже — контракт к согласованию, не существующие endpoints/schema.

| API под platform authentication | Назначение |
| --- | --- |
| `POST /api/client/clubs/{clubId}/bot-connection-intents` | Создать/повторить собственный intent |
| `GET /api/client/clubs/{clubId}/bot-connection` | Sanitized status, candidates, identity, timestamps, availableActions |
| `POST /api/client/clubs/{clubId}/bot-connection-intents/{intentId}/confirm` | Подтвердить серверный candidate для неизменного scope |
| `POST /api/client/clubs/{clubId}/bot-connection-intents/{intentId}/cancel` | Отменить pending intent |
| `POST /api/client/clubs/{clubId}/bot-connection/disconnect` | Закрыть authority без удаления истории |

Mutations используют existing `ApiError`, fresh identity, Idempotency-Key,
expectedVersion; ответы `no-store`, с прежними non-enumeration правилами. Read
требует exact club.read; candidates/management actions — ещё bot.manage и creator
match. Rotate/recover — отдельные подтверждаемые команды после принятия lifecycle.
Никаких tokens/webhook secrets в этих API.

Минимальная additive data model без назначения migration number:

- Intents и observed candidates: scope/creator/manager, expiry/version, command
  replay, trusted event provenance и audit; orphan events не создают connection.
- Connections: stable ID, unique Telegram bot ID, manager ID, один client/club
  composite FK, purpose, creator, owner evidence, state/version, security generation,
  credential reference, health/reconcile time, revoke reason. Unique active guest
  connection на клуб для первого этапа; cross-client reassignment запрещён.
- Credential record/reference изолирован от DTO; encrypted bytes, key version,
  nonce/tag и AEAD additional data с connection/client/club IDs при DB encryption.
- Inbox/dedup/queue: `(telegram_bot_id, update_id)` вместо global update_id;
  сохраняется при ротации. Legacy rows явно принадлежат platform bot; не стирать
  их или обнулять dedup при migration. Durable enqueue до ACK, retry после restart.
- Новые outbound intents фиксируют connection/client/club/user/action, generation,
  payload identity, audit/idempotency. Retry не выбирает бота по текущему UI state.

Адресные source anchors на base (app paths относительно
`app-bot/src/main/kotlin/com/example/bot/`):

| Surface | Текущее evidence → необходимая дельта |
| --- | --- |
| `di/OpsNotificationsModule.kt:13–26`, `telegram/TelegramClient.kt:24–66` | Singleton platform bot/client, cached bot ID → отдельный registry/resolver и scoped outbound для branded purpose; старый sender не перенаправлять. |
| `routes/TelegramWebhookRoutes.kt:39–79`, `telegram/webhook/TelegramWebhookIngressWorker.kt:18–60` | Один secret; worker несёт только Update → trusted connection envelope до parsing/queue/dispatch. |
| `core-data/src/main/kotlin/com/example/bot/data/security/webhook/WebhookSecurityTables.kt:23–53` | Global dedup/queue update_id → composite bot key и legacy-preserving migration. |
| `telegram/TelegramCallbackRouter.kt:11–39`, `polling/PollingMain.kt:12–39` | Нет managed handler; polling allowlist не включает managed_bot → отдельный manager router, явные allowed_updates; не включать branded updates в legacy handlers. |
| `plugins/ClientContextAuth.kt:21–69`, `routes/ClientContextRoutes.kt:25–53` | TrustedBotConnection и fresh HMAC есть, production adapter один → verified registry lookup и отдельная branded guest audience. |
| `core-data/src/main/kotlin/com/example/bot/data/client/JdbcClientContextRepository.kt` | Fresh membership/mapping/exact grants → reuse predicates, новый grant без legacy fallback. |
| `Application.kt`, `docker-compose.yml`, `Caddyfile` | Single platform credential/ingress → будущие scoped routes и secret injection; текущая задача deployment/config не меняет. |
| `miniapp/src/modules/client/ClubProfile.tsx` | Сейчас честная статическая надпись → будущая серверная status projection и только реальные действия. |

Webhook route может иметь opaque selector `/telegram/connections/{key}/webhook`.
Selector выбирает server record, **не авторизует**. Separate constant-time secret
check, active state/generation, bounded parser и trusted destination bot ID обязательны;
body Update не является доказательством адресата. Неверный secret/body bot claim
не попадает в чужой inbox. Manager events принимаются только manager ingress.

Branded Mini App/API получает trusted connection из отдельного server route;
проверяет initData HMAC нужным token, freshness и разрешённую guest/preview audience.
Нет перебора токенов и fallback на Night Concierge. Клиентский URL/ID — selector,
не authority. Все profile/files/cache/queue keys учитывают connection/client/club.

### Credentials, revoke, rotation и пределы доказательства

Предпочтение — trusted secret manager, **если его наличие/доступ подтверждены**;
repository этого не доказал. Альтернатива для одного сервера — envelope encryption,
root key вне БД/Git/образа, separate runtime access, key version и согласованные
backup/recovery. Выбор store/key custody существенный и пока не принят.
Manager token, branded token и webhook secret независимы. Token нельзя показывать
Mini App, помещать в bundle, пользовательские/webhook URLs, логи, errors/traces,
audit payload, ordinary API или Git. Стандартный Bot API требует token в
**server-to-Telegram HTTPS path**; это протокольное исключение явно отделяется от
запрета URL-утечек. Полный запрет token даже в этом transport path несовместим с
обычным Bot API. HTTP logging/tracing обязаны редактировать path до сериализации.

- Любой `managed_bot` для действующего connection сначала ставит `attention` и
  увеличивает generation. Нельзя автоматически назвать событие безопасной ротацией
  или передать клуб новому owner. Duplicate event не должен повторно менять state.
- Local revoke/disconnect атомарно запрещает новые ingress/auth/send admissions,
  инвалидирует caches/sessions и старые queued intents; remote cleanup — после
  локального закрытия. История и private club сохраняются. Уже принятый Telegram
  запрос нельзя отозвать задним числом; эту границу фиксируют audit/tests.
- Каждый send повторяет fresh connection/client/club/user/action/generation checks
  под per-connection admission serialization между всеми workers/processes с
  durable generation/fence; одного JVM mutex недостаточно. Нельзя использовать default platform
  sender при ошибке lookup или слать queued work после revoke. HTTP timeout не
  доказывает отсутствие доставки; exactly-once Telegram send не обещается.
- Rotation: pause/fence → trusted replacement → getMe identity → protected store
  новой версии → новый webhook secret/setup/readback → проверенный preview → явный
  resume. Lost response после replacement требует read/reconciliation, не слепого
  повторного destructive replace. Old secret/initData/generation не принимаются.
- Restart продолжает durable шаги; наличие credential не активирует connection.
  Management outage, auth error, неизвестная owner continuity или потерянная история
  переводят в attention, без auto-transfer/recovery по одному getMe.

**UNKNOWN до отдельно разрешённой pilot acceptance:** точная owner/revoke semantics
и completeness management notifications, особенно после длительного offline.
Документированный Bot API getter current owner в проверенных разделах не найден;
token access/getMe не доказывают owner. Нельзя обещать мгновенное обнаружение любого
внешнего изменения. Требуется согласовать freshness deadline/watchdog, проверенный
reconciliation и ручное восстановление при недоказуемой continuity. Отсутствие
решения блокирует live readiness, но не synthetic tests предложенного контракта.

### Проверки и readiness первого подключения

| Набор | Обязательный результат будущей реализации |
| --- | --- |
| Client A/B | Один creator в двух клиентах, одинаковые имена, forged client/club/intent/candidate IDs, отсутствие grant, revoke membership между initiate/confirm, повторы/гонки: нет чужого binding/response/grant. |
| Ingress | Secret A + route B, manager event на guest route, forged user/bot, malformed/oversize JSON, orphan/late event отвергнуты. Одинаковые update_id у разных ботов независимы; duplicate одного бота идемпотентен. |
| Restart/faults | Crash до/после enqueue, token retrieval, webhook setup и DB step; lost ACK, stale worker claim, concurrent confirm не дают двойной связи или ложного connected. |
| Revoke/rotation | Event во время send, queued retry после revoke, old secret/initData, owner change, management outage и lost replacement response закрываются безопасно, история остаётся. |
| Isolation/redaction | Synthetic Telegram фиксирует правильного отправителя; нет platform fallback, чужих файлов/cache/PII; canaries token/initData отсутствуют в API/UI/log/error/trace. |
| Gates | Ktor test host, Postgres integration/concurrency/migration, SDK/adapter parser fixtures, legacy Night Concierge regression, mobile browser; реальный Telegram iPhone acceptance только отдельным разрешением. |

Первый реально работающий бот готов только после принятия решений ниже, local
gates, проверенной identity/capability manager, creator-owned bot ID, durable recovery,
isolated inbound/outbound/initData и проверенного revoke. Owner/tester должен реально
открыть branded entry, получить ответ **от этого бота** и private preview только своего
клуба. Публичная доступность/booking/рассылки остаются отдельными разрешениями.

### Решения владельца перед реализацией

1. Отдельный manager либо расширение authority Night Concierge.
2. Exact bot.manage grant: кто выдаёт, кому, на какой клуб; без автоматического
   наследования из profile.write/GLOBAL и без молчаливого backfill.
3. Узкий server adapter либо pinned SDK upgrade с compatibility gate.
4. Secret store, custody/backup/root key, transport-path исключение и redaction.
5. Двухэтапное подтверждение, TTL 30 минут, один intent на creator/manager,
   preview audience, explicit resume/recovery и запрет автоматической передачи owner.
6. Owner continuity/reconciliation, допустимое окно обнаружения внешних изменений
   и fail-closed freshness policy; pilot обязан проверить недокументированные границы.

Один следующий шаг: принять этот ограниченный контракт для **локальной** реализации
с synthetic Telegram и disposable PostgreSQL. Provisioning реального manager/бота,
credentials, webhook и любой Stage/prod шаг требуют отдельного разрешения.
