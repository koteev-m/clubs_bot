# Signed TARGET identity и admission — технический контракт

**ROLE_TARGET_IDENTITY_CONTRACT: PROPOSED / READY_FOR_APPROVAL.**

> Local maintenance follow-up: [read-only selector](../ops/role-target-maintenance-selector.md)
> reuses the same TARGET parser/HMAC/freshness with a separate platform-channel
> maintenance identity type. It does not invent a client audience or infer business
> ownership. Current context/authority/durable pins are rechecked; missing providers
> deny, and successful metadata selection grants no mutation/TARGET permission.

> Local read-only follow-up: [V078 durable metadata source](../ops/role-target-durable-binding-source.md)
> inspects exact inactive root/incarnation/credential/mapping fences. It supplies
> no TargetVerifiedIdentity/TargetAudienceBinding, production selector, credential
> resolver, proof v2 or Issue/Confirm authority; current verifier remains unwired.

> Follow-up PROPOSED: [trusted binding identity/lifecycle](ROLE_TARGET_TRUSTED_BINDING_CONTRACT_PROPOSED.md)
> уточняет platform/branded semantics, durable generation, mapping ABA и будущие
> transaction locks. Read-only identity/audience не заменяют эти отсутствующие
> fences. Registry, proof v2 и TARGET mutation по-прежнему не реализованы.

> Local follow-up, ROLE_TARGET_CANONICAL_AUDIENCE_PROVIDER (2026-11-11): read-only
> canonical client/club snapshot + explicit pinned/current server binding provider.
> [Sources, checks and inactive boundary](../ops/role-target-canonical-audience-provider.md).
> Не создаёт durable bot registry, не подтверждает user authority и не подключён
> к production. Без авторитетного finite binding — deny; branded registry остаётся
> prerequisite. Invitation/proof v2/admission и будущие mutation fences не реализованы.

> Local follow-up, ROLE_TARGET_IDENTITY_VERIFIER (2026-11-11): отдельно разрешён и
> реализован только read-only signed identity/audience verifier с TARGET age<300с
> и zero future skew. [Гарантии, tests и inactive boundary](../ops/role-target-identity-verifier.md).
> Это узкое уточнение прежней design-only границы ниже, не approval invitation
> nonce/proof v2, ownership provider, TARGET admission или permission activation.

CLB-role-target-identity-contract, 2026-11-11. Принятые восемь ролей и обязанности
не меняются. Только проектирование и offline synthetic reference; implementation,
новые migrations/API, реальные invitations/assignments и materialized SHADOW запрещены.
Base/main `5550b944990d8c1c93c0a5012d31db209f93ea6d`, registered detached worktree
`/private/tmp/clubs-role-workspaces-proposed-20261109`, PROJECT_IDENTITY_OK, foreign=0.
Все69 hashes проверенного кандидата совпали с manifest
`9b783569ed141f793fd3e3aeb7aa64db833a6008fdee857c43735785337db4ed` до правок.
Baseline/evidence: `/private/tmp/clb-role-target-identity-contract-9bt8yx3q/`.

## 1. Authority, статусы и проверенное состояние

