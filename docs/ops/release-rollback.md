# Release rollback runbook

## Цель

Стандартизировать решение: делаем `forward-fix` или откатываемся (`rollback`/`PITR`) при проблемах после релиза.

Этот документ также фиксирует repository contract для управляемого quiesced release. Он не является
свидетельством того, что deploy, recovery, migration или staging operation фактически выполнялись.

## Durable release state

Каждый active release привязан к точным `owner`, full revision, immutable candidate digest и hash ожидаемой
Compose path. Authoritative state находится не в `/tmp`, а в environment-scoped
`COMPOSE_PATH/.clubs-bot-release-state/<stage|prod>`. Для `stage`/`prod` remote helper считает это durable boundary
только после mount-aware проверки exact canonical `COMPOSE_PATH` и каждого фактически существующего
authoritative subtree. Production detector выполняет для одного mount identity ровно один Linux-вызов
`findmnt --noheadings --pairs --output FSTYPE,SOURCE,FSROOT,TARGET --target <path>` и принимает только одну
bounded machine-readable запись с exact ordered fields `FSTYPE`, `SOURCE`, `FSROOT` и `TARGET`. Zero/multiple
records, missing/duplicate/extra/reordered fields, malformed pairs или escaping закрывают проверку fail-closed.
Approved mount fingerprint имеет явную версию `mount-v2`: raw value каждого из четырёх fields сначала отдельно
SHA-256-хешируется, затем fixed-label digests в fixed order вместе с protocol marker хешируются в итоговый
fingerprint. Эта encoding не имеет delimiter collision; incompatible old binding/fingerprint не
переинтерпретируется. Transient mount ID не является authority, а raw source/root/target не попадают в public
protocol.

Принимаются только `ext2`, `ext3`, `ext4`, `xfs`, `btrfs`, `zfs`, `f2fs`; executable production-helper suite
положительно проверяет каждый из этих семи filesystem types через тот же coherent record parser. `/tmp`,
`/var/tmp`, `/run`, `/var/run`,
`/dev/shm`, `tmpfs`, `ramfs`, `devtmpfs`, `overlay`, unknown/malformed detector output и symlink в любом component
path отклоняются до создания authority. Base `docker-compose.yml` должен быть regular non-symlink file. Runner
выполняет лишь bounded syntax/path precheck; authoritative remote mount decision всегда остаётся за helper.
Перед mutation и read-only status helper повторно доказывает, что shared root, environment root, active/finalizing
state, results, migration ledgers, terminal receipts/prune marker и active-candidate anchor находятся на том же
approved backing. Для ещё не созданного directory сначала проверяется nearest existing trusted parent, затем под
shared lock directory durably создаётся и немедленно проверяется до первой authority write. Любой nested mount под
state root — включая supported filesystem с другим mount identity — отклоняется; слово `persistent` в имени path
ничего не доказывает. Таким образом, каждый authoritative path должен оставаться на approved backing, чтобы
protocol мог заявлять переживание process restart, SSH disconnect и host reboot.
Maintenance/finalizing/result/migration-ledger directories имеют mode `0700`, а state, result,
outcome, ledger и completion records — `0600`.

Shared root содержит durable `application.binding` и cross-environment advisory lock. Binding version `3`
фиксирует mount fingerprint version `2`, один environment, Compose-path fingerprint, approved mount fingerprint
и exact Compose project/service identity; file fsync, atomic rename и parent fsync завершаются до создания
environment state. Поэтому один Compose application root нельзя
последовательно или одновременно независимо bind-ить к `stage` и `prod`; exact same binding принимается
идемпотентно, а malformed, wrong-mode, symlinked или cross-environment binding закрывает operation fail-closed.

