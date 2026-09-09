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

### Corrected stage incident executor (CLB-82)

**Локальный bound-state кандидат CLB-82.** Последняя независимая CLB-83 закрыла оба import findings
и согласовала additive helper contract для оставшегося root/parent finding. Пользователь разрешил его
локальную реализацию; это не final acceptance, публикация или разрешение исполнения. Обязательная root-регрессия
остаётся обычным delivery gate, без skip/xfail. Старые counterexamples и результаты review сохранены в checkpoint.

`.github/workflows/corrected-stage-release.yml` / `scripts/deploy/corrected-stage-release.py` — отдельный
stage-only manual executor для incident owner `33468965282-1`. Наличие файлов и fixture PASS не означает
публикацию workflow, dispatch, восстановление stage или разрешение на исполнение. Применяется ordered gate
protocol из [PRODUCT_ROADMAP.md](../product/PRODUCT_ROADMAP.md#first-slice-execution-gate-protocol).

**Предшествующий gate остаётся отдельным:** сначала independent provider/VNC authentication exact host key,
проверка fingerprint, live `DEC-037` protection/main-only policy и отсутствия overlap repository/stage secret names,
provisioning/verification pinned stage `SSH_KNOWN_HOSTS`, отдельное разрешение и один исходный
`Release Status (read-only)` dispatch, затем проверка его полного результата под deployment principal.
Исходный status проверяет retained incident helper SHA-256
`8d8321d325d6ca25f48bcfdd7d9fb0eeb6f80af9c26f136ea06953cf1c2b914e`, Git blob
`430595929a09566ff29ad6fe58bd19fa4f0c7ca4`; expected hash этого канала не заменяется новым.
Corrected inspect не закрывает этот gate задним числом. Checkbox, строка authorization или переданный run ID
не доказывают содержимое status result. Executor принимает обязательный reference `prior_status=run_id:attempt:head_sha` и сам проверяет
исходный результат через GitHub до corrected execution. Reference не является доказательством без успешной
проверки источника. Остальные live trust gates и отдельную execution authority проверяет оператор до dispatch. Root observer и наблюдение UID 1000
не являются доказательством identity deployment principal.

Workflow принимает девять inputs: `environment` (только `stage`), `incident_tag`, `release_owner`,
`expected_revision`, `image_digest`, `implementation` (`revision:blob:sha256`), `action`, `authorization` и `prior_status`.
Incident inputs должны точно совпасть с constants runner; implementation — с текущими bytes и отдельно принятой
protected configuration. Default action — `inspect`,
authorization пустая. Только `workflow_dispatch`, `refs/heads/main`, branch ref/default branch `main` и
`koteev-m/clubs_bot`; workflow baseline — `contents: read`; двум jobs corrected workflow добавлено только
`actions: read` для чтения original evidence штатным `GITHUB_TOKEN`. До SSH credentials validation job исполняет
`python3 -I -S -B scripts/deploy/corrected-stage-release.py --validate` (offline syntax, результат `SYNTAX_VALIDATED`),
затем `--verify-prior` (GitHub evidence, результат `PRIOR_STATUS_VERIFIED`). Outputs сами не заменяют evidence. Protected
`stage` job выполняет `--validate-execution` до SSH-agent step (включая rerun только failed job), затем исполняет
`python3 -I -S -B scripts/deploy/corrected-stage-release.py` с этими outputs, штатными stage SSH credentials и `runner.temp`. Общая concurrency boundary — `payments-schema-stage`, `cancel-in-progress=false`.
Inputs передаются только через environment, не как executable shell. Checkout закреплён на `github.sha`.

Incident tuple остаётся `deploy-stage-44497dc` / `44497dcd28139cef865c3f98ac3f2c4a5afac636` /
`ghcr.io/koteev-m/clubs_bot/app-bot@sha256:ddf5486e02835855178cc3b30bd2f22899335131e6dc388def20feac328016fe`,
Compose `/opt/clubs-bot-stage`, project/service `clubs-bot-stage/app`. Новые implementation bytes отдельно:
path `scripts/deploy/remote-compose-release.sh`, type `blob`, Git mode `100644`, size `174422`,
blob `fc09080ba4864133ca23ec5c777339b881094279`, SHA-256 `48bcbafde22b90dc3902cac0ba80754239964466ce612d11565dd1fdeb75e2ec`.
Прежние blob `d4741ff04e0ffc578157c607b885333464c251d5` / SHA-256 `5503333309d30a4401f8712b259b31cc4085b6793c8ac418453f23bff16188b1`
описывают только implementation ДО bound-state дополнения. Нового production commit SHA пока нет.
Protected stage variable `CLB82_APPROVED_IMPLEMENTATION` должна отдельно связать новые bytes с принятым реальным
`revision:blob:sha256`; dispatch input обязан точно совпасть. Default пуст, fallback на working file/старый hash нет.
Локальные end-to-end tests используют отдельные synthetic Git objects, не index/refs проекта.
Runner проверяет commit type, единственный exact tree path `scripts/deploy/remote-compose-release.sh`, mode/type/blob,
size и оба object/content digests. Git replace objects отключены; checkout paths, filters, URL или input script body
не используются. Единственный immutable process-local `bytes` snapshot подаётся через SSH stdin во все вызовы.
Retained `/tmp/clubs-bot-release-33468965282-1.sh` не открывается, не заменяется и не удаляется этим каналом.

SSH использует только protected deployment principal (`SSH_USER` + agent `SSH_PRIVATE_KEY`), без fallback к
`root`/`hookah-staging`. Remote wrapper проверяет actual `id -un` против выбранного principal и отвергает UID 0;
сам helper проверяет canonical root, binding, filesystem, ownership/type/mode/path под actual UID. UID не закреплён
на 1000. Только pinned `SSH_KNOWN_HOSTS`, strict checking, отключённые global/DNS/command/proxy trust sources;
нет keyscan/accept-new. Pin — anonymous mode-0600 descriptor в validated canonical runner-owned root;
cleanup обнуляет/закрывает только свой descriptor. Stdout ограничен памятью, stderr отбрасывается; raw captures
и secret artifacts не создаются. Shared private-root validator и status grammar повторно используются старым
read-only каналом; его incident execution bytes и CLI не меняются.

`inspect` вызывает только corrected status для `start` и никогда не переходит к mutation. `resume-start` требует
отдельной exact-action authorization для всех указанных identities, Compose path, SHA-256 canonical authorized root binding и **одного workflow run number**.
Формат — одна строка с literal `|` separators, без пробелов/переносов:

```text
authorize-resume-start|stage|deploy-stage-44497dc|33468965282-1|44497dcd28139cef865c3f98ac3f2c4a5afac636|ghcr.io/koteev-m/clubs_bot/app-bot@sha256:ddf5486e02835855178cc3b30bd2f22899335131e6dc388def20feac328016fe|/opt/clubs-bot-stage|<approved revision:blob:sha256>|<authorized binding SHA-256>|<authorized workflow run_number>
```

Это несекретный текст отдельного разрешения, не credential и не runtime evidence. Оператор заранее включает
выбранный ожидаемый `run_number` в разрешение; если номер занял иной dispatch, автоматическая подстановка нового
номера запрещена. Новый run number требует нового решения; `GITHUB_RUN_ATTEMPT != 1` блокирует mutation до secrets.
Новый run ID и rerun не возобновляют старое разрешение. Не dispatch/rerun для «проверки» неизвестного результата.

Перед mutation требуется свежий trusted corrected status: exact owner/revision/digest, `migration_completed`,
`migration_evidence=present`, `app_state=absent`, `resume_permitted=yes`, `abort_permitted=no` и
`operation_result=remote_failure` именно для прежнего `start`. Busy, malformed, unknown, unavailable, identity drift
или ранее записанный `resume-start` блокируют submission. Сам helper повторяет guards под существующими locks.
Additive helper argv (документация интерфейса, не разрешение запуска):

```text
bash --noprofile --norc -s -- corrected-start OWNER stage COMPOSE REVISION IMAGE PHASE
PHASE = inspect | claim | resume-start | reconcile
```

Runner передаёт один captured helper snapshot и bounded JSON control по SSH stdin в isolated Python bootstrap;
bootstrap проверяет size/blob/SHA и передаёт control через private data FD 4. В argv, logs/artifacts нет token.
Control содержит implementation, fixed binding, principal, hash authorization и свежий случайный token.
Caller не передаёт lock/root descriptors или lock-held bypass. Legacy `status`, `resume`, release и остальные CLI
сохраняются; новый path не вызывает их рекурсивно. Inspect/reconcile возвращают canonical status для `start` /
`resume-start` соответственно. Inspect дополнительно выдаёт bounded `corrected-binding-candidate:v=1`.
В публичном candidate principal представлен `name_sha256` и UID: значение защищённого `SSH_USER` не выводится.
Этот redacted candidate не имеет формата принятого pin. При отдельном решении имя principal берётся из
доверенной protected configuration и сверяется с hash; runner не восстанавливает его и не принимает observation автоматически.

До mutation атомарно расходуется authorization по отдельному persistent protocol ниже. Только подтверждённый
claim допускает единственную submission, без retry. После submission выполняется ровно одна bounded read-only
reconciliation тем же snapshot для **resume-start**, в том числе при timeout, SSH 255 и malformed/lost acknowledgement.
Connect timeout — 15 s, connection attempts — 1; inspect/claim/reconcile wall timeout — 90 s, resume wall timeout — 180 s.
Успех требует trusted `candidate_healthy`, exact `candidate_running`, migration evidence, согласованных
`resume_permitted=yes` / `abort_permitted=no` и durable `operation_result=success`; success record пишет helper после проверки candidate и обоих `/ready`/`/health`.
`SUCCESS` означает также exact acknowledgement; `COMPLETED_ACK_LOST` — подтверждённый durable success при потере
acknowledgement. Неподтверждённый/partial результат сохраняется и требует отдельного решения, без второго start.
`candidate_start_begun` и уже работающий candidate не допускаются pre-gate этого executor. Отказ pre-gate после
старого `resume-start` также требует отдельного решения, а не повторного запуска. Нет migration, нового candidate,
rollback, cleanup/retention или удаления retained migration container/ledger/outcome. При убийстве всего runner
до reconciliation результат остаётся unknown: дальнейшая read-only reconciliation требует отдельного scope.

Логи содержат bounded canonical status, fixed result category и несекретную provenance-запись incident/implementation,
action, run ID/number/attempt и hash выбранного principal. Provenance перед SSH — запись намерения; только
успешный transport с remote principal guard и trusted helper status подтверждает чтение actual account.
Historical CLB-81 compatibility evidence хранится отдельно в [PROJECT_STATUS.md](PROJECT_STATUS.md).

**Prior-status authority (F1).** `scripts/deploy/release_authority.py` содержит проверку prior status и bounded
producer provenance v2. Прежний standalone Python claim удалён; расход разрешения принадлежит новому helper path.

F1: исходный protected status job с `STATUS_PROVENANCE=yes` выдаёт одну строку
`release-status-evidence:v=2 {…}` после проверки полного SSH stream старым runner. В ней только canonical trusted
status, incident tuple, requested operation, incident helper hash, GitHub repository/workflow/ref/event/run/attempt/
executed revision и hashes principal/Compose path. Raw captures, SSH credentials и произвольные логи не публикуются.
Server-side канал по-прежнему read-only и исполняет retained incident bytes. Failure transport, malformed status
или cleanup failure не могут стать успешным verified evidence; общий job должен завершиться успешно.

Consumer самостоятельно использует `gh api --hostname github.com --method GET` с fixed repository paths:
workflow identity, конкретный run attempt, его полный список двух jobs и logs именно его status job. Проверяются
run/attempt/job/step success и связи с `head_sha`, `main`, `workflow_dispatch`, stage и точным incident/start.
Через Contents API на exact `head_sha` сверяются bytes producer workflow, старого runner и трёх его dependencies
с доверенным checkout consumer. Старый код без provenance или иная producer revision с отличающимися bytes не
принимаются. Job log ограничен 1 MiB в памяти; принимается ровно одна bounded evidence line, остальной log не
выводится и не сохраняется. Полный canonical result обязателен: одних conclusion или произвольного JSON недостаточно.
`resume_permitted=no` старого selector принимается; corrected readiness и explicit authorization остаются отдельными
gates. Основной CLI заново проверяет evidence, включая совпадение principal hash, перед любым SSH, поэтому обход
workflow validation не открывает executor. Отсутствие/удаление logs, API failure, oversized/malformed или смешанные
attempts блокируют execution. Child environment не наследует debug/credentials settings; `gh` update notifier и
telemetry явно отключены. Отдельных artifacts, PAT, write permissions или нового сервиса нет.

REST interfaces: [run attempt](https://docs.github.com/en/rest/actions/workflow-runs#get-a-workflow-run-attempt),
[attempt jobs и job logs](https://docs.github.com/en/rest/actions/workflow-jobs),
[repository contents](https://docs.github.com/en/rest/repos/contents#get-repository-content).

**Fixed binding и authoritative consumed-state (F2/root).** `CLB82_AUTHORIZED_ROOT_BINDING` — несекретный
canonical JSON pin из protected stage configuration, вне server state tree; workflow-dispatch override отсутствует.
Он связывает exact incident, новую implementation identity, principal name/UID, Compose path/project/service,
mount-v2 persistent backing, device/inode всей canonical parent chain, Compose directory, protocol parent/root,
state/results/ledger directories и обоих существующих lock files. Также фиксируются hashes проверенных config bytes
и разрешённой Compose model. Mutable checkpoint/result inodes не фиксируются: штатные atomic replacements разрешены.
Missing/malformed/mismatch pin блокирует claim/resume/reconcile. Inspect может без pin показать candidate evidence,
но не принимает его как authority и ничего не provision-ит. Получение, review и provision реального pin — отдельные
будущие действия; новая папка/run ID/status/attempt не обновляют его. Сам pin не разрешает исполнение.

Каждая фаза helper сама открывает context no-follow относительно удерживаемых parent FDs, проверяет fstat,
UID/type/mode/persistent backing и pin, затем берёт application lock → operation lock в прежнем порядке
(shared для inspect/reconcile, exclusive для claim/resume, nonblocking). Удерживаются дочерние directories и
lock objects, не только root. Проверяются существующий application.binding, exact state, correlated completed
ledger/succeeded outcome и retained successful oneoff; отсутствующий storage не bootstrap-ится.

| Transitive access | Backend нового пути |
|---|---|
| Canonical chain, protocol directories, lock files | No-follow `open(..., dir_fd=parent)`, fstat/pin; retained FDs, проверка parent edges до/после разрешающих операций |
| Binding/state/ledger/outcome/result | Ограниченное чтение проверенных regular mode-0600 files относительно соответствующего retained directory FD; прежние schemas |
| Checkpoint/result и EXIT/signal finalization | Собственный temp, полный write, file fsync, descriptor-relative rename, parent fsync; тот же context даже после rename публичного root |
| Consumed record | Фиксированное имя в pinned root FD; exclusive/no-follow mode-0600 creation, полный write и file/parent fsync; никогда не удаляется |
| Compose YAML, release/public override, `.env` | Checked descriptor reads, captures, content hashes; explicit project name/directory/env-file; rendered model передаётся через private FD |
| Docker client configuration | Пустой retained anonymous directory FD; без ambient context/credentials helpers; локальный Docker socket и установленные system CLI/plugins |
| App lifecycle/identity/health | Existing helper functions через внутренний private RPC; state/config берутся из bound backend; только app `up --no-deps --pull never` |

Compose config сначала проверяется на поддерживаемый plain YAML subset: file-expanding `include`, `extends`,
`env_file`, `label_file`, build/configs/secrets, anchors/tags и неоднозначные mappings отвергаются до Compose reads.
Реально используемая `.env` захватывается отдельно; запрещены её COMPOSE_/DOCKER_ overrides. Compose выполняет
interpolation и resolution относительных путей при explicit canonical project directory. Его JSON model уже
экранирует literal dollars для повторного чтения; helper не экранирует их второй раз. App bind mounts/внешние
configuration sources запрещены. Relative Caddy bind path сохраняет смысл, но этот path не читает и Caddy не запускает.
Этот incident-specific subset не является обещанием поддержки произвольного Compose project.
Linux использует sealed memfd captures и `/proc/PID/fd`; файлы/секреты не публикуются. System Python/stdlib,
Bash, Docker CLI/Compose plugin и kernel остаются runtime trust base. Версии и поддержка FD captures требуют
проверки на целевом Linux; локальный macOS fixture адаптирует повторное чтение `/dev/fd` на fake Docker boundary,
сохраняя captured bytes и используя настоящий offline Compose parser без Engine.
Fixture interpreter запускается через `env -S` с absolute Python path: Linux передаёт весь shebang optional argument
одной строкой ([execve(2)](https://man7.org/linux/man-pages/man2/execve.2.html)). Executable regression проверяет
этот argv и native startup с poison environment; это не заменяет проверку Linux FD/mount backend.

После fresh corrected status/exact authorization фаза claim повторяет readiness под своими locks. Она создаёт
`/opt/clubs-bot-stage/.clubs-bot-release-state/stage/.clb82-resume-start-33468965282-1.consumed`.
Record содержит consumed/version, incident, implementation, binding digest, authorization digest и hash свежего
256-bit token. Token остаётся приватным у текущего процесса. ACK выдаётся только после полного write и fsync
file/parent; lost ACK не позволяет восстановить token или получить новый claim. Existing/partial/malformed/
unsafe marker всегда блокирует повтор, без expiry/reset/cleanup. Claim не означает resume submission или start.

Только подтверждённый winner отправляет одну отдельную resume-start submission. Новый helper invocation снова
открывает только pinned objects, сам берёт locks, сверяет token/record/binding/implementation и fresh guards.
Он durably пишет `candidate_start_begun` до единственного lifecycle start; success требует exact identity,
`/ready`, `/health` и durable result. Между фазами locks освобождаются: B отвергается по pin, restored A сохраняет
marker. Поздний competing claim может занять lock до отдельного resume победителя: такой busy означает
остановку и read-only reconciliation, без возврата authority или retry. Positive concurrency fixture отдельно
фиксирует расписание с освобождёнными locks; executable busy fixture проверяет один claim/submission и ноль starts.
Advisory flock не запрещает rename; безопасность обеспечивает descriptor-relative доступ ко всем
удерживаемым directories и captures. Проверяемые parent edges выявляют подмену; ни следующая phase, ни finalizer
не получают authority на другое дерево. Даже полная копия marker/token hash в B не совпадает с pin A.

Timeout/255/signal/write/fsync uncertainty не возвращают разрешение. После submission — максимум одна read-only
reconciliation для resume-start теми же bytes/binding; после потери claim ACK — остановка до resume. Restart,
новый job/run/temp и прежний start/remote_failure не освобождают marker. Arbitrary rollback всего persistent
storage не является поддерживаемым способом повтора. Cleanup удаляет только собственные transient captures,
а не migration/container/consumption evidence. Настоящий consumed record этой задачей не создаётся.

Локальные проверки: `python3 -B scripts/tests/test_corrected_stage_release.py`,
`python3 -B scripts/tests/test_read_only_release_status.py --strict`,
`python3 -B scripts/tests/test_quiesced_release_state.py --strict-ci`,
`ruby scripts/validate-corrected-stage-workflow.rb .`, `bash scripts/validate-quiesced-deployment.sh .`,
`ruby scripts/validate-workflow-yaml.rb .`, `ruby scripts/validate-workflow-capabilities.rb .`,
`./scripts/selfcheck-quality-gates.sh --ci-delegated-release-state`. Full strict release-state остаётся в своём
CI job; selfcheck включает bound identity validator, реальные CLI/helper regressions и прежний status suite.
SSH/API/Engine синтетические, locks/FDs/durable writes реальные локальные, Compose parser настоящий offline.
Счётчики отдельно фиксируют claim attempts, durable claims по root identities, ACK, transport submissions,
helper invocations, starts и migrations. Gradle/unit/static/IT evidence переиспользуется только при доказанно
неизменных backend/Gradle artifacts; старый helper PASS не проверяет новые bytes.

**Сохранённый import repair, CLOSED независимой CLB-83.** Supported Python baseline — 3.11+ (existing
lint workflow requirement). Producer private-files bootstrap, его private-fs children, provenance producer,
corrected CLI (`--validate`, `--verify-prior`, `--validate-execution`, execution), remote bootstrap, embedded
bound helper и его RPC Python children запускаются с `-I -S -B` до imports.
`-I` исключает cwd/script directory, user site и `PYTHON*` overrides; `-S` дополнительно исключает site initialization
и startup hooks; `-B` не оставляет новые bytecode caches. Эти свойства дополняют друг друга, см.
[официальную документацию Python](https://docs.python.org/3/using/cmdline.html#cmdoption-I).
Bare `python3 corrected-stage-release.py` не является supported execution interface; workflow validators
требуют изолированную команду. Shell и system Python executable/stdlib остаются доверенной runtime base;
глобальные environments, profiles и site-packages этой задачей не меняются.

Own dependency closure producer — workflow, read-only shell runner, `release_authority.py`,
`release_private_root.py`, canonical pattern. Workflow checkout теперь exact `github.sha` с прежней проверкой
равенства HEAD. Consumer проверяет эти exact GitHub sources; version 1 и прежний уязвимый producer отвергаются.
Bootstrap компилирует точный `release_private_root.py`; corrected runner загружает два собственных
модуля относительно canonical script path, включая запуск через symlink entrypoint. Directory не возвращается в `sys.path`, `.pyc` не читается, extra checkout modules не становятся
зависимостями. Старый helper/hash, trusted canonical output, prior attempt/job/incident/principal checks и
допустимое старое `resume_permitted=no` сохраняются; corrected readiness остаётся отдельным gate.

Исторический root/parent counterexample и прежний `BLOCKED_ROOT_BOUND_RESUME_CONTRACT` сохранены в checkpoint.
Адресное разрешение пользователя сняло прежний запрет на helper/CLI только для описанного additive contract.
Теперь candidate возвращается в ту же CLB-83; независимая acceptance и фактические LIVE gates остаются впереди.

### Protected GitHub Environment contract for stage

Accepted operational decision `DEC-037` задаёт постоянный exact contract для configuration и verification existing
GitHub Environment `stage`:

- required reviewer — live-verified GitHub user `koteev-m`, numeric ID `117291255`;
- `prevent_self_review=false` — explicit temporary solo-maintainer exception, а не preferred multi-maintainer target;
- wait timer — exactly 5 minutes;
- administrators may not bypass configured protection rules;
- deployment refs используют custom deployment branch policy с единственным rule: type `branch`, exact name `main`;
- tag rules и wildcard branch rules отсутствуют;
- gate применяется ко всем jobs, которые reference environment `stage`, включая normal stage deployment и
  `Release Status (read-only)`;
- stage environment secrets недоступны job, пока protection gate не пройден;
- repository-level secrets не должны дублировать имена stage environment secrets;
- environment `prod` этим contract или его documentation task не создаётся.

Documentation acceptance or merge не доказывают, что live environment удовлетворяет этому contract. Применение policy
и его independent verification — отдельные operational tasks; завершение каждой требует отдельного live evidence.
Authenticity staging SSH host key, canonical `SSH_KNOWN_HOSTS` payload, provisioning и independent verification pinned
evidence остаются отдельными последовательными gates. Environment protection не доказывает существование
`SSH_KNOWN_HOSTS`, authentic host key, запуск Release Status или health staging и не предоставляет authority для
deployment, resume, rollback или recovery.
Если появляется second trusted maintainer, temporary exception заменяется только отдельным accepted decision с
independent required reviewer и `prevent_self_review=true`.

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
