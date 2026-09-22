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

### CLB-91 local private-snapshot env-file proposal (no live writer)

User-provided terminal evidence for [run 35371386455](https://github.com/koteev-m/clubs_bot/actions/runs/35371386455),
number 2 / attempt 1, is on merged `45637cae26f1e5d27c21e826e4c74d74857698a7`,
tree `8ce7f0b38780c271c291601bf2808295cbf9d018`; validate/diagnose succeeded:

```text
compose-diagnostic:v=2 result=complete subset=invalid violations=key_env_file mapping_details=none top_level_details=none env_file_occurrences=one env_file_shapes=service/app/sequence/canonical_dotenv static_inputs=pass managed_override=pass managed_release=pass dotenv_metadata=pass retained_layout=pass retained_identity=pass retained_checkpoint=pass prior_override=pass migration_records=pass result_record=pass
```

This establishes the bounded `services.app` canonical-dotenv sequence shape at
that capture. It supplies no `.env` content, effective environment, redundancy,
future compatibility or readiness evidence. Run 35262529462 below is historical
v1 evidence. Historical provenance is not required for the next semantic decision.

The new [local planner](../../scripts/deploy/stage-compose-env-file-plan.py) is an
import-only private-snapshot API with [real Compose tests](../../scripts/tests/test_stage_compose_env_file_plan.py).
The API still has no live writer; the separate future semantic workflow below
adds a distinct private-read authorization boundary. Its public CLI refuses use; callers explicitly supply immutable private bytes for
base, present `.env`, exact incident managed override, interpolation environment,
project name, installed Compose executable and safe disposable temporary root.
`None`/missing dotenv evidence is refused; `b''` means a captured PRESENT empty
file. No snapshot is discovered from the checkout, caller cwd, Docker config or
ambient environment. Import no longer loads project files with `runpy`. The
verified loader supplies its three explicit dependencies from captured bytes;
local tests inject the same inert primitives. No `BoundContext` is constructed.

The locally tested executable reports **Compose 5.1.1**. The current planner's
version-string check is NOT an exact executable/build attestation and is not a
credentialed runtime gate. Local test executable fingerprints on Darwin are:

| Component | Version | Executable SHA-256 |
| --- | --- | --- |
| Compose, arm64 Mach-O | 5.1.1 | `a704f5f87565d61ee38cf39333438c56e1c3b7b6f0ea5fb5a5f31281e593bb5c` |
| Python, arm64 Mach-O | 3.13.2 | `42f792544842512d02eb8a95a6009062591476558f09f07553c138661a0bca8e` |
| System Ruby, universal Mach-O | 2.6.10p210; Psych 3.1.0 | `9d6ff3e289c7d908e3c785e0bedd6692d1d6a3377965c88c04d847104b7c892c` |

These are measured local binaries, not Linux/stage pins. Python/Ruby also load
runtime libraries; executable hashes alone do not attest that dependency closure.
The separate Linux reference closure below is now pinned and tested; these
Darwin measurements are historical local evidence only.
Neither repository helper nor supplied stage evidence pins the live version;
**stage Compose version/build remain unknown**. Another version is refused,
not silently accepted. No stage package, engine, image, VM or dependency is installed.
Only `version --short` and offline `config --format json` are invoked. An isolated
HOME/config/cwd and a nonexistent local Engine socket are used. Ruby/Psych,
already used by repository validators, supplies a bounded syntax tree, without
constructors, aliases, tags, merge expansion or emitting a rewritten YAML file.
This is not a new generic YAML/dotenv implementation. The helper's lexical dotenv
boundary remains required: each active stripped physical line must match its
assignment regex. This rejects bare unset lines but is not a multiline dotenv
parser; accepted quoted multiline values still require real Compose equivalence.
The local preparer is deliberately more restrictive about
process/tool control names. References to parser-control variables such as HOME,
PATH or PWD (even quoted/escaped spellings), environment entries with those names
in ANY service, environment list forms, tilde-prefixed bind sources and dynamic
mount sources are unsupported. A dynamic source could expand to a tilde path;
the planner declines it instead of adding another interpolation parser.
Fixture-specific values must never masquerade as the
deployment principal's values. Ruby/version checks receive no application
environment; normalization receives only the explicitly supplied non-control
interpolation map. Existing helper acceptance is not widened.
The fixed cwd handoff uses a shell before exec. Shell-special variables (including
IFS, OPTIND and the BASH prefix) are therefore excluded from every input channel,
not treated as application inputs: the shell can reset or synthesize them.
The same control-reference guard also examines every decoded YAML scalar in the
existing bounded AST: escapes that synthesize a dollar sign or part of a variable
name must not bypass the raw-input check. No additional parser is introduced.

Let `N(B,D,O,E,P,Q,V)` denote the real Compose normalized JSON model from
base B, dotenv D, managed override O, explicit interpolation environment E,
project name P, project directory Q and tested executable V. The present API
uses ONE private temporary Q for all comparisons; it does not establish canonical
stage-directory equivalence. A local proposal C is eligible only if:

1. Input syntax/trust assumptions are supported; exactly one canonical sequence
   belongs to `services.app`, and no other service/reference-file expansion is allowed.
2. `same_json(N(B,D,O,E,P,Q,V), N(C,D,O,E,P,Q,V))` as complete typed JSON trees. This includes
   every environment value and key presence, null versus absent versus empty,
   interpolation/escape effects, other services and every remaining model field.
   Boolean, string, null, object and array kinds are distinct. Integer versus
   float and finite float signed zero are conservatively distinct too; object
   key order is irrelevant, array order is significant. Nonfinite numbers,
   non-string object keys, cycles and non-JSON objects refuse. This replaces
   Python `==`, which incorrectly equates `true` with `1`. The same comparator
   guards removal, explicit proposal and BOTH normalized-reuse decisions.
3. Reusing each normalized JSON model as input, as the existing captured helper
   does, produces the identical model again. Literal dollar serialization must
   survive without another layer of escaping.
4. All bounded captures and private temporary cleanup finish before a plan returns.

The proposal algorithm first deletes only the exact AST-located `env_file` line
range. Comments and unrelated bytes outside that property are retained. If the
whole model remains equal, strategy is `remove`. Otherwise it may add only names
missing from the removed model's app environment, without touching existing keys:
`NAME: ${NAME?CLB91 required contribution}`. This selects the existing Compose
interpolation channel; it does not embed dotenv values or serialize resolved
secrets into Compose. An existing environment mapping is extended, or a small
mapping is inserted where the removed property was. A contributed null/unset
value, unsupported environment form, malformed YAML, ambiguity, incompatible
ambient precedence or any remaining model difference refuses the proposal.

The explicit strategy is a candidate to VERIFY, not an algebraic assumption:
service env-file values can differ from interpolation values (for example when
an ambient variable overrides the latter). The real comparison detects that and
refuses. Both strategies retain environment precedence. Repeated `.env`/`./.env`
references use the same supplied bytes in their original order; arbitrary paths,
optional/object forms and other reference files are unsupported.

`Plan.candidate` is sensitive PRIVATE memory, potentially including existing
inline base values. Never print it, put it in Git/public patches, or log model,
dotenv, exception/stderr text or hashes of secret-bearing data. `repr(plan)` is
redacted; `plan.public()` contains only the fixed strategy and
`scope=snapshot future=requires_recheck application=not_authorized`.
Private fixture copies use a validated root, 0700 workspace and 0600 files; they
are cleaned on normal completion/errors. A forcibly killed local process can
leave private temporary files and never produces a successful plan. This is not
the sealed-FD stage backend or a claim of durable secret erasure.

**Future `.env` edits.** Equality is specific to ALL supplied inputs. Value rotation
can preserve the same interpolation proposal, but every change requires a fresh
proof. New dotenv names would otherwise be silently lost after removing the
wildcard import. Missing names in the explicit strategy fail via `${NAME?...}`;
empty values remain empty. Changed ambient environment or Compose version can
also invalidate equivalence. No finite explicit mapping is equivalent for all
possible future dotenv key additions. There is no integrated deployment gate for
this proposal yet; therefore local PASS alone cannot authorize permanent removal.

**Minimum next live authorization (NOT granted by local preparation).** The
separate private semantic capture needs the exact base, present dotenv contents,
managed override/release bytes, effective helper interpolation environment and
project context, plus actual installed Compose version/build identity. The
interpolation snapshot must contain every relevant non-control helper variable;
control variables are excluded only with the planner's strict no-reference/no-key
guard. An incomplete projection or control-dependent input must refuse. Capture
must use the existing no-follow/owner/mode/nlink/device/size/identity checks and
application→operation locks; values stay in private stage memory/captures and
never become job output/artifacts or local exports. Normalization may use only
those captured inputs, no referenced files or Engine/lifecycle query. Public
output may contain only fixed success/refusal/strategy/snapshot-scope enums.
An unsupported live version or incomplete context stops before a proposal.

**Linux capture-only context adapter and separate semantic channel.** The prior
blocker is reproduced by retained negative controls: a temporary project directory
changes bind sources, and CLI `--env-file` alone does not redirect service
`env_file`. The [planner](../../scripts/deploy/stage-compose-env-file-plan.py)
now accepts an explicit canonical project directory for its Linux capture backend.
It preserves that directory and trusted project name, leaving private HOME,
Docker config and temporary resources separate. A bounded Psych outline supplies
the exact supported scalar token spans. Only app's accepted `.env` / `./.env`
reference tokens are replaced in a PRIVATE computational projection, in their
original order and multiplicity, with the held sealed dotenv FD path. CLI
`--env-file` uses the same capture. Base/projection/override/resolved JSON inputs
are sealed memfds at `/proc/<supervisor>/fd/<fd>`, held through subprocess cleanup,
with independent repeated opens and rewinds. No original configuration pathname
is given to the normalizer. The candidate is always transformed from ORIGINAL
base bytes and never contains these service FD references. Neither projection
nor candidate is written to the target.

Real Linux tests prove A: original and projected full models match, including
canonical other-service bind sources; B: replacing AND deleting the original
`.env` after capture cannot change projection output (kernel file-open traces
also exclude original configuration-content reads); C: projected BEFORE and
candidate AFTER match with the unchanged strict typed comparator, and BOTH
normalized JSON models pass reuse. Existing removal/explicit, priority,
absent/null/empty/unset, dollars/escaping, future dotenv additions and negative
context controls run on that backend. Equivalent remains snapshot-only.

The current local production candidate uses the independently sourced, fixed
Linux x86_64 closure from the [Linux harness](../../scripts/tests/linux-semantic/README.md):
digest-pinned Ubuntu amd64, signed/pinned APT package metadata and official
Compose 5.1.1 Linux x86_64 SHA-256
`2ac954c9d506b912a12477d72f01601dc72ec918c429c7bae48fd707bdf0f3e5`.
The exact manifest SHA-256 is
`93a9d29cba93770fab9cc6605709a3b159cfb7ce2c627677ff77bd9cacd62008`.
Native Tests [run 35679432271](https://github.com/koteev-m/clubs_bot/actions/runs/35679432271)
on `67cabe65843fe803f53213aac4d3d5c3fdd73b58`, attempt 1, passed runtime,
planner 33/33, context/syscall 34/34 and semantic 15/15, no skips. Production
runtime behavior differs from the old profile only in the architecture predicate,
Ruby architecture directory and manifest, exactly the three tested adaptations.
The ARM64 Dockerfile/package evidence and
[old manifest](../../scripts/tests/linux-semantic/arm64-runtime-reference.json)
are retained as history, not a second accepted production profile.
Python 3.12.3, Ruby 3.2.3 and bundled Psych 5.0.1 are the tested runtime; Ruby gems
are disabled and the Linux parser load path is fixed to the two pinned standard
library directories. The [runtime manifest](../../scripts/deploy/stage-compose-env-semantic-runtime.json)
pins actual Linux executable, Python source/cache, Ruby/Psych and shared-library
bytes, loader cache and command aliases. The future operation verifies these root-installed,
non-writable files, architecture and interpreter isolation BEFORE opening target
configuration, and holds/revalidates their descriptors through completion.
Resident Python native mappings must belong to that allowlist. Before target
capture, a bounded Ruby/Psych probe with the same fixed load path checks its
actual loaded source/native closure and versions against the already hashed
files. The pinned Compose executable is a static Linux ELF (no interpreter).
Version strings alone, missing tools, another architecture/build or an unavailable
safe `/run/user/<uid>` root cannot authorize private reads. Root/kernel remain
trusted system authorities; this is not hostile-root attestation or a tool installer.
Historical inventory `35646675380` observed Ubuntu 24.04/x86_64/CPython 3.12.3,
missing Ruby/Psych and standalone Compose in the checked locations, and a plugin
with unknown version/provenance. The profile is partial and not approved; the
full root-installed trusted closure and actual applicability remain unproven.
No tools may be installed/upgraded there by this channel. Container runtime is a local test facility,
not a new stage dependency.

The separate [workflow](../../.github/workflows/stage-compose-env-semantic.yml)
is `Stage Private Env Semantic Check (CLB-91 manual)`. Its ONLY input is exact
`confirmation=CLB-91:35371386455:private-env-semantic-dry-run`, after separate user
authorization for private `.env` capture and offline normalization. Structural
v2's old confirmation does not pass. It remains manual-only, repository/main,
initial attempt only, `contents: read`, Environment `stage`, checkout dispatched
SHA, validation before credentials and again in execute job, shared
`payments-schema-stage` concurrency with cancellation disabled. Existing six
stage SSH/compose secret names and pinned-host deployment principal are used;
no protected variable, approved-implementation change or root binding is added.
One SSH transport, no retry/fallback/second channel; each later dispatch needs
new user authorization. Attempt=1 is NOT incident-wide durable consumption.

The [consumer](../../scripts/deploy/stage-compose-env-semantic.py) verifies its
whole fixed Git source closure before any loaded project module executes, and
runs those same captured bytes. The fixed dependency adapter reuses unchanged
corrected transport/private-root/authority sources; the remote bootstrap verifies
and compiles the complete bundle before execution. No working-copy loader,
`runpy`, `.pyc` or project import fallback is used. The unchanged diagnostic capture
class supplies no-follow directory/file traversal, shared nonblocking application
then operation locks, principal/owner/nlink/mode/device/read-size checks and
held-FD/path/drift revalidation. Neither old structural diagnostic nor corrected
helper/authority/mode-repair bytes are changed.

Private target content allowlist: `docker-compose.yml` (65536), PRESENT `.env`
(65536), `docker-compose.override.yml` (4096), retained
`.clubs-bot-release-state/stage/clubs-bot-schema-stage.lock/docker-compose.release.yml`
(4096), and `.clubs-bot-release-state/application.binding` (2048). Existing lock
files and protocol directories are opened for trust/locking, never created or
changed. Base shape and exact managed override/release are checked before dotenv
content. No referenced config/secret/env/label files are opened. The complete
applicable process interpolation environment stays in private memory; all
BEFORE/AFTER/reuse operations receive that SAME context. Tool/shell controls are
private and control-dependent input is refused rather than approximated.
This capture does not repeat or imply success of every historical static record.

Private effects are bounded anonymous memfd allocation/write/seal, and a private
0700 temporary HOME/empty Docker config under the safe runtime root. They are
not target writes. Ordinary reads may affect atime/audit state. All FD/process/
temporary-resource cleanup and final cancellation handoff precede publication.
The operation has a 90-second bootstrap alarm, bounded capture primitives,
fixed per-source/bundle/model/frame limits, existing target total/read limits,
and at most eight planner subprocesses. Cancellation, unexpected I/O, incomplete
acquisition, cleanup or identity/context mismatch cannot publish equivalence.

Public protocol, authenticated as a whole by a fresh private nonce/HMAC:

```text
compose-env-semantic:v=1 result=equivalent strategy=remove|explicit scope=snapshot future=requires_recheck application=not_authorized
compose-env-semantic:v=1 result=unavailable reason=<fixed enum>
```

Only the first form exits 0. Compact unavailable bodies remain valid for local,
transport and legacy failures, and make **no** capture/phase assertion. New remote
operation refusals append the following exact ordered inventory to the unavailable
line (still protocol v1; the unchanged HMAC covers the whole body):

```text
phase=<phase> guard=<guard> private_capture=not_started|attempted platform=<status> manifest=<status> availability=<status> path_safety=<status> integrity=<status> interpreter=<status> modules=<status> aliases=<status> maps=<status> descriptors=<status> private_root=<status> ruby=<status>
```

- `phase`: `initial`, `pre_capture`, `post_open`, `capture`, `prepare`,
  `post_prepare`, `finalize`. `guard`: `none` or one of the twelve prerequisite
  field names. The first failed runtime guard/phase is retained even if later
  independent checks also fail or cleanup/cancellation supersedes the reason.
- Each status is `pass`, `fail`, or `not_evaluated`. Failure takes precedence;
  skipped dependencies prevent a positive result for the aggregate class.
  Availability means stat/open succeeded; path safety includes canonical parents,
  root ownership, non-writability, regular type and the existing per-file size
  bound. Integrity hashes only safely opened files under the existing total byte
  budget. Interpreter/modules/maps/aliases are metadata/allowlist observations;
  they do not approve an otherwise mismatched installation. Descriptors describes
  only held runtime files. Missing inputs do not become successful integrity checks.
- Initial platform/manifest/file/closure observations and private-root safety are
  independent where safe. Invalid manifest makes dependent inventory checks
  `not_evaluated`. Ruby/Psych is probed **only after every non-executing gate passes**;
  otherwise `ruby=not_evaluated`. No unverified Ruby/Compose executable is run for
  version discovery. Initial failure prevents target acquisition and normalization.
- `private_capture=attempted` is latched **before** copying private process interpolation inputs and
  `ReadOnlyCapture.open()`, which already reads `application.binding`; it does not claim a read succeeded or that
  `.env` was reached. An added pre-capture recheck precedes this latch; the existing
  post-open and post-prepare checks remain. A late drift therefore cannot claim
  zero reads. Cancellation/cleanup never publishes equivalence; positive
  prerequisite statuses become `not_evaluated` on that incomplete finalization.
  The remote final signal handoff preserves capture attribution. A local transport
  or publication failure may only provide the compact form: capture is unknown.

`reason` remains exactly one of `runtime`, `request`, `principal`, `layout`,
`identity`, `busy`, `backing`, `bounds`, `io`, `interrupted`, `cleanup`, `transport`,
`protocol`, `input`, `unsupported`, `version`, `parser`, `model`, `different`.
Body <=512 bytes; authenticated frame <=4096. Unknown, extra/reordered/duplicate
fields, startup output, bad authentication, replay or exit/body contradiction
fail closed. No observed hashes, paths, architecture strings, usernames/IDs,
values, YAML/model, exception text or child stderr are reportable. The map is
compatibility evidence for the exact production reference allowlist, not an
approval of the observed installation. No Engine queries, pulls, HTTP, lifecycle,
writer/apply, root-binding, claim/resume or automatic continuation.

Historical semantic run `35558150872` (number 1, attempt 1, merged
`edfa135255f06a3f42073af02ea74aa68ba59c8b`) returned authenticated
`compose-env-semantic:v=1 result=unavailable reason=runtime`. That old result
conflates initial build/root/Ruby checks with late runtime rechecks. It establishes
neither the precise failing prerequisite, absence of private reads, nor semantic
equivalence. Reference Linux PASS does not establish actual stage compatibility.
The local incident history does not establish stage OS/architecture, installed
executable/library closure, safe-root availability or Ruby/Psych applicability.
The subsequent user-provided run `35604124263` (number 2, attempt 1) returned
`phase=initial guard=platform private_capture=not_started`, with aggregate
prerequisites only. This run did not begin private capture or normalization, but
does not identify architecture/distro/installed builds. The subsequently completed
inventory `35646675380` adds the partial observations described above; they do not
prove server identity or unchanged state between the two runs.
Choosing/installing a server toolchain is a new operational decision;
this channel never changes pins, installs tools, falls back, or probes stage here.

A future separately authorized single dispatch may only establish one captured
semantic result on an applicable runtime. It does not approve permanent removal.
A later writer needs separate review/write authorization, revalidation at apply
and an enforceable gate for ALL dotenv/context/toolchain changes, including new
keys. That writer/gate is not implemented. No live private read/normalization,
stage write, dispatch or lifecycle action occurred during local preparation.

Linux suites and bootstrap coverage are in the harness; portable request/source/
protocol selectors are wired into the existing selfcheck. Workflow inventory is
26 after adding the separate runtime inventory below, including the exact
alias-inventory expectation; existing checks are retained.
Full semantic tests require the pinned Linux harness and must not be reported as
passing on a mocked/unsupported normalizer. Exact final candidate/review/check
results are recorded in the handoff. Local tests do not establish live equivalence.

### CLB-91 bounded non-secret runtime inventory

The [inventory workflow](../../.github/workflows/stage-runtime-inventory.yml),
[consumer](../../scripts/deploy/stage-runtime-inventory.py) and
[collector](../../scripts/deploy/stage-runtime-inventory-operation.py) are a
separate capability. The only input is
`confirmation=CLB-91:35604124263:inventory-stage-runtime`. The previous semantic
confirmation cannot select this operation. Manual dispatch, repository
`koteev-m/clubs_bot`, exact dispatched main SHA, initial attempt 1, `contents: read`,
Environment `stage`, validation before SSH credentials and execution revalidation,
`payments-schema-stage` concurrency / `cancel-in-progress: false` remain mandatory.
The same deployment principal, pinned known-hosts, isolated Python `-I -S -B`,
whole-project-source verification before execution and one transport/no retry
are retained. The remote bundle contains only collector bytes and the unchanged
reference manifest as comparison data; no planner, normalization or target reader.

**Finite observation/read contract (no discovered path traversal):**

| Source / fixed slots | Bound and interpretation |
| --- | --- |
| Running bootstrap memory / system interface | OS family (`linux`, `darwin`, `freebsd`, unknown), fixed architecture vocabulary, 32/64 process bits, Python implementation/version and isolation flags. No environment, command line, hostname, user or UID output. |
| `/etc/os-release`, missing-only fallback `/usr/lib/os-release` | 16 KiB each, 256 lines; only `ID` from a fixed distro list and numeric `VERSION_ID`. Parse data without source/eval; unsupported/malformed values become unknown. |
| Python `/usr/bin/python3`, `/usr/local/bin/python3`, active `/proc/self/exe` readlink | Only those directories and finite `python3.8` through `python3.14` targets may resolve. The kernel link itself is not opened; arbitrary targets are not followed. Active binary hash is a system-artifact observation, not renewed bootstrap trust. |
| Compose fixed slots | `/usr/local/bin/docker-compose`, `/usr/bin/docker-compose`, `/usr/lib/docker/cli-plugins/docker-compose`, `/usr/libexec/docker/cli-plugins/docker-compose`, `/usr/local/lib/docker/cli-plugins/docker-compose`. No other plugin search or execution. |
| Ruby / Psych fixed slots | `/usr/bin/ruby`, `/usr/local/bin/ruby`, finite `ruby3.0` through `ruby3.4` targets in those directories; `/usr/lib/ruby/3.0.0/psych.rb` through `/usr/lib/ruby/3.4.0/psych.rb`. Slot names indicate placement only, not an inferred installed version. No Ruby require, gem enumeration or extensions executed. |
| `/var/lib/dpkg/status` | 4 MiB, 8192 stanzas, 65536 characters per stanza; emit only Package/Status/Version/Architecture for the twelve packages listed below. No full package dump, package-manager command/scripts or arbitrary build strings. |

Package keys: `python3`, `python3-minimal`, `ruby`, `ruby-psych`, `docker-compose`,
`docker-compose-v2`, `docker-compose-plugin`, `libc6`, `libssl3`, `libssl3t64`,
`libyaml-0-2`, `libffi8`. Versions are at most 64 ASCII characters from a narrow
numeric/Debian build vocabulary; architecture has its own fixed vocabulary.
Other package systems/build formats, custom directories, gem layouts, extension
closure and cryptographic installation provenance remain unknown, not guessed.
Metadata version is a **reported installation version**, not executable identity.
No generic ELF, dependency or package scanner is added.

All system paths use root-owned no-follow directory descriptors, reject writable
ancestors, and require regular root-owned single-link files. Metadata/library
modes are 0444/0644; executable modes 0555/0755. Only at most four leaf-link hops
within the same fixed artifact kind are permitted; no directory links, home/app
or secret redirection. Hashes are computed only after these guards. Bounds:
64 MiB per artifact, 192 MiB total reads, 256 retained descriptors, 75-second
collector deadline, 90-second bootstrap alarm, 110-second transport. Held FDs,
pathname edges, missing edges and links are revalidated before complete output;
detectable in-place/path drift or unexpected I/O refuses the entire report.
This is a bounded observation under the trusted OS, not compromise attestation
or an atomic filesystem-wide snapshot.

There is no subprocess/tool execution in the collector, no ldd/version probes,
recursive walk, target file access or target writes. In particular it never opens
`/opt/clubs-bot-stage`, `.env`, Compose, application.binding, release records,
home/SSH/config, process environment/cmdline or the Docker socket. Investigated
files are never executed after hashing. It creates no remote files/directories,
and performs no chmod/chown/unlink/fsync or installation. Normal read atime/audit
effects are possible. The existing local SSH pin resource is anonymous and
cleaned by the existing transport; it is not a stage artifact.

**Authenticated output:** prefix `runtime-inventory:v=1 ` followed by canonical
ASCII JSON and one newline, at most 8192 bytes. Fixed top-level keys for an
observation are `artifacts`, `bootstrap`, `completeness`, `distro`, `packages`,
`result`, `trust`; all slots and per-slot fields are fixed. Values are bounded
numbers/booleans, allowlisted strings or system-artifact SHA-256 digests. There
are no arbitrary paths or metadata strings. Each observation has its fixed source.
Artifact status is observed/missing/unknown, with fixed reasons; unsupported
observations use `-`, not fabricated values. `same_digest` / `different_digest` /
`not_listed` compare with the immutable reference manifest only, never update it.

`result=observed`, exit 0, means collection and cleanup finished; `completeness`
is partial when any requested observation is unknown. Explicitly observed missing
optional slots do not make it partial. Even complete always carries
`trust=observed_not_approved`: it proves neither runtime compatibility nor full
module/library closure. A required collection/identity/bounds/interruption/cleanup
failure returns only `result=unavailable` plus a fixed reason, exit 1, without a
partial profile masquerading as completed. Every byte is bound to a fresh private
nonce by HMAC in the <=12288-byte frame. Consumer rejects unknown/duplicate/
reordered/extra fields, arbitrary strings, startup output, replay, altered HMAC
and exit/body mismatch. Cancellation and owned-resource cleanup precede output.

**Manual boundaries:** the main-only policy requires publication, manual CI review
and merge of these new bytes before execution. Do not use a feature-branch policy
exception, semantic workflow script injection, direct SSH, another principal/host
or a trial probe. After merge, verify exact reviewed sources, main/tree, stage
reviewer/timer/branch policy, five secret names `SSH_USER`, `SSH_HOST`, `SSH_PORT`,
`SSH_KNOWN_HOSTS`, `SSH_PRIVATE_KEY`, operational conflicts and duplicate runs.
`COMPOSE_PATH` and protected variables are not consumed. Dispatch once on main,
then stop without run lookup/polling; user approves stage and returns terminal
output. One invocation/no retry is not durable incident-wide consumption.
No duplicate is authorized on failure/timeout/ambiguous submission.

Tests: [dedicated suite](../../scripts/tests/test_stage_runtime_inventory.py),
[synthetic filesystem surrogate](../../scripts/tests/runtime_inventory_fixtures.py).
Use the existing [Linux harness](../../scripts/tests/linux-semantic/README.md)
without the `/opt/clubs-bot-stage` mount and run only the inventory suite. The
real Linux test uses actual root-owned system artifacts and audited bootstrap;
fixture tests substitute root/UID for disposable files and label that fault
injection. Neither yields a stage profile. Dedicated regressions are registered
in the existing selfcheck, with 26 workflows and exact capability validation.

Once authenticated inventory arrives, compare observed OS/arch/bootstrap,
artifact placement/digests and reported package versions to the checker contract.
Unknown observations remain explicit prerequisites. The local x86_64 candidate
uses independently verified upstream artifacts and native synthetic tests, not
stage-observed hashes. The inventory consumer's reference hash changes with this
production manifest; historical `same_digest`/`different_digest` fields still refer
to the manifest at their run revision. This neither reruns inventory nor changes
collector authority. Before any server work, separately review installation and
its impact on libraries, caches and aliases; do not approve pins from observed hashes or
reinstall stage to mimic the reference image. No semantic run, server install,
private read, writer or lifecycle action follows automatically.

The shared SSH action pin is locally migrated to
`webfactory/ssh-agent@e83874834305fe9a4a2997156cb26c5de65a8555`
([v0.10.0](https://github.com/webfactory/ssh-agent/releases/tag/v0.10.0), Node 24).
It descends from [v0.9.1](https://github.com/webfactory/ssh-agent/releases/tag/v0.9.1),
which fixes custom-command cleanup; exact source and bundled post modules export
and consume a defined `sshAgentCmd`. A bounded upstream fixture and offline
[regression](../../scripts/tests/test_ssh_agent_pin.py) test default/custom cleanup
without an agent or key, reproduce the old undefined-command failure and reject
old pins in all active credentialed workflows. Only the action revision changes;
permissions, Environment, host-key checks, secrets and one-transport contracts do
not. This has not been exercised in a new credentialed hosted run.

### CLB-91 bounded runtime closure feasibility (local preparation)

Lint #618 / job `106795287027` exposed a separate fixture-placement defect:
its two positive bootstrap paths returned authenticated `unavailable/io`.
The surrogate translates logical `/` and `/proc/self/maps` to its physical
fixture; the old test audit rejected those opens under `/home/runner/...`.
Both failures reproduce on unchanged `52f12f220ab8d6e517048b31dc79100f279381e8`
with a synthetic hosted-style temporary root. The selected temp path itself
was not printed by that job. The test-only correction permits exactly two
preidentified physical objects, not an entire prefix; no-follow, canonical
path and device/inode/mode checks reject substitutions. Absolute neighbors,
byte paths and forbidden effects remain blocked. Regressions cover RUNNER_TEMP
precedence and TMPDIR fallback using real Linux FDs/bootstrap/HMAC/consumer.
This does not alter production access or establish hosted x64/full CI success.


PR #515 CI-fixture follow-up: the positive bootstrap proof must use a bounded
synthetic filesystem rather than assume every artifact on an arbitrary CI host
fits the reader limits. Lint `35695122661` / job `106640199984` executed merge
`29ed527558d3e4b801dafdb2dca3433d6e89acc1` and failed the `code == 0` assertion
after authenticating a refusal. Its [exact runner image](https://github.com/actions/runner-images/blob/ubuntu24/20260907.300/images/ubuntu/Ubuntu2404-Readme.md)
reports Compose 2.38.2; [official asset metadata](https://github.com/docker/compose/releases/tag/v2.38.2)
identifies the x86_64 plugin as 75108694 bytes, above the unchanged 64 MiB bound.
Synthetic reproduction returns authenticated `unavailable/bounds`, exit 1,
171 stdout bytes and no stderr; the original job log did not record the reason.
The new regression fails with the original positive method, and also tests
64 MiB minus one, exactly 64 MiB, plus one and the hosted asset size. Oversized
artifacts remain unread/refused. The positive fixture substitutes only test root,
ownership and own-proc metadata, retaining real Linux FD operations, actual
bootstrap/HMAC/consumer and one synthetic transport. It is not root-installation
or hosted x64 parity evidence. Test diagnostics report fixed stages/counts and
verified refusal categories, never raw stdout/stderr or nonce/canary values.
Full delegated selfcheck remains required in a suitable environment; executing
its exact affected suite block locally is partial integration evidence only.
No production limit, read scope, workflow/pin/authority or installation policy
changes for this fix, and no server run is authorized by it.

The follow-up test-helper P2 separates capture EOF from process termination.
It uses the existing capture primitive's `waitid(WNOWAIT)` approach within the
original deadline: the leader stays unreaped until group cleanup, preventing
PID/PGID reuse while preserving its real exit status or signal. A child that
closes both outputs and hangs still times out. Signal-synchronized regressions
fail on the old helper, and check exit 0/1/SIGTERM, timeout, waitable leader
identity before `killpg`, reap afterwards, and descendant termination via a
separate pipe EOF. This correction is test-only; it does not change the
production transport or remove the full hosted verification requirement.

PR #514 is merged at `a436f74632acb2af2df88aeb06fc6cdb6df4802b`, tree
`cb80285b4d71a386ab73338ee82334fc4d0145a2`. User-supplied terminal evidence for
manual Tests `35686542517`, attempt 1 / `63ad587bd694a5e79ccd5efe4dcf99c3aa1dfaa9`,
is runtime 1/1, planner 33/33, context 34/34 and semantic 19/19 PASS, no skips;
PR CI and 15 post-merge workflows succeeded. This is synthetic tool evidence,
not installed stage closure or recovery authority. The original inventory
`35646675380` remains a spent one-shot, partial observed profile.

The new [workflow](../../.github/workflows/stage-runtime-feasibility.yml),
[consumer](../../scripts/deploy/stage-runtime-feasibility.py) and
[collector](../../scripts/deploy/stage-runtime-feasibility-operation.py) are
**local preparation only**. A separate workflow is necessary because the old
inventory authorization/read set must not silently expand. Exact capability
validation registers only this main/initial-attempt operation. Workflow inventory
is now 27; prior counts above remain historical. The only input is
`confirmation=CLB-91:35646675380:runtime-feasibility`. That token does not supply
human authorization, and old inventory/semantic confirmations cannot select it.
No semantic, installer, writer, binding or recovery continuation exists.

Finite source/read allowlist (no caller paths or filesystem discovery):

| Object | Limit / interpretation |
| --- | --- |
| All 191 `files` and four `aliases` of the unchanged production manifest | Manifest SHA-256 `93a9d29cba93770fab9cc6605709a3b159cfb7ce2c627677ff77bd9cacd62008`; safe regular-file metadata and SHA-256, compared per exact path. Aliases disclose only an approved target or `outside_allowlist`, never an arbitrary observed target; targets are never followed for content reads. `/bin` may only be the root-owned link to `/usr/bin`. |
| Six recovery-tool slots | `/usr/bin/docker`, `/usr/local/bin/docker`, `/usr/bin/docker-compose`, `/usr/lib/docker/cli-plugins/docker-compose`, `/usr/libexec/docker/cli-plugins/docker-compose`, `/usr/local/lib/docker/cli-plugins/docker-compose`. Strict regular system artifacts; no symlink following, execution or implicit plugin search. Observed hash does not approve Docker CLI/plugin. |
| `/var/lib/dpkg/status` | At most 4 MiB, 8192 stanzas, 64 KiB/stanza. Only the 37 literal `PACKAGES` in the collector: Python packages, Ruby/Psych, directly relevant runtime libraries/cache owner, dash/util-linux and five Docker/Compose package names. Output status/version/architecture and dpkg selection/hold; no package-manager command, scripts, database dump or omitted-record inference when metadata is unavailable. Version grammar is bounded to 64 ASCII characters. |
| Accepted isolated bootstrap | Fixed memory fields for OS/arch/bits/implementation/version/isolation. `/proc/self/exe` readlink and at most 64 KiB of **own** `/proc/self/maps`; loaded module/cache names come only from this bootstrap's memory. Emit only manifest-known names and an outside-allowlist count. Never stat/open/hash a discovered path. No other process, environ/cmdline, raw maps, host/user/UID output. |
| Private root | Metadata/descriptor only for `/run/user/<current deployment principal UID>`; root-owned no-follow `/run/user` parents, selected directory owned by that principal, non-writable-by-others, owner-access bits. No contents, mount query, mkdir/chmod or public UID/path. This does not prove future captures/locks will succeed. |

Retained no-follow directory/leaf descriptors, single-link regular files,
root ownership and non-group/world-writable paths precede content reads. Leaf
symlinks are not accepted even inside the allowlist; unsupported layouts are
explicitly unknown. FDs, path edges, link identities, missing entries and observed
unsafe metadata are rechecked before reporting. Detectable substitution/in-place
drift refuses the whole collection. This is not an atomic filesystem snapshot.
Bounds: 320 retained FDs, 64 MiB/artifact, 256 MiB total artifact/metadata reads,
75-second collector deadline, 90-second bootstrap alarm, 110-second transport,
131072-byte canonical public body and 131200-byte authenticated frame. Own procfs
has its separate 64 KiB bound. Source files are at most 65536 bytes each; the
captured source/data bundle remains at most 524288 bytes. Unexpected I/O, exceeded
bounds, identity drift, malformed protocol, interruption or cleanup failure
returns only a fixed `unavailable` reason, exit 1. Unknown package syntax is
reported unknown, not absence; malformed/oversized output is rejected entirely.

Source-before-exec and authentication reuse the unchanged corrected capture and
pinned-host transport primitives. The unchanged inventory primitive module,
production manifest, amd64 inputs and package inventory have explicit SHA-256
pins. All local source closure is captured from exact dispatched Git objects and
verified before project execution. Remote code runs from those captured bytes in
memory, no import/path fallback. Before credentials and again in the execution
job, exact-source validation enforces repository/main/attempt 1. The workflow
retains `contents: read`, Environment stage, `payments-schema-stage`, no concurrency
cancellation, canonical SSH action and strict pinned-host principal. One SSH
transport, no retry, no alternate command. Cleanup/cancellation precede HMAC
publication. No remote files, subprocesses or target writes are created by the
collector; ordinary atime/audit effects remain possible. Local transport still
owns and cleans its anonymous host-pin resource and process group.

`runtime-feasibility:v=1` contains canonical ASCII JSON with fixed top-level
`result/trust/reference_sha256/files/aliases/docker/packages/bootstrap/private_root/
closure/comparison/completeness`. Per-file rows distinguish exact `match`, safely
hashed `mismatch`, `missing`, and `unknown` with unsafe/permission/malformed/
unsupported reason. Only safely observed system artifacts expose SHA-256/mode/size.
Unknown is never promoted to match. `result=observed` means collection completed,
not runtime readiness; even a complete result is `observed_not_approved`.

The consumer validates every key/type/bound and recomputes `comparison()` locally
from the pinned manifest, `amd64-inputs.json` and `amd64-packages.txt`. It separately
identifies Python, Ruby/Psych, system-library and loader-cache changes, expected
package versions/known architectures and whether a signed archive or official
base reference is available. Host package provenance stays unauthenticated;
version equality does not prove byte equality, an approved package transaction,
or executable origin. Hold is only the recorded dpkg selection, not all possible
APT policy/preferences. No package scripts/restart plan is inferred.

Synthetic example (subset, from the dedicated fixture):

```text
files: match=188, mismatch=1, missing=1, unknown=1
/usr/bin/python3.12: mismatch -> Python package/cache/closure decision required
/usr/bin/ruby3.2: missing -> Ruby/Psych installation decision required
/etc/ld.so.cache: unknown/unsafe -> do not read/hash or approve; resolve path safety
package_provenance=host_not_authenticated
installation=separate_transaction_review_required
recovery_toolchain=not_approved; runtime_acceptance=not_proven
```

This result would prevent a Ruby/Compose-only installation assumption: Python
and loader-cache questions remain explicit. It neither proposes overwriting the
191 files nor approves upgrading/downgrading Python/libc/OpenSSL. Standalone
Compose and Docker plugin are separate observations. Any package transaction,
server trust change, semantic run and recovery require distinct decisions.

Tests: [feasibility suite](../../scripts/tests/test_stage_runtime_feasibility.py)
and [FD fixtures](../../scripts/tests/runtime_feasibility_fixtures.py). Existing
Linux harness, read-only root, nonroot, network none, cap-drop ALL and
no-new-privileges; only disposable source export and safe tmpfs mounts, no app,
home, credentials or Docker socket. Real Linux collection exercises root-owned
system artifacts and actual bootstrap/HMAC/consumer; fixture root/owner and fault
substitutions are labelled fault injection. No positive Rosetta semantic gate or
repeat native experiment is required. The suite is wired into the existing
selfcheck; exact topology/capability counts remain enforced.

Future boundaries: after independent review, separately authorize publication
and one Draft PR; user checks hosted CI and merges. Then a fresh main/source,
Environment reviewer/timer/branch policy, secret-name-only, conflict and duplicate
preflight must precede a **separately authorized** initial dispatch of this exact
workflow with its own confirmation. User performs Environment approval. A failure
or ambiguous submission does not authorize a retry. No collection, installation,
private semantic execution or recovery has occurred in this local task.

### CLB-91 aggregate Compose diagnostics and local env-file structural extension

The user handoff records completed independent review of the mode-repair and
count-fix candidates, merged through PR #508 at `29b521969f5e5afc3c7f64be5f06dc56a675cf8f`.
The earlier authorized repair returned `compose-mode-repair:v=1 result=changed`.
User-provided corrected inspect `35206468948` (run 7, attempt 1) subsequently
returned `compose_subset/invalid`, authenticated `helper_started/helper_blocked`,
then `STATUS_UNAVAILABLE`. At that snapshot the preceding file guards, including
mode `0600|0644`, device and read-based size, had passed. Exact live bytes are
unknown. Neither observation proves continuity between runs or readiness.

The separate [workflow](../../.github/workflows/stage-compose-diagnostic.yml),
[consumer](../../scripts/deploy/stage-compose-diagnostic.py) and
[remote diagnostic](../../scripts/deploy/stage-compose-diagnostic-operation.py)
were published as Draft PR #509 at `47cf73511ac3dcdd863f162031ad642ac5ac39ea`
after the independent focused F1/F2 review passed. That review corrected the
pre-verification module execution and incomplete acquisition findings in
`11be0b0e6f417b1a2b82c0e025ad7f8b72b75187`.

The user then supplied a hosted `Lint core` test failure: the post-snapshot
source replacement scenario returned `unavailable/protocol` instead of complete.
Private synthetic tracing established a fixture startup defect: the old SSH
test-double shebang supplied `-I -S -B` as one Linux argument, causing Python
exit 2 before bootstrap and an absent HMAC frame. Darwin native startup split
those flags, masking the defect. The local correction uses `env -S` and adds a
Linux single-argument regression requiring successful authenticated completion,
no pathname canary execution/disclosure, one transport, and rejection before SSH
on the next invocation. The old header fails this regression; the corrected
header passes. The reviewed correction `93758bb6319eea3f80194f947eb859ba093fcc6b`
was subsequently merged through PR #509 at `9fde0f8bf62cd1ea41d5ec149dd0bcf01a878af6`,
tree `448b77ea88ba4cb5683464da0f5881722527bada`. The user handoff records successful
post-merge CI reconciliation. The fixture correction did not change production
diagnostic or protected/shared bytes. This is CI evidence, not stage evidence.

The separately authorized [diagnostic run 35262529462](https://github.com/koteev-m/clubs_bot/actions/runs/35262529462),
number 1 / attempt 1, executed that exact merged SHA on `main`. A bounded read
confirmed workflow identity, both successful jobs and the single public result:

```text
compose-diagnostic:v=1 result=complete subset=invalid violations=key_env_file mapping_details=none top_level_details=none static_inputs=pass managed_override=pass managed_release=pass dotenv_metadata=pass retained_layout=pass retained_identity=pass retained_checkpoint=pass prior_override=pass migration_records=pass result_record=pass
```

Green means authenticated diagnostic collection completed, not that the lexical
subset is clear or Compose is semantically invalid. All ten independent static
checks passed at that snapshot. No live contents, scope, count, referenced path,
or effective container environment were exposed. The earlier corrected inspect
run #7 remains `compose_subset/invalid`; neither result establishes continuity,
repair, readiness, root-binding, claim, resume, deployment or recovery.

**Provenance and decision (Outcome B).** The [repository base](../../docker-compose.yml)
has no `env_file` and explicitly maps environment variables, as did the initial
import `5c310c6`. Available non-shallow history of Compose/templates/deployment
paths did not reveal an `env_file` provisioning template; relevant introductions
were helper rejection predicates (`82b2ecb`, `5e4bb8a`). The initial SSH workflow
used an existing host directory; the current [release runner](../../scripts/deploy/quiesced-release.sh)
uploads the helper, not the repository base. The helper requires the base to
preexist and generates the fixed image-only managed override. Retained state
captures prior override/identity/checkpoint records, not an authenticated copy
of the base; `compose_path_hash` hashes the directory string, not Compose bytes.
Passing managed/retained checks therefore cannot identify who introduced this
base key. Legacy/manual bootstrap is plausible, not proven. The confirmed
divergence is a lexical mapping-branch `env_file` occurrence in captured stage
base absent from repository base, not proof of a normal service `env_file`.

The helper deliberately rejects this key before Compose normalization, including
collisions in `environment`. It later passes its privately captured `.env` as
`--env-file` for interpolation. Neither that contract nor repository mappings
prove that removing a live service key preserves its effective environment.
No mutating remediation is prepared and the helper predicate remains unchanged.
The extension below distinguishes lexical location/form without reading
secret-bearing dependencies. The P1-corrected `f261d62945aabb2dc337c76fe9413c588ec94e56`
passed independent review and was merged through PR #510 at the base recorded
above. The subsequent user-provided v2 result is also recorded above. Every new
execution still needs explicit dispatch authorization; no authority is inferred
from the original confirmation token or this historical execution.

These paths do not extend corrected inspect, construct `BoundContext`, change
approved implementation/root-binding or authorize repair, claim, resume, deploy,
recovery or rollback. No stage execution is authorized by the fixture fix.

The sole dispatch input is confirmation
`CLB-91:35206468948:diagnose-compose-subset`. Repository/default branch/ref are
fixed to `koteev-m/clubs_bot`/`main`; Environment is `stage`, concurrency is
`payments-schema-stage`, and cancellation of in-progress work is disabled.
Validation occurs before SSH credentials in both jobs and again in execution;
checkout and source snapshots use exact dispatched `github.sha`. Attempt other
than 1 is rejected. Before executing any loaded project module, the diagnostic
uses its own byte-identical copy of the existing bounded capture primitive to
verify the complete fixed closure against dispatched Git objects. This includes
the consumer, `corrected-stage-release.py`, `release_private_root.py`,
`release_authority.py` and the remote diagnostic. Local consumer/shared-module
bytes must equal their Git blobs; shared modules also match fixed SHA-256 pins.
All three shared modules compile before any of them execute. Only the pinned
corrected transport's `source_module` AST definition is omitted; its two fixed
calls resolve captured dependency modules. The rest of that source is unchanged.
There is no generic import hook, working-path reread, `.pyc`, `sys.path` or
revision fallback. Incomplete/mismatched/unreadable source or cancellation before
execution fails without executing project modules or submitting SSH. Replacement
after capture cannot substitute new executable bytes; only the verified snapshot
can execute. Each new invocation verifies its own snapshot.
The same deployment principal and anonymous pinned `SSH_KNOWN_HOSTS` primitive
are used, with one bounded strict SSH transport and no sudo/keyscan/proxy/retry.
Import is inert; remote startup is `python3 -I -S -B` without a remote temp script.
One invocation and rejection of reruns are **not** durable incident-wide
consumption. Every future dispatch needs separate explicit user authorization
and manual `stage` Environment approval. No execution is authorized by this text.

The fixed base is `/opt/clubs-bot-stage/docker-compose.yml`. A no-follow directory
chain, canonical protocol directories, exact application binding/backing
fingerprint, and existing application then operation **shared, nonblocking**
locks are required before reading the base or its dependent static inputs. Locks
are not created or changed. The base is opened once, without create/follow,
and must be a deployment-owned regular single-link file, mode `0600|0644`, on
the expected device. Byte 65536 is allowed; observing byte 65537 fails closed.
Held descriptors, pathname edges and detectable size/mtime/ctime/inode drift are
revalidated before/after reads and before completing collection, while locks
remain held. Advisory locks cannot exclude root or noncooperating same-UID
writers, and metadata checks cannot prove absence of undetectable writes.

No recursive walk, target create/truncate/chmod/chown/rename/unlink/fsync,
Compose normalization, Docker/Engine query, worker/RPC/HTTP/health check or
lifecycle action occurs. Ordinary reads can have system atime/audit effects.
The only subprocess is bounded `findmnt` over retained FD references, using the
approved fingerprint predicate. There are at most 16 such captures (initial and
final), each at most 4096 output bytes/2 seconds, within a 20-second operation
deadline and 25-second bootstrap alarm; the single SSH capture is bounded by
45 seconds. Each of four fixed inventories is limited to 64 entries; there are
at most 48 retained FDs and 196608 total bytes read. Limit/timeout/cancellation
failure makes the whole report unavailable, never a partial complete report.

The lexical scanner operates only on captured bytes. Its BASIC vocabulary is:
`utf8_invalid`, `tab_active_line`, `document_prefix`, `directive_prefix`,
`tag_prefix`, `anchor_prefix`, `alias_prefix`, `flow_mapping_prefix`,
`flow_sequence_line_prefix`, `mapping_syntax`, `block_scalar_value`,
`top_level_key`, `key_include`, `key_extends`, `key_env_file`, `key_label_file`,
`key_build`, `key_configs`, `key_secrets`, `key_develop`, `key_provider`,
`key_models`. Violations are unique and sorted, with no counts or line numbers.
Valid UTF-8 uses the original strip/splitlines, comment skipping, list-vs-mapping
branch, mapping grammar, case sensitivity and positional top-level rule. Thus
`key: [a,b]` is accepted, quoted literals are not automatically anchors, and an
`env_file` key inside `environment` still collides lexically. An empty file clears
this lexical scope but is not valid Compose. Invalid UTF-8 adds `utf8_invalid`;
surrogateescape permits further private indicators without normalizing YAML.

`mapping_details` uses only `bom`, `quoted_key`, `merge_key`, `x_extension`,
`other`; `top_level_details` uses the same fixed vocabulary (the current scanner
can produce `x_extension` or `other` there). These explain only reliably visible
lexical forms and do not alter BASIC acceptance or infer semantic context.
Arbitrary keys, service names, snippets and inline values never leave memory.
A lexical key does not prove that any external file was read.

**Structural output v2.** Two mandatory fields are derived only from the same
captured base bytes. BASIC scanner acceptance and the 22 categories are unchanged.
`env_file_occurrences=zero|one|multiple` counts only exact mapping-branch lexical
collisions (case-sensitive; comments, quoted keys and `- env_file: ...` list
items are excluded exactly as in the existing scanner). It is not the number
of semantic YAML keys or files read. `env_file_shapes` is `none`, or at most eight
unique sorted tuples `scope/service/form/reference`:

| Component | Fixed vocabulary / meaning |
| --- | --- |
| scope | `service`: plain indentation ancestry `services/<service>`; `environment`: `services/<service>/environment`; `other`: other plain mapping ancestry; `unresolved`: outline is unsupported/ambiguous. These are lexical observations, never a YAML semantic verdict. |
| service | Only already-public `app`, `db`, `caddy`; other names collapse to `other`; outside a service to `none`; unresolved outline to `unknown`. |
| form | `scalar`, `sequence` (plain block or a closed single-line flat flow sequence of scalars), `mapping`, `empty`; unresolved outline to `unresolved`. No general YAML parser or object normalization is introduced. |
| reference | Only for `service` scope: `canonical_dotenv` for exact `.env` or `./.env` literals (optional single/double quotes) or a nonempty plain sequence consisting solely of these literals; otherwise `other_or_unknown`. Other scopes always use `not_applicable`, so environment values are never described as references. |

Plain indentation maps require consistent sibling indentation/kind, empty-valued
parents and no duplicate mapping keys. Scalar continuations, unsupported list
objects/indentationless sequences, tabs/non-ASCII whitespace, invalid UTF-8, BOM, directives, documents,
anchors/aliases/tags, block scalars or ambiguous outlines discard **all** resolved
locations: the sole tuple is `unresolved/unknown/unresolved/not_applicable`.
Every mapping/list value must have a supported single-line boundary. Single and
double quoted scalars require actual closure; doubled single quotes and the fixed
YAML double-quote escape spellings are recognized without evaluating values.
Flat flow sequences require balanced closure, scalar items and valid separators;
nested collections, flow maps, implicit flow mappings, multiline quotes/flows,
malformed escapes and comments consuming the closer make the entire outline
unresolved. Brackets and `#` inside a closed quoted scalar are content; brackets
inside ordinary block plain text are not automatically flow syntax. A later
ambiguity invalidates earlier resolved tuples too; no guessed recovery point is
used. This is deliberately a conservative boundary recognizer, not YAML parsing.
The occurrence bucket still reports the scanner's collisions. Unresolved is
honest bounded evidence, not a partially resolved location list. Sequence object
forms and interpolated/escaped/absolute/parent paths do not receive the canonical
literal classification. No path resolution, interpolation, referenced metadata
probe, `.env` content read or environment equivalence comparison occurs.

Each tuple preserves the association between its four components; repetitions
are deduplicated, while the bucket distinguishes one from multiple occurrences.
No per-service/exact counts, arbitrary names, paths, snippets or values are
published. Processing permits at most 4096 active lines and 32 outline levels;
more than eight unique shapes or any processing bound yields whole-report
`unavailable/bounds`, never truncation. All capture, identity, FD/lock, static,
cleanup and final cancellation checks still gate publication of the whole body.
`canonical_dotenv` proves only a literal spelling, not file use, safe contents,
variable coverage or permission to delete the key. Even a resolved structural
result cannot by itself authorize remediation or prove environment equivalence.

The independent P1 against local candidate `b8beb3d77f8dfbc76564615b06d072f9370da246`
showed that a multiline quoted item in `labels: ["...` could make an embedded
lexical `env_file: .env` look like a service reference; an unclosed `[.env` also
received a resolved sequence form. Both were reproduced through the classifier
and authenticated bootstrap/consumer. The local correction above preserves
`complete subset=invalid violations=key_env_file` and the lexical occurrence
bucket, but returns only the unresolved tuple. This is a collected report with
unknown structure, not unavailable I/O or proof of semantic key absence. The
historical run 35262529462 remains v1 evidence only; live role/origin and any v2
result remain unknown. Local tests use the repository's existing safe YAML reader
only as an independent synthetic oracle; production gains no dependency or reads.

Fixed read allowlist and dependency map (paths here are design documentation,
never public diagnostic fields): `compose` is the fixed base directory; `parent`
is its `.clubs-bot-release-state`; `root` is `parent/stage`; `state`, `results`
and `ledger` are the existing `clubs-bot-schema-stage.lock`,
`clubs-bot-schema-stage.results` and `clubs-bot-schema-stage.migration-ledgers`.
The incident owner is fixed `33468965282-1`.

| Static field | Required bounded evidence / exact predicate |
| --- | --- |
| `static_inputs` | Metadata/read availability of the fixed record inputs below; missing/unsafe inputs are `invalid` here, not a claimed content mismatch. Binding failure instead makes the entire report unavailable. |
| `managed_override` | `compose/docker-compose.override.yml`, at most 4096 bytes; exact approved managed override bytes for fixed revision/image. |
| `managed_release` | `state/docker-compose.release.yml`, at most 4096 bytes; same exact managed bytes. |
| `dotenv_metadata` | Optional `compose/.env`, open/fstat only with the original regular/owner/nlink/mode-0600/device predicate; absent is allowed. `pass` means metadata only, never content validity. |
| `retained_layout` | Bounded `parent`, `root`, `state` inventories; original absent active anchor, allowed root names and exact state-name set. No file contents inferred from names. |
| `retained_identity` | Fixed state values `owner`, `expected_revision`, `image_digest`, `compose_path_hash`, `compose_project`, `compose_service`, `migration_image_digest`; exact approved equality, with project from trusted application binding. |
| `retained_checkpoint` | State `checkpoint`, `migration_image_id`; exact original enum/format predicates. |
| `prior_override` | `state/prior-override` (1024 bytes), `prior_override_exists`, `prior_override_sha256`, `candidate_override_sha256`, and captured managed override. Only when exists is exactly `yes`, also `old_app_digest`/`old_app_revision`; exact original bytes/hash/form predicates. |
| `migration_records` | Fixed `ledger/33468965282-1.ledger` and `.outcome` (2048 bytes each), bounded exact ledger inventory; original record keys/enums/identity/epoch syntax. |
| `result_record` | Fixed `results/33468965282-1.result` (2048 bytes), exact original record keys/enums/pairs/identity. |

All state values above are at most 4096 bytes. `parent/application.binding` is
at most 2048 bytes and is an obligatory trust dependency, not a skippable check.
No other file contents are read. In particular `.env` content, referenced
env/include/label/config/secret files, container identity records and consumption
marker contents are excluded. Migrated predicates are differentially tested
against scopes extracted from the exact approved production helper; no
`BoundContext` constructor or continuation after its exception is used.

Expected negative observations are narrowly classified: ENOENT from the fixed
file **open** is absence; ELOOP is metadata rejection only if no-follow `stat`
proves a symlink at that edge; an opened file failing the original regular/owner/
nlink/mode/device predicate is metadata rejection. Symlink observations are
retained and revalidated. Optional `.env` absence is `absent`; missing or
metadata-rejected record dependencies yield `not_evaluated` for the dependent
field, while other applicable checks still run. Other acquisition/resource
errors, including EIO, EACCES, EPERM, EMFILE, ENFILE, ENOMEM, ENOTDIR or an
unproven ELOOP, make the entire report `unavailable/io`, exit 1. ENOENT during
fstat/read after an open is not absence; disappearance of a previously observed
pathname instead fails identity revalidation. Interrupted acquisition remains
`unavailable/interrupted`; failed cleanup remains `unavailable/cleanup`. These
failures discard even an already collected portion of the report. A safely captured
record with a false exact predicate yields `invalid`. Malformed/missing protocol
directories, busy locks, changed edges/backing, I/O during capture or any bound
violation invalidate the entire collection. Static checks do not depend on
successful lexical scope or Compose normalization, and static failures are
collected jointly. No status reports record contents or proves runtime state.

Exact public field order for complete collection is:

```text
compose-diagnostic:v=2 result=complete subset=<clear|invalid> violations=<sorted BASIC enums|none> mapping_details=<sorted details|none> top_level_details=<sorted details|none> env_file_occurrences=<zero|one|multiple> env_file_shapes=<sorted fixed tuples|none> static_inputs=<status> managed_override=<status> managed_release=<status> dotenv_metadata=<status> retained_layout=<status> retained_identity=<status> retained_checkpoint=<status> prior_override=<status> migration_records=<status> result_record=<status>
compose-diagnostic:v=2 result=unavailable reason=<fixed reason>
```

Static status vocabulary is `pass|invalid|not_evaluated`, plus `absent` only for
`dotenv_metadata`. Unavailable reasons are `request`, `principal`, `layout`,
`identity`, `busy`, `backing`, `bounds`, `io`, `interrupted`, `cleanup`,
`transport`, `protocol`. Complete collection exits 0 even with violations;
unavailable exits 1. A bootstrap that cannot produce a trusted frame exits
nonzero with no accepted body; the consumer emits bounded unavailable evidence.
This is collection success, not a successful inspect/deploy or permission to
continue. No partial list is labelled complete.

The public body is at most 2048 bytes; the private HMAC frame at most 4096
(the maximum v2 enum contract measures 1177 and 1269 bytes respectively,
including eight tuple entries). A
fresh private nonce authenticates the entire canonical body. Unknown, duplicate,
reordered or extra fields, startup output, multiple/trailing lines, wrong HMAC,
nonce replay or exit/body disagreement fail closed.
The v2 consumer also rejects old v1 bodies, missing structural fields, invalid
tuple combinations, duplicate/unsorted tuples, bucket/category disagreement and
mixed unresolved/resolved observations. Private HMAC envelope v1 is unchanged
and authenticates all v2 fields. Both remote FD/process
cleanup and local SSH/pin cleanup precede publication. Final signal handoffs
reject cancellation already observed or pending before completion publication.
No contents, paths, usernames, UID/GID, line numbers, exact counts, content hashes,
container IDs, exception text or child stderr are public.

Local tests exercise real descriptors/flock/rename/read, exact-source lexical
acceptance and static-predicate oracles, and the actual bootstrap/consumer with
a substitute transport. Synthetic ownership/backing and Linux fault injection
on Darwin are identified as simulations; they do not replace run-specific hosted
or live evidence such as the user-provided v2 result above. No successful
future corrected inspect, external-reader compatibility or recovery readiness
is implied by any diagnostic result.

### CLB-91 incident-only Compose mode repair (reviewed prior capability)

User-provided run `35108589661` (run 6, attempt 1) reached authenticated
`helper_started/helper_blocked` with `compose_file_mode/invalid`, then
`STATUS_UNAVAILABLE`. This proves the mode predicate failed at that snapshot;
it does not prove the current mode or exclude later device/size/state blockers.
The direct metadata probe remained blocked and is not an alternative channel.

The separate [Stage Compose Mode Repair workflow](../../.github/workflows/stage-compose-mode-repair.yml)
and [runner](../../scripts/deploy/stage-compose-mode-repair.py) were independently
reviewed and published according to the user handoff above; that is not renewed
permission to execute. They do not extend corrected-stage actions or use
deploy-ssh's live keyscan path. Any future dispatch requires separate explicit repair authorization and manual `stage`
Environment approval. Confirmation is exactly `CLB-91:35108589661:repair-compose-mode-0600`;
it is a human-error guard, not a substitute for that approval. The only input is
confirmation. Repository/default branch/ref must be `koteev-m/clubs_bot`/`main`,
and reruns (`run_attempt != 1`) are rejected before SSH credentials. Checkout and
remote operation source are bound to dispatched `github.sha`. Shared concurrency
is `payments-schema-stage`, with no cancellation of in-progress operations.

The target is fixed `/opt/clubs-bot-stage/docker-compose.yml`; protected
`COMPOSE_PATH` must agree. Target mode is `0600`. Repository Compose clients run
under the deployment principal; the Compose YAML itself is not a container
mount. No other-UID reader was found in repository evidence. This does not prove
absence of external readers: compatibility must be checked before authorizing
application, without silently widening the target to `0644`.

The [fixed remote operation](../../scripts/deploy/stage-compose-mode-operation.py)
walks a trusted no-follow directory chain, retains descriptors, checks
regular-file/owner/nlink/device and rechecks object/path identities. It acquires
existing `application.lock` then `operation.lock` exclusively and nonblocking;
missing, malformed or busy locks block execution. No lock is created, removed,
truncated or reset. Snapshots detect in-invocation drift and are not persisted
root-binding approval. Advisory locks serialize cooperating clients; they do not
exclude malicious root/same-UID actors or noncooperating writers.

ACL/capability names or inability to enumerate them block before mutation.
Filesystem assumptions are restricted to Linux x86_64/aarch64 local ext[234],
XFS or Btrfs; unsupported filesystems (including NFS/SMB/FUSE) are rejected.
If mode is already `0600` or `0644`, no mutation occurs. Otherwise only one
`fchmod(target_fd, 0600)` may be attempted. Same-object readback must confirm
`0600`, with identity/size/mtime preserved; mode and ctime may change. No content
read, checksum, chown/chgrp, ACL rewrite, directory chmod, retry or rollback is
performed. A readback/cleanup/interruption failure after an attempted write is
ambiguous, never a reason to try again automatically.

SSH uses the same stage secrets, unchanged anonymous pinned-known-hosts/capture
primitives, strict host verification and principal guard as corrected-stage.
There is one transport, `ConnectionAttempts=1`, no keyscan/proxy/fallback/sudo.
The fixed source executes in isolated Python without remote temporary files.
A fresh private stdin nonce authenticates the single bounded result with HMAC;
raw startup/child output, wrong tags, unknown enums or duplicate lines cannot
be logged as accepted evidence. Stderr is discarded. Public output is exactly:

```text
compose-mode-repair:v=1 result=changed
compose-mode-repair:v=1 result=already_valid
compose-mode-repair:v=1 result=blocked reason=<fixed_reason>
compose-mode-repair:v=1 result=ambiguous reason=<fixed_reason>
```

Blocked reasons: `request`, `principal`, `layout`, `identity`, `busy`, `acl`,
`filesystem`, `io`, `interrupted`, `local`. Ambiguous reasons: `write`, `readback`,
`cleanup`, `interrupted`, `transport`, `protocol`. Transport failure/timeout or
unverifiable output after submission is ambiguous because chmod may have happened.
No username/UID/GID/path/prior-mode/inode/device/size/content/exception is printed.
No automatic inspect, Docker/Compose or lifecycle command follows. `changed`
means only verified mode repair, not recovery readiness. This capability neither
reads nor changes `CLB82_APPROVED_IMPLEMENTATION`/`CLB82_AUTHORIZED_ROOT_BINDING`;
their corrected-stage authority remains separate and unchanged.

Local tests use disposable fixtures, with Linux filesystem/ACL and selected
error metadata injected on Darwin. Hosted Linux/OpenSSH, actual filesystem ACL
behavior, reader compatibility and live application remain unverified.

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

**Предшествующий gate остаётся отдельным:** в порядке [PRODUCT_ROADMAP.md](../product/PRODUCT_ROADMAP.md#first-slice-execution-gate-protocol)
сначала применяются и независимо проверяются live `DEC-037` protection/main-only policy; затем устанавливается основание
доверия для exact host key и проверяется его привязка по fingerprint: по умолчанию independent provider/VNC authentication, либо только в exact
scope accepted `DEC-038` retained Ed25519 trust basis. Далее остаются read-only comparison complete repository/stage
secret names без overlap, provisioning/independent verification pinned stage `SSH_KNOWN_HOSTS`, отдельное разрешение и
один исходный `Release Status (read-only)` dispatch, затем проверка его полного результата под deployment principal;
subsequent execution authority остаётся отдельной.
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
producer provenance v3. Прежний standalone Python claim удалён; расход разрешения принадлежит новому helper path.

F1: исходный protected status job с `STATUS_PROVENANCE=yes` выдаёт одну строку
`release-status-evidence:v=3 {…}` после проверки полного SSH stream старым runner. В ней canonical trusted
status, environment/tag/owner/revision, requested operation, incident helper hash, GitHub repository/workflow/ref/event/run/attempt/
executed revision, hashes principal/Compose path и boolean `exact_incident`. Raw `IMAGE_DIGEST` и его hash
не публикуются как provenance authority: GitHub secret masking может изменить даже несекретный digest в persisted log.
Producer сам вычисляет `exact_incident=true` только при совпадении фактических inputs с immutable `INCIDENT`
в `release_authority.py`: `stage`, `deploy-stage-44497dc`, owner `33468965282-1`, revision
`44497dcd28139cef865c3f98ac3f2c4a5afac636`, exact accepted digest
`ghcr.io/koteev-m/clubs_bot/app-bot@sha256:ddf5486e02835855178cc3b30bd2f22899335131e6dc388def20feac328016fe`,
плюс `ORIGINAL_OPERATION=start` и `INCIDENT_HELPER_SHA256` выше. Caller не задаёт attestation.
`corrected-stage-release.py` использует тот же immutable mapping из уже изолированно загруженного модуля;
consumer независимо проверяет собственный current incident tuple по этому mapping. Другой допустимый incident,
digest, operation или helper даёт `exact_incident=false`; generic trusted read-only status остаётся успешным,
но такая запись не является execution authority. Raw captures, SSH credentials и произвольные логи не публикуются.
Server-side канал по-прежнему read-only и исполняет retained incident bytes. Failure transport, malformed status
или cleanup failure не могут стать успешным verified evidence; общий job должен завершиться успешно.

Consumer самостоятельно использует `gh api --hostname github.com --method GET` с fixed repository paths:
workflow identity, конкретный run attempt, его полный список двух jobs и logs именно его status job. Проверяются
run/attempt/job/step success и связи с `head_sha`, `main`, `workflow_dispatch`, stage и точным incident/start.
Через Contents API на exact `head_sha` сверяются bytes producer workflow, старого runner и трёх его dependencies
с доверенным checkout consumer. Старый код без provenance или иная producer revision с отличающимися bytes не
принимаются. Attestation принимается только после authentication run/job/step и всех producer bytes, только
с JSON boolean `true` (не `1`, string, missing или false), при exact field inventory. Только v3 получает authority;
v1/v2, mixed versions, duplicate records/JSON keys и malformed evidence fail closed. Masking любого оставшегося
обязательного поля также блокирует authority; v3 устраняет зависимость от raw digest, а не обходит masking вообще.
Job log ограничен 1 MiB в памяти; принимается ровно одна bounded evidence line, остальной log не
выводится и не сохраняется. Полный canonical result обязателен: одних conclusion или произвольного JSON недостаточно.
Требуются availability/owner/revision/digest=`yes`, `failure_category=none`, `checkpoint=migration_completed`,
`migration_evidence=present`, `operation_result=remote_failure` именно для `start`, canonical Compose path hash
и principal binding; positive attestation не заменяет эти predicates.
`resume_permitted=no` старого selector принимается; corrected readiness и explicit authorization остаются отдельными
gates. Основной CLI заново проверяет evidence, включая совпадение principal hash, перед любым SSH, поэтому обход
workflow validation не открывает executor. Отсутствие/удаление logs, API failure, oversized/malformed или смешанные
attempts блокируют execution. Child environment не наследует debug/credentials settings; `gh` update notifier и
telemetry явно отключены. Отдельных artifacts, PAT, write permissions или нового сервиса нет.

**CLB-90 historical observation и masking defect.** Run [34437951351](https://github.com/koteev-m/clubs_bot/actions/runs/34437951351),
attempt 1, implementation `3f8a5d978ff5d0d17f0d57736ef819376895a13c`, завершился success
2026-09-10 и вернул trusted channel для `requested_operation=resume-start`: `migration_completed`,
`migration_evidence=present`, `app_state=ambiguous`, `operation_result=unavailable`, оба permissions=`no`.
Это historical trusted observation, не usable execution-authority `PRIOR_STATUS`: persisted v2 provenance
содержит замаскированный raw digest, а requested operation/result также не соответствуют требуемому `start/remote_failure`.
V3 исправляет producer/verifier contract локально; historical log не исправляется и v2 fallback отсутствует.
Corrective `Release Status requested_operation=start` остаётся blocked до review/merge нового producer contract,
fresh ordered gates и отдельного dispatch authorization. Этот документ не разрешает dispatch или recovery.

**CLB-91 — corrected-side phase diagnostics (local continuation, 2026-09-10).**
По подтверждённому user handoff usable v3 `PRIOR_STATUS` теперь
`34510160767:1:b8bcd3029f9397963be5d2b92839b5e0f132933a`.
Corrected run [34514548987](https://github.com/koteev-m/clubs_bot/actions/runs/34514548987)
завершился `corrected-stage:v=1 result=blocked category=PRIOR_STATUS_UNAVAILABLE` в validate job,
до Environment gate и SSH. Actions/Contents/Metadata read permissions и непустой masked `GITHUB_TOKEN`
были present. Локальный verifier выполнял все девять reads; exact hosted failing endpoint, HTTP status
и transport root cause остаются unresolved. Это handoff evidence, не новый hosted diagnostic run.
`CLB82_APPROVED_IMPLEMENTATION` уже provisioned; `CLB82_AUTHORIZED_ROOT_BINDING` всё ещё отсутствует.
Эти сведения уточняют historical CLB-82/90 gate snapshots выше, не переписывая их evidence.

Локальный adapter в corrected executor применяется и в `--verify-prior`, и перед SSH в обычном execution path.
Он принимает только current fixed `gh api` argv contract и выводит при capture failure ровно один marker:

```text
corrected-prior-api:v=1 phase=<fixed_phase> failure=<fixed_failure>
```

Allowlist фаз: `workflow_metadata`, `run_attempt`, `source_workflow`, `source_status_script`,
`source_private_root`, `source_status_pattern`, `source_authority`, `attempt_jobs`, `producer_job_logs`.
Failure classes: обычный ненулевой CLI exit → `command_failed`, capture code `124` → `timeout`,
`125` → `output_limit`, `OSError` непосредственно при process spawn → `spawn_failed`.
Success marker отсутствует. Unknown argv/timeout/limit contract блокируется до capture фиксированной
категорией `PRIOR_API_CONTRACT_INVALID` без echo неизвестного argv. Marker содержит только fixed tokens:
без stderr/stdout/body, HTTP status, argv, headers, environment, token, URL и credential/config paths;
GitHub response body для диагностики не разбирается.

Nonzero capture без изменений возвращается verifier и заканчивается `PRIOR_STATUS_UNAVAILABLE`;
spawn exception повторно выбрасывается и сохраняет terminal `LOCAL_FAILURE`. Internal/programming exceptions
не превращаются в `PRIOR_STATUS_UNAVAILABLE` и не маскируются как spawn failure.
Retries, fallback, credential/endpoint/permission changes и увеличение timeout/output limit отсутствуют.
Authenticated `PRODUCER_PATHS`, включая `release_authority.py`, helper bytes/pins и workflows неизменны:
существующий usable v3 source snapshot остаётся совместимым. Diagnostics не дают inspect/recovery authority.
Patch предназначен для следующего отдельно разрешённого hosted прохода после review/publication;
в этой local continuation нового inspect dispatch, Environment approval или SSH execution не было.
Synthetic tests проверяют instrumentation contract, а не real HTTP/TLS/hosted-token behavior.

**CLB-91 — producer job-log escape compatibility (local continuation, 2026-09-11).**
Следующий hosted run [34617555667](https://github.com/koteev-m/clubs_bot/actions/runs/34617555667)
локализовал отказ: `corrected-prior-api:v=1 phase=producer_job_logs failure=command_failed`.
По user handoff локальная диагностика exact endpoint/bytes воспроизвела несовместимость:
`gh 2.82.1` PASS, `gh 2.100.0` default FAIL после download, modern CLI с
`--allow-escape-sequences` PASS; `302` → signed download `200`, без Authorization на втором hop.
Historical hosted stderr/HTTP hops не сохранены: этот mechanism сужает root cause,
но не является полным ретроспективным доказательством причины hosted failure.

Official availability boundary — `gh api` в `v2.97.0`: flag отсутствует в
[v2.96.0 source](https://github.com/cli/cli/blob/v2.96.0/pkg/cmd/api/api.go),
добавлен вместе с non-JSON escape guard в
[v2.97.0 source](https://github.com/cli/cli/blob/v2.97.0/pkg/cmd/api/api.go).
[Release notes](https://github.com/cli/cli/releases/tag/v2.97.0) и
[GHSA-3m3g-3wcr-px46](https://github.com/cli/cli/security/advisories/GHSA-3m3g-3wcr-px46)
объясняют terminal-injection boundary. Production adapter использует capability, не version comparison.

Только перед `producer_job_logs` corrected executor выполняет offline `gh api --help`:
private capture, timeout `5 s`, output limit `32768`, credential-free environment и временный
пустой config directory с mode `0700`, удаляемый после probe. Это необходимо, поскольку CLI
загружает config даже для help; child-only `GH_CONFIG_DIR` исключает чтение saved credentials.
Installed CLI/config/PATH не меняются. API request, token read, pager и network update check
в probe отсутствуют. Корректный `USAGE`/`FLAGS` без нового flag сохраняет old CLI argv;
opt-out добавляется только при объявлении exact flag в `FLAGS`, не по example/substring.
Failed/malformed probe останавливает retrieval, не считается legacy capability и не запускает
log request. Сохраняются девять phases и четыре fixed failure classes; probe failures относятся
к `producer_job_logs`, malformed help — `command_failed`. Ошибки local setup остаются `LOCAL_FAILURE`.

Opt-out безопасен в этой границе: stdout дочернего процесса идёт только в bounded private pipe/memory,
stderr отбрасывается; raw log, ANSI content, token, headers и signed URL не печатаются. ANSI bytes
не попадают в terminal/pager и не используются как shell/code. Unchanged verifier получает exact
downloaded bytes, извлекает только fixed evidence после run/attempt/job/source authentication и
применяет прежние semantic predicates. Strip/normalize log и новая acceptance predicate отсутствуют.
Для самого log capture остаются `30 s` и `1048576` bytes; probe добавляет отдельные bounded `5 s`,
не увеличивая API timeout. Другие восемь API calls, redirect/auth semantics, endpoint, credentials,
permissions, helper/workflow/producer bytes неизменны. Retries, fallback credentials и alternative
endpoint отсутствуют. Оба production call sites (`--verify-prior`, normal execution до helper/SSH)
используют этот adapter.

Fresh independent local probe после regression tests: disposable official macOS arm64 `gh 2.100.0`
скачан заново, archive SHA-256 проверен по official release checksums; installed `gh 2.82.1` сохранён.
В обоих случаях production `verify_prior()` с production adapter/capture PASS для usable prior
`34510160767:1:b8bcd3029f9397963be5d2b92839b5e0f132933a`, producer job `102982071336`.
Probe выбирает absolute executable только на process-launch boundary, без изменения PATH/validator;
каждый путь выполнил девять read-only API requests и один offline help probe.
Log: `36630` bytes, `250` ESC bytes,
SHA-256 `1ce46d4e6383ece60483ccf4d1216a148b504e5007547859ab35e6ecab4e5207`;
evidence SHA-256 `704e919ff3d4d97c6afcb7270693dfad77ea7eef8a9fca84d4a824d59ae470d5`.
Public raw-log output отсутствует. Это local macOS/current-credential evidence, не hosted Linux/token
verification. Hosted verification после fix ещё не было; нового inspect, stage access, publication
или recovery authority нет. Пять `PRODUCER_PATHS`, включая `release_authority.py`, остаются
byte-for-byte source-compatible с существующим usable prior.

REST interfaces: [run attempt](https://docs.github.com/en/rest/actions/workflow-runs#get-a-workflow-run-attempt),
[attempt jobs и job logs](https://docs.github.com/en/rest/actions/workflow-jobs),
[repository contents](https://docs.github.com/en/rest/repos/contents#get-repository-content).

**CLB-91 — runner SSH boundary diagnostics (local continuation, 2026-09-12).**
Hosted inspect [34707584391](https://github.com/koteev-m/clubs_bot/actions/runs/34707584391),
attempt `1`, run number `3`, job `103590359083`, head
`ff6913973340f5b96819cbf0c0da2daa2ecf4bcd` завершился
`corrected-stage:v=1 result=blocked category=STATUS_UNAVAILABLE`.
Предыдущее расследование подтвердило prior/execution authority, загрузку deployment key,
local pin validation и запуск SSH child. Session establishment / remote principal guard —
первая недоказанная граница. Candidate/claim/resume отсутствуют. Stderr был подавлен,
а `status("inspect")` отвергал nonzero до разбора stdout. Поэтому retained evidence не различает
transport, bootstrap и helper failure:
`CLB_91_INSPECT_STATUS_UNAVAILABLE_EVIDENCE_INSUFFICIENT`.
Новые milestones ниже в этом historical run **не существовали** и его root cause не устанавливают.

Independent review локального patch нашёл P2 F1: заранее известный stdout prefix мог ложно
повысить diagnostic boundary до helper при отказавшем principal guard. F1 исправлен private
transport-bound framing: каждый transport получает отдельный случайный 256-bit nonce, переданный
только отдельным полем существующего bounded stdin payload. Nonce не входит в SSH argv,
environment, command text, helper stdin/control/argv/environment или публичные outputs.
Bootstrap выдаёт отдельный HMAC-SHA256 tag для каждой exact boundary; runner принимает только
tags текущего вызова. Сам nonce не выводится. Helper не получает ни ключ, ни private framing.
Это аутентификация диагностических меток в пределах доверенного runner/bootstrap и private stdin
transport, не execution authority и не защита от скомпрометированного SSH peer/remote OS.

`principal_ok` теперь выдаётся самим bootstrap, который exec-ится только после прежних двух
principal guards; `bootstrap_entered` — после Python startup/isolation guard и чтения private
binding. До этого чтения отсутствие метки не доказывает отказ самого principal guard.
`bootstrap_ready` следует framing, implementation hash/size/blob и private control-FD checks;
`helper_started` — успешному `Popen` Bash, до передачи approved helper bytes. Последний marker
доказывает создание процесса, но не parsing helper или его внутренние root/config/state checks.
Bootstrap не заменяет их. Private prefix полностью удаляется перед canonical status/candidate/ACK
parsing. Startup noise, wrong/missing tag, duplicate/reordered/skipped/partial frame или framing
в helper stdout закрываются целиком: public boundary `local`, fixed `framing_invalid`, без raw bytes
и без successful canonical parsing. На local capture failure сохраняется его отдельная категория.

На failure публикуется только одна bounded строка:

```text
corrected-ssh:v=1 boundary=<fixed_boundary> failure=<fixed_failure>
```

`boundary` — последняя последовательно доказанная граница из списка выше либо `local`, если
remote boundary не доказана. Это evidence, а не authorization. Allowlist `failure`:

| Fixed failure | Точное значение |
| --- | --- |
| `capture_timeout` | Локальный capture исчерпал deadline. |
| `capture_output_limit` | Capture превысил stdout bound; body отброшен, milestones не сохраняются. |
| `capture_interrupted` | Локальный capture получил обработанный runner signal либо явный `InterruptedError`. |
| `capture_failed` | Local capture OSError, включая spawn; terminal остаётся `LOCAL_FAILURE`. |
| `capture_cleanup_failed` | Не удалось доказать cleanup собственной process group/reap/close; terminal `LOCAL_FAILURE`. |
| `framing_invalid` | Private framing невалиден; все diagnostic boundary claims отброшены, canonical parsing не разрешён. |
| `child_signal` | Наблюдаемый отрицательный returncode локального SSH child; не вывод о remote signal. |
| `principal_not_proven` | Nonzero без доказанного principal-passed boundary; причина connect/auth/host-key/guard не различается. |
| `bootstrap_not_ready` | Principal passed, но bootstrap-ready boundary не доказана. |
| `helper_not_reached` | Bootstrap ready, но успешный запуск Bash child не доказан. |
| `helper_blocked` | После helper-started получен только exact `corrected-start:v=1 result=blocked` с одним LF. |
| `child_nonzero_unknown` | Helper-started доказан, но bounded body пустой, malformed или содержит иной/дополнительный output. |

Capture использует внутреннюю причину завершения: обычные child exit `124`/`125` больше не
считаются timeout/output-limit в SSH diagnostics. Общий `capture()` сохраняет прежний tuple API
и compatibility behavior для остальных callers, включая prior API adapter. Production обрабатывает
только `SIGHUP`, `SIGINT`, `SIGTERM`. На serial main-thread capture его existing handler записывает
scoped cancellation flag без исключения: scope установлен до `Popen` и снят только после cleanup
либо failed spawn. Capture проверяет flag между bounded selector/wait iterations; EINTR retry не
теряет cancellation. Final outcome выбирается после снятия scope, поэтому signal на последнем
handoff не превращается в cached success. Несколько signals внутри scope дают один interruption
outcome после cleanup: compatibility code `124` с internal `capture_interrupted`. Cleanup failure
имеет приоритет и остаётся fail-closed. Signal mask и handlers capture не изменяет, child наследует
исходную mask; искусственной очереди blocked signals не создаётся. Вне scope сохраняется прежний
terminal `CaptureInterrupted` / `LOCAL_FAILURE`. Это контракт serial production callers, не новый
параллельный capture API или изменение signal policy за пределами invocation.

P2 F2 review выявил inherited cleanup defect: живой descendant мог пережить capture, если leader
уже завершился. Capture теперь удерживает leader unreaped (`waitid/WNOWAIT`) до единственного
`SIGKILL` собственной process group, созданной `start_new_session=True`. Cleanup выполняется при
любом исходе, включая success/nonzero и EOF после закрытия descendant stdout; затем leader
reap-ится с bound 2 s, pipes закрываются. Final independent review обнаружил ещё один P2:
cancellation после spawn или на входе в finalizer могла обойти cleanup. Scoped deferral теперь
охватывает оба перехода заранее; внутри них handler не может unwind-ить ownership. Локальные
regressions доставляют реальные supported signals непосредственно на обоих edges, в том числе
pending и повторные signals, и проверяют descendant EOF, single kill/reap/close и signal state.
`ProcessLookupError` безвреден. Darwin `EPERM` для zombie-only group допустим только при последующем
доказанном отсутствии группы после reap; проверка signal 0 не повторяет command/kill. Иная ошибка
cleanup закрывается фиксированной категорией без OS text. Гарантия относится к оставшимся членам
созданной process group: им посылается `SIGKILL`, leader boundedly reap-ится. Она не распространяется
на descendants, намеренно покинувшие session/group, и не обещает время реакции зависшего kernel task.
Никакой сырой body,
stderr, exception text, host/user/path/key не печатается и не сохраняется как diagnostic artifact;
stderr остаётся `DEVNULL`, nonzero stdout после классификации отбрасывается. Capture bounds,
единственный SSH attempt и strict pinned-host trust сохранены. Retries/fallback отсутствуют.
`STATUS_UNAVAILABLE` по-прежнему возникает до status parsing на nonzero; success/readiness,
prior verification, root-binding и one-use resume authorization predicates не меняются.

Approved helper **byte-for-byte unchanged**: revision
`b8bcd3029f9397963be5d2b92839b5e0f132933a`, blob
`fc09080ba4864133ca23ec5c777339b881094279`, size `174422`, SHA-256
`48bcbafde22b90dc3902cac0ba80754239964466ce612d11565dd1fdeb75e2ec`.
Внутренние причины helper не диагностируются. `inspect` остаётся read-only и не вызывает claim/resume.
Protected root binding по handoff отсутствует; эта локальная работа его не читает и не provision-ит,
не создаёт recovery authority. Будущий inspect требует **отдельного разрешения** после review/publication;
этот шаг не dispatch-ит workflow и не обращается к stage.

**CLB-91 — fixed inspect guard diagnostics (local candidate, after run #4).**
Описанный выше published runner PR #504 совместим с прежним однострочным helper rejection.
Post-diagnostics inspect [34981781124](https://github.com/koteev-m/clubs_bot/actions/runs/34981781124),
run number `4`, attempt `1`, head `281a32472c27c0f4afad7ae3c2eced57e124d0e9`, дал
`boundary=helper_started failure=helper_blocked` и terminal `STATUS_UNAVAILABLE`.
Это доказывает запуск helper и его generic rejection, но не конкретный внутренний guard:
`CLB_91_HELPER_BLOCK_NARROWED`. Status/observation/binding candidate не получены;
claim/resume/provisioning/recovery не выполнялись. Historical run `34707584391` не получает
ретроспективно новых diagnostics; его exact root cause остаётся недоказанной.

Локальный кандидат расширяет только fatal `inspect` body до ровно двух ASCII/LF строк:

```text
corrected-inspect:v=1 guard=<fixed_guard> failure=<fixed_failure>
corrected-start:v=1 result=blocked
```

Static guard allowlist: `control`, `input`, `principal`, `compose_chain`, `compose_owner`,
`protocol_layout`, `protocol_device`, `lock_files`, `lock_shared`, `context_edges`,
`application_binding`, `mount_query`, `mount_identity`, `configuration_capture`,
`compose_file`, `compose_file_type`, `compose_file_owner`, `compose_file_nlink`,
`compose_file_mode`, `compose_file_device`, `compose_file_size`, `compose_subset`,
`override`, `dotenv`, `compose_command`, `compose_model`,
`binding_candidate`, `retained_layout`, `retained_identity`, `retained_checkpoint`,
`prior_override`, `migration_records`, `result_record`, `worker_protocol`, `worker_capture`,
`status_classification`, `status_read`, `inspect_output`, `finalize`, `interrupted`, `internal`.
Static failure allowlist: `invalid`, `mismatch`, `missing`, `permission`, `busy`, `command`,
`protocol`, `io`, `interrupted`, `internal`. Это semantic operation и механизм отказа,
не значения защищённых inputs/state и не конкретный syscall или внутренний exception type.

`compose_file` остаётся scope для open/fstat/read и поэтому сохраняет fixed
`missing`/`permission`/`busy`/`io` attribution. Только существующие boolean rejections получают
более точные `invalid` guards: `compose_file_type` (regular file), `compose_file_owner`
(effective-UID match), `compose_file_nlink` (ровно одна ссылка), `compose_file_mode`
(`0600`/`0644`), `compose_file_device` (тот же filesystem device) и `compose_file_size`
(не более 65536 прочитанных bytes). Значения UID/mode/link/device/size не публикуются.
Size contract остаётся read-based: ровно 65536 bytes разрешены, rejection возникает только
после наблюдения следующего byte; metadata `st_size` не заменяет чтение.

Guard scope прикрепляет только fixed tag к исключению, приводящему к terminal rejection;
innermost fatal scope сохраняется при propagation. После success или обработанного RPC exception
глобального last-guard state нет. Неожиданная ошибка вне scope — `internal/internal`, обработанное
прерывание — `interrupted/interrupted`. Ordinary negative ready/healthy RPC по-прежнему даёт valid
status с `resume_permitted=no`; transient RPC failure без recorded cancellation не выдаётся как fatal guard.
Recorded cancellation проверяется после recoverable RPC catch, в worker/status safe points и перед
success publication: она не может превратиться в ordinary negative readiness или `INSPECTED`.

Только inspect подготавливает status/candidate в памяти, затем ровно один раз выполняет обязательный
`BoundContext.close()` до их публикации. Его handler записывает SIGINT/SIGTERM/SIGHUP от установки
до завершения inspect finalization; он не unwind-ит ownership transitions или cleanup. После cleanup
восстанавливается immediate interruption и проверяется recorded flag до success output. PR #504
runner capture/cancellation ownership, process-group bounds и signal policy mutation phases не меняются.
`close()` делает best-effort попытку закрыть каждый owned stream/FD один раз, включая остальные
ресурсы после ошибки одного close; сохранены порядок streams → reversed FDs и lock release через close.
Новый `finalize` обозначает failure обязательной finalization, не ошибку уже начатого output.
Primary fatal cause сохраняется при secondary cleanup failure; без primary cause первая cleanup
ошибка становится terminal diagnostic. Recorded signal без другого fatal cause даёт
`interrupted/interrupted`. Никаких raw secondary exceptions, повторного close или success body
при failure finalization. Это не обещает доставку diagnostic при неисправном/частично записанном stdout.

Runner принимает новый body только после authenticated `helper_started`, на nonzero child result,
с exact grammar, allowlisted значениями и bound 160 bytes. Legacy exact one-line blocked остаётся
допустимым. Для нового body он печатает только проверенную первую строку, затем прежний
`corrected-ssh:v=1 boundary=helper_started failure=helper_blocked`; terminal остаётся
`STATUS_UNAVAILABLE`. Extra/duplicate/reordered/unknown/CRLF/raw child bytes не публикуются.
Успешный inspect не выдаёт failure diagnostic; canonical status/candidate contract прежний.

Это только diagnostic evidence, не execution authority. Claim/resume/reconcile output и predicates,
prior verification, protected binding, SSH trust и capture/cancellation ownership PR #504 не меняются.
Inspect допускает отсутствие protected root pin; никаких retries, дополнительных transports,
persistent state writes или автоматического принятия candidate не добавлено.
Новые helper bytes имеют новую Git/blob/SHA identity, которую runner обязан проверить. Прежняя
protected approved implementation не изменена этой локальной работой. Candidate **не approved**:
после независимого review и отдельной publication потребуется отдельное решение об approval новой
identity; live inspect также требует отдельного явного разрешения. Hosted/live execution новых bytes
не проверялся.

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
равенства HEAD. Consumer проверяет эти exact GitHub sources; versions 1/2 и прежние уязвимые producers отвергаются.
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
Authenticity staging SSH host key остаётся отдельным последовательным gate: по умолчанию это independent provider/VNC
authentication, а только exact scope `DEC-038` использует принятое retained-key basis без заявления independent
authentication или первоначального происхождения ключа. Canonical `SSH_KNOWN_HOSTS` payload, provisioning и independent
verification pinned evidence остаются отдельными последовательными gates. Environment protection не доказывает существование
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