Каждая authoritative file запись проходит полный durability boundary: temporary file в том же directory, полный
write, content `fsync`, atomic rename и `fsync` parent directory. Создание, rename и удаление authoritative directory
также завершаются sync его parent. Ошибка write/fsync/rename/sync не считается достигнутым checkpoint и оставляет
protocol fail-closed. В `/tmp` допустимы только disposable registry config, отфильтровываемый migration log,
same-process rollback scratch и owner-specific uploaded helper; их потеря не меняет release authority. Malformed/oversized record,
owner/revision/digest/path mismatch или неразрешённый transition также закрывают protocol fail-closed.

До публикации candidate override state хранит только bounded prior-state evidence:

- был ли managed persistent override и его exact allowlisted content/hash;
- digest/revision старого running app и hashes его container/image/start identity;
- restart count и ожидаемые Compose project/service.

Unmanaged, malformed, changed или ambiguous override/app identity не принимаются. State не содержит credentials,
tokens, environment dump, application payloads или raw command output.

### Canonical checkpoints

| Checkpoint | Доказанная граница |
|---|---|
| `maintenance_prepared` | Atomic maintenance state с exact release identity создан; application lifecycle ещё не изменён. |
| `prior_state_captured` | Exact trusted prior override и old running app/Compose identity зафиксированы. |
| `candidate_override_published` | Canonical persistent override атомарно публикует ожидаемые digest/revision и проверен Compose config; old app ещё не должен быть изменён. |
| `app_stop_intent` | До первой app lifecycle command записано намерение stop/remove; это не доказательство остановки. |
| `app_quiesced` | Exact prior app отсутствует в ожидаемой Compose project/service boundary. Abort с этого момента запрещён. |
| `migration_started` | До единственного migration invocation записан durable environment/owner/revision/digest/invocation ledger. Любая неопределённость здесь не разрешает второй invocation. |
| `migration_completed` | После успешного canonical migration protocol durable success outcome и terminal migration ledger записаны до checkpoint, container removal или candidate start; migration digest/image совпадают с candidate и app всё ещё отсутствует. |
| `candidate_start_begun` | До единственной candidate start lifecycle command записан durable intent. Resume не повторяет start, а только проверяет already-running exact candidate. |
| `candidate_healthy` | Exact candidate digest/image/revision запущен, `/ready` и `/health` успешны. |
| `cleanup_started` | До finalization и allowlisted state removal записан durable cleanup intent. |
| `cleanup_completed` | Terminal release completion record и shared active-candidate anchor durable до удаления allowlisted active state; candidate override и healthy candidate сохранены. |
| `abort_started` | Все pre-quiesce abort guards доказаны до restore/cleanup; old app всё ещё exact и running. |
| `abort_completed` | Terminal abort completion record записан до allowlisted cleanup; trusted prior override восстановлен, old app не изменялся. |

Canonical normal transition:

`maintenance_prepared → prior_state_captured → candidate_override_published → app_stop_intent → app_quiesced → migration_started → migration_completed → candidate_start_begun → candidate_healthy → cleanup_started → cleanup_completed`.

Abort branch разрешён только из `maintenance_prepared`, `prior_state_captured`, `candidate_override_published` или
`app_stop_intent`: `… → abort_started → abort_completed`. Terminal completion record с exact identity делает repeated
cleanup после `cleanup_completed` и repeated abort после `abort_completed` явным `already_clean` no-op; unrelated
files не изменяются. Abort против нормально завершённого `cleanup_completed` release отклоняется как post-quiesce.

## Operation result и read-only status

Каждая mutating remote operation до начала записывает atomic bounded result с `owner`, requested operation,
checkpoint before, ожидаемыми revision/digest/path hash и `incomplete_unknown`. Trap по возможности
заменяет его на `success`, `remote_failure` или сохраняет `incomplete_unknown`, добавляя checkpoint after и
bounded failure category. Remote child exit `1` и remote child exit `255` поэтому отличимы от SSH loss, если
result record успел сохраниться. Если record не был записан, protocol не приписывает transport или remote
child недоказанную причину. Валидная запись предыдущей operation при запросе status для ещё не запущенной
operation означает `unavailable`, а не `malformed`; структурно или identity-некорректная запись остаётся
`malformed`. Raw stdout/stderr и secrets в records не попадают.

