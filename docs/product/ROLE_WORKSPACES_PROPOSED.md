# Восемь ролей: принятая продуктовая модель и проект технического перехода

<a id="role-model-20261110"></a>
## Текущая редакция, 2026-11-10

Техническое уточнение: пять направлений [assignment contract §0](ROLE_ASSIGNMENT_CONTRACT_PROPOSED.md)
приняты только для локальной inactive foundation. [Implementation evidence](../ops/role-assignment-foundation.md)
не активирует матрицу ниже: proposed permissions/operational APIs, новые staff
назначения и cutover требуют своих gates. Продуктовые восемь ролей не меняются.

**ACCEPTED — прямые решения владельца продукта:** восемь ролей, одна роль
Владельца бизнеса, необязательный Управляющий сети, иерархия назначений,
делегирование отдельных разделов Менеджеру, строгая финансовая изоляция,
создание Промоутером своих броней в назначенных клубах и отделение attribution
от финансовых условий. Canonical accepted contract —
[PRODUCT_SPEC §29.18](PRODUCT_SPEC.md#business-role-model-20261110);
[decision overlay](OPEN_DECISIONS.md#role-model-20261110-decision).

**PROPOSED:** технические permission codes/пакеты, schema, grant provenance,
конкретные DTO/экраны, admission/revoke/исправление attribution и аварийное
восстановление. Принятие продукта не выдаёт grants и не доказывает runtime.
`CONCEPT_SOURCE.md` immutable; технические ENTRY_MANAGER/MANAGER/CLUB_ADMIN и
legacy OWNER/GLOBAL не переименовываются. Предыдущий девятиролевой проект
сохранён ниже как датированная история; он больше не актуальный target catalogue.

| Текущая роль | Scope и принятая ответственность |
|---|---|
| Владелец бизнеса | Один и несколько своих клубов в существующем client; показатели, готовность, посещения/результаты, закрытые финансовые итоги; назначает/отзывает Управляющих сети и клубов |
| Управляющий сети | Необязателен; просмотр/отчётность и контроль только явно назначенных клубов; назначает их Управляющих клубами, не другого Управляющего сети |
| Управляющий клуба | Конфигурация конкретного клуба; залы/столы/ночи/календарь/booking rules/разрешённые настройки; назначает Менеджеров, Менеджеров входа, Финансовых менеджеров, Промоутеров; делегирует разделы Менеджеру |
| Менеджер | Столы/брони/посадка/зал в назначенном клубе; отдельное делегирование профиля, залов, ночей и правил не даёт staff/ownership/смену |
| Менеджер входа | Назначенный club/night, QR/списки/поиск/приход/отказ; прежние Night Pass/outcome decisions сохраняются |
| Финансовый менеджер | Финансовые данные явно назначенного club/shift и отдельные permissions; не сеть по умолчанию |
| Промоутер | Явные назначения в нескольких клубах той же сети + booking permission; создание своих броней и допустимые статусы; без чужих броней/списков/входа/посадки/денег/staff |
| Гость | Разрешённая витрина и собственные объекты; canonical guest enablement отдельно |

Platform owner — отдельная Telegram authority уровня платформы, вне этих восьми.
DJ/бармен/официант и другие профессии — будущие карточки персонала/артистов и
shift assignments, не обязательные RBAC-роли этого этапа. Исторические музыкальные
требования/возможности сохраняются; upload authority остаётся отдельным решением.

### Один клуб → несколько клубов

При одном клубе отдельный сетевой кабинет не обязателен. Второй клуб добавляется
в тот же client без переноса данных. Он не расширяет membership, staff scopes,
делегирования, финансовые grants или legacy GLOBAL. Принадлежность клуба бизнесу
должна иметь проверяемую server provenance; membership/application.manage и
immutable creator старого onboarding не объявляются автоматически новой business
ownership. Владелец видит свои клубы по принятому target-контракту; техническое
установление нужного read допуска при создании клуба — отдельная атомарная
авторизованная операция, не массовый inheritance job. Другие действующие права
остаются прежними. Устаревшие owner grants сохраняются как compatibility.

### Точная матрица для проектирования

ВБ/УС/УК/М/Вх/Ф/П/Г соответствуют восьми строкам выше.
`A` = ACCEPTED продуктовая возможность; `P` = PROPOSED подробность; `AD` =
ACCEPTED возможность только при **отдельном явном делегировании**; `—` = не
включено в роль. Для всех ячеек техническая реализация permission остаётся
PROPOSED, grants не выдаются. Общего наследования старших ролей нет.
Для совмещения функций нужны отдельные действующие назначения и capabilities.

<!-- ROLE_MATRIX_CURRENT -->
| Действие / минимальный набор данных | ВБ | УС | УК | М | Вх | Ф | П | Г |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| Публичная разрешённая витрина | P | P | P | P | P | P | P | P |
| Готовность, показатели, посещения и результаты разрешённых клубов | A | A | A | — | — | — | — | — |
| Межклубное сравнение разрешённого набора | A | A | — | — | — | — | — | — |
| Итоги закрытых смен, финансовые отчёты разрешённых clubs/shifts | A | A | P | — | — | A | — | — |
| Scoped ledger/deposits/operations; М только минимальная информация стола отдельным правом | P | P | P | AD | — | A | — | — |
| Столы/брони/минимум гостя текущей ночи | — | — | P | A | — | — | — | — |
| Вход: списки/поиск/минимум гостя | — | — | — | — | A | — | — | — |
| Свои promoter bookings и допустимые статусы | — | — | — | — | — | — | A | — |
| Собственные records/subscriptions без чужих private данных | P | P | P | P | P | P | P | P |
| Read конфигурации делегированного раздела | — | — | A | AD | — | — | — | — |
| Write профиля | — | — | A | AD | — | — | — | — |
| Write залов/столов/схемы | — | — | A | AD | — | — | — | — |
| Write календаря/ночей; отдельно materialize | — | — | A | AD | — | — | — | — |
| Write booking rules | — | — | A | AD | — | — | — | — |
| Read/write разрешённых финансовых шаблонов и module settings | — | — | A | — | — | — | — | — |
| Выполнить подготовку, submit результата | — | — | A | — | — | — | — | — |
| Назначить/отозвать Управляющего сети | A | — | — | — | — | — | — | — |
| Назначить Управляющего клуба | A | A | — | — | — | — | — | — |
| Отозвать назначение Управляющего клуба | A | P | — | — | — | — | — | — |
| Назначить Менеджера/вход/финансиста/промоутера | — | — | A | — | — | — | — | — |
| Делегировать отдельный раздел конфигурации Менеджеру | — | — | A | — | — | — | — | — |
| Отзыв одного staff assignment / full club disable | — | — | P | — | — | — | — | — |
| Минимальный scoped roster для разрешённых staff действий | A | A | A | — | — | — | — | — |
| Собственный guest HOLD/confirm/cancel | P | P | P | P | P | P | P | P |
| Работа с бронями / продление arrival retention | — | — | P | P | — | — | — | — |
| Посадка по брони или walk-in без создания прихода | — | — | P | A | — | — | — | — |
| Free/no-show/stop-sales/Undo с отдельными controls | — | — | P | P | — | — | — | — |
| Проверка pass/QR, arrival/denial | — | — | — | — | A | — | — | — |
| Собственные guest lists/invitations/outcomes | — | — | — | — | — | — | P | — |
| Создать promoter booking с подтверждением/idempotency/audit | — | — | — | — | — | — | A | — |
| Финансовый ввод people/bracelets/revenue | — | — | — | — | — | P | — | — |
| Отдельные table deposit/top-up/security operations | — | — | P | P | — | P | — | — |
| Finance-only close после сверки/freeze | — | — | — | — | — | P | — | — |
| Прочитать attribution связанного booking/table при посадке | — | — | P | P | — | — | — | — |
| Запросить исправление attribution с причиной | — | — | P | P | — | — | — | — |
| Подтвердить исправление attribution отдельным правом | — | — | P | — | — | — | — | — |
| Ограниченный аудит своих разрешённых действий | P | P | P | P | P | P | P | — |
| Аварийно восстановить управление по отдельному контракту | P | — | — | — | — | — | — | — |
| Ownership/platform/GLOBAL, чужой client/club, refund/correction без super contract | — | — | — | — | — | — | — | — |
<!-- /ROLE_MATRIX_CURRENT -->

AD не добавляется из роли: scope, раздел, операция, issuer, validity/revoke и
supporting read должны быть отдельной проверяемой delegation. Для финансового
чтения AD означает отдельный минимальный денежный read permission, не delegation
конфигурации. Ни grant на профиль/зал, ни роль в клубе не дают financial reports.
P также не означает включённую по умолчанию capability: future operation contract
и явное назначение обязательны. Существующая read/team.manage authority creator
остаётся current-only; coarse team.manage нельзя выдать новым управляющим вместо
принятой узкой иерархии назначений.

### Серверная финансовая граница — ACCEPTED

Проверять одновременно signed actor, активные client/membership, server mapping,
конкретное assignment и **capability на объект в том же club/shift**. Это касается
reports/revenue/closed totals/deposits/operations/aggregates/exports/analytics и
связанных private данных. Все API/DTO/download/cache paths обязаны сохранять scope.
Один client не разрешает соседний клуб. Owner — только свои; УС — назначенные;
УК — явно разрешённые; М — необходимый минимум отдельным permission; Ф — назначенный
club/shift. Promoter booking permission не даёт финансовую отчётность.
Closed/final и live operational summaries различаются; формулы/детализация DTO P.

### Промоутер бронирует в назначенных клубах — ACCEPTED

В сети из пяти клубов назначение A/B/C и permission booking.create позволяют
создание/просмотр своих допустимых booking statuses только в A/B/C, не D/E.
Техническое имя `booking.create` здесь — report label, не production grant.
Каждый write/replay повторно проверяет signed Telegram identity, active promoter
assignment, текущие membership/authority, exact client/club, разрешённую canonical
night, rules, table availability, HOLD/booking invariants, подтверждение и
transactional idempotency/audit. Идентичность guest и creator/promoter различается:
промоутер не должен подменять guest ownership поля существующего guest endpoint.
Revoke блокирует дальнейший доступ/действия, не удаляя прежние booking/attribution.

DEC-006 уточнён прямым решением по assigned clubs; DEC-008 persistent night,
DEC-009 NONE и DEC-018 DB booking foundation сохраняются. GuestList DEC-010,
Night Pass/entrance и online provider остаются отдельными границами. Новое promoter
booking не открывает чужие брони/гостей/списки, QR, seat, money, reports или staff.
Ни current A3, ни protected prototype не объявляются готовым client API. Cutover
не выполняется; V070/V072 identity и V073 writer/admission fences сохраняются.

### Минимальный attribution contract — PROPOSED

Принято направление: источник **CLUB** либо **конкретный PROMOTER**, независимо
от deposit/bill/club-funded условий. `source=CLUB` не означает бесплатно;
`source=PROMOTER` не означает paid-by-promoter. Финансовый mode/payment ledger —
отдельная ось; один enum для источника и денег запрещён принятым решением.
Детали ниже требуют отдельного принятия, новые schema/migration numbers не задаются.

1. **На booking create:** immutable origin (actor, канал/доказанный reference,
   время) и initial attribution. Server-derived client/club/night + canonical
   booking UUID; source_kind CLUB/PROMOTER/UNKNOWN для недоказанного legacy.
   PROMOTER включает user_id **и устойчивую ссылку на exact club assignment**,
   его version и минимальный historical snapshot; одного user ID недостаточно.
   При создании самим промоутером assignment берётся из текущей authority, не body.
   Без доказанного origin старые записи не классифицировать по догадке.
2. **На seating:** менеджер видит source связанной брони; source не меняется от
   выбора стола, QR/NO_QR, seat mode или оплаты. Walk-in — явный source без фиктивной
   брони. Для нового выбора server selector показывает только подходящие active
   promoter assignments данного client/club; write заново проверяет eligibility.
   Source selection не создаёт/восстанавливает назначение и не раскрывает чужие
   брони промоутеру. Legacy selector с disabled/global rows не подключать.
3. **Уточнение/исправление:** до фиксации — подтверждённый выбор с теми же guards;
   после commit — отдельная append-only correction, не overwrite origin. Предложение:
   Менеджер запрашивает с причиной, УК с отдельной capability подтверждает;
   self-approval/автоматическое исправление промоутером выключены до решения.
   expected_version, actor, before/after, reason, server time, immutable command
   receipt и transactional audit/history; current authority при replay.
   UI/approver limits/после close/сроки исправления остаются PROPOSED.
4. **Статистика:** booked/arrived/seated — разные факты с canonical identities.
   Unique attribution revision на subject; retry/booking→session link/table move
   не создают второй результат. Отмена/no-show сохраняют source/history, но не
   считаются arrival/seated; изменение source после результата пересчитывает
   единственную projection с сохранёнными correction events. Не начислять visit
   от seating и не считать deposit подтверждением прихода. Вознаграждение P.
5. **Lifecycle:** move table в той же booking сохраняет source/subject; перенос
   club/night требует отдельного разрешённого booking lifecycle, не смены ID
   в body. Revoke промоутера прекращает доступ, но историческая запись остаётся
   с прежним assignment reference. Изменение состава персонала не переписывает
   origin/историю. Archived/unknown promoter можно показать в historical source,
   но нельзя выбрать для нового назначения. Конфликт bookings.promoter_user_id
   и promo_attribution — manual conflict, без скрытого приоритета.

Связь с `GL-006` и `TOPS-002` сохраняется. Attribution corrections не меняют
деньги, финансовую смену, check-in/visits, guest identity или legacy grants.
DB booking UUID, A3 BIGINT и table session identity не смешиваются.

### Current evidence и безопасный переход

**AS_BUILT на 5550b944:** исторические client packages/creator-only team и
preparation PR #567, независимые legacy roles/permissions, revoke fences и
PrivateClubContainment сохранены. Current booking.promoter_user_id — nullable
user ID, не assignment/source/history; protected confirm пишет null. Отдельная
promo_attribution не атомарный canonical attribution lifecycle; table session
не хранит booking/source/financial mode. Минимальный депозит не означает оплату.
[Свежий inventory](../ops/role-shadow-inventory.md) и generated report дают locators.

**PROPOSED migration unit:** role assignment с независимым ID/scope/version и
происхождением; capability delegation с issuer/target assignment/permission/scope;
отзыв одного assignment/delegation; full club disable всех перечисленных club
assignments/capabilities. Отзыв роли не удаляет независимые роли/delegations и
не активирует tombstones. Полный disable — отдельное подтверждение с полным diff.
Старый `replace` всё ещё выключает все grants tuple и не пригоден для coexistence.
Audit/receipt/version/lock redesign всех writers требуется до реального cutover.

### Рабочие места и первый следующий этап

ВБ: обзор бизнеса, закрытые итоги/посещения/готовность, назначения управленцев;
при одном клубе без обязательного network shell. УС: аналогичный ограниченный
обзор назначенных клубов и их club-manager appointments. УК: настройки/подготовка,
staff и делегирования. М: bookings/tables/seating с attribution и только явно
делегированные разделы. Вх: scanner/search/lists/journal; Ф: scoped shift/ledger;
П: свои bookings/lists/outcomes; Г: разрешённая витрина/свои объекты.
Непринятые модули не появляются в active navigation. Новый кабинет не объявляется
AS_BUILT из-за новой матрицы или существования legacy endpoint.

Последовательность PROPOSED: validate product overlay/shadow deny cases → принять
узкий assignment/delegation/revoke + grant-writer coexistence contract → отдельная
local реализация typed authority без выдачи реальных grants → canonical promoter
booking adapter с attribution → самостоятельные operational cabinets по принятым
domain gates. Сейчас разрешены только docs/helper/fixtures/tests, без publication.

<details>
<summary>Исторический девятиролевой PROPOSED-проект 2026-11-09 — не текущий target</summary>

# Девять ролей: проект прав, кабинетов и безопасного перехода

Статус: **PROPOSED**, 2026-11-09. Это один проект решения для review, а не новая
спецификация принятого поведения, permission registry или разрешение на реализацию.
Спорные права ниже не приняты. Новые DEC/CLB номера не назначаются.

База анализа: `koteev-m/clubs_bot`, актуальный remote `main` на момент preflight —
`5550b944990d8c1c93c0a5012d31db209f93ea6d`, merge [PR #567](https://github.com/koteev-m/clubs_bot/pull/567).
Изолированный detached worktree: `/private/tmp/clubs-role-workspaces-proposed-20261109`.
`PROJECT_IDENTITY_OK` получен с exact expected HEAD; active Clubs Bot instructions,
active foreign instructions = 0. Исходный checkout на `e83f806…` и его ранее
изменённые `AGENTS.md`/`PROJECT_STATUS.md` сохранены. Это repository evidence:
stage/prod, реальные назначения, актуальное содержимое БД и rollout не проверялись.

## 1. Что уже принято и что предлагается

Authority: [PRODUCT_SPEC §29.17](PRODUCT_SPEC.md#owner-manager-model),
[decision addendum](OPEN_DECISIONS.md#owner-manager-model-decision),
[owner-manager-contract](../ops/owner-manager-contract.md),
[owner-team-contract](../ops/owner-team-contract.md),
[roadmap sequence](PRODUCT_ROADMAP.md#owner-manager-sequence).
Source requirements `RBAC-001–006`, `NET-001–003`, `UX-002/003`, `BKG`, `GL`,
`PASS`, `CHK`, `TOPS`, `DEP`, `FIN`, `ANL` задают трассировку, а не готовую выдачу grants.
Исходный [CONCEPT_SOURCE](CONCEPT_SOURCE.md) и статусы прежних решений не меняются.

| Роль | Принятая ответственность | PROPOSED точный scope для будущей выдачи |
|---|---|---|
| Владелец сети | Контроль своей сети/клубов; без platform authority | Одна явно определённая сеть внутри client и конечный список её клубов; право в каждом клубе проверяется отдельно |
| Владелец клуба | Результаты, персонал, важные решения; настройки по grants | Конкретная пара client/club с доказанной owner authority; несколько клубов — отдельные назначения |
| Управляющий сети | Реальные показатели назначенных клубов, подготовка, координация; без полных настроек | Несколько явно назначенных клубов одного client; добавление клуба в client не добавляет доступ |
| Управляющий клуба | Подготовка и текущее управление клубом | Один назначенный client/club; повторное назначение для другого клуба независимо |
| Менеджер | Столы и гости в зале, включая бывшие обязанности Host по столам | Один или несколько явно назначенных клубов; операции только в выбранной операционной ночи |
| Менеджер входа | QR, списки, поиск, приход и отказ | Один или несколько явно назначенных клубов; выбранная ночь и принадлежащие ей записи |
| Финансовый менеджер | Финансовая ответственность; денежный контракт ещё требуется | Назначенный client/club и его смены; несколько клубов — отдельные назначения |
| Промоутер | Промоутерские функции; cross-club scope не принят | Предлагается DEC-006/B: явные club assignments, свои списки/приглашения/результаты; никакого автоматического GLOBAL |
| Гость | Гостевой сценарий разрешённой витрины | Собственные данные + публичные данные разрешённого клуба/витрины одного client |

**Принято:** ровно девять названий; отдельного Host нет; обязанности Менеджера и
Менеджера входа разделены. **Не принято:** точные новые operation permissions,
network mapping/authority, promoter scope и финансовая модель.
`client` не объявляется автоматически сетью: состав сети и owner authority требуют
отдельного принятого mapping. Межклиентские назначения не объединяются в один scope.
Платформенный владелец остаётся отдельной служебной authority, не десятой бизнес-ролью.

Принятая сегодня выдача сотрудникам — только точные **технические пакеты**:

| Пакет | Existing permissions с префиксом `client.club.` |
|---|---|
| ENTRY_MANAGER | `read` |
| MANAGER | `read`, `profile.write`, `layout.write`, `calendar.write`, `booking_rules.write` |
| CLUB_ADMIN | Всё из MANAGER + `finance.read`, `finance.write`, `modules.read`, `modules.write` |

`finance.*` здесь — финансовые шаблоны/настройки, не деньги или смена.
Ни один пакет не содержит `team.manage`, QR/check-in, посадку, client management,
legacy/GLOBAL или platform authority. Управление командой сейчас требует immutable
creator и exact active read/team.manage; название «владелец» этого не заменяет.
Подготовка использует полный existing CLUB_ADMIN, отдельное назначение задачи и
прежний двухэтапный admission. Этот принятый контракт не расширяется таблицей ниже.

## 2. Конкретная целевая permission matrix — PROPOSED

ВС = Владелец сети; ВК = Владелец клуба; УС = Управляющий сети;
УК = Управляющий клуба; М = Менеджер; Вх = Менеджер входа;
Ф = Финансовый менеджер; П = Промоутер; Г = Гость.

Каждая `П` — **предлагаемое разрешение после отдельного принятия**, не действующий
grant. `—` — предлагаемый явный запрет для этой роли. Неназванное действие запрещено.
Нет иерархического наследования: владелец не становится кассиром/оператором входа.
Для совмещения функций требуется отдельное явное назначение на тот же exact scope.
Действия в строках — проект capability catalogue, не новые `permission_code` в БД.

| Действие / минимальный набор данных | ВС | ВК | УС | УК | М | Вх | Ф | П | Г |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| Публичная витрина разрешённого клуба/ночи, без private-настроек | П | П | П | П | П | П | П | П | П |
| Обзор подготовки/готовности назначенного клуба, без выдуманных результатов | П | П | П | П | — | — | — | — | — |
| Агрегаты ночи/результаты клуба, без гостевых контактов | П | П | П | П | — | — | П | — | — |
| Сравнение агрегатов только клубов своего разрешённого набора | П | — | П | — | — | — | — | — | — |
| Просмотр рабочих столов, статуса брони/посадки и минимальных данных гостя этой ночи | — | — | — | П | П | — | — | — | — |
| Поиск гостей/списков/проходов этой ночи: имя, источник, статус; без финансов и контактов | — | — | — | П | — | П | — | — | — |
| Свои списки, приглашения и attribution invited/arrived/seated | — | — | — | — | — | — | — | П | — |
| Собственные брони/проход/история/настройки подписки, без чужих данных | П | П | П | П | П | П | П | П | П |
| Read конфигурации: профиль, схема, календарь, правила бронирования | П | П | — | П | — | — | — | — | — |
| Write профиля | П | П | — | П | — | — | — | — | — |
| Write залов/зон/схемы/столов как конфигурации | П | П | — | П | — | — | — | — | — |
| Write расписания/особых дат; отдельно materialize ночи | П | П | — | П | — | — | — | — | — |
| Write правил бронирования, без включения provider payment | П | П | — | П | — | — | — | — | — |
| Read/write финансового шаблона и module settings в принятом allowlist | П | П | — | П | — | — | — | — | — |
| Самостоятельная подготовка; submit результата назначенным исполнителем | П | П | — | П | — | — | — | — | — |
| Назначить/снять ответственного; принять/вернуть подготовку | П¹ | П¹ | — | — | — | — | — | — | — |
| Просмотр сотрудников и точных пакетов/состояния доступа, без invitation secrets | П¹ | П¹ | П² | П² | — | — | — | — | — |
| Invite/confirm/change/revoke staff access в своём клубе | П¹ | П¹ | — | — | — | — | — | — | — |
| Создать/отменить собственную бронь; HOLD по canonical lifecycle | П | П | П | П | П | П | П | П | П |
| Продлить post-arrival удержание конкретной брони, без изменения guest HOLD TTL | — | — | — | П | П | — | — | — | — |
| Посадить по брони / walk-in, режим bill/club; pass link не создаёт приход | — | — | — | П | П | — | — | — | — |
| Освободить «гости ушли»; отдельный no-show только без подтверждённого прихода | — | — | — | П | П | — | — | — | — |
| Stop-sales выбранного стола до конца ночи / Undo | — | — | — | П | П | — | — | — | — |
| Проверить QR/Night Pass; найти по имени; записать arrival/denial | — | — | — | П | — | П | — | — | — |
| Создать/дополнить собственный guest list; выпустить персональное приглашение | — | — | — | — | — | — | — | П | — |
| Просмотр ledger/reconciliation выбранной смены, без посторонних гостевых контактов | П | П | П³ | П³ | — | — | П | — | — |
| Депозит при посадке / отдельная доплата / отдельная услуга охраны | — | — | — | П⁴ | П⁴ | — | П⁴ | — | — |
| Ввод people/bracelets/revenue в открытой финансовой смене | — | — | — | — | — | — | П⁴ | — | — |
| Закрыть смену после сверки и итогового подтверждения | — | — | — | — | — | — | П⁴ | — | — |
| Читать ограниченный аудит собственных операций / owner staff changes | П | П | — | П | П | П | П | П | — |
| Исправить закрытую смену / вернуть деньги / провести provider payment | — | — | — | — | — | — | — | — | — |
| Передать ownership / выдать network/GLOBAL / изменить platform access | — | — | — | — | — | — | — | — | — |
| Bulk export PII / чужие списки промоутеров / произвольная массовая рассылка | — | — | — | — | — | — | — | — | — |
| Менять собственные права / восстанавливать revoked grants через миграцию | — | — | — | — | — | — | — | — | — |

¹ До отдельного ownership/network решения — только существующий immutable creator
с fresh exact grants. Сетевой статус не обходит creator gate ни в одном клубе.
² Предлагается отдельный redacted roster: имя/рабочая роль/статус, без secrets,
персональных контактов и прав выдачи. Существующий owner-only team API не переиспользуется
для этого чтения. В новом target DTO это отдельная ещё не принятая capability.
³ Предлагается operational read в своём scope; агрегаты не становятся финальными
до close. Это не право редактировать/закрывать и не автоматический legacy finance GET.
⁴ Всё денежное блокируется до принятия DEC-012 и отдельного operation/ledger contract.
Предложение: менеджер/управляющий записывают фактические table payments только
с отдельной денежной capability; финансист — отдельную проводку по существующей
сессии, без права физической посадки. Роль сама по себе такую capability не выдаёт.

Данные о гостях нужны только активной операции, с проверкой объекта и ночи.
Нельзя выдавать полный `client.club.read` как замену ограниченному operational DTO.
Владельцы получают агрегаты; детализация гостевых контактов/общий поиск людей не
подразумеваются. Промоутер видит attribution своих записей, не финансовые суммы
всех гостей/других промоутеров. Исторические PII и срок хранения требуют отдельного
data contract перед включением соответствующего экрана.

### Критические действия и подтверждение

**Уже принято:** бронь/отмена/посадка/денежная операция/закрытие смены — явная
форма/кнопка (`RBAC-004`, SPEC §24); текущая смена staff package — причина,
полный пакет before/after и явное подтверждение по owner-team-contract.
Preparation return сохраняет причину; owner accept не публикует клуб.

**PROPOSED:** для новых выдач/отзыва — scope, получатель, точные grants, причина
и подтверждение; для free/no-show — отдельный просмотр последствий и причина
для no-show; для stop-sales/Undo — выбранные стол/ночь, срок, явное действие и audit.
Дополнительное подтверждение free/no-show и policy stop-sales/Undo пока не приняты
в generic critical-action register; этот проект их не закрывает.
Arrival/denial требуют явного действия оператора; автоматическое признание QR
за проход, исправление ошибочного outcome и обязательность denial reason —
часть ещё не принятого DEC-021 contract.

Закрытую смену нельзя исправить одной из девяти ролей по названию. Будущее super-right
не является десятой ролью и требует отдельного решения о выдающем, получателе,
пределах, причине, подтверждении, audit и correcting entry. Refund/payment provider,
publication, ownership transfer, rewards/contests/music, broadcasts, exports и
template cloning не добавляются скрыто к этой матрице; действуют их отдельные
решения и границы DEC-013/014/022/031/034/035.

## 3. Сопоставление с кодом и фактическим wiring

Все locators ниже относятся только к exact base этого анализа. «Подключено» означает
production composition в repository, не проверенный запуск в stage/prod.

| Evidence | Факт / расхождение |
|---|---|
| [V061 PostgreSQL](../../core-data/src/main/resources/db/migration/postgresql/V061__client_context.sql), строки 1–46; [JdbcClientContextRepository](../../core-data/src/main/kotlin/com/example/bot/data/client/JdbcClientContextRepository.kt), 54–68 | client/membership/mapping/exact grants отделены от legacy; membership не даёт все клубы. Mapping закрепляет один client за клубом |
| [ClubStaffAccess](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubStaffAccess.kt), 10–61,113–153; [V075](../../core-data/src/main/resources/db/migration/postgresql/V075__client_club_staff.sql) | Пакеты из §1, metadata не authority; team — creator-only. `replace` выключает старые grants и включает весь выбранный пакет: это explicit admission/change, НЕ безопасный migration helper |
| [OnboardingSql](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/OnboardingSql.kt), 219–249 | Fresh client lock, active membership/read/operation grant; exact user/client/club predicate |
| [StaffInvitationOperations](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/StaffInvitationOperations.kt), 91–131,179–263; [ClubTeamOperations](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubTeamOperations.kt), 76–132 | Claim не выдаёт grants; owner confirm проверяет весь пакет. Disable, versions, old-invitation fence; inactive staff не восстанавливается change_role |
| [ClientAccessOperations](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientAccessOperations.kt), 220–237,275–283 | Platform revoke/restore отделены; restore не восстанавливает staff club grants вместо нового admission |
| [CanonicalBookingCheckin](../../core-data/src/main/kotlin/com/example/bot/data/checkin/CanonicalBookingCheckin.kt), 76,129–164 | PRESENT_UNWIRED: нет route/DI; требует client read и exact legacy CLUB assignment. Новый staff invitation такого legacy назначения не создаёт; подключение по совпадению названия невозможно |
| [Application](../../app-bot/src/main/kotlin/com/example/bot/Application.kt), 209,388–456,527–536; [PrivateClubContainment](../../app-bot/src/main/kotlin/com/example/bot/plugins/PrivateClubContainment.kt), 25–70 | Legacy host/check-in/list/promoter/table/finance routes реально mounted. Mapped client clubs и descendants защищены от legacy API, включая GLOBAL. Для legacy unmapped clubs wiring сохранён |
| [GuestListRoutes](../../app-bot/src/main/kotlin/com/example/bot/routes/GuestListRoutes.kt), 64–71,218–219; [HostCheckinRoutes](../../app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt), 117–122,138–146,188–203 | Старая authorization system; `arriveByName` использует широкий legacy predicate, включая ENTRY_MANAGER. Не адаптер нового client permission |
| [AdminTableOpsRoutes](../../app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt), 142,792–829 | Legacy seat/free/deposit подключены; seat вызывает `tryCheckIn(TABLE_DEPOSIT)` и `markHasTable`. Это entrance-first gap, нельзя переносить в новый кабинет |
| [AdminFinanceShiftRoutes](../../app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceShiftRoutes.kt), 204–214,267–295 | Legacy admin/global close; GET `getOrCreateDraft` меняет состояние. Не finance-only auto-open и не безопасный read для сетевого обзора |
| [HostWaitlistRoutes](../../app-bot/src/main/kotlin/com/example/bot/routes/HostWaitlistRoutes.kt), 143–153; [ShiftChecklistService](../../app-bot/src/main/kotlin/com/example/bot/host/ShiftChecklistService.kt), 8–13 | Waitlist invitation возвращает disabled; checklist in-memory. Наличие вкладки не доказывает завершённую операционную функцию |
| [App](../../miniapp/src/app/App.tsx), 65–120; [CabinetEntry](../../miniapp/src/app/CabinetEntry.tsx), 15–55 | Client/owner/support/guest entry + старые URL modes admin/entry/promoter. Nine-role resolver отсутствует; URL — presentation, не authority |
| [ClientAccess](../../miniapp/src/modules/client/ClientAccess.tsx), 35–52; [ClubCabinet](../../miniapp/src/modules/client/ClubCabinet.tsx), 122–173; [ClubPreparation](../../miniapp/src/modules/client/ClubPreparation.tsx) | Подключены owner overview, разрешённые настройки, team, readiness и durable preparation. Это рабочая configuration foundation PR #567, не полный кабинет ночных операций |
| [EntryConsole](../../miniapp/src/modules/entry/pages/EntryConsole.tsx), 9–25; [entry API](../../miniapp/src/modules/entry/api/entry.api.ts), 74–115 | Legacy вход/сканер/waitlist/checklist с ручными club/event IDs и `/api/host/*`; не client-scoped кабинет Менеджера входа |
| [AdminShell](../../miniapp/src/modules/admin/pages/AdminShell.tsx), 13; [table API](../../miniapp/src/modules/admin/api/adminTableOps.api.ts), 25–30,69–118 | Общая admin surface смешивает tables/finance/promoters/analytics. Выдавать shell целиком новой роли нельзя |
| [PromoterShell](../../miniapp/src/modules/promoter/pages/PromoterShell.tsx), 10–18; [promoter API](../../miniapp/src/modules/promoter/api/promoter.api.ts), 167–238 | Lists/invitations/tables/creatives есть в legacy; это не принятие DEC-006 или target client grants |
| [GuestBookingPreview](../../miniapp/src/modules/client/GuestBookingPreview.tsx), 30–99; [AS_BUILT booking delta](AS_BUILT.md) §1 | Private preview/availability не публичный booking cutover; TABLE_MAPPING_REQUIRED/GUEST_BOOKING_DISABLED не фиктивное разрешение брони |

Прямая role-to-code crosswalk (совпадение функций не является migration mapping):

| Новая роль | Что подключено сейчас | Что есть только в legacy / чего нет |
|---|---|---|
| Владелец сети | Client context и exact club grants могут показать разрешённые клубы | `OWNER/GLOBAL_ADMIN` не tenant owner; verified network ownership, network grants и сравнительного кабинета нет |
| Владелец клуба | Immutable creator + exact grants: ClientAccess/ClubCabinet, team/preparation/settings через `/api/client/clubs/{id}/*` | Legacy OWNER/CLUB_ADMIN — отдельная authority; полный nightly results dashboard и денежные owner права не следуют из existing overview |
| Управляющий сети | Доказанного target role/router нет | HEAD_MANAGER/GLOBAL_ADMIN — широкие legacy роли; assigned-network cabinet и tenant-scoped metrics ещё нет |
| Управляющий клуба | Подтверждённый assignee + полный client CLUB_ADMIN: preparation/settings/submit | Это temporary configuration package; legacy MANAGER/CLUB_ADMIN не mapping. Client night operations ещё не подключены |
| Менеджер | Client MANAGER даёт настройки, а не работу зала | Legacy AdminTableOps/AdminShell имеет seat/free/deposit; client capability, отдельного safe floor cabinet и entrance-first посадки нет |
| Менеджер входа | Client ENTRY_MANAGER даёт только configuration read | EntryConsole + `/api/host/*` и guest-list arrival существуют для legacy; canonical client pass/check-in adapter не подключён |
| Финансовый менеджер | Финансовые шаблоны по client finance grants | Legacy смена, table deposits/allocations и admin close существуют; semantic operation ledger требует реализации. Financial-manager technical role, client operations/auto-open/accepted close contract отсутствуют |
| Промоутер | Client target-role admission отсутствует | Legacy PROMOTER/PromoterShell/list/invitation/quota APIs есть; новый client scope/authoring/единый pass ещё не определены/подключены |
| Гость | GuestShell, legacy booking/pass fragments, свои support flows; private owner preview отдельно | Canonical DB foundation принята, но публичный client booking cutover не открыт; unified user/night pass отсутствует |

Технические legacy `OWNER/GLOBAL_ADMIN/HEAD_MANAGER` нельзя сопоставить владельцу/
управляющему сети: их прежняя GLOBAL authority шире своего client. Legacy
`ENTRY_MANAGER/MANAGER/CLUB_ADMIN` и одноимённые client packages — разные источники.
`PROMOTER` ближе по функциям, но scope не принят; `GUEST` не доказывает ownership
данных запроса. Системной financial-manager роли/новых target role grants нет.

Исторические snapshots [AS_BUILT](AS_BUILT.md) и
[CONCEPT_CODE_GAP](CONCEPT_CODE_GAP.md) сохраняют свои даты. Свежие bounded deltas
по booking/table adapters и owner/team/preparation не означают готовность всех
legacy modules для нового client namespace. Нельзя помечать весь legacy dead code,
весь target AS_BUILT или использовать старую pending-формулировку roadmap как
отмену принятого DEC-008/009/018.

## 4. Безопасный переход — PROPOSED

Рекомендуется capability-based policy с отдельной role metadata, без новых строк
в legacy `user_roles` как побочного эффекта staff admission. Конкретная additive
schema/versioned permission registry утверждаются в implementation contract;
эта документация не резервирует migration number и не создаёт grants.

1. **Read-only инвентаризация.** Для обеих систем сохранить exact assignment IDs,
   user/client/club, scope, permission, active/inactive, version, invitation revoke
   fence, provenance. Runtime inventory только после отдельного разрешения;
   сейчас анализируется schema/code, а не реальные пользователи. Нет mapping или
   доказанного владельца — ручной review, без авто-adoption GLOBAL.
2. **Совместимость без переименования.** Только **client staff packages**
   ENTRY_MANAGER/MANAGER/CLUB_ADMIN показать как «Исторический пакет конфигурации»
   с точным составом. Одноимённые legacy `user_roles` показать отдельно: исходные
   assignment/permission IDs, фактические scope и операционная authority; такой
   доступ нельзя скрыть под configuration label. Existing assignments, grants,
   inactive rows и история остаются. Не урезать настройку текущему client MANAGER
   молча при выборе новой должности «Менеджер» и не добавлять ему посадку.
3. **Shadow evaluator без authority.** Для каждого exact tuple сформировать
   before/after diff: эквивалентные сохранённые действия, новые действия, предполагаемые
   удаления, несовместимые scopes. Автопроекция допускается только в отчёт и только
   для семантически одинаковых active configuration capabilities; operational
   права из названия старого пакета не выводятся. Effective доступ пока прежний.
4. **Поштучное принятие.** Владелец с текущей authority рассматривает полный пакет
   и scope. Новый operation grant — явно новая выдача после принятия контракта,
   не «безопасная миграция». Выдающий должен иметь право выдать весь пакет.
   Existing creator gate не обходится сетевой должностью. Inactive staff проходит
   новое invitation → signed claim → owner confirmation, не migration/restore.
5. **Точный cutover.** Единица — client/club/user/assignment с reviewed baseline
   и policy version. В одной транзакции locks/version checks, утверждённые изменения,
   audit и durable receipt. Изменился baseline или прошёл revoke — conflict/deny,
   без автоматического пересчёта/повтора с расширенными правами. Нельзя вызвать
   существующий `ClubStaffAccess.replace` для backfill: он активирует весь пакет.
   **Обязательный gate coexistence:** проверить все grant writers: confirm,
   change_role, disable, platform revoke/restore, replay и preparation admission.
   Сейчас `replace` также выключает все grants exact tuple: обычный change_role
   может молча стереть новые operation capabilities. До исправления этого
   конфликта смешанный cutover запрещён. Предлагается: старый writer при наличии
   target assignment возвращает conflict без записей; изменения проводятся через
   новый reviewed before/after workflow. Configuration-only change сохраняет
   независимые operational assignments; явно подтверждённый full staff disable
   отзывает все перечисленные client capabilities и target assignments этого
   клуба, сохраняя историю/другие scopes. Никакой writer не восстанавливает
   tombstones из старого package snapshot. Это будущая контрактная/code delta,
   требующая отдельного принятия и тестов, а не готовое свойство нынешнего кода.
6. **Новая operational boundary.** Каждый endpoint проверяет signed actor,
   server-derived mapping, active client/membership, exact capability и принадлежность
   night/event/list/entry/table/session/shift. Role metadata, URL mode, preparation
   task, cached context, membership и legacy GLOBAL не дают fallback. Запрещена
   комбинация роли из назначения A с club scope назначения B.
7. **Scoped revoke.** Новая роль/операционная capability отзывается независимо
   от сохранённого старого configuration package; membership и другие клубы
   сохраняются. Снятие preparation task отдельно. UI перечисляет, что осталось.
   Client revoke не выключает независимые legacy `user_roles`: нельзя обещать
   «весь доступ отозван». Для полного cutover/revoke нужны отдельно разрешённые
   exact legacy assignment IDs, без массовой очистки и потери истории.
8. **Rollback без воскрешения.** Отключить новый adapter/назначение и сохранить
   журнал/revoke fence; snapshot grants не восстанавливать. Любая compatibility
   path заново проверяет текущую authority, не оживляет tombstones. Завершённые
   операции не переисполняются. Ошибка/неизвестный результат остаётся unknown до
   read-only resolution с текущей authority, не создаёт новый grant.

Проверяемые инварианты: automatic `effective_after \ effective_before = ∅`;
у каждого old assignment сохраняется идентичность/ссылка на историю; inactive не
становится active без нового явного admission; revoke монотонен относительно
старых invitation/replay/rollback. Новые вручную подтверждённые capabilities
учитываются отдельно от no-expansion migration и не скрываются в её статистике.
Перед rollout zero-unreviewed-diff gate обязателен; одно имя роли не является diff.

## 5. Карта рабочих экранов — PROPOSED

Одна Mini App; server capabilities определяют доступные workspaces/действия.
Role switch меняет представление, не полномочия. Club picker содержит только
разрешённый набор; night picker использует canonical business date/TZ.
Новые пути ниже — информационная архитектура, не существующие URL/routes.

| Роль | Домашний экран и рабочие разделы | Основной законченный сценарий |
|---|---|---|
| Владелец сети | Сеть → клубы/сравнение → клуб: результаты, подготовка, команда; настройки отдельно | Сравнить реальные показатели разрешённых клубов, открыть проблему и доступный owner workflow; отсутствующий источник обозначить |
| Владелец клуба | Обзор клуба → подготовка/ответственный → команда → результаты; настройки/готовность отдельно | Назначить по accepted admission, проверить сохранённый результат, принять/вернуть; revoke отличать от unassign |
| Управляющий сети | Назначенные клубы → подготовка/доступные результаты → проблемы координации | Найти отставание; открыть разрешённую детализацию без кнопок настройки/team/денег; write workflow координации пока не предлагается |
| Управляющий клуба | Задачи подготовки → настройки → рабочая ночь → столы/вход → доступная сводка | Submit подготовки уже accepted; ночные действия только после отдельных capability contracts |
| Менеджер | Рабочая ночь → схема/список столов → бронь/сессия → посадка/освобождение → stop-sales | Посадить/освободить конкретный стол, увидеть сохранённый исход; денежные операции отдельным явным допуском |
| Менеджер входа | Рабочая ночь → сканер / поиск / списки → arrival/denial → журнал исходов | Один человек/ночь, единый pass, повторный scan без второго эффекта; отказ камеры переводит к поиску; offline reconciliation отдельно |
| Финансовый менеджер | Смена → люди/браслеты/выручка → ledger/сверка → предварительный итог → close → frozen report | Проверить расхождения, подтвердить закрытие; correction/refund не показывать |
| Промоутер | Назначенный клуб/ночь → мои списки → импорт/ошибки/dedup → приглашения → мои результаты | Создать свой список, выпустить разрешённые приглашения, видеть реальное attribution без чужих данных |
| Гость | Разрешённая витрина → ночь/стол → HOLD/подтверждение → мои брони → единый пропуск → свои ночи/вопросы | Одна durable бронь и свой pass; NONE не означает внесённый депозит; незапущенные модули не выводятся |

Общие состояния: проверка доступа, нет назначений/клубов/ночей, нет данных,
источник недоступен, version conflict, pending/unknown command, access revoked.
Отозванный private context очищается целиком; late responses не возвращают его.
Контакты, invitation secrets, initData, QR и raw Idempotency-Key не идут в логи,
telemetry или optional browser storage. Back/history/account/club switch защищают
черновики и pending intent. Мобильные 320/390 px, light/dark и Telegram Back
проверяются отдельно. Раздел попадает в active navigation после принятых решений,
полного wiring/RBAC/tests и отдельно разрешённого staging smoke (DEC-007/017).

## 6. Зависимости и вопросы для отдельного принятия

| Контур | Принято / reusable | Незакрытая граница |
|---|---|---|
| Каноническая ночь/бронь | DEC-008/C, DEC-018/B: persistent night и DB/PostgreSQL HOLD/confirm; DEC-009/C: NONE | V070 night/event и V072 plan/physical-table mapping; V073 admission изначально пуст, activation helper только TEST_ONLY. Перед guest write/cutover — доказать quiescence конфликтующих legacy writers и guards во всём fleet; preview не write. Night Pass/check-in сохраняют отдельные решения |
| Guest lists | GL-001/002/006: ввод, поиск, arrival и attribution target | DEC-010 names/links/dedup identity; DEC-020 внутренние/внешние invitations; DEC-006 scope промоутера |
| Night Pass | PASS-001–004: один user/night pass, один QR/context | DEC-011 subject/format/rotation/revoke/forward policy; cross-source identity; существующие QR codecs не объявляются единым pass |
| Check-in | CHK-001/002/007: once-per-night, dedup, entrance-first visits | DEC-021 outcomes/причина/коррекция; manual journal reconciliation; no seating→visit bypass; exact operation permissions |
| Столы/посадка | TOPS и принятый DEC-029 walk-in: без фиктивной брони/прихода; bill/club/deposit modes | Будущая permission matrix, DEC-012/021, ledger/shift prerequisite, no-show/free/stop-sales controls; DEP/DEC-031 allocations только после своего принятого каталога |
| Финансовые смены | FIN target: auto-open, finance-only close, сверка/freeze | DEC-012 и exact monetary contract; super-right correction отдельно; legacy GET/close не переносить |
| Network | Девять ролей и ограничение assigned clubs приняты | Verified network composition/owner authority, точные scopes/metrics и delegation; legacy GLOBAL не источник нового допуска |

Для следующего review предлагается принять **именно** матрицу §2 со scope §1,
причём финансовые строки, network authority, promoter scope и outcome contracts
остаются выключенными до отдельных решений. Требуют ответа: может ли УК выполнять
вход одновременно с залом; какие денежные capability выдаются М/УК/Ф; кто имеет
super-right; допустимы ли roster/ledger read для УС/УК; правила сохранения PII;
free/no-show/Undo confirmation и correction outcomes. Это вопросы, не принятые defaults.
За пределами matrix продолжают действовать DEC-013/014/022/031 и остальные
не закрытые этим проектом decisions. Наличие кода не является ответом.

## 7. Первый срез и последовательность завершённых реализаций — PROPOSED

**На указанном main ни один новый полный кабинет ночной staff-операционки не готов
к реализации без отдельных продуктовых решений.** Вход блокируют DEC-010/011/020/021;
финансы — DEC-012; полный зал — permission/lifecycle/ledger и entrance-first gates;
промоутера — DEC-006 и list/pass; сеть — отдельная network permission model.
Это product/contract blocker, не failure среды и не разрешение на обход legacy.

Рабочее место **подготовки клуба** уже accepted и реализовано в PR #567: настройки
→ submit → owner accept/return. Его можно использовать в existing authority, но
оно не доказывает полный кабинет Управляющего клуба и не требует повторной реализации.
Private Support Loop — другой уже принятый bounded workflow: attached legacy
MANAGER/CLUB_ADMIN CLUB permissions по DEC-025; он не наследуется новыми ролями.
Guest booking NONE — отдельный возможный guest outcome после своих wiring/cutover
gates, не обход блокеров staff-кабинетов и не закрытие Night Pass.

**Рекомендуемый первый новый функциональный кабинет — Менеджер входа**, после
отдельного принятия его точных permissions и DEC-010/011/020/021, а для создания
реальных списков — upstream DEC-006 и list-authoring authority. Это ограниченная
ответственность без настройки/денег/персонала; полнота означает scanner + поиск +
списки + arrival/denial + dedup/journal, а не один read-only список или names-only
демо. До принятия и wiring этих частей обещать полностью реализуемый кабинет нельзя.

| Порядок / зависимость | Небольшой законченный outcome | Проверяемый DoD |
|---|---|---|
| 1. До выдачи новых прав | Read-only inventory + shadow migration report на synthetic fixtures | Каждый exact assignment сохранён; additions/removals/inactive и обе authority systems видимы; ноль writes/grants; stale/ambiguous mapping явно blocked |
| 2. После принятия matrix/scope | Capability resolver/DTO для рабочих мест, сначала shadow | Exact tuple/active states; no role/URL/GLOBAL fallback; функциональность старых packages неизменна; late-response/revoke tests; нет automatic grants |
| 3. После explicit admission contract и grant-writer gate §4 | Один club/user перевод на явно подтверждённый target package в disposable среде | Before/after preview; reason/confirmation; атомарность/audit/receipt; no tombstone restore; старые назначения сохранены; прежний writer не стирает target grants; rollout реальных scopes отдельно разрешается |
| 4. Booking dependency | Canonical guest booking NONE как отдельный E2E outcome | V070/V072 ownership, one active HOLD, durable confirm/cancel/no-show policy; минимум депозита ≠ оплата; старые routes сохраняются до разрешённого cutover |
| 5. DEC-010/011/020 accepted; перед list authoring также DEC-006 и exact create/import authority | Canonical user/night pass + list/entry identity; затем свой list create/import outcome | Booking/list/invitation сходятся к одному субъекту и pass; однозначный dedup; scope/owner checks; один QR; отсутствие разрешений входа у автора списка. Пока DEC-006 не принят — только synthetic list fixtures, без promoter authoring |
| 6. Полный production list/pass outcome 5, DEC-021 + entrance permissions accepted | Полный кабинет входа: scan/search → accepted outcome → журнал | Один durable outcome/visit при повторных/разных source scans; denial policy; камера/fallback; fresh revoke; cross-client/club/night denial; seating не создаёт visit |
| 7. DEC-012 minimum lifecycle + table contract accepted | Auto-created night shift/semantic ledger foundation, затем Менеджер: столы/seat/free/stop-sales | Auto-open exactly once; table session без fictitious booking/check-in; режимы bill/club; race seat/free/stop-sales; существующая shift freeze boundary; депозитная capability пока выключена |
| 8. Monetary/allocations contract accepted | Отдельные deposit/top-up/security → финансовая сверка → finance-only close | Separate append-only effects, exact allocation totals; retry dedup; concurrent close/write сериализованы; frozen mutations denied; correction/refund отсутствуют |
| 9. DEC-006 + attribution foundation | Полный promoter кабинет поверх уже проверенного list/pass flow | Только свои данные назначенных клубов; attribution invited/arrived/seated фактическое; нет собственных check-in или финансовых итогов всех гостей |
| 10. Verified metrics + network contract | Сетевые owner/manager обзоры; дополнение club manager workflow | Только finite assigned set в одном client; отсутствие источника честно; read не создаёт смены; owner не получает чужие grants/денежные операции |

Каждый outcome заканчивается одним reviewable handoff; publication, migration на
реальной БД, staging smoke и deploy требуют своего разрешения. Порядок 4–10 —
dependency order, не календарное обещание и не принятие pending contracts.

## 8. Тестовые и security-критерии для будущей реализации

Существующая опора: [ClubStaffContract](../../core-data/src/test/kotlin/com/example/bot/data/client/onboarding/ClubStaffContract.kt),
особенно fixed packages/legacy unchanged, downgrade/revoke, fresh issuer authority,
expiry/concurrent confirm, audit rollback, platform restore и V075 tombstones;
[ClientStaffRoutesDataTest](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientStaffRoutesDataTest.kt),
[PrivateClubContainmentTest](../../app-bot/src/test/kotlin/com/example/bot/routes/PrivateClubContainmentTest.kt),
[ClientPreparationRoutesDataTest](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientPreparationRoutesDataTest.kt).
Это существующий test code, не новые выполненные в этой задаче прогоны.

Обязательная будущая verification matrix:

- **Migration:** clean upgrade и populated upgrade, H2/Postgres parity; exact старые
  IDs/assignments/history сохранены; inactive/fence/version не активированы;
  deterministic rerun без дублей; ambiguous/GLOBAL no-adoption; stale baseline
  conflict; audit/receipt failure rollback; restart/unknown; rollback после revoke
  не возвращает grants. Для explicit new issuance additions перечислены отдельно.
  Mixed-writer case: после нового operational assignment обычный configuration
  change не удаляет его; старый writer даёт conflict без writes, новый workflow
  сохраняет independent assignment. Full staff disable отзывает показанный
  полный набор только данного клуба; confirm/restore/replay не возвращают его.
- **Authorization:** allow/deny каждой строки §2 для всех девяти ролей, no implicit
  owner/manager inheritance; role-A + club-B не складываются; client A clubs A1/A2,
  client B B1; missing/mixed/inactive membership/mapping/grant; forged body/URL IDs;
  BIGINT IDs; client admission не открывает legacy admin/host/promoter.
- **Revocation/concurrency:** confirm/change/revoke/cutover races на двух настоящих
  PG соединениях; новая проверка после commit отказывает; old invitations и replay
  не воскрешают доступ; unaffected club/client остаются; UI очищает private state,
  late 200/403 и смена аккаунта не возвращают старые значения. Отдельный тест
  показывает, что client-only revoke оставляет независимый legacy assignment,
  пока отдельно не принят и выполнен его scoped cutover.
- **Production composition:** Ktor test host со всеми production guards/adapters,
  не только изолированный handler. Legacy API по mapped club/descendant возвращает
  отказ и для GLOBAL; private clients не открываются через scan/search/body IDs/
  cache/replay. В PrivateClubContainment отсутствие wiring в isolated tests нельзя
  принимать как доказательство полной защиты.
- **Domain:** canonical TZ/business date/DST, V070/V072 ownership, HOLD uniqueness;
  единый pass/cross-source dedup/replay; ARRIVED отдельно от SEATED; table-before-scan
  не создаёт visit/stamp; concurrent seat/free; financial auto-open/close/freeze и
  separate operations. Финансовый read не создаёт draft и не меняет state.
- **Delivery/security:** fail-closed stage/prod; свежая signed identity; стандартный
  `ApiError`, no-store/Vary; transactional audit без чувствительных payload;
  никакого initData в prod query-string, secrets/QR/raw keys в logs/storage;
  CancellationException rethrow. Мобильные journeys/Back/drafts/access loss,
  packaged assets и явно разрешённый staging smoke перед активной navigation.

Начинать с affected module/task tests; routing/security/payments — Ktor test host,
DB/locks/migrations — Postgres IT плюс H2 parity. Для изменённого Kotlin обязательны
affected ktlint/Detekt; полный JVM gate — по межмодульному scope/regression risk и
обязательным repository gates. Уже успешные unchanged checks не гонять повторно.

## 9. Результат этой задачи и один следующий шаг

**PROPOSED_DESIGN_READY_FOR_PRODUCT_REVIEW.** Только проектная документация:
матрица, scope, evidence, безопасный переход, экраны, dependencies и DoD.
Никакие спорные права не переведены в ACCEPTED; production RBAC/grants/БД/routes
не менялись; commit/push/PR/deploy/workflow/stage/prod действий нет.

Проверки документации: identity preflight, независимые bounded product/RBAC/frontend
reads, review итогового diff, whitespace и локальные ссылки. Фактические результаты
финальных проверок зафиксированы в [checkpoint](../ops/PROJECT_STATUS.md).
Runtime readiness, реальные назначения, миграция и новый функциональный код
не проверены и не заявляются. Gradle/static/IT для docs-only задачи не запускаются.

**Один следующий шаг для реализации:** отдельно разрешить маленький local этап
«read-only inventory + shadow migration report» (строка 1 последовательности)
на synthetic fixtures с DoD из §8, без новых grants и runtime доступа. Он даст
конкретный before/after для последующего принятия новых operation contracts;
неразрешённые строки matrix при этом остаются deny.

</details>