**ACCEPTED:** [SPEC §29.18](PRODUCT_SPEC.md#business-role-model-20261110),
[прямое решение](OPEN_DECISIONS.md#role-model-20261110-decision),
[матрица](ROLE_WORKSPACES_PROPOSED.md#role-model-20261110). Восемь продуктовых ролей:
Владелец бизнеса, Управляющий сети, Управляющий клуба, Менеджер, Менеджер входа,
Финансовый менеджер, Промоутер, Гость. Platform owner — отдельная authority.
Двухэтапный принцип identity claim + отдельное подтверждение сохраняется.
Точные технические choices ниже **PROPOSED**; прежние permissions/policy bindings
из [assignment contract](ROLE_ASSIGNMENT_CONTRACT_PROPOSED.md) не становятся принятыми.

**AS_BUILT legacy:** [StaffInvitationOperations](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/StaffInvitationOperations.kt)
использует purpose=staff, 7 суток, первый signed claim, comparison code и creator
confirmation; затем [ClubStaffAccess.replace](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubStaffAccess.kt)
заменяет плоский пакет. [Owner/team](../ops/owner-team-contract.md) и
[preparation](../ops/owner-manager-contract.md) остаются прежним протоколом.
Ни этот secret, ни accepted legacy invitation не являются TARGET proof.

**Implemented but inactive:** [V077](../../core-data/src/main/resources/db/migration/postgresql/V077__client_role_assignment_foundation.sql),
[foundation](../ops/role-assignment-foundation.md),
[platform adapter](../ops/role-platform-revoke-adapter.md). Нового authorization
reader/DI/policy installation нет. [Providers](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentProviders.kt)
default-deny; `Admit`/validation работают только в SHADOW, а не готовом TARGET.
Текущий proof имеет Long invitationId/epoch, но не полный протокол ниже.
Legacy `invitationFence` считает только purpose=staff; adapter не fencing TARGET
unclaimed в клубе без admission. До исправления этих blockers TARGET закрыт.

## 2. Issuer matrix и ownership

| Issuer | Кому может Issue и Confirm | Scope / current proof |
|---|---|---|
| Владелец бизнеса | Управляющий сети; Управляющий клуба | Проверенная ownership attestation + finite owned clubs этого client |
| Управляющий сети | Управляющий клуба | Live NETWORK_MANAGER source **этого** клуба, pinned policy и assign capability |
| Управляющий клуба | Менеджер; Менеджер входа; Финансовый менеджер; Промоутер | Live CLUB_MANAGER source этого клуба, exact assign capability |
| Менеджер / входа / финансовый / промоутер / гость | Никому | Наличие роли/делегированной настройки не выдаёт staff management |
| Platform owner | Не выдаёт клиентские staff роли через этот протокол | Прежняя configured platform authority отдельно; не staff contribution |

Владелец бизнеса и Гость не создаются как staff role assignments: ownership и SELF
остаются отдельными providers. УС на A/B/C — три exact-club bindings, без GLOBAL
и автоматического включения D/E/нового клуба. Для финансового менеджера selector
пинится как finite SHIFT_SET или отдельно разрешённый ALL_SHIFTS_OF_THIS_CLUB;
это не право читать финансы client. Конкретные policy codes остаются PROPOSED.

Для Issue/Confirm требуются управление целевой ролью (`client.staff.assign.<suffix>`
по существующему proposed suffix mapping), а не весь operational permission set
получателя. Cancel invitation / revoke source — отдельная current revoke authority
для этой роли. Delegation требует execution + supporting read + delegate capability
CLUB_MANAGER и live FLOOR_MANAGER recipient; staff/ownership/platform не делегируются.
Full disable сохраняет отдельный existing proposed contract: live disable capability,
existing admission, protected-subject check и допустимый lower-role target для УК;
owner canDisable требует отдельного explicit provider proof, по умолчанию deny.
Это не новое право блокировать любого пользователя без admission.

**Ownership provider blocker:** текущего business-owner provider нет. Предлагается
отдельная versioned ownership attestation: client/user, finite verified club set,
status, immutable evidence/approval reference и audit. Начальная attestation требует
отдельного проверяемого platform-owner onboarding/ownership approval; она не выводится
из creator_user_id, membership, client.application.manage, username или legacy OWNER/GLOBAL.
Это PROPOSED trusted root и отдельная будущая persistence/approval операция, не
staff writer. Неизвестная ownership/protected-subject информация запрещает выдачу.
Изменение/revoke attestation сериализуется тем же client lock. При добавлении клуба
принадлежность бизнесу подтверждается отдельно, прежние staff bindings не расширяются.

Один неизменный issuer user и **точный issuer authority source/ref + epoch/version**
фиксируются при Issue. Confirm выполняет тот же issuer, всё ещё имеющий этот proof.
Новый manager/source не может подхватить старую ссылку: current-authorized reissue.
Это не runtime-зависимость уже законно выданной роли от её issuer: после Confirm
она самостоятельна до своего revoke/disable. Delegation имеет отдельную dependency.
Self-invite/self-confirm, staff назначение business/platform owner и косвенное
повышение через delegation запрещены. При утрате issuer authority pending invite
не может выдать доступ, даже если issuer позже получил новую похожую роль.

## 3. Signed identity и trusted audience

Официальный [Telegram HMAC contract](https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app)
проверяет raw initData сервером с bot token; `initDataUnsafe` не является доверенным.
TARGET proposal: строгий decode один раз, duplicate keys/невалидный JSON/нецелый
положительный 64-bit user.id/невалидные даты отвергаются. HMAC-SHA256: ключ получается
из bot token с WebAppData; отсортированные field=value строки разделены LF, исключён
только hash, signature остаётся покрытым. Constant-time comparison. Отдельный
third-party Ed25519 алгоритм не смешивается с bot-token проверкой.

Возраст **0 ≤ now-auth_date <300 секунд**, future auth_date запрещён. Это наш
PROPOSED предел, не предписанный Telegram TTL. Сейчас InitDataValidator по умолчанию
допускает 24ч/2мин future; TARGET обязан передать строгие параметры и дополнительно
проверить boundary через VerifiedAssignmentIdentity. Проверки повторяются после
lock waits и перед commit; expiry во время ожидания означает rollback.

Только server-selected trusted connection: bot ID/key reference, connectionKey,
registry version/status, canonical или конкретный approved branded bot, разрешённые
client/club. Bot token не берётся из body/query. Invitation фиксирует отдельно
issuer connection и recipient connection/version: owner может работать в canonical
bot, кандидат — в явно указанном brand bot. Unknown/disabled audience, rotation
версии или другой bot требуют deny/reissue. Никакого перебора bot secrets/fallback.
Один Telegram ID может иметь разные назначения в разных clients; общая identity
не создаёт membership/authority в соседнем client. Managed Bots registry ещё не
готов: canonical допустим первым slice только с явным trusted server binding.

URL/start_param, body userId/clientId/clubId, display name, header role — locators
или недоверенный ввод. Club/client берутся из locked invitation и checked mapping;
claim user — исключительно из verified identity. Telegram user.id разрешается через
unique users.telegram_user_id в local users.id; body userId/username не участвует
в этом lookup. Неоднозначный mapping запрещён, minimal user не даёт membership. Signed start_param не разрешение.
Raw initData принимается одним canonical header по TLS; query/body aliases запрещены.
Raw initData, secrets, MAC envelopes и raw Idempotency-Key — только request memory,
без audit/telemetry/browser storage. No-store/no-referrer; без analytics на secret
entry. Deep-link secret извлекается в память и удаляется из app URL; ingress redact
до логирования. В audit только safe IDs, revisions, policy/intent hashes и references.

PROPOSED operation challenge: server MAC envelope с random nonce, actor, audience,
client/club, operation, exact intent/version hash, planned source UUID и TTL≤120с.
Nonce — server-minted idempotency key; durable receipt хранит только digest.
Нельзя сменить key и переисполнить envelope; wrong actor/body/scope не проходит MAC.
Это replay fence конкретного intent. InitData остаётся краткоживущим bearer proof:
повторное использование в пределах TTL само по себе не означает fresh consent.
Новые действия требуют новой явной confirmation/challenge и current authority.

## 4. TARGET invitation lifecycle

Отдельный **PROPOSED** `target_staff_invitations`, не новый purpose старого staff flow.
Stable BIGINT ID + exact client/club, client issue_seq, operation FIRST/ADDITIONAL/
READMIT, role, immutable policy version/hash/selector/expiry, issuer user + source
ref, issuer/recipient audiences/version, optional intended recipient reference,
secret_hash, ISSUED/CLAIMED/CONSUMED/REVOKED/EXPIRED, CAS version, claim proof/ref,
planned_source_id UUID и nullable consumed_source_id FK, command/audit references. Unique(client,issue_seq), source ID
unique; composite FK к существующим users/mapping и source при consumption.
Нет role/business-owner/platform wildcard. Expiry не удаляет tombstones/receipts. Invitation expires_at (7 суток), claim
validUntil (≤300с) и optional pinned assignment_expires_at — разные deadlines.
Просроченный assignment не выдаётся, его срок не продлевается refresh/reissue
без нового explicit intent; invitation expiry не отзывает уже законную роль.

| Переход | Проверки и результат |
|---|---|
| Issue → ISSUED | Current issuer/mapping/policy/audience; новый CSPRNG secret≥256 бит, только SHA-256 domain-separated hash в DB; TTL 7 суток PROPOSED; server seq/ID. FIRST может быть unbound, ADDITIONAL/READMIT требуют verified existing recipient reference. Constant-time secret hash comparison; grants/admission/membership не выдаются |
| Claim → CLAIMED | Fresh signed recipient, exact audience, secret hash, consent, expiry, seq/floors; bind одного Telegram/local user. Минимальный user допустим; никаких permissions. Consume secret один раз; сохранить normalized identity attestation ≤300с, target member/epoch/version snapshot, comparison code |
| Refresh claim evidence | Только тот же signed claimant; live invite, unchanged epochs/floors/intent. Обновляет короткий proof и code/version после новой consent, не меняет recipient и не оживляет terminal row |
| Owner/Manager Confirmation → Admission + source + CONSUMED | Fresh original issuer + exact source/ref; fresh stored recipient attestation и current audience; expected claimant/version/code, явное confirmed=true, policy/selectors/seq/floors/membership. Admission/source и consumption атомарны (§7) |
| Cancel / expire | Terminal invitation, secret/code/proof недействительны; уже выданные sources требуют отдельного source revoke |
| Reissue | Current issuer authority, explicit action: старое live pending приглашение terminal, уже REVOKED/EXPIRED запись неизменна; новый ID/seq/secret/pins. Нельзя продлить срок, сменить роль/клуб/recipient у прежней записи |

Known recipient reference выводится сервером из current/historical assignment
этого клуба либо собственного signed TARGET claim того же клуба. Это не global
поиск Telegram accounts или body ID. Если новый FIRST claim обнаружил историю
platform revoke, Confirm требует отдельного READMIT reissue с этим проверенным
candidate reference; автоматической смены intent нет. Legacy invitation не proof.

Comparison code — шестизначный aid для сверки **в исходном личном чате**, вместе
с server-verified candidate identity; не credential. Только код не позволяет
claim/confirm. Неверно claimed account → revoke/reissue, не перепривязка. При
unbound FIRST фактического человека до claim криптографически знать нельзя:
подтверждающий обязан сверить кандидата; копия secret даёт лишь возможность
предъявить кандидатуру, не получить permissions. Intended recipient reference,
если задан, неизменяем и тоже проверяется по signed account.

Повтор Claim с consumed secret запрещён; same-command replay читает текущий статус
для того же signed claimant. После потери claim response код доступен только его
авторизованному current view. Потерянный Issue response: hash-only storage не умеет
восстановить secret; read-only resolve показывает committed/unknown и требует
**отдельного explicit reissue**, без auto-rotation на чтении. Потерянный Confirm:
resolve/replay возвращает current state, не сохранённые permissions. Expired envelope
можно разрешить read-only по проверенному reference/current actor, но не выполнить.

Expiry проверяется по времени даже при ещё не обновлённом status=CLAIMED/ISSUED;
cleanup job для correctness не нужен. Reissue сохраняет client/club/role, policy
может быть заново pinned; другая роль — явный cancel + новый Issue intent.
Consumed invitation не reissue: дальнейшая роль идёт через ADDITIONAL/READMIT
с новым intent, старый source отзывается отдельной операцией.

Ошибки future API используют общий envelope проекта: invalid/missing signed
identity или unknown ingress → unauthorized; нет issuer authority → forbidden;
recipient unknown/invalid secret → неразличимый not_found; stale CAS →
version_conflict; terminal/fenced transition → invalid_transition только для уже
авторизованного actor, иначе not_found; changed same-key intent → idempotency_conflict.
Audit/DB failure → internal_error/неизвестный результат с read-only resolve, без
автовыдачи. UPPER_CASE labels reference report — invariant tags, не новые API codes.

## 5. Authoritative seq/floors: все unclaimed invitations

**PROPOSED future additive fields; V077 сегодня не меняется.**

- `clients.target_issue_seq`: монотонный per-client ordinal; future default NULL
  означает NOT_INITIALIZED, не 0. Audited initialization доказывает отсутствие
  прежних TARGET entries и готовность всех writers; только тогда 0 известен. Issue и любой revoke
  используют существующий client FOR UPDATE. Allocate/insert/audit/receipt вместе;
  timestamp/MAX legacy IDs не заменяют serial issuance frontier. Откат Issue не
  выпускает secret наружу. Все TARGET entries получают seq, в том числе unclaimed.
- `client_memberships.target_revoke_floor`: при platform membership revoke установить
  `max(old, clients.target_issue_seq)` **даже без admissions**. Membership version
  повышается как прежде; restore floor не сбрасывает. NULL marker неизвестен и запрещает TARGET выдачу. Membership tombstone/marker
  сохраняется, delete/recreate запрещён; иначе требуется отдельный durable marker.
- `client_club_access_state.target_revoke_floor`: на role/delegation revoke и full
  disable установить тот же frontier для exact club/user. Full disable bumps epoch;
  source-only revoke bumps revision, сохраняет независимые live sources. Cutoff и
  revision также обновляются у recipients отозванных dependent delegations, с
  включением их permissions в regrant history; остальные их live roles сохраняются. Если
  admission отсутствует, staff full-disable не разрешён: можно отозвать само
  pending invitation по ID. Platform membership cutoff всё равно покрывает
  unclaimed entries без admissions. Старые pending invites отключённого tuple
  инвалидируются, текущие независимые права от source-only revoke не удаляются.
- Старый `revoked_through_invitation_id` сохраняет прежнюю legacy размерность.
  Нельзя сравнивать/MAX legacy ID с TARGET seq или выдавать фиктивный legacy ID.
  Нужен versioned proof v2 и отдельные typed fields/checks в будущем writer.

На Claim, Refresh, Confirm и новой выдаче:
`invite.seq > max(member.target_revoke_floor, admission.target_revoke_floor)`;
затем точные current member/version и admission epoch/version. Неизвестный frontier
или состояние — deny, не default0; 0 допустим лишь доказанно первоначальному ledger.
Claim сохраняет snapshot; после любого revoke старый claim proof нельзя освежить
через тот же invite. FIRST требует отсутствие admission/tombstone и client revoke
history; иначе только новая явная READMIT invitation с known recipient и ack.

Это покрывает unopened link до full disable, claimed link, secret replay и
приглашение в другом клубе того же client без прежней admission после platform
revoke/restore. У не затронутых users/clubs/clients floors не меняются. Потеря issuer
source проверяется отдельно: новое назначение issuer не оживляет прежний invite.
Read-only resolution проверяет current actor/audience и показывает current fence/terminal
record; seq≤floor никогда не авторизует повторную mutation или старый allow payload.

## 6. Пять разных операций

| Операция | Что разрешено / что сохраняется |
|---|---|
| A FIRST | Claim + issuer confirm; отсутствующая membership создаётся active без client.application.manage; inactive membership запрещена. Новый admission epoch1 и новый source UUID в одной transaction |
| B ADDITIONAL | Confirm нового invite; существующая ACTIVE admission того же epoch/member version, новый source UUID; никаких replace/epoch bump или удаления других источников |
| C Delegation | Отдельный existing-design writer: live CLUB_MANAGER → live FLOOR_MANAGER, exact section/read bundle, expiry/reason/current epochs; не role invitation и не повышение роли |
| D READMIT | После отдельного platform restore при необходимости: новая invitation после cutoff, свежий claim/confirm, explicit reason/ack; admission epoch+1, новый source UUID; старые roles/DIDs terminal, не копируются |
| E Source revoke | Только указанный source и его dependencies terminal; revision++ и floor=max(old,frontier). Другие законные contributions остаются; full disable — отдельное действие |

`regrant_ack` — точное пересечение нового pinned permission set и revoked target/
reviewed compatibility permissions, с source references/history revision. Нельзя
заменить boolean «согласен на всё». Unknown historical policy/mapping запрещает
выдачу до classification. READMIT не восстанавливает delegations, ownership,
platform authority или legacy user_roles. Один client revoke не отзывает другой.

## 7. Atomicity, conflicts и response fencing

Все future writers: fresh actor → actor NO KEY UPDATE → **client FOR UPDATE** →
sorted club/fence/source rows → TARGET invitation row. Не брать target actor lock
после client; existing NOWAIT/read порядок не меняется. Проверить current club
mapping/active client/membership, original issuer authority, both audiences,
fresh compatibility classification/revision и current legacy rows под client lock;
новое opaque legacy состояние требует deny до review. Проверить также
post-wait clock/expiry, pinned policy eligibility и subject protection.

Issue/Claim/Cancel/Reissue/Refresh — каждый одна transaction с CAS/audit/receipt.
Confirm — **одна compound transaction**: checks → durable receipt FK → source audit
→ membership/admission change (если нужно) → новый source → invite CONSUMED →
final authority/identity/floor/policy check → complete audit → commit. Existing client_commands/audit переиспользуются,
но будущий receipt discriminator target_staff_invitation и namespace `ti:<connection hash>`
отдельны от staff_invitation/role_foundation. BIGINT result_id указывает TARGET invite;
source composite receipt FK остаётся точным. CHECK расширяется только будущей
additive migration, сегодня не изменён. Ни два вызова
`JdbcRoleAssignmentFoundation.mutate`, ни nested transactions не обеспечивают это.
Нужен внутренний connection-bound primitive; новый endpoint этим документом не принят.

Policy definition immutable. Добавление v2 не перепривязывает v1. Eligibility
retire/restore и ownership/audience изменения должны сериализоваться тем же client
lock и иметь monotonic revision; retired pin запрещает pending confirm. Нельзя
проверить registry один раз до lock и забыть. Global registry cutover требует
отдельного доказанного lock/provider contract, не in-memory TOCTOU.

| Конфликт / оба порядка | Безопасный итог |
|---|---|
| Confirm / source revoke | Confirm первый: новый source lawful до последующего точного revoke; revoke первый: старые seq/proof/CAS запрещают confirm. Для отмены всего сотрудника требуется full disable, не revoke одного ID |
| Claim / invite cancel | Claim первый: только identity без доступа, cancel терминален; cancel первый: claim deny |
| Confirm / issuer revoke | Confirm первый: уже выданная независимая роль сохраняется; issuer revoke первый: pending Confirm deny. Исходящие delegations отзываются по отдельной dependency semantics |
| Issue / issuer revoke | Issue первый: pending proof становится непригоден; revoke первый: issue deny; новая роль issuer не заменяет ref |
| Confirm / platform membership revoke | Confirm первый: все новые client sources отзываются adapter+TARGET marker; revoke первый: inactive member/floor запрещают confirm |
| Confirm / full disable | Confirm первый: полный revoke+epoch/floor закрывают source; disable первый: confirm deny, включая unclaimed |
| Confirm / policy retire | Confirm первый: законная выдача до policy retire; дальнейшая eligibility fail-closed; retire первый: confirm deny; просто новая v2 сохраняет v1 pin |
| Expiry в lock wait | Post-wait expiry ⇒ rollback без выдачи/consumption/receipt |
| Старый receipt / re-admit | Только current authorized state/new epoch; никаких прошлых allow payloads |
| Два Confirm | Один source/consumption; same key current replay, distinct stale key CAS conflict |
| Audit/receipt failure, cancellation, 40001/40P01 | Полный rollback; retry заново проверяет identity/issuer/expiry/epoch, без partial issue |

Ответ содержит persisted/effective invitation status (включая FENCED/EXPIRED),
actor/subject, audience/registry revision, membership version,
admission epoch/version, source ID/version, invite version, seq/floors и validUntil.
UI merger обязан отбросить старую generation/revision; каждый новый server action
всё равно проверяет current DB. Issued до revoke HTTP response не сохраняет authority
после commit revoke. Фактический UI merger и PostgreSQL proof этого TARGET протокола
сейчас не реализованы; synthetic interleavings не являются lock tests.

## 8. Blockers / compatibility gates до implementation

| Blocker AS_BUILT | Минимальное будущее исправление PROPOSED |
|---|---|
| Business ownership provider отсутствует; creator/global не доказательство | Approved ownership attestation + same-transaction default-deny provider/protection и отзыв |
| Current Telegram DTO/audience не содержит всего v2 proof; Managed Bots registry не готов | Strict signed identity/audience adapter с 300с/0future, explicit canonical binding; branded позже тем же contract |
| V077 legacy watermark и platform adapter не видят все TARGET unclaimed | Dedicated TARGET ledger + typed client/club floors и extension **всех** revoke writers в том же commit; без этого Issue закрыт |
| Foundation SHADOW-only, Admit/IssueRole раздельные transactions, proof слишком узкий | Versioned proof v2 + atomic Confirm primitive; нельзя обойти проверку mode/передать synthetic proof в рабочий writer |
| Exact policies/compatibility classifications/reader gates ещё не приняты | Pinned eligibility registry, reviewed per-tuple classification и staged cutover; legacy opaque state запрещает TARGET projection |

TARGET никогда не пишет client_club_staff, client_club_grants, user_roles или
legacy invitation purpose/status; не включает AdminShell. Старые workflows/guards,
owner/team/preparation и platform identity остаются прежними. Existing adapter/V077
побайтно сохранены; extension выше проектируется отдельно до любого TARGET issue.
Privileged/manual SQL не объявляется защищённым application guards.

## 9. Acceptance, approval и следующий slice

[Generated synthetic report](../ops/role-target-identity-shadow-report.md),
[reference helper](../../scripts/tests/target_identity_reference.py),
[fixtures](../../scripts/tests/fixtures/role-target-identity-contract.json),
[tests](../../scripts/tests/test_role_target_identity_contract.py). Только artificial
values, stdlib HMAC и pure state transitions; нет DB/network/apply mode. Это не
проверка Kotlin verifier, Telegram runtime, SQL locks/migrations или UI.
В reference символические user IDs представляют trusted Telegram→local mapping,
policy hashes имеют namespace модели, не заменяют Kotlin PolicyReference bytes.
Финансовые селекторы проверяются только на искусственных shift IDs; operational
booking/finance contracts не считаются готовыми.
Все прежние69 paths, кроме README/checkpoint, должны сохранить reviewed hashes;
старые46+38 scenarios, helpers и fixture pins не редактируются.

Пять решений на отдельное approval (всё PROPOSED):

1. Ownership attestation trust root и confirmation только original issuer/source.
2. Signed identity 300с/0future, exact two-audience binding и MAC intent nonce≤120с.
3. TARGET invitation 7 суток, hash-only one-use secret, short claim refresh и explicit reissue.
4. Dedicated TARGET ledger/per-client seq, отдельные membership/club cutoffs и proof v2.
5. Compound atomic Confirm, current-only replay/eligibility и default-deny cutover gates.

**Один следующий implementation slice:** локальный read-only strict signed TARGET
identity/audience verifier для explicit canonical binding, с synthetic HMAC/TTL/
wrong-audience tests и default-deny ownership/admission; без issue/assign/routes,
изменений V077 или real materialization. Только после отдельного разрешения.
