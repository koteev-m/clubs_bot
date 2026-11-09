# OWNER_MANAGER_UX — unknown command recovery

2026-11-09, продолжение `CLB-owner-manager-ux`, без нового task/decision ID.

**OWNER_MANAGER_UNKNOWN_RECOVERY_FIX: PASS. READY_FOR_FOCUSED_REVIEW: YES.**
Локальная дельта, без publication/deploy. Внешний verdict предыдущего86-path кандидата
`OWNER_MANAGER_CALENDAR_FOCUSED_REVIEW: FINDINGS / READY_FOR_DRAFT_PR: NO` сохранён.

## 1. Root causes

1. Unknown retry использовал `plan?.overlaps` из старого React render. После
   London→UTC→London confirmed ref уже содержал London, approval ref был отозван,
   но старый UTC render не требовал overlap-согласия и пропускал retry.
2. Чтение committed receipt отсутствовало. UI пытался использовать mutation retry
   для выяснения результата. No-op materialization сохраняет durable receipt без
   роста calendar version; после полуночи fresh-plan gate правильно запрещал старую
   mutation, но одновременно блокировал получение уже существующего результата.

Оба дефекта воспроизведены до исправления: unit **2 RED**, Chromium/WebKit **4 RED**.
Evidence: `/private/tmp/clb-owner-manager-unknown-79qu19w9`.

## 2. Read-only resolution отдельно от execution

Добавлен узкий business-read endpoint:

```text
POST /api/client/clubs/{id}/calendar/commands/resolve
X-Telegram-Init-Data: fresh existing signed identity
Idempotency-Key: original key
body: exact original calendar request
```

POST используется, чтобы key/body не попадали в URL. Resolver использует прежнюю
таблицу `client_commands`, не вызывает `mutate`/`execute`, не создаёт command,
night, audit или grant, не меняет version/configuration. Новых migrations нет.

| Status | Что доказано | UI |
|---|---|---|
| `completed` | Exact actor/connection/key/request/operation/client/club receipt committed | Завершает unknown; показывает факт выполнения и актуальный авторизованный календарь |
| `indeterminate` | Actor lock занят; это может быть другая команда | Сохраняет original intent/key; доступно повторное read-only чтение |
| `not_committed_at_snapshot` | Actor lock получен, exact receipt отсутствует на момент проверки | Не выполняет mutation; показывает отдельный explicit retry, заново проверяемый перед dispatch |

Отсутствие receipt не отменяет delayed original HTTP request и не гарантирует,
что он никогда не придёт. Нового key по отсутствию автоматически не создаётся.
Unknown после no-op/потерянного ответа разрешается через read, независимо от смены
клубного дня, DST-согласия или роста calendar version.

Storage boundary: `client_commands` хранит result type/resource reference, а не
исторический response JSON. `completed` подтверждает durable success исходной
команды; `calendar` — current authorized view, как в прежнем replay contract.
UI явно пишет: «Операция выполнена. Показано актуальное расписание.» Исторические
интервалы/версия не выдумываются, новая command infrastructure не вводится.

## 3. Serialization, authorization и no-write proof

- Shared early Telegram guard и platform connection validation сохранены.
- Внутри transaction сначала берётся existing client lock и проверяются active
  membership/mapping, club.read и calendar.write, как при existing calendar replay.
- Затем actor row проверяется через NOWAIT внутри savepoint. Client→actor не
  создаёт цикла с mutation actor→client: resolver никогда не ждёт actor. При busy
  rollback до savepoint сохраняет ранее взятый client lock и fresh authority.
- Под actor/client locks receipt выбирается по actor+connection+hashed key;
  result type `club_calendar`, result id, client id и canonical request hash
  проверяются до получения current view. Чужие user/client/club/operation не
  становятся доступны от знания key. Wrong intent → existing idempotency_conflict;
  потерянные grants → existing404 до status/data.
- Business-read требует техническую non-readOnly JDBC transaction для row locks;
  ни одного DML business write в resolver нет. Ошибка откатывает locks/transaction.
- Raw key/credential/body не логируются и не возвращаются. Прежний error envelope,
  header validation и calendar64KiB body limit используются без ослабления.

