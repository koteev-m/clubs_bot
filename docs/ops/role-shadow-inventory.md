# ROLE_MODEL_REVISION — восемь ролей, 2026-11-10

Последующий технический этап: [assignment contract — ACCEPTED local architecture](../product/ROLE_ASSIGNMENT_CONTRACT_PROPOSED.md)
и [отдельный CURRENT/TARGET reference report](role-assignment-shadow-report.md).
Прежние 46 scenario/60 test результаты ниже сохраняют свой scope. Новые reference
transactions не являются production implementation или SQL migration proof.
Фактическая inactive foundation и disposable DB evidence вынесены
[отдельно](role-assignment-foundation.md); текущая production authorization не
использует новый evaluator. Ранее предложенные permissions не выдаются.

**READY_FOR_REVIEW** — для продуктовой редакции и offline shadow-модели.
Это не готовность новой RBAC к rollout. Base/main остаётся
`5550b944990d8c1c93c0a5012d31db209f93ea6d`; registered detached worktree
`/private/tmp/clubs-role-workspaces-proposed-20261109`; PROJECT_IDENTITY_OK,
active Clubs Bot instructions, foreign=0. Публикации и runtime действий нет.

## Текущий контракт и история

[PRODUCT_SPEC §29.18](../product/PRODUCT_SPEC.md#business-role-model-20261110)
и [dated decision](../product/OPEN_DECISIONS.md#role-model-20261110-decision)
фиксируют **ACCEPTED** прямые решения. [Точная восьмиролевая матрица](../product/ROLE_WORKSPACES_PROPOSED.md#role-model-20261110)
различает product A/P/AD и **PROPOSED** technical bindings. Platform owner отдельно.

- Один **Владелец бизнеса** для одного и нескольких клубов; закрытые финансовые
  итоги/показатели/готовность/посещения своих клубов, назначение и отзыв управленцев.
  Второй клуб — тот же client, без переноса данных/расширения existing grants.
- Необязательный **Управляющий сети** — явные club assignments, сравнимый scoped
  read и назначение управляющих этих клубов, без назначения peer network manager.
- **Управляющий клуба** — конфигурация, назначение Менеджеров/входа/финансистов/
  промоутеров и отдельное делегирование разделов Менеджеру.
- **Менеджер** — брони/столы/посадка/зал; config только явным delegation.
  **Менеджер входа**, **Финансовый менеджер**, **Промоутер**, **Гость** сохраняют
  свои отдельные обязанности/границы. DJ и профессии не новые обязательные роли.

Технические enums/user_roles/packages остаются прежними. Исторические
owner-team/owner-manager contracts дополнены только датированными указателями;
их creator-only wiring/фиксированные пакеты/invitation/revoke semantics не менялись.
Раньше полученные owner configuration grants не удаляются новой целевой моделью.

До изменений сохранён полный baseline 15 файлов и hash manifest:
`/private/tmp/clb-role-model-revision-6cj22ljc/before/`, `baseline.json` рядом.
Предыдущий 24-сценарный generated report сохранён там byte-for-byte; предыдущий
handoff ниже явно HISTORICAL. Девятиролевой проект сохранён в historical details
ROLE_WORKSPACES_PROPOSED. Новая fixture сохраняет все 24 прежних scenario IDs и
explicit historical owner labels; они не являются production migration mappings.

## Обновлённый shadow

[Helper](../../scripts/role_shadow_inventory.py),
[fixture](../../scripts/tests/fixtures/role-shadow-inventory.json),
[tests](../../scripts/tests/test_role_shadow_inventory.py),
[актуальный generated CURRENT / PROPOSED / DIFFERENCE / RISK](role-shadow-synthetic-report.md).
26 source fingerprints привязывают расчёт к текущим документам и неизменённому
production code; изменение doc fingerprint — evidence refresh, не product approval.

```bash
python3 -I -S -B scripts/role_shadow_inventory.py --format markdown
python3 -I -S -B scripts/role_shadow_inventory.py --lifecycle cancel
python3 -I -S -B -m unittest discover -s scripts/tests -p test_role_shadow_inventory.py
```

Теперь **46 сценариев**: 24 сохранённых и 22 новых. Восемь helper role IDs —
только labels отчёта. `product_status=ACCEPTED` для прямо принятых действий
отделён от `status=PROPOSED` каждого technical permission binding. Прежнее
«вся matrix не принята» заменено точным статусом: продуктовая модель принята,
детальные permissions/процессы ещё PROPOSED. Ни role metadata, ни proposal input
не используются для реальной authority; `applied_additions/removals=∅/∅`.

CURRENT по-прежнему содержит только реальные по schema synthetic projections;
legacy authority opaque, endpoint allow/deny UNVERIFIED. `policy_fixture` —
отдельный hypothetical typed-assignment model, не новые таблицы БД.
`ALLOW_IN_MODEL` означает лишь результат offline проверки, **никогда API доступ**.
Все future probes явно показывают `CURRENT=NO_NEW_RUNTIME_AUTHORITY`, zero API calls
и zero grant effects. Attribution preview также не применяет corrections.

## Финансовые deny cases и промоутерское бронирование

Проверяется **scope AND capability**, а не просто club assignment. Отдельно
смоделированы reports/revenue/ledger/closed totals/deposits/operations/aggregates/
exports/analytics/related-private и минимум стола. Во всех 11 surfaces проверены
allow своего клуба при отдельном permission, deny соседнего club в том же client
и deny другого client даже при active membership обоих. Финансист с finite shifts
не видит другую смену; пустой список shifts запрещает всё, а не означает unlimited.
Для final totals closed-state обязателен независимо от флага request body.

Менеджер с delegation профиля получает только read+profile.write данного раздела;
не получает staff, смену или отчёты. Live issuer должен иметь supporting read,
operation и delegation authority. Отзыв delegation/issuer read прекращает эту
конфигурационную возможность, сохраняя независимое финансовое назначение.

Промоутер назначен A/B/C из пяти клубов: canonical create в A/B/C проходит **в
модели**, D/E и другой client — deny. Отдельные guards: signed identity, active
assignment/membership, verified business/client/club, capability, night/rules,
availability, HOLD invariants, confirmation, idempotency/audit. Body promoter ID
не подменяет actor; чужая бронь не становится own. После revoke create/read
прекращаются. Booking capability не превращается в finance/check-in/seat/staff.

## Attribution: AS_BUILT, ACCEPTED direction и PROPOSED контракт

**AS_BUILT на base:** [BookingTables](../../core-data/src/main/kotlin/com/example/bot/data/booking/BookingTables.kt)
и [BookingCoreModels](../../core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingCoreModels.kt)
имеют nullable promoter user ID без source/assignment/history. Protected confirm
[BookingRepository](../../core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingRepository.kt),
386–398, записывает null. Старые writers могут сохранить promoter user ID, но это
не принятый client attribution contract. Отдельная
[PromoRepositories.attachUnique](../../core-data/src/main/kotlin/com/example/bot/data/promo/PromoRepositories.kt)
связывает UUID booking с promo attribution; A3 promoter_booking_assignments работает
с BIGINT, не с этим UUID. Table session не содержит booking/source/mode. Booking
min/total deposit может отражать условие, а не фактически принятые деньги.
SeatMode WITH_QR/NO_QR — способ ввода, не финансовый режим.

**ACCEPTED:** источник CLUB/конкретный PROMOTER учитывается отдельно от финансовых
условий. «От промоутера» не paid-by-promoter; CLUB не free. **PROPOSED:** immutable
origin actor/channel/reference + initial attribution; promoter user и stable exact
assignment reference/version/snapshot; current attribution/history отдельно;
financial mode/payment ledger независимо. Подробный минимальный lifecycle и
correction contract — в ROLE_WORKSPACES_PROPOSED, без новой migration/schema.

Новые сценарии показывают CLUB+BILL, PROMOTER+DEPOSIT и тот же promoter с другим
финансовым режимом — только synthetic примеры, не принятие этих вариантов.
Same-club selector исключает foreign/revoked/inactive assignments и повторно
проверяется на write; manager view/correction UI пока P. Request body не может
приписать стол чужому промоутеру. Correction требует отдельного права/причины/
version/audit; candidate не меняет immutable origin, club/night, деньги или visits.
При cancel/no-show source/history сохраняются; table move/retry не удваивает
booked/arrived/seated. Seat без entrance не создаёт arrived. Revoked assignment
остаётся historical attribution, но не eligible для нового выбора. Counter
формулы и rewards P; conflicts разных attribution sources требуют review.

## Совместимость и остающееся PROPOSED

Role assignment, permission delegation, revoke одного assignment/delegation и
full staff disable — разные будущие операции. Fixtures с совмещением FLOOR+FINANCE
показывают, что отзыв роли или delegation не удаляет независимый finance доступ;
full club disable прекращает оба. Старый production `replace` по-прежнему выключает
все grants tuple и может re-enable tombstones; его повторное использование blocked.
Никакие historical roles/grants/memberships/fences/invitations не изменены.

Остаются PROPOSED: точные permission codes/packages/typed assignment storage,
delegate/revoke/disable/admission/replay и writer redesign, business ownership
binding/emergency recovery, source correction/manager UI/dedup formulas/financial
modes/rewards, data DTO/retention и отдельные domain decisions. DEC-006 assigned
promoter scope принят; DEC-010/011/012/020/021 не закрываются booking-направлением.
DEC-008/009/018 canonical DB/NONE/V070/V072/V073 boundaries сохраняются.

Проверки: 60 focused unittest PASS; исходные 38 проверок сохранены/уточнены для
восьми ролей, добавлены 22 проверки новых scopes/deny/delegation/attribution.
Static AST/compile/CLI/source fingerprints и generated report comparison — PASS.
Product/code independent review найденные расхождения закрывает до handoff;
финальные links/decision-status/scope checks фиксируются в checkpoint.
Не проверялись реальные назначения/БД, SQL locks/transactions, runtime API и
stage/prod. Gradle/ktlint/Detekt/IT не запускались: JVM/routing/БД не менялись.

**Один следующий шаг:** согласовать узкий PROPOSED контракт независимых
assignments/delegations/revoke/full-disable и безопасной совместимости всех
grant writers, до отдельного разрешения на local RBAC реализацию.

<details>
<summary>HISTORICAL: предыдущий CLB-role-shadow-inventory, 24 сценария / 38 тестов</summary>

# CLB-role-shadow-inventory — локальный результат

Дата: 2026-11-10. **ROLE_SHADOW_INVENTORY: PASS** — для offline inventory/shadow
инструмента на artificial fixtures. **RBAC migration readiness: BLOCKED** до
продуктового принятия и grant-writer contract. Фактической миграции нет.

Repository/main: `koteev-m/clubs_bot`, `5550b944990d8c1c93c0a5012d31db209f93ea6d`
после PR #567, свежий read-only remote snapshot подтвердил тот же SHA.
Зарегистрированный detached worktree:
`/private/tmp/clubs-role-workspaces-proposed-20261109`.
PROJECT_IDENTITY_OK с exact expected HEAD; active Clubs Bot instructions,
active foreign instructions=0. Основной checkout на `e83f806…` и его чужие
изменения не затрагивались. Предыдущие PROPOSED-документ и README сохранены.

## Что можно воспроизвести

- [Helper](../../scripts/role_shadow_inventory.py): только stdlib, bundled synthetic
  JSON и source reads; вывод в stdout. Нет DB/network/credentials/input URL,
  apply/export-to-DB или production imports. Никаких новых production roles.
- [Fixture](../../scripts/tests/fixtures/role-shadow-inventory.json): 24 сценария,
  172 безопасные projection rows, 20 source fingerprints, включая неизменённый
  [PROPOSED-проект](../product/ROLE_WORKSPACES_PROPOSED.md). Fixture не выгрузка БД.
- [Полный CURRENT / PROPOSED / DIFFERENCE / RISK по каждому сценарию](role-shadow-synthetic-report.md).
  JSON с теми же полями получается командой ниже; сохранённая локальная копия:
  `/private/tmp/clb-role-shadow-report-20261110.json`.
- [Тесты](../../scripts/tests/test_role_shadow_inventory.py): meaningful differences,
  isolation/negative fixtures, writer blast radius и отсутствие mutations.

```bash
python3 -I -S -B scripts/role_shadow_inventory.py --format markdown
python3 -I -S -B scripts/role_shadow_inventory.py --lifecycle rollback
python3 -I -S -B -m unittest discover -s scripts/tests -p test_role_shadow_inventory.py
```

Команды запускаются из этого worktree; helper также работает из disposable cwd,
поскольку его bundled source paths привязаны к собственному файлу. CLI не принимает
путь к иной fixture, DB URL, output path или apply. Новых dependencies нет.
Source drift/неполный fingerprint set/неправильная schema прекращают расчёт.

## Текущая техническая модель

| Источник authority | Хранение и реальные границы |
|---|---|
| Legacy роли | `Role`: OWNER, GLOBAL_ADMIN, HEAD_MANAGER, CLUB_ADMIN, MANAGER, ENTRY_MANAGER, PROMOTER, GUEST. `user_roles.id/user_id/role_code/scope_type/scope_club_id`; scope только GLOBAL или CLUB. Нет active/version/tombstone колонок |
| Legacy explicit permissions | `user_role_permissions(user_role_id,permission_code)`; например support.view/reply/status.manage. Permission связано с конкретным assignment ID. Отсутствие legacy row не доказывает исторический revoke |
| Client | `clients`, `client_memberships(client_id,user_id)` с active/version, отдельный `client_membership_grants` для `client.application.manage`. Членство не даёт клубы |
| Club | `client_clubs` mapping одного клуба к одному client; `client_club_grants` с composite identity client/user/club/permission, active/timestamps, **без surrogate ID/version** |
| Staff metadata | `client_club_staff` с composite PK client/club/user, role_code, active, version, invitation_id и revoked_through_invitation_id. Metadata не заменяет grants |
| Ownership | Immutable creator из direct-club/onboarding provenance + current exact read/team.manage. Ни client membership, ни legacy OWNER/GLOBAL, ни новая сетевая должность не заменяют эту проверку |
| Invitations | issued/claimed/accepted/revoked; expiry derived для issued/claimed. Accepted invitation после expiry не отзывает уже выданный доступ. Claim без confirm не даёт grants; старое staff invitation не invitation новой роли |
| Commands/audit | Durable command identity actor/connection/key hash; fresh authorization при replay. Grants/business result/receipt/audit атомарны в existing repository. Helper сохраняет только безопасные result/action projections и не воспроизводит команды |

Точные existing client packages, все suffixes с префиксом `client.club.`:

| Пакет | Permissions |
|---|---|
| ENTRY_MANAGER | read |
| MANAGER | read, profile.write, layout.write, calendar.write, booking_rules.write |
| CLUB_ADMIN | MANAGER + finance.read/write, modules.read/write |

Finance здесь **configuration**, не деньги/смена. Team.manage не входит ни в один
staff package. Пакеты из [owner-team-contract](owner-team-contract.md) приняты;
новый catalogue из [owner-manager-contract](owner-manager-contract.md) не создаёт
новую permission registry и не задаёт механическую миграцию.

Generic grant predicate в `OnboardingSql.requireClubGrant` требует active,
unarchived client, active membership, server mapping, read и exact operation grant.
Staff active/role_code туда не входят: inactive staff + active grants нельзя
замаскировать как «доступ уже отозван». Такой противоречивый snapshot отмечен
blocker. Finance/modules/calendar/team имеют дополнительные domain/creator guards.

Legacy API mounted в Application, но PrivateClubContainment исключает mapped
client clubs/descendants, включая GLOBAL. Старый role set и union club IDs
не превращаются в новые client capabilities. OWNER/GLOBAL_ADMIN/HEAD_MANAGER
имеют legacy global bypass; новый network access из него не выводится.

Evidence: V1/V058/V061/V062/V075 migrations, Role.kt, ClubStaffAccess,
ClubTeamOperations, StaffInvitationOperations, ClientAccessOperations,
OnboardingSql, JdbcClientOnboardingRepository, RbacHelpers и
PrivateClubContainment — exact paths/hashes в fixture; locators и API/frontend
crosswalk находятся в [PROPOSED §3](../product/ROLE_WORKSPACES_PROPOSED.md).
PRODUCT_SPEC §29.16/17, OPEN_DECISIONS и roadmap сохраняют свои статусы.

## Семантика shadow и конкретные результаты

**CURRENT** сохраняет исходные exact assignments/grants, inactive rows, staff
versions/fences, membership state и безопасные invitation/command/audit projections.
Отдельный bounded projection показывает active **client tokens с read prerequisite**.
Он не доказывает разрешение каждого API: endpoint authorization явно UNVERIFIED.

**PROPOSED** читается из существующей Markdown matrix; tool IDs ролей/действий —
только labels отчёта. Все target permissions имеют статус PROPOSED. Для существующих
permission_code сравниваются точные tokens в одном tuple. Для действий без принятого
production permission_code выводится concrete action intent, а не выдуманный grant.
Гостевой SELF intent также имеет diff и собственные blockers, без staff authority.

**DIFFERENCE** — гипотетическая замена, не безопасный executable plan. Exact token
additions/removals/retained/restoration attempts перечислены по scope. Совмещение
нескольких **явных** targets объединяется только внутри одного client/club/user.
Legacy сохраняется opaque с assignment IDs и attached permissions: полный доступ
всех legacy API не вычисляется и не маскируется ложной автоматической эквивалентностью.
GLOBAL→finite-client scope candidate всегда MANUAL_ONLY, без adoption.

| Synthetic пример | Что показал расчёт, ничего не применено |
|---|---|
| Client MANAGER → Менеджер | Потеря read/profile/layout/calendar/booking_rules — 5 exact tokens при замене; seat/free/stop-sales — отдельные PROPOSED actions. Compatibility нужно сохранить |
| Client ENTRY_MANAGER → Менеджер входа | read не даёт QR; при замене конфигурационный read исчез бы, scanner/arrival/denial требуют нового контракта |
| Client CLUB_ADMIN → Финансовый менеджер | 9 configuration tokens не являются денежным доступом и исчезли бы при замене; close/table-money остаются PROPOSED |
| Владелец сети, creator только A1 | Existing A1 owner grants сохранены; A2 требует ещё 9 token intents и не проходит creator gate. Сетевое название не передаёт ownership |
| Управляющий клуба из preparation package | 9 configuration tokens сохраняются; вход/зал/деньги не возникают автоматически |
| Промоутер A1/A2 | Два явных club targets, только own-list/outcome intents; legacy assignments сохранены отдельно, GLOBAL не создаётся |
| Два независимых клиента | FLOOR в A1 и ENTRANCE в B1 не смешивают роли/club sets; данные и raw evidence обоих клиентов сохранены |
| Несколько targets | FLOOR+ENTRANCE объединены только в A1; CLUB_MANAGER в A2 не переносит конфигурационные tokens в A1 |
| Revoked grants | profile/calendar tombstones видимы; proposed restoration BLOCKED; old writer preview показывает риск обеих reactivations |
| Inactive membership / inactive/archived client | Stored active rows не исчезают, но bounded current token projection пуст; target остаётся blocked |
| Client-only disable + legacy ENTRY_MANAGER | Client tokens выключены, staff fence сохранён; независимый legacy assignment 1201 остаётся. «Весь доступ отозван» было бы неверно |
| Два legacy GLOBAL OWNER rows | Оба IDs 1301/1302 сохранены. Postgres NULL-distinct unique допускает такие строки; их нельзя схлопнуть или принять за network ownership |
| Исторический CLIENT | Намеренно malformed negative input: текущая legacy schema такой scope отвергает. Отчёт INVALID_LEGACY_SCOPE_OR_ROLE, без исправления в membership |
| Old invitation / same role name | Без explicit target PROPOSED пуст, роли не назначаются; accepted expiry не отзывает уже существующие grants |
| Guest / BIGINT / cross-client | SELF-only intents с blockers; ID 9007199254740993 сохранён точно; GLOBAL/all-clubs/mismatched mapping запрещены |

Snapshot SHA до/после: `4aab1ecf31b03046ed9739ea4f8ac00f8229026c36e76b382676675aa5d76445`.
Applied additions/removals **∅/∅**
во всех сценариях; `writes=0`. Cancel/rollback — report modes, не вызов реального
rollback engine: fixture/tombstones/commands остаются byte-equivalent как данные.

## Старый writer: конкретный blocker и минимальный безопасный вариант

`ClubStaffAccess.replace:113–153` сначала выключает **все** grants exact
client/club/user, затем активирует строки выбранного пакета или создаёт отсутствующие.
Его вызывают change_role/disable и staff confirm. Это корректная часть текущего
явного package-replacement contract, а не найденное несанкционированное расширение
в действующем production flow. Переиспользовать его как migration helper нельзя.

В preview ENTRY_MANAGER→CLUB_ADMIN видны **8 создаваемых grants**. На fixture с
отозванными profile/calendar видны **2 reactivations**. Все read/write effects —
низкоуровневая симуляция SQL pattern, без вызова production writer/базы и без
утверждения, что issuer прошёл бы верхние guards.

Если будущие operation grants положить в ту же таблицу после расширения schema,
повторный старый writer сотрёт QR/arrival, seat/free, table payments, shift close
или иные независимые capability. Сейчас эти новые коды не разрешены DB CHECK;
они моделируются только отдельным hypothetical list, не как существующие rows.

**PROPOSED минимальное исправление перед coexistence:** отдельный grant-writer
contract для confirm/change/disable/restore/replay/preparation admission. Старый
writer на смешанном target assignment должен отказывать conflict без writes;
новый explicit before/after workflow должен различать configuration-only change
и full club disable, сохранять несвязанные operational assignments, проверять
issuer authority/version и атомарные audit/receipt. Нужны origin/assignment-aware
границы изменений. Никакого автоматического backfill, tombstone restore или
очистки legacy. Этот writer в данной задаче **не изменён**.

## Компактная матрица для владельца продукта — всё ещё проект

**A = ACCEPTED**, только прежний контракт/current exact grants и его условия.
**P = PROPOSED**, требует отдельного принятия. A не означает автоматического
сопоставления старого пакета новому target role. Для каждой строки действуют
A: client isolation, current identity и запрет передачи platform/ownership.

| Роль | Область | Просмотр | Настройка | Операции | Сотрудники | Финансы | Запрещено |
|---|---|---|---|---|---|---|---|
| Владелец сети | P: verified сеть, finite clubs одного client | P: сравнение/результаты | P: отдельно по клубам | P: контроль подготовки | A: только creator+grants в клубе; P: network authority | P: ledger read, без проводок | A: чужие clients/platform; P: вход/посадка/close без отдельной роли |
| Владелец клуба | A: creator, exact client/club | A: existing overview; P: новые результаты | A: current configuration grants | A: preparation accept/return | A: invite/confirm/change/disable с read/team.manage | A: шаблоны по grants; P: ledger read | A: ownership transfer; P: денежные операции/close по названию |
| Управляющий сети | P: назначенный finite set | P: агрегаты/roster | P: нет | P: read coordination | P: только redacted roster | P: ledger read | P: team/config/деньги и all-clubs inheritance |
| Управляющий клуба | A: current assigned club; P: новая role binding | A: preparation; P: ночь/гости | A: existing полный CLUB_ADMIN при admission | A: submit; P: зал/вход | P: roster, без выдачи | P: отдельная table-money capability; без close | A: team/ownership из preparation; P: самостоятельные grants |
| Менеджер | P: явные club assignments, selected night | P: столы/минимум гостя | P: нет | P: seat/free/extend/no-show/stop-sales | P: нет | P: депозит/доплата/охрана отдельно | P: QR arrival, team, настройки, close |
| Менеджер входа | P: явные club/night | P: list/search/pass | P: нет | P: scan/arrival/denial | P: нет | P: нет | P: seating, ledger, настройки, team |
| Финансовый менеджер | P: назначенные club shifts | P: ledger/сверка | P: нет config | P: ввод people/bracelets/revenue, close | P: нет | P: отдельные проводки, DEC-012 | P: посадка, team, refund/correction/super-right по названию |
| Промоутер | P: несколько explicit clubs, свои записи | P: own lists/outcomes | P: нет | P: create/import/invite | P: нет | P: нет | P: чужие списки, собственный check-in, GLOBAL, чужие финансы |
| Гость | A: свои объекты/разрешённая витрина | P: own records/history/pass | P: личные настройки в своём scope | P: canonical booking/pass enablement отдельно | A: нет staff authority | A: NONE не оплата; P: provider отдельно | A: чужие private данные; P: все staff действия |

## Не более пяти решений перед изменением RBAC

1. **Точная matrix/scope:** принять ли предложенное распределение, finite network
   mapping, совмещение ролей и сохранение creator-only team authority; автоматического
   mapping старых packages нет.
2. **Вход/промоутер:** принять DEC-006/010/011/020/021 — club authoring, canonical
   subject/pass, приглашения, arrival/denial, причины и корректировки.
3. **Зал:** кто seat/free/extend/no-show/stop-sales/Undo, режимы bill/club и
   подтверждение/причина; вход/visit не создаётся посадкой.
4. **Деньги:** DEC-012, отдельные table-money capabilities, finance-only close,
   allocations/freeze; correction/refund/super-right остаются выключенными до
   отдельной политики.
5. **Read/data:** точные redacted roster/ledger/aggregates/audit DTO, PII fields,
   retention/history; read не создаёт смену и не даёт operation permission.

## Проверки, границы доказательства и следующий шаг

- 38/38 Python unittest PASS; входят exact delta assertions, existing-token loss,
  new-row creation/reactivation preview, same-tuple role union, independent clients,
  no inheritance при добавлении клуба, inactive/tombstones/invitations, malformed
  legacy scopes, stale baseline, immutable ownership, BIGINT, cancel/rollback и CLI.
- Static AST/import/call surface + compile PASS: только argparse/hashlib/json/pathlib,
  нет DB/network/write/apply API. CLI из disposable cwd не оставляет файлов и
  отвергает --apply/--database-url/--input/--output. 20 source fingerprints PASS.
- Независимый bounded review выявил и исправил duplicate invitation PK в fixture,
  неполный writer preview без INSERT delta и отсутствие SELF diff/risk. После
  исправлений targeted suite PASS; итоговые docs/scope checks — в checkpoint.
- Это **не** Postgres migration/transaction/lock proof, не execution production
  writer, не full legacy API authorization audit, не проверка реальных назначений.
  Grant origin/schema redesign остаются PROPOSED. Production tests/Gradle/Detekt/
  ktlint/IT не запускались: Python helper не меняет JVM/security/routing/БД-код.
- Secrets/hash credentials/raw initData/Idempotency-Key/полные audit/command payloads
  не нужны fixture/report и не читаются. Projection deliberately omits DB columns
  с ними, timestamps и часть domain context; это явно ограниченный inventory.

Один следующий шаг: **принять продуктовую матрицу и пять ограниченных решений
выше**, сохранив их неопределённые пункты deny. Реализация и фактическая миграция
из этого handoff не следуют.

Изменены только local helper/fixtures/tests, этот отчёт, generated report и scoped
checkpoint. PROPOSED source, концепция, production code/migrations/grants/API/staff
assignments не менялись; stage/prod/real DB и commit/push/PR/merge/deploy не было.

</details>