Canonical `status` — единственная read-only reconciliation operation. До чтения records она read-only проверяет
canonical Compose/root chain, actual mount backing каждого существующего authoritative subtree, approved mount
fingerprint, owner/mode/type/link guards, shared binding и оба lock boundary.
Она может читать только доказанный guarded state и делать
read-only Docker/Compose inspection, но не выполняет persistent filesystem writes и не вызывает lifecycle command.
Executable tests доказывают это для trusted и untrusted roots сравнением no-follow before/after tree snapshots
(paths, types, device/inode/link metadata, modes, sizes, mtimes, content hashes и symlink targets) и отдельным
zero-count audit для `mkdir`, create/open-for-write, `chmod`, rename, unlink/rmdir, `fsync`, truncate и prune.
Ответ — ровно одна
allowlisted line, а `operation_result` относится к явно запрошенной operation:

```text
release-status:v=1 status_available=<yes|no> owner_match=<yes|no> revision_match=<yes|no> digest_match=<yes|no> checkpoint=<none|maintenance_prepared|prior_state_captured|candidate_override_published|app_stop_intent|app_quiesced|migration_started|migration_completed|candidate_start_begun|candidate_healthy|cleanup_started|cleanup_completed|abort_started|abort_completed|unavailable> operation_result=<success|remote_failure|incomplete_unknown|unavailable|malformed> migration_evidence=<present|absent|unknown|migration_outcome_requires_incident_reconciliation> app_state=<old_running|absent|candidate_running|replaced|ambiguous|unknown> abort_permitted=<yes|no> resume_permitted=<yes|no> failure_category=<none|untrusted_state_root>
```

Status не раскрывает paths, host/SSH target, container/image IDs, credentials, application data или logs. `abort_permitted`
и `resume_permitted` могут быть `yes` только при exact identity match, stable operation lock и непротиворечивых
checkpoint/override/app/migration evidence. Отсутствующий или malformed result directory/operation lock не
восстанавливается status-командой и даёт только fail-closed `unavailable/unknown` с обоими permissions=`no`.
Недоверенная root chain всегда даёт `status_available=no`, `failure_category=untrusted_state_root` и оба
permissions=`no`, не раскрывая path/stat/mount details и оставляя authoritative filesystem tree byte-for-byte и
metadata-for-metadata неизменным.
`unknown`, `malformed` или identity mismatch (owner/revision/digest/path) никогда не разрешают mutation; в частности
malformed operation result всегда принудительно выставляет оба permissions=`no`.

### Deployment-principal read-only status channel

`.github/workflows/release-status.yml` — отдельный manual-only канал наблюдения, а не deploy/recovery workflow.
Диагностический observer principal отличается от deployment SSH principal: канал работает через deployment
principal выбранного protected GitHub environment (`stage` или `prod`). Серверное ownership изменять не надо;
owner retained helper не сравнивается с предполагаемым observer owner, и канал не выполняет ownership repair.

Dispatch допускается только вручную с `main` и только после отдельного явного разрешения пользователя. Первый,
непривилегированный job проверяет `refs/heads/main`, branch ref и default branch `main`, валидирует все inputs и
передаёт только sanitized outputs. Привилегированный status job получает environment исключительно из этого
результата. Общий non-cancelling lock `payments-schema-${{ inputs.environment }}` сериализует status со штатным
deploy для того же environment.

Implementation и incident проверяются независимо. Credential-free implementation checkout берёт
`refs/heads/main`, и его HEAD обязан совпасть с `GITHUB_SHA`. Второй credential-free checkout берёт sanitized exact
incident tag; его HEAD обязан совпасть с validated expected revision. После этой проверки incident checkout
используется только как Git object database: `git ls-tree --full-tree -z` для exact expected revision
должен вернуть ровно один exact path `scripts/deploy/remote-compose-release.sh` с type `blob`, mode `100644`
или `100755` и full object ID. SHA-256 вычисляется только по raw bytes этого object через
`git cat-file blob`; filesystem path, symlink любого ancestor component и implementation checkout не являются
hash authority и не могут подменить incident helper. Код и blob bytes из incident checkout не исполняются.