Ktor/H2 и PostgreSQL тесты сравнивают fingerprints12 таблиц (commands/audit/nights/
configuration/users/clients/membership/grants), проверяют число effects, сохранность
locks/savepoint, restart, delayed original/duplicate, current grants и rollback.

## 4. Immutable retry и синхронный DST guard

`calendarCommand.ts` получает JSON-copy/frozen source и original wire request.
Effective rules восстанавливаются из именно этого body, а не rendered draft.
Перед каждым потенциальным retry проверяются:

- current confirmed club/configuration, server version, timezone, bound club day,
  full affected-date metadata и текущий canWrite;
- exact immutable plan identity и реальные DST opening/closing decisions;
- fresh `approvedPlan.current` для этого исходного intent, если рассчитанный план
  содержит overlap. Rendered `plan`/`overlaps` не участвуют в retry-решении.

Source callback синхронно отзывает approval; A→B→A не восстанавливает согласие.
Read-only completion не требует DST-согласия, потому что не исполняет команду.
Отдельная кнопка «Повторить исходную команду» появляется после read-only absence;
она не меняет body/key и не запускается автоматически.

Дополнительное confirmed finding focused review исправлено: календарный opt-in
`preserveUnknownOnRetryFailure` сохраняет pending intent при retry4xx/409 и
непринятом retransmission payload. Такой отказ сам по себе не отменяет delayed
original request. `version_conflict` effect не скрывает original confirmation,
пока command всё ещё unknown. First-attempt4xx и остальные command consumers
сохраняют прежнее поведение; общий command lifecycle целиком не переписан.

Безопасное obsolete завершение допускается только при read-only absence и
**returned receipt-snapshot version > original expectedVersion**. Эта monotonic
проверка доказывает, что old body уже не может выполнить новый эффект; результат
не объявляется успешным. Более новый параллельный GET сам по себе не заменяет
version из absence snapshot. Для переноса правок остаются fresh reapply и новое
подтверждение. Reversible timezone/day mismatch не служит таким доказательством.

## 5. Frontend lifetime и восстановление

`useCommand.resolve` — отдельный opt-in метод чтения pending identity. Он использует
те же lifecycle/scope epoch, abort/isCurrent и executing guard, что существующие
hooks, включая fencing глобальных authority errors. Concurrent clicks не создают
две reads/retries. Late responses старого клуба/Telegram identity/revoked epoch
не могут применить private view или завершить новый context.

Parent проверяет authority epoch и не откатывает более новый GET stale receipt view.
Malformed/foreign receipt DTO не считается completed. Resolver timeout/503 и
indeterminate оставляют body/key и read action. Новый безопасный read после
восстановления возвращает committed result и снимает blocked/unknown, включая
no-op после полуночи. Protected club-context cleanup не изменялся.

## 6. Регрессии и собственные проверки

| Проверка | Результат / evidence |
|---|---|
| Original-source unit RED |2/2, `vitest-red.log`|
| Original-dist Chromium/WebKit RED |4/4, `browser-red-verified.log`; фактически2 PATCH при ABA вместо1 и stuck no-op unknown|
| New unit unknown suite |13/13, `vitest-unknown-final.log`|
| Full Vitest |461/461,54 files, `vitest-full-final.log`|
| TS / full ESLint |PASS, `typescript-final.log`, `eslint-final.log`|
| New browser recovery + retry409 |14/14, `browser-retry-conflict-green.log`|
| Full affected mobile Chromium/WebKit |84/84, `browser-mobile-final.log`, light/dark320/390 existing journeys retained|
| New Ktor/H2 resolver suite |8/8, `backend-resolution-green.log`, XML/result/contract JSON|
| Affected ktlint/Detekt |PASS: core-data/app-bot ktlint; core-data/app-bot Main + app-bot Test Detekt|
| Full JVM + strict dependency graph |1511 passed/1 skipped,1512 total, `jvm-full-final.log`, XML archive|
| PostgreSQL current recovery + calendar + onboarding |153/153,no skips, `postgres-final.log`, XML archive/counts; owned loopback PG16 + disposable Testcontainers|
| Vite / dist / both JARs |PASS, `vite-final.log`, `dist-validator.log`, `packaging-check.json`|
| Actual packaged Ktor/H2 |New no-op lost-response receipt journey + owner/delegation4/4; existing Chromium/WebKit onboarding/team/config8/8;12/12 total|
| Focused invariant/security review |PASS/no remaining confirmed findings, `unknown-pipeline-focused-review.json` hashes final production inputs|
| Source/history scan, scope/links/manifest |Final verification JSON and sanitized scanner output in new evidence|

