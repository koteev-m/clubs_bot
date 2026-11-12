# RBAC assignments, delegations и revoke — технический контракт

Статус архитектуры: **ACCEPTED для ограниченной локальной foundation**, 2026-11-10.
Владелец прямо принял пять направлений §7: независимые sources/admission,
operation boundaries, terminal tombstones/epochs/explicit regrant, staged
compatibility и client-lock/CAS/idempotency/audit/response fencing.
Это не acceptance permission matrix и не разрешение активировать staff RBAC.

## 0. Локальная реализация и граница активации

**Implemented but inactive:** additive V077 H2/PostgreSQL; три таблицы §1;
[internal repository](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/JdbcRoleAssignmentFoundation.kt),
server-owned pinned policy catalog, virtual contributions и шесть lifecycle
operations. Нет DI binding, route, установленного production policy catalog,
ownership/identity/admission adapter или вызова evaluator из рабочей авторизации.
Default providers запрещают операции. Только disposable synthetic tests создают
новые admissions/sources. Существующие `user_roles`, grants и staff не импортируются.
Они не изменяются новым writer; нет ни выдачи новых production codes, ни backfill.

Фактические уточнения persistence/операций:

- Admission дополнительно связывается с `membership_version`: deactivate/restore
  membership с новой версией не возвращает старые sources. Epoch и CAS version
  независимы. Новый readmission очищает compatibility manifest и меняет epoch.
- UUID source IDs входят в immutable typed intent; внутренний caller обязан
  генерировать их сервером. `client_commands.result_id` хранит subject user ID,
  не усечённый UUID; discriminator `role_foundation` и отдельный connection
  namespace. Same-key replay проверяет hash/scope/current authority и возвращает
  только текущие states. Сохранённого grant payload нет.
  Отдельный `resolve` не выполняет command и не резервирует key при отсутствии
  receipt. Response содержит **actor** scope/membership/admission/authority ref,
  connection hash и **subject** revision; срок ограничен также actor authority.
- Mutable collections intent копируются до dispatcher/DB wait; policy sets
  неизменяемы. Изменение caller selector не меняет уже принятый intent/receipt.
- Role и delegation policies требуют существующие registry codes; отсутствующий
  code/hash/version даёт deny. Delegation требует живые exact issuer/recipient,
  execution + bounded read + delegate authority и expiry. Explain сохраняет
  source ID/kind/version, selector, epoch и policy version/hash.
- Owner proof не заменяет отдельную authority для full disable или отзыва
  delegation. Providers для этих операций default-deny. Владельца/платформу/
  себя нельзя назначить staff subject. Guest SELF остаётся вне этого evaluator.
- Admission confirmation — только trusted future TARGET protocol proof с signed
  claim/confirmation. Старые invitations не переиспользуются. Full disable
  требует authoritative watermark **всех**, включая unclaimed, target invitations;
  неизвестный watermark блокирует операцию. В данной foundation protocol adapter
  отсутствует, тесты поставляют synthetic provider; рабочие invitations неизменны.
- Full disable отключает все новые sources/dependencies этого exact club, bump
  recipient revisions и epoch. При active opaque legacy grants/roles — отказ
  до записи: inactive foundation не обещает отключить старую runtime authority.
  Для этого нужен отдельный совместимый cutover; partial disable запрещён.
- Старые writers отказывают под тем же client lock для TARGET, DISABLED либо
  любой новой source history, включая outgoing delegation. ACTIVE/PENDING empty
  SHADOW fence сам по себе прежний доступ не отключает. Исключение — локально
  реализованный source-aware platform adapter §0.1: он завершает dependency tail
  до legacy cleanup в той же транзакции. Остальные guards сохранены.
  Materialized SHADOW-запись для реальных сотрудников по-прежнему не разрешена:
  этот этап не принимает compatibility/identity/assignment/cutover gates.
  Сейчас только disposable fixtures; inventory не создаёт таких rows.
- Actor → client → sorted child locks, READ COMMITTED; проверки после wait,
  transactional source/audit/receipt и cooperative cancellation после lock и
  перед commit. Revoke линеаризуется commit. UI adapter не подключён: сервер
  возвращает revision/valid-until; реальное отбрасывание UI late responses пока
  future integration. Domain resource lookup/DTO/finance/booking guards остаются
  обязательными; `AssignmentView.permits` не является endpoint authorization.