До будущего dispatch в каждом protected environment должен быть отдельно provisioned секрет
`SSH_KNOWN_HOSTS` с заранее закреплёнными `known_hosts` entries. Repository не утверждает, что секрет уже настроен.
Live `ssh-keyscan`, DNS-derived host trust и альтернативные known-host sources запрещены. Status job напрямую и
без условия передаёт `TMPDIR` и `RUNNER_TEMP` из GitHub `runner.temp`; runner выбирает непустой `TMPDIR`, затем
`RUNNER_TEMP`, а без них fail closed до SSH и никогда не использует shared `/tmp`. Уже существующий canonical
runner-owned private root открывается один раз с no-follow semantics и закрепляется descriptor-ом; дочерняя run
directory не создаётся и не удаляется. Fixed mode-`0600` captures эксклюзивно создаются относительно anchored root,
сразу теряют pathname и дальше существуют только как проверенные descriptors. `SSH_KNOWN_HOSTS` передаётся OpenSSH
только через retained descriptor с `StrictHostKeyChecking=yes` и `GlobalKnownHostsFile=/dev/null`; stdout/stderr
также читаются и стираются только через retained descriptors. Supervisor передаёт только первый HUP/INT/TERM,
cleanup bounded обнуляет и закрывает anonymous objects и не смотрит на replacement или neighboring paths.
Пустой environment secret `SSH_PORT` нормализуется workflow к literal `22`, как в штатном deploy; runner всё равно
принимает только явный numeric nonzero port.

После проверок выполняется ровно один SSH call без retry и ровно один retained-helper mode — literal `status`.
Helper не загружается и не заменяется. После открытия helper path повторно проверяется как regular non-symlink с
link count `1`; opened-object mode не может содержать setuid/setgid/sticky, group-write или other-write bits, но
безопасные uploader outcomes `0600`, `0644`, `0700` и repository source mode принимаются без owner coupling.
Opened helper читается ровно один раз в bounded, non-exported process-local base64 snapshot, после чего live fd
закрывается. Decoded size и SHA-256 проверяются по этому snapshot, и те же captured bytes подаются в
`bash -s -- status ...`; последующие path replacement, same-inode overwrite, append или truncate не меняют execution.
Нет deploy, `prepare`, `publish`, `quiesce`, `migrate`,
`start`, `cleanup`, `abort`, resume, retention, helper cleanup, registry login или image pull.

Успешный transport принимается только как весь bounded byte stream: одна printable-ASCII
`release-status:v=1` line с единственным terminal LF. Trusted channel требует одновременно
`status_available=yes`, `owner_match=yes`, `revision_match=yes` и `digest_match=yes`; `resume_permitted` не меняет
trust канала. Trusted status — только evidence, не разрешение recovery. Canonical untrusted status печатается в
безопасной форме и завершает job non-zero; malformed status не отражается в output. Любой transport failure также
fail closed: official-looking stdout подавляется, raw stderr не печатается, и наружу выходит только fixed normalized
category. Raw stdout/stderr captures и credentials не сохраняются.

Фактические repository, merge и dispatch состояния устанавливаются по Git/GitHub evidence в момент операции и
фиксируются в project journal; этот durable runbook описывает только protocol contract, а не rollout state.
Наличие channel в repository не свидетельствует о dispatch или чтении stage. Каждый dispatch требует отдельного
явного разрешения и не разрешает retry, recovery, lifecycle operation или ownership change; trusted status остаётся
только evidence и сам по себе не разрешает resume или recovery. Raw evidence не retained.