New unit tests cover immutable body reconstruction, read completed/indeterminate/
absence/error, late response after club/identity/revoke, duplicate lookup, stale
absenceV1 beside GETV2, retry409 TZ roundtrip and unacceptable retry payload. Browser
tests control HTTP response order and click before React commit; counters and
original body/key проверяются, а не только warning text.

New actual packaged test forwards the first PATCH to real Ktor until commit, drops
its response, then resolves through the real new endpoint. It verifies no-op version/
nights/storedDates unchanged, exactly1 PATCH, original read identity, visible outcome
and Back unblocked. Client clock is advanced; server midnight is separately tested
with real Ktor/H2/PG injected clocks. Это не выдаётся за изменение live server clock.

Backend production changed, поэтому old full-JVM/PG не подменяют текущую проверку.
Full ordinary JVM и relevantPG153 выполнены заново. Полный historicalPG corpus не
перезапускался; неизменённые unrelated migration/staff/security-script checks можно
reuse только по соответствующим inputs. Gate/baseline/threshold/dependency pins
не менялись. Подтверждённые style/test failures исправлены и affected checks повторены.

## 7. Inventory, scope и evidence

Existing registered worktree `/private/tmp/clb-owner-manager-ux-20261109`, branch
`codex/owner-manager-ux-20261109`; HEAD/base/merge-base/origin main preflight
`16761e43887036688028358524847db1af0e5326`. PROJECT_IDENTITY_OK, active foreign=0.
Никакого rebase/merge/clone/new worktree. Primary checkout и чужие changes сохранены.

Все предыдущие86 paths совпали с manifest
`99f80c9b8f8ae59fc5fa9548ed3881f55ea6e2fccfd80efdccce1eb9f947e011` до edits;
их bytes сохранены в новом `baseline/`. Старые evidence не перезаписывались.

Production delta: existing calendar-read enum/HTTP route, Jdbc command lookup,
OnboardingSql NOWAIT helper; frontend Calendar, new immutable-intent helper и
opt-in useCommand resolution/unknown retention. Related tests/config registrations,
generated chunks и эта документация — только текущий scope. Calendar resolver/
affected-date/DST policy, shared club-context cleanup, staff grants, delegation,
9-role target, V076 и immutable CONCEPT_SOURCE не изменялись.

`delta-paths.json` перечисляет каждый modified/new/replaced path, before/after
hashes/byte sizes; `candidate-manifest.json` фиксирует новый кандидат;
`unknown-delta.patch` — узкая дельта, `candidate.patch` — все изменения от base,
включая untracked. Старые generated chunks сохранены в baseline и заменены
новыми referenced assets; retired bundles не оставлены в shipping dist.

## 8. Ограничения и один следующий шаг

`not_committed_at_snapshot` не является cancellation. Если immutable intent
несовместим с актуальными inputs и receipt ещё не подтверждён, UI сохраняет key,
объясняет отказ execution и оставляет безопасное повторное чтение; не выдаёт новый
key и не придумывает terminal failure. Cancel/tombstone/receipt-history schema
здесь не добавлены. Pending identity живёт в текущем Mini App context, без browser
storage raw keys. Исторического JSON результата в existing storage нет.

First HTTP200 invalid first-attempt payload не заявлен как новый покрытый recovery
сценарий: scoped retention исправляет именно unknown retransmission. Real Telegram
WebView/devices, hosted CI и staging/prod не проверялись. Новое приложение требует
нового endpoint; при его недоступности unknown сохраняется и read можно повторить.

Один следующий шаг: независимый focused review unknown recovery дельты и нового manifest.