**Pending writer cutover / future activation:** owner/Telegram/TARGET-invitation
providers; reviewed policy/code catalog и DTO; compatibility classification и
frozen-manifest projection; seeds/manual privileged
SQL fencing; per-tuple cutover/rollback и mounted API/UI integration. Ничего из
этого не включается наличием V077. All-writer/reader/seed gates §6 сохраняются.

Проверки, CURRENT/TARGET/DIFFERENCE/RISK, ограничения и команды:
[foundation evidence](../ops/role-assignment-foundation.md). Исторические
[38 reference cases](../ops/role-assignment-shadow-report.md) и
[46 inventory cases](../ops/role-shadow-synthetic-report.md) сохранены отдельно.

### 0.1. Platform membership revoke/restore adapter — implemented locally

AS_BUILT кандидата, не deployed behavior. Единственная интеграция — существующий
`ClientAccessOperations.change`, без нового route, миграции или projection.
Точный [контракт и evidence](../ops/role-platform-revoke-adapter.md).

- Configured platform authority → actor lock → client lock → current membership
  CAS/transition → sorted admissions/roles/delegations. Transaction-local marker
  привязан к session/client/user/version и потребляется один раз; это не общий
  bypass legacy guard.
- Revoke терминально отзывает все ACTIVE role sources пользователя и incoming/
  outgoing delegations данного client, включая dependency tail. Каждая его
  существующая admission становится DISABLED с epoch/version +1 и без старого
  compatibility manifest. У зависимых получателей меняется revision, независимые
  sources сохраняются. Другой client/platform identity не изменяются.
- Source/fence audit, старые staff/grants cleanup, membership CAS, legacy audit и
  durable receipt — одна транзакция. SQLState retry заново проверяет authority и
  state; stale CAS fail-closed. Cancellation перед commit откатывает всю операцию.
- Restore возвращает membership и только явно выбранные прежним legacy contract
  scopes. Он не создаёт IDs/admissions, не возвращает epoch, не активирует sources
  или invitations. Уже terminal state не меняется; обнаруженные dangling ACTIVE
  sources/admissions карантинируются. Flat club restore для controlled scope
  запрещён, иначе mounted legacy reader обошёл бы disabled admission. Старые
  staff/creator/whitelist/existing-grant ограничения действуют дополнительно.
- Нет V077 state у этого client/user — прежний legacy результат без source audit.
  Новая authority возможна только через будущий отдельный authorized re-admission
  и новые source IDs с explicit regrant acknowledgement. TARGET invitation protocol
  пока отсутствует; adapter не создаёт/не принимает его proofs и не заменяет
  требуемый authoritative watermark будущего admission provider.

### Исторический draft до прямого approval

Прежний статус: **PROPOSED / READY_FOR_APPROVAL**. Формулировки ниже о ещё не
выданном разрешении, отсутствии schema/SQL tests и пяти выборах для approval
описывают исторический этап до этой задачи. Текущий implementation boundary
определён §0 и evidence; они не превращают будущие routes/activation в AS_BUILT.