## Runner classification и no-retry rule

Runner вызывает каждую mutating SSH operation не более одного раза и никогда не retry-ит её автоматически.
Exact successful acknowledgement при SSH exit `0` — confirmed remote success. Non-`255` failure — confirmed remote failure. SSH exit
`255`, missing/malformed acknowledgement или acknowledgement loss считаются ambiguous и разрешают не более одного
automatic read-only `status` query.

Семантические classifications:

- confirmed remote success — exit `0` и exact acknowledgement; только здесь runner переходит к следующей phase;
- `confirmed_remote_failure` — durable result доказывает remote failure;
- `completed_but_acknowledgement_lost` — durable result доказывает success, но runner не получил exact acknowledgement;
- `transport_loss_with_durable_checkpoint` — result текущей operation остался incomplete/unknown либо ещё не был
  записан, но guarded checkpoint читается;
- `status_unavailable` — status недоступен, malformed, identity-mismatched или не содержит durable checkpoint.

После любой non-success/ambiguous classification runner печатает один bounded outcome и одну redacted recovery
instruction (`explicit-abort`,
`explicit-resume-quiesce`, `explicit-resume-migrate`, `explicit-resume-start`, `explicit-resume-cleanup` или
`manual-investigation`) и останавливается fail-closed. Runner не запускает abort/resume, не продолжает следующую
release phase и не печатает raw SSH stderr.

## Explicit abort

`abort` — отдельное operator action, а не workflow fallback. Он разрешён только если одновременно доказаны:

- exact owner/revision/digest/path identity;
- checkpoint из `maintenance_prepared`, `prior_state_captured`, `candidate_override_published`, `app_stop_intent`
  или guarded continuation из `abort_started`;
- отсутствие migration digest/image/log и migration container evidence;
- тот же old app всё ещё running с теми же container/image/digest/revision/start/restart/project/service признаками;
- persistent override в точности равен либо captured trusted prior override, либо exact candidate override,
  который можно атомарно заменить captured prior state.

Abort атомарно восстанавливает prior managed override или exact отсутствие override, если его не было,
и удаляет только explicit allowlist release-state files. Он
не вызывает Docker/Compose lifecycle, SQL или migration, не пересоздаёт app и не трогает unrelated files. Abort
запрещён при `app_quiesced` и любом более позднем checkpoint, при absent/replaced/ambiguous/changed app,
при migration evidence или недоверенном/changed override. Повтор после terminal `abort_completed` возвращает explicit
`already_clean` без side effect; `cleanup_completed` нормального release не является abortable состоянием.

## Explicit target-specific resume

`resume` — тоже только operator action. Он всегда требует exact owner/revision/digest/path identity, guarded override,
checkpoint и app/migration evidence и точно одну target phase:

| Target | Допустимый checkpoint/state | Поведение |
|---|---|---|
| `resume quiesce` | `candidate_override_published` или `app_stop_intent`; также `prior_state_captured`, только если exact candidate override уже опубликован, effective Compose `config --images` повторно доказывает единственный expected digest, а checkpoint acknowledgement был потерян; app — exact unchanged old app или already absent | Если old app running, один guarded stop/remove; если exact app already absent, только reconciliation до `app_quiesced`. Replaced/ambiguous identity rejected. |
| `resume migrate` | `app_quiesced`; app absent; exact candidate override; migration evidence absent | Один migration invocation под existing verified-image guards. `migration_started` нельзя resume-ить или retry-ить. |
| `resume start` | `migration_completed` + app absent, либо `candidate_start_begun` + exact candidate already running | Из `migration_completed` один guarded start; из `candidate_start_begun` только identity/readiness/health probe, без второго start. |
| `resume cleanup` | `candidate_healthy` или `cleanup_started`; exact candidate running и healthy | Re-probe, terminal record и allowlisted cleanup; completed state — idempotent no-op. |

Если exact `migration_completed` уже записан, повторная migration-target verification может быть только no-op:
она сверяет completion evidence и app absence, но не запускает migration второй раз. Canonical status для этого
checkpoint указывает `resume start`, а не `resume migrate`.

После reboot/re-entry durable `migration_started` без valid completed ledger всегда даёт
`migration_outcome_requires_incident_reconciliation`: migration и candidate start запрещены. Если success outcome и
completed ledger durable, но active checkpoint acknowledgement потерян, exact same owner/revision/digest может через
explicit `resume start` сначала reconcile checkpoint и затем выполнить guarded start. Successful prior release с теми
же revision/digest нельзя запустить как новый ordinary full release: terminal ledger возвращает `already_released` и
не позволяет второй migration invocation. Completed historical ledger другой candidate не блокирует нормальный future
candidate; unresolved `started` ledger блокирует, потому что состояние schema ещё не доказано.

Ни один resume target не выбирается автоматически из SSH failure. Wrong target/checkpoint, owner/revision/digest mismatch,
changed override, replaced/ambiguous app или inconsistent migration evidence оставляют maintenance fail-closed.

## Post-quiesce и post-migration boundary

С `app_quiesced` abort и запуск old image запрещены. `migration_started` — ещё более строгая terminal/fail-closed
граница для migration invocation: ни transport loss, ни remote failure, ни incomplete result не разрешают
автоматический или operator `resume migrate`. Сначала нужно вне repository workflow установить outcome Flyway
transaction, schema history и migration-container evidence; protocol не обещает safe second migration invocation.

После `migration_completed` разрешены только exact migration-correlated candidate start/probe и cleanup. Ни
saved "last working" image, ни captured prior override не являются authority для container rollback. Start/readiness/health
failure сохраняет maintenance и требует schema-compatible forward-fix или incident escalation.

`migration_started` и `migration_completed` authority не передаётся новому owner автоматически. Ordinary workflow
rerun — включая rerun с новым forward candidate — заблокирован до отдельной incident/adoption procedure. Эта
operator procedure не реализована как automatic workflow operation и обязана вне обычного deploy: доказать
schema/Flyway outcome, сверить owner/revision/digest и durable ledger, явно принять или передать release authority и
выбрать либо разрешённый guarded resume, либо schema-compatible forward candidate. Новый ordinary release допустим
только после документированного завершения этой incident procedure. До этого нет migration retry, candidate auto-start,
old-image rollback или silent owner replacement.

## Bounded retention

Guarded retention сохраняет terminal artifacts минимум `30` дней и всегда сохраняет `32` новейших terminal receipt
bundles. Pruning запускается только под shared operation lock и удаляет лишь exact canonical owner filenames. Он никогда
не удаляет current/active owner, incomplete result, non-terminal state или unresolved `migration_started`.

После durable migration completion, candidate start/health и terminal release receipt protocol атомарно публикует
shared mode-`0600` active-candidate anchor. Он связывает environment binding, candidate revision/digest, canonical
migration-ledger key/fingerprint и terminal receipt key и переживает normal active-state cleanup/reboot. Перед pruning
protocol сверяет anchor с binding, managed persistent override и текущим Compose app, когда он доступен. Ledger и
receipt, на которые указывает anchor, не удаляются независимо от wall clock, timestamp ties, clock rollback или
количества более новых bundles. Новый healthy release заменяет anchor только после своего durable terminal receipt;
лишь после этого ledger прежнего active candidate может стать eligible по одновременным age/count filters. Malformed,
symlinked или runtime-disagreeing anchor блокирует pruning. Same revision/digest ordinary rerun сверяется с anchor и
не может повторить migration.

Canonical symlink, malformed record или invalid owner/path/type останавливает pruning; unrelated files не затрагиваются,
recursive/broad cleanup не используется. Перед terminal bundle deletion durable mode-`0600` prune marker фиксирует
exact owner; marker удаляется последним, поэтому прерванный pruning безопасно и идемпотентно завершается при следующем
guarded запуске, а повтор после completion является no-op. Current uploaded helper удаляется отдельной
`helper-cleanup` operation только после confirmed successful cleanup acknowledgement. При acknowledgement loss helper
может остаться до следующего guarded pruning; authoritative state и migration ledger от helper не зависят.