Authority продукта: [SPEC §29.18](PRODUCT_SPEC.md#business-role-model-20261110),
[decision addendum](OPEN_DECISIONS.md#role-model-20261110-decision),
[восьмиролевая matrix](ROLE_WORKSPACES_PROPOSED.md#role-model-20261110).
Матрица целиком здесь не повторяется. Восемь ролей и их обязанности ACCEPTED;
target permission codes/variants, операции и persistence ниже PROPOSED.
Base/main: `5550b944990d8c1c93c0a5012d31db209f93ea6d`, registered detached worktree
`/private/tmp/clubs-role-workspaces-proposed-20261109`, PROJECT_IDENTITY_OK.
Active Clubs Bot instructions, foreign=0. До правок сохранён baseline:
`/private/tmp/clb-role-assignment-contract-cxica0qf/`. Concept/history неизменны.

## 1. Объекты и минимальная persistence

Повторно использовать users, clients, client_memberships, client_clubs, каталог
`permissions`, `client_commands`, audit_log и текущий client lock. Не создавать
вторые membership/client/club, legacy role catalogue или отдельную audit очередь.
Предлагаются **три** additive сущности; SQL/Flyway номера сейчас не создаются.

| Объект | Поля и ограничения PROPOSED |
|---|---|
| `client_role_assignments` | Stable UUID source ID; exact client/club/user; один из шести staff role IDs; immutable policy version+hash; typed finite object selector; admission_epoch; ACTIVE/REVOKED; source version; issuer authority ref, admission/command/audit refs, timestamps/reason. Composite FK к membership/mapping; никакого GLOBAL/client wildcard. REVOKED terminal |
| `client_permission_delegations` | Stable UUID; exact client/club/user; recipient assignment FK; issuer CLUB_MANAGER assignment FK; конкретный permission code FK к existing registry; immutable delegation-policy version/hash, показывающая bounded supporting read; issuer/recipient epochs, expiry, ACTIVE/REVOKED, version, reason/audit/command refs. Все связи в том же client/club; не transitive |
| `client_club_access_state` | PK client/club/user, FK **users + client_clubs mapping**, не membership; admission PENDING/ACTIVE/DISABLED, epoch, CAS version, revoked-through invitation watermark и COMPAT/SHADOW/TARGET mode; frozen reviewed compatibility manifest/hash. PENDING может предшествовать membership, но не даёт authority. Full disable увеличивает epoch; старые references не совпадут после re-admission |

Role assignment — самостоятельное назначение, а не новое значение старого
`client_club_staff.role_code`. Несколько разных assignment IDs допустимы одному
пользователю в одном клубе. Промоутер A/B/C из пяти — три exact-club назначения;
УС с несколькими клубами — конечный набор таких же bindings. UI может группировать
их, но DB/resolver не превращает группу в GLOBAL. Второй клуб не добавляет bindings.

**Владелец бизнеса** — одна продуктовая роль из проверенного ownership provider;
это не седьмой staff assignment и не результат staff invite. **Гость** — SELF
provider signed identity/owned objects, без лишних staff rows. Platform owner —
отдельная configured Telegram authority, не contribution клиента.
Business owner provider обязан доказать client и finite owned-club set; application.manage,
membership, legacy OWNER/GLOBAL и прежний immutable creator не подменяют его.
Пока proof/provider не принят и не подключён, owner-target mutations fail-closed.
Existing creator-only API продолжает прежний COMPAT-контракт отдельно.

**Permission contribution** — вычисляемый provenance record, а не четвёртый
справочник/таблица: source kind/ID, client/club/user, permission, resource selector,
policy hash, source version и admission epoch. Для ROLE набор берётся из immutable
policy version; для DELEGATION — из одного разрешения и явно показанного bounded
supporting read. Другая policy version не применяется к старым assignments автоматически.
Удалённая/изменённая policy с неверным hash даёт отказ, не новый набор прав.

Для нескольких делегированных разделов создаются отдельные delegation IDs.
Write+необходимый section read — одна показанная policy bundle одного source;
revoke этого source снимает оба, если их не даёт другой законный source.
Нельзя скрыто выдать broad `client.club.read` ради profile/calendar delegation.
Требуются section-scoped reads/DTO; старый широкий GET нельзя подключить напрямую.

Selectors явные: `CLUB` либо, для финансов, `SHIFT_SET` с непустым конечным списком
или явно разрешённый `ALL_SHIFTS_OF_THIS_CLUB`. Пустой/невалидный список запрещает
всё. Union нескольких selectors не разрешает иные clubs/shifts. Read finance и
mutation finance — разные codes; report/deposit/export/analytics не взаимозаменяемы.

## 2. Effective permissions и tombstones

Для actor/resource в **одной** паре client/club общие identity/mapping/domain
guards применяются всегда. Client membership требуется ветви client management/staff,
но **не Guest**: SELF/public использует signed identity, owned/public объекты и
trusted club/audience context без создания/требования client_membership.
Шаги 2–4 ниже описывают только staff ROLE/DELEGATION
и reviewed compatibility contributions. ВБ и Guest SELF имеют отдельную ветвь:
verified ownership provider + finite own-club scope либо owned-object SELF,
без требования staff admission/assignment row и без staff union. Platform owner
проверяется своим независимым exact identity path. Пока owner provider не принят,
его target mutations запрещены; membership не создаёт его автоматически.

1. Fresh trusted identity и server parent/audience context. Для client management/
   staff — active/unarchived client и active membership. Для Guest — разрешённая
   public/owned-object видимость без client membership. URL/body/role name сами
   не authority.
2. Staff admission ACTIVE и source epochs равны текущему subject epoch; exact
   source owner/user, scope, version/policy hash, expiry и resource selector.
3. ROLE contributions только active assignments. DELEGATION дополнительно требует
   active issuer и recipient assignments/epochs, current issuer execution permission,
   supporting read и delegation authority; конфигурация не делегирует управление
   персоналом, деньги, ownership, platform или право новой delegation.
4. `Effective(p, resource) = есть хотя бы один допустимый source(p, resource)`.
   Источники сохраняются в ответе explain; одинаковые codes не теряют provenance.
   После этого выполняются domain guards: night/table/HOLD, own booking, shift
   freeze и прочее. Наличие permission не доказывает весь endpoint allow.

Пример: A1 и A2 дают calendar.write. Revoke A1 → permission остаётся от A2.
Revoke последнего source → исчезает. Revoke delegation не меняет role assignments.
Recipient/issuer revoke делает dependent delegation недействующей; новая роль
с новым ID не оживляет прежнюю delegation. Restore membership тоже её не оживляет.

**Tombstone source-scoped:** REVOKED ID никогда не становится ACTIVE. Inactive
`client_club_grants` остаётся inactive compatibility source. Это не универсальный
отрицательный grant, снимающий другое уже законное независимое назначение.
Новая явная выдача того же permission после tombstone допускается только новым
source ID: свежий admission при необходимости, точный before/after, явное
`regrant_ack` revoked permissions и current fence/version, полная issuer authority,
reason/confirmation/audit. Старые rows/IDs остаются revoked. Migration, policy
refresh, replay, rollback или истечение invitation не являются новой выдачей.

Full-disable/membership deny — отдельные общие fences, перекрывающие **все** staff
sources независимо от их количества. Входящие и исходящие delegations должны
оставаться отозванными/epoch-invalid после restore; не только временно inactive
из-за membership, иначе restore воскресит доступ.

## 3. Writer authority и операции

Имена codes/routes ниже — **PROPOSED**, не новые production permissions.

| Операция | Допуск и атомарный эффект |
|---|---|
| Assign role | `client.staff.assign.<target-role>` + accepted hierarchy в exact club. ВБ→УС/УК; УС→УК назначенного клуба; УК→М/Вх/Ф/П. Проверенный owner provider отдельно. Создать новый assignment ID с pinned complete policy/selector, не менять другие sources |
| Replace role | Explicit revoke(old ID)+create(new ID) одной транзакцией с полным diff/regrant_ack; старый source terminal, независимые sources сохраняются. Stale/revoked old source → conflict, не revive |
| Delegate config | `client.staff.delegate.<section>`; только УК→active М того же клуба; issuer имеет execution+supporting read из своего active role source. Whitelist profile/layout/calendar/booking_rules. Нет self, transitive delegation, staff/finance/ownership |
| Revoke assignment | `client.staff.revoke.<target-role>` + exact ownership/version и permissible hierarchy; source terminal, dependent delegations invalid/revoked, другие sources не трогать |
| Revoke delegation | Exact delegation ID/version; lawful issuer либо вышестоящая явно разрешённая authority; revoke только source. Дальнейшая возможность от прочего источника сохраняется и показывается |
| Full club disable | Отдельный `client.staff.disable.club`, reason+полный before/after+confirmation. ВБ для staff; УК только когда **все** sources target staff относятся к четырём подчинённым ролям. УС не получает full disable по умолчанию. Нельзя затронуть owner/platform или себя |
| Re-admit | Новая TARGET-version invitation, signed claim, отдельный lawful confirmation; active platform membership, current disable fence и явно новый набор IDs/permissions. Активировать новый epoch; старые sources/invitations не возвращаются |

При issue получатель может быть неизвестен: создаётся только target invitation.
Первичный admission получает PENDING fence **после signed claim**, привязавшего
minimal user и club, без membership/grants. Fence с FK users+mapping не требует
ещё не созданную membership; assignments по-прежнему требуют её composite FK.
При confirmation отсутствующая
membership может быть создана атомарно, как в existing staff admission, без
application.manage; **существующая inactive membership не восстанавливается**.
В этом единственном случае до commit не требуется уже active target membership:
её отсутствие не заменяет fresh signed claim/confirmation, actor hierarchy,
client/mapping/fence checks. Existing DISABLED fence при claim не заменяется.
Full disable сохраняет MAX issued target-invitation ID клуба под client lock:
старый, в том числе ещё не claimed, invitation не принимается для данного user
при ID ≤ watermark. Новый ID должен быть после fence, protocol TARGET и текущей
generation; issued/claim не дают grants. Старый client/staff invitation не подходит.

Revoke-права УС/УК и full-disable policy в таблице — технические **PROPOSED** детали,
не новое молчаливое product acceptance. Actor не может назначать вышестоящую роль,
самого себя или использовать чужой assignment ID. Target business owner/платформа
защищены от обычных staff mutations. Совмещение не позволяет нижней роли отозвать
весь доступ пользователя с более высокой независимой ролью: только разрешённый source.

**Grantable != executable:** owner может назначить УК с configuration package,
не имея повседневных configuration execution прав. Role assignment требует
управленческую authority на **весь** pinned target package, а не на execution
каждого permission у выдающего. Delegation наоборот требует текущего execution
выдающего. Старое packageAllowed с другой семантикой сохраняется только COMPAT.

Full disable в одной транзакции: fence DISABLED+epoch++, revoke всех staff IDs и
delegations получателя в клубе, invalidate/revoke исходящие от его assignments,
выключить все exact compatibility club grants, сохранить tombstones/history,
bump versions и audit/receipt. Другие clubs/clients, ownership, guest SELF/public
и historical bookings/attribution не удаляются. UI говорит «staff доступ отключён»,
не обещает удалить уже раскрытые данные или чужую независимую legacy authority.
В v1 УК не выполняет full disable при **любом opaque compatibility source**:
подчинённость нельзя выводить из permission code. Такой scope отключает только
проверенный ВБ с отдельной full-disable authority; допустим и compatibility-only
staff subject без active role rows. Обычный revoke разрешённого role ID всё ещё
сохраняет независимые opaque права. Owner/self/platform protection сохраняется.

Platform revoke остаётся отдельным exact configured-owner действием: membership
false, все affected subject epochs данного client/user++, target sources и
dependent delegations отозваны, current grants/staff выключены, остальные clients
не затронуты. Membership restore меняет только явно выбранные старые допустимые
scopes и не активирует target admissions/assignments/delegations. Staff возвращается
только новой admission. Client container restore/limit change также не grants.

### Минимальный API shape PROPOSED

Reuse signed client namespace/ApiError/no-store/Vary/idempotency infrastructure.
GET exact club staff/effective/explain; POST assignments; POST explicit role-change;
POST assignment/{id}/revoke; POST delegations и delegation/{id}/revoke; POST
staff/{user}/disable; новый target-admission workflow. Это названия будущих
операций, не работающие маршруты. GET никогда не provisions или меняет grants.

Write intent содержит target IDs, exact policy/version/selector, expected subject
epoch+version и source versions, reason/confirmed, regrant_ack. Actor/client/parent
scope выводятся сервером, не доверяются body. Возвращать current authority revision,
source statuses и full diff; receipts не хранят secrets/исторический access payload.
Не логировать initData, invitation secrets, QR/raw Idempotency-Key/PII.

## 4. Atomicity, locks и немедленный revoke

Existing mutations: actor lock → client row lock → child locks, READ COMMITTED.
Новый writer сохраняет этот порядок; все изменения одной client authority
сериализуются тем же **client lock**, включая platform revoke и два разных issuer.
После client lock — target fence rows по sorted club/user, затем source IDs.
Не добавлять blocking target-actor lock после client lock. Client→actor calendar
resolver использует только existing NOWAIT/savepoint и остаётся отдельным read path.

Внутри одной connection/transaction заново проверить identity/audience/current
authority, membership, source/issuer/recipient/fence versions, full grantable set,
scope/selector/expiry по времени **после** ожидания lock. Ни partial package,
ни silent downgrade не допускаются. Обновить source/fences, transactional audit
и `client_commands` receipt атомарно. Audit/receipt failure, cancellation и SQL
failure откатывают всё. Serialization/deadlock retry повторяет свежие проверки,
не старое решение. CancellationException rethrow обязателен в JVM реализации.

Idempotency bound actor/connection/client/target/operation/immutable intent.
Same key+different intent → conflict. Repeat не исполняет mutation; под current
authority возвращает current protected state, включая REVOKED, не прежний успешный
grant payload. Unknown resolution только read; отсутствие receipt — snapshot,
не cancellation. Старый результат/rollback не меняет tombstones.

Revoke линеаризуется commit под client lock. Новая authorization decision после
commit видит текущие states и отказывает, если последнего source уже нет.
Операция, захватившая lock раньше revoke, может закончиться раньше него; прошлые
эффекты не отменяются. Read должен брать свежую authority и не использовать
singleton cache/repeatable-read snapshot как authorization новых writes.
UI fences: actor/client/club context generation + membership version + subject
epoch/revision. При revoke/replace/full disable/platform revoke issuer source
под тем же client lock отозвать dependent delegations и **bump versions их
recipient fences**, не меняя независимые sources. Affected closure/fence keys
собираются заранее и блокируются в sorted order. Старый recipient revision
не может совпадать после committed dependency loss. Response также содержит
valid-until по expiry dependencies; UI перепроверяет при expiry/foreground.
После наблюдения нового revision/потери authority late response отбрасывается;
без нового наблюдения UI не может узнать чужой revoke мгновенно. Серверный отказ
новых действий не зависит от UI/cache; уже раскрытые данные не отзываются.

## 5. Все текущие authority writers на exact base

Префикс Kotlin ниже: `core-data/src/main/kotlin/com/example/bot/data/`.
Locators отражают main, не утверждение нового runtime. Поиск охватывает SQL,
Exposed mutation calls, callers/DI/routes, scripts и migration entrypoints.

| Точка / locator | AS_BUILT эффект | Gate перед coexistence |
|---|---|---|
| [onboarding/ClubStaffAccess.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubStaffAccess.kt):113–153, replace | Disable **всех** grants exact tuple; re-enable package inactive rows, INSERT отсутствующих | Не source writer; BLOCKED для TARGET/mixed tuple до redesign |
| [ClubTeamOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubTeamOperations.kt):59–62,76–132 | confirm/change_role/disable → replace; staff active/role/version/fence, reason/audit | Все callers, не только helper, должны отказать/перейти на provenance-aware operation |
| [StaffInvitationOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/StaffInvitationOperations.kt):18–56,91–131,134–263 | Issue/claim/revoke invitation без grants; confirm создаёт отсутствующую membership, replace, insert/update staff, accepted invitation, preparation notification | Старое invitation остаётся старым; нельзя target upgrade/admission через old confirm |
| [InvitationOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/InvitationOperations.kt):46–95,158–257 | Создание client invitation; confirm/acceptRecipient включает client, INSERT membership/application.manage; существующую inactive membership не восстанавливает | Client admission не ownership/staff/all-clubs; preserve old flow отдельно |
| [ClientAccessOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientAccessOperations.kt):128–157,220–321 | Platform membership revoke/restore; disableScopes всех club/membership grants; restore сначала disable, затем selected existing rows; existing staff tuple restore запрещён | Membership restore не возвращает target sources; platform revoke должен охватить новый fence/dependency tail |
| [ClubStaffAccess.platformRevoke](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubStaffAccess.kt):175–216 | Отключает staff данного client/user, version/fence/audit/team bumps | Добавить target source invalidation; не оставлять будущий обход membership |
| [DirectClubOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/DirectClubOperations.kt):18–110 | Новый private club/mapping/immutable creator и **10** active club grants | Сохранить exact COMPAT bootstrap; не выводить target owner из строки/не выдавать соседние клубы |
| [ClubOnboardingOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubOnboardingOperations.kt):16–28,48–127,247–255 | Approved application → тот же bootstrap 10 grants, immutable consumption | Отдельно адаптировать atomic creation/replay; не backfill иных клубов автора |
| [ClubPreparationOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubPreparationOperations.kt):52–77,97–107,117–203 | Invite вызывает staff issue; последующий confirm выдаёт package. Остальные transitions только task metadata | Preparation task не authority; unassign не revoke; indirect confirm входит в gate |
| [ClientLifecycleOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientLifecycleOperations.kt):16–41; [ClientLimitOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientLimitOperations.kt):32–55 | Container archive/restore и club limit, не staff grants | Учитывать новые активные sources в archive blockers; restore/limit не расширяют доступ |
| [JdbcClientOnboardingRepository.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/JdbcClientOnboardingRepository.kt):130–218,284–334 | Actor/client locks, current replay, command/audit transaction; resolver NOWAIT | Reuse без обратного blocking lock; новые result types/schema потребуют отдельной migration |
| [PromoterAdminRepositoryImpl.kt](../../core-data/src/main/kotlin/com/example/bot/data/promoter/PromoterAdminRepositoryImpl.kt):85–186 | Mounted admin promoter access: upsert promoter_club_access; enable INSERT legacy PROMOTER CLUB role, disable DELETE role с cascade attached permissions | Legacy-only boundary/containment; никакого target admission/revoke; repository direct call также gate |

Последний route: Application:432 → PromoterAdminRoutes:91–118 → service/DI;
mapped client clubs защищены PrivateClubContainment. Сам repository не проверяет
этот HTTP containment. Exposed role/permission repositories read-only; startup
CoreDataSeeder пишет domain data, minimal identity provisioning пишет users;
configured platform owner не seed legacy OWNER. Privacy anonymize не revoke.

### SQL, seeds, entrypoints и FK effects

- Обе dialect migrations V065:37, V067:54, V068:55, V074:50/112/174/236,
  V075 PG:76/H2:83 — bounded owner grant INSERT, `NOT EXISTS` учитывает inactive
  tombstone. V075 actual predicate требует read/creator/client/member/private mapping;
  его комментарий про все прежние configuration grants шире реального predicate.
- V2__seed обеих dialects создаёт legacy role catalogue и seeded PROMOTER CLUB
  assignment. V058 создаёт permission catalogue/support codes; V061/062/063/065/
  067/068/074/075 добавляют client permission codes/constraints. V076 grants не выдаёт.
- [seed-demo.sql](../../scripts/seed-demo.sql):5–9,92–101 и
  [seed-demo.sh](../../scripts/seed-demo.sh):4–19 — manual external DB writer
  CLUB_ADMIN roles, без нового revoke/admission fencing. App/CI caller не найден.
- Migration entrypoints: Application→MigrationsPlugin→MigrationRunner (prod/stage
  обычный startup validate-only), tools/MigrateMain, protected QuiescedMigrateMain/
  db-migrate workflow, Gradle Flyway. DatabaseFactory.migrate присутствует, callers
  не найдены — PRESENT_UNWIRED, не test-only. Всё это отдельные privileged writers,
  не разрешение данной задачи запускать их.
- User-role DELETE каскадно удаляет attached permissions (реально в promoter disable).
  User/club/membership/mapping deletes имеют CASCADE и последующие RESTRICT FK;
  успешный cascade нельзя предполагать. Hard-delete authority route не найден;
  cleanup SQL/seed не должен обойти target fences или удалить историю.

**Небезопасна замена permissions array:** она теряет source ownership, снимает
независимый source, может revive inactive rows и путает role revoke с full disable.
Проверка одного replace недостаточна: future TARGET gate обязан закрыть все
перечисленные callers, bootstrap/migration/manual paths и все relevant readers.

## 6. Переход, rollback и проверяемые gates

1. Read-only baseline обеих authority systems: exact grant tuple/active, legacy
   assignment IDs/attached permissions, promoter_club_access, memberships, staff
   versions/fences/invitations и command/audit provenance. Ничего не исправлять GET.
2. Item-level классификация: VERIFIED_ROLE_SOURCE, KEEP_OPAQUE_COMPAT, REVOKED,
   или AMBIGUOUS_BLOCKED. Role name не доказательство provenance. Неактивные rows
   не импортировать как активные. GLOBAL не становится client/network assignment.
3. Additive schema в disposable H2/PostgreSQL **после отдельного разрешения**;
   сейчас только contract/reference fixtures, без SQL migration. Flat grants
   остаются exact opaque compatibility contributions, не угадываются их issuers.
   KEEP_OPAQUE требует явного review и не предоставляет новые capabilities.
   В fence хранить frozen manifest exact raw identities/creation provenance и
   разрешённых codes; resolver берёт только совпадающие current active rows.
   Новая/изменённая строка от seed/старого writer не попадает в этот manifest
   автоматически. Новый admission не переносит старый compatibility manifest.
   Сохраняются прежние prerequisites: active write при inactive read — dormant,
   не новый permission при cutover. Reviewed COMPAT projection проверяет и старый
   supporting read, а domain adapters сохраняют прежние private/freeze guards.
4. SHADOW resolver объясняет source IDs и diff; existing effective доступ прежний.
   Перед TARGET все writers/readers и seeds должны поддерживать fence/provenance
   либо fail-closed без writes на migrated tuple. Старый API может использовать
   новый resolver только после совместимого domain/DTO contract; иначе закрыт.
5. Поштучный reviewed cutover exact tuple/client/club: expected baseline/version,
   no unreviewed additions/removals, old grants/history остаются; contribution
   import не assignment по названию. New operation grant — отдельная явная выдача,
   не migration. Смена bytes/fence/revoke между preview и commit → conflict.
6. Rollback не включает старый writer/receipt/snapshot, способный resurrect rights.
   Только отключение новой path или compatibility по **текущим** raw states/fences;
   ни один revoked ID/epoch не возвращается. Если fallback не умеет fences —
   deny/manual repair, а не восстановление access snapshot. Транзакционный rollback
   до commit отличается от release rollback: первый сохраняет состояние до
   неуспешной команды, второй не отменяет уже committed revoke.

### Disposable H2/PostgreSQL implementation gate

Обязательны clean/populated upgrade, H2/PG FK/CHECK/policy parity и immutable history;
Postgres два соединения/два issuer, barriers вместо sleeps: grant/revoke/full-disable/
platform revoke/restore и role-change races в обоих порядках. Общий client lock,
source+subject CAS, commit-before-next-request deny. Fault injection на audit и
receipt INSERT, cancellation, deadlock retry, restart/unknown result/replay.
Production-composition Ktor tests охватывают all writer families/containment/old
API bypass, no-store/late responses. Старые JVM/security/payment gates не ослаблять.
Этот набор сейчас **не выполнен**: настоящая schema/implementation не создавалась.

## 7. Acceptance и пять решений для утверждения

Synthetic evidence проверяет CURRENT/TARGET/DIFFERENCE/RISK, source counts,
одну/две роли, role+delegation, два sources одного permission, scoped revoke,
full disable/re-admission, tombstones, inactive membership, interleavings,
promoter 3/5, calendar delegation, соседние финансы, audit rollback и blocked
legacy writer. Это чистая reference model, не SQL/concurrency/runtime proof.
Точные команды/результаты — в [shadow report](../ops/role-assignment-shadow-report.md)
и [checkpoint](../ops/PROJECT_STATUS.md).

Для approval предлагаются ровно пять технических решений:

1. Три additive сущности, virtual source contributions/pinned policy, отдельные
   ownership/SELF/platform providers без обычной staff выдачи владельца.
2. Узкие codes/hierarchy writers, grantable-role vs executable-permission,
   whitelist config delegation и предложенные revoke/full-disable actor bounds.
3. Terminal source tombstones, subject epochs, explicit new-ID regrant_ack и
   новая target admission; отсутствие global negative grant от старой inactive row.
4. KEEP_OPAQUE compatibility и поштучный TARGET cutover только после all-writer/
   reader/seed gates; rollback без восстановления access snapshots.
5. Reuse actor→client serialization, CAS, durable current-state replay,
   transactional audit/receipt и authority-response fencing.

DoD реализации: acceptance этих пяти choices; verified business-owner authority
adapter; immutable registered target policy/codes; все writer gates; meaningful
Ktor/H2/Postgres/rollback/restart tests и independent review. Runtime rollout и
публикация требуют отдельной задачи. Сейчас production sources, migrations,
grants, invitations, API, real data, stage/prod и Git publication не изменяются.

**Один следующий шаг:** утвердить этот PROPOSED-контракт для отдельно разрешённого
минимального local implementation этапа с disposable H2/PostgreSQL проверками.