Этот operational protocol не отменяет и не обходит mandatory quality gates, включая `Payment hardening required runtime`.

## Decision tree

1. **Проверка готовности релиза**
   - `/ready` = `200` → переходим к функциональным smoke-проверкам.
   - `/ready` != `200` > 60 секунд → релиз считается неуспешным.
2. **Что доказывает canonical status?**
   - Exact old app всё ещё running и migration evidence absent → operator может выбрать только явно разрешённый status-ом guarded abort или target-specific resume; workflow ни один из них не запускает.
   - `app_quiesced` или более поздний checkpoint → pre-migration image запускать запрещено; только разрешённый target-specific resume либо incident/adoption procedure перед schema-compatible forward candidate.
   - `migration_started`, unknown/malformed/mismatched status или inconsistent app state → manual investigation; нет retry, abort, old-image start или автоматического продолжения.
3. **Новый image не проходит readiness/health?**
   - Ordinary full-release rerun заблокирован durable authority. Сначала выполняем описанную выше incident/adoption procedure; только после её завершения допускается guarded resume либо новый schema-compatible forward candidate. Автоматического rollback или authority adoption нет.
   - Если безопасный forward-fix невозможен, app остаётся остановленным либо на новом image и инцидент эскалируется.
4. **Затронуты данные (потеря/коррупция/необратимые миграции)?**
   - Нет → готовим `forward-fix`, но новый релиз выполняем только после завершённой incident/adoption procedure и явной release-authority проверки.
   - Да → запускаем процедуру `PITR` по `docs/dr.md`.

## Когда выбирать forward-fix

Выбираем `forward-fix`, если одновременно верно:
- schema-compatible image с исправлением готов и проходит локальные smoke-проверки;
- схема БД консистентна, необратимого изменения данных нет;
- есть быстрый, проверяемый фикс < 30 минут.

## Когда выбирать PITR

Выбираем PITR, если есть хотя бы один пункт:
- необратимая ошибка миграции/данных;
- массовая порча критичных таблиц;
- schema-compatible forward-fix не может безопасно восстановить сервис и требуется восстановление данных.

## Минимальный протокол восстановления

1. Зафиксировать время инцидента и tag неуспешного релиза.
2. Зафиксировать применённую Flyway version, verified image digest/revision и сохранённые в maintenance lock `migration_image_digest`/`migration_image_id`. Они должны совпадать с final app image reference/ID.
3. Не удалять stale remote maintenance lock без проверки owner/checkpoint, состояния migration/app containers и schema history. При `migration_started` сначала установить, завершилась ли Flyway transaction; repository resume/retry/cleanup до этой ручной оценки запрещены, а второй migration invocation не разрешается самим stale state.
4. Проверить, что `docker-compose.override.yml` остаётся managed-файлом и его digest совпадает с
   `image_digest` текущего maintenance lock, а revision — с `expected_revision`. До этой проверки не запускать
   ни base Compose, ни сохранённый «последний рабочий» image: после V056 он может быть schema-incompatible.
5. После forward-fix подтвердить `/ready` и `/health`, сохранить отдельно собранные incident application logs и реконструированные canonical `migration-safe:v=1` events вместе со schema-history evidence. Unknown/malformed/duplicate/out-of-order raw output является protocol failure и не разрешает выход из maintenance. Repository protocol не пересылает в CI полный migration-container output и не сохраняет его как durable release evidence: parser читает его только из mode `0600` temporary file и удаляет этот файл на success/failure/trap paths. Raw Flyway/JDBC/exception logging не включать в bounded result/status records.
6. Открыть postmortem-задачу с причиной и корректирующими действиями.
