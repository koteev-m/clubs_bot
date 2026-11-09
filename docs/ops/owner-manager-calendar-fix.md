# OWNER_MANAGER_UX — calendar reconciliation и DST consent

> Исторический этап. Следующий independent review подтвердил affected-date set и
> обычный DST consent, но нашёл два unknown recovery P2. Текущая локальная дельта:
> [read-only receipt recovery](owner-manager-unknown-recovery-fix.md).

2026-11-09, продолжение `CLB-owner-manager-ux`, без нового task/decision ID.

**OWNER_MANAGER_CALENDAR_FIX: PASS. READY_FOR_FOCUSED_REVIEW: YES.**
Только локальная дельта; publication/deploy не разрешены. Внешний verdict
`OWNER_MANAGER_FOCUSED_REVIEW: FINDINGS / READY_FOR_DRAFT_PR: NO` остаётся историческим
до нового независимого review. Проверенный отказ доступа и остальные части кандидата сохранены.

## 1. Причины двух P2 и исправления

**Удалённая dated rule:** прежний planner видел draft и фиксированный horizon,
но server reconcile также обрабатывает прежние dated rules, explicit touched dates
и все stored nights, включая скрытые за восьмью preview/cancelled rows. Удаление
exception 30.10.2027 вне horizon возвращает weekly fallback с London opening
31.10.2027 01:30. Теперь это требует явного подтверждения overlap.

**Устаревшее согласие:** строка `overlapChoice='first'` переживала новый GET.
Теперь consent хранит точный идентификатор проверенного плана; `approvedPlan` ref
синхронно отзывает его при изменении авторизованного source snapshot. Select получает
`first` только при совпадении текущего ID. Возврат A→B→A не воскрешает прежнее согласие.

Dispatch заново рассчитывает план от последнего `confirmed.current`, проверяет
совпадение source/base snapshot, текущий клубный день, plan ID и approval ref до
`command.run`. Это закрывает окно между GET callback и React effect/render.
Ручная правка, reapply, операция save/materialize и смена клуба не переносят consent.

## 2. Полный конечный affected-date set

Для `D = club businessDate`, `first = D−3`:

```text
[D−3 … D+55]
∪ previous.holidays/exceptions dates ≥ first
∪ proposed.holidays/exceptions dates ≥ first
∪ changed holidays/exceptions keys (включая remove и даты < first)
∪ all stored business dates ≥ first (включая CANCELLED)
```

Это тот же finite candidate set, что `ClubOperationalNights.reconcile`; ранее
сохранённые touched dates старше first уже входят через explicit changed keys.
Weekly changes влияют на effective resolution каждой даты набора. Предыдущее
расписание используется для delta/removed keys, новое — для конечного интервала.
Exception → holiday → weekly priority, opening-day и closing-day offsets сохранены.

Невозможно получить полный stored set из прежнего GET: `nights`/`cancelledNights`
обрезаны до восьми. Поэтому доказанно необходима минимальная additive metadata
в существующем ответе `reconciliation:{businessDate,storedDates}`. Она читается под
прежним client lock и club.read, вместе с version/TZ/rules. Ни новых grants,
endpoint, записей, migrations или changes к immutable night identity нет.

Новый UI передаёт optional backward-compatible `reconciliationDate` в PATCH.
Server сверяет её с текущим клубным днём до apply; при несовпадении использует
существующий `version_conflict`. Это не позволяет transaction после полуночи
расширить horizon за пределы проверенного дня. Legacy bodies без поля остаются
совместимыми. Новый UI без полной metadata или при несовпадении дня блокирует
запись и предлагает обновить проверку; усечённый preview не считается полным set.

## 3. DST decisions и идентичность плана

Plan identity — точная JSON serialization canonical tuples, без lossy hash:
club ID, server version, IANA TZ, source rules/metadata, draft effective rules,
operation, club day, sorted affected dates и конкретные результаты разрешения
(open/close instants, gaps, overlaps, invalid intervals). Object/array reference
identity не используется. Preview и send gate получают один результат planner.

Gap блокирует отправку, без принятия server auto-shift. Overlap допускает только
новое явное подтверждение действующего первого вхождения. Второе вхождение не добавлено.

Expired lookback пропускается только после консервативного доказательства в UTC:
latest closing candidate из IANA offsets раньше earliest current-day boundary.
Сравнение одних wall dates было недостаточно для суточного gap Apia 30.12.2011.
Gap candidates используются только для доказательства expiry, никогда как допустимый
сдвинутый интервал. Explicit touched old dates остаются проверяемыми; started,
frozen, dependencies и окончательный transactional результат решает прежний backend.

V067 constraints сохраняют точную семантику legacy inherited hours без нового DTO:
оба часа NULL → наследовать closing day; один NULL → вывести день из effective
close/open; оба заданы → использовать existing `nextDay`. Schema-invalid гипотеза
NULL hours + explicit TRUE была опровергнута H2 и не является подтверждённым дефектом.
Ненужное экспериментальное поле `nextDayOverride` удалено до финальной проверки;
проверка schema constraints позволила оставить inherited-rule исправление во frontend.

## 4. Unknown outcome и повтор

Повтор всегда сохраняет исходные body/key и исходный утверждённый intent. При тех же
optimistic preconditions перед retry повторно проверяются snapshot/plan/consent.
Если GET изменил inputs без изменения preconditions, повтор блокируется. Только если calendar version строго выросла, прежний body может получить уже
committed receipt либо `version_conflict` до mutation; новый план с новым expectedVersion не подставляется.
Изменение timezone/day при той же version само по себе не разрешает retry: без
совпадения проверенного плана и нового согласия он остаётся остановленным с сохранением
unknown body/key. Разрешение результата такой unknown операции при продолжающемся
расхождении inputs требует отдельного resolution contract; здесь его не добавляли.
Таким образом повтор не переносит DST-согласие на другую конфигурацию. После conflict
требуются fresh GET/reapply и новое явное подтверждение.

## 5. Регрессии и проверки

Новый evidence root: `/private/tmp/clb-owner-manager-calendar-rx84a1o5`.

- RED до fix: `vitest-red.log` — 4/4 meaningful failures; `browser-red.log` —
  8/8 Chromium/WebKit failures: удаление exception и fresh hours/TZ/version.
- Новый `calendar-reconciliation.test.tsx`: 26 tests. Exact finite date set,
  hidden stored/cancelled, add/change/remove holiday/exception, old touched,
  London gap/overlap, обычная Friday midnight→08, metadata completeness,
  source identity, version conflict/reapply, unknown body/key, late GET с no-op
  AbortController, A→B→A, valid legacy partial rules и Apia expiry.
- Новый browser suite: 16/16, обоим движкам задан device TZ Honolulu, club London,
  порядок HTTP controlled. Save и materialize, fresh GET, reapply, скрытые даты,
  gap no-write/no-success и synchronous dispatch через клубную полночь.
- Весь frontend: **448/448 Vitest**, **70/70 mobile Chromium/WebKit**, TypeScript,
  full ESLint, Vite и dist validator PASS. Screenshots через штатный
  `test.info().outputPath` в UUID per-run roots.
- Ktor/H2 новый parity suite: **8/8**, реальные persisted UTC/ID, hidden CANCELLED,
  gap canonical shift (UI блокирует), partial rules, Apia, old touched started,
  midnight rollback/audit/idempotency, cross-client/read-only authority.
- Полный `./gradlew test` на final backend: **1503 passed / 1 skipped** (1504 total),
  strict production dependency graph PASS. `gradle-final-jvm-package.log`, XML archive.
- PostgreSQL calendar Ktor: **23/23**; existing onboarding contract **122/122**,
  итого **145/145**, без skips. Первое combined invocation остановило только
  onboarding class до tests из-за отсутствующего explicit disposable URL; после
  создания собственного loopback-only PG16 тот же suite прошёл без изменений.
  Отдельный container остановлен и auto-removed; реальные базы не использовались.
- Actual packaged Ktor/H2: owner/assignee **2/2**, прежний onboarding/staff/config
  Chromium/WebKit **8/8**, итого **10/10**. Final installDist PASS; все5 dist
  ресурсов byte-identical в обоих app-bot JARs; production classes проверены отдельно.
- Final source/history secret scan, scope/diff/links/manifest: см. final verification JSON.
- ktlint обоих затронутых модулей и Detekt затронутых source sets PASS.

Команды и terminal results перечислены в новом evidence README/results.
Module formatter первоначально затронул 131 посторонний Kotlin-файл; все его
unowned изменения восстановлены по exact initial snapshot/HEAD, с отдельным
`formatter-restoration.json`. Ни baseline, ни gate/threshold не менялись.

## 6. Сохранность, состав кандидата и reuse

Repository `koteev-m/clubs_bot`; existing registered worktree
`/private/tmp/clb-owner-manager-ux-20261109`, branch `codex/owner-manager-ux-20261109`,
HEAD/base/origin main (read-only preflight)
`16761e43887036688028358524847db1af0e5326`. PROJECT_IDENTITY_OK, active foreign=0.

Все прежние78 paths сверены до edits по manifest
`62277ef9c190a9639e0413552c517068f8bf090d819c6835a4b9a0c6675c24ba` и сохранены в
новом `baseline/`. Прежние source/feature paths не удалены. Старые generated JS
заменены новой referenced сборкой и сохранены в baseline, не оставлены в shipping dist.

Изменена календарная цепочка, tests/runner registration, generated resources и этот
checkpoint. Три новых backend production deltas — только calendar metadata/day fence.
Прежние candidate backend17 paths, V076, grants, роли, preparation/delegation,
authority fencing/hooks/Cabinet и immutable CONCEPT_SOURCE остаются byte-identical.
Чужие checkout/worktrees не изменялись, индекс и HEAD сохранены. Старые evidence,
включая focused review, не перезаписывались.

Итог: **86 candidate paths**. Дельта к78: 12 изменённых прежних paths, 8 новых
source/test/doc paths и замена2 generated JS chunks; остальные64 прежних entries
сохранены byte-for-byte (включая ранее удалённые tracked dist resources).

Новые к кандидату paths:

- `core-data/src/main/kotlin/com/example/bot/data/client/onboarding/{CalendarChanges.kt,ClubCalendarOperations.kt,ClubOperationalNights.kt}` (ранее clean tracked);
- `app-bot/src/test/kotlin/com/example/bot/routes/{ClientCalendarReconciliationRoutesDataTest.kt,ClientCalendarReconciliationPostgresRoutesIT.kt}`;
- `miniapp/src/modules/client/calendar-reconciliation.test.tsx`;
- `miniapp/tests/owner-manager-calendar.spec.ts`;
- `docs/ops/owner-manager-calendar-fix.md`.

Изменённые прежние paths:

- `miniapp/src/modules/client/{calendar.ts,ClubCalendar.tsx,calendar.test.tsx}`;
- `miniapp/tests/{client-calendar.spec.ts,owner-manager-p2.spec.ts}` (calendar fixture metadata only);
- `miniapp/{playwright.config.ts,playwright.owner-manager-p2.config.ts,playwright.owner-ux.config.ts}`;
- `miniapp/dist/index.html` и новые hashed JS chunks;
- `docs/ops/{owner-manager-p2-fix.md,PROJECT_STATUS.md}`, `docs/product/AS_BUILT.md`.

`delta-paths.json` перечисляет все modified/new/replaced paths и hashes;
`candidate-manifest.json` фиксирует новый кандидат, `calendar-delta.patch` — только
эту дельту, `candidate.patch` — весь кандидат от base с untracked файлами.

Прежние результаты PostgreSQL/JVM/security не подменяют проверки изменённого
calendar backend: relevant PG145, full ordinary JVM и affected static выполнены заново.
Полный прежний PG corpus1904 passed/11 skipped не повторялся; он остаётся историческим
результатом. Неизменённые migrations/staff/invitation/security-script gates и
core-data static после последнего same-byte backend использованы по их inputs;
это не новый full-PG verdict. Dependency manifests, production pins и test gates
не менялись. Runtime/hosted CI результаты не заявляются.

## 7. Focused self-review и ограничения

Bounded review проверил инварианты A–E, включая полный stored set, remove/reapply,
expiry, source freshness, synchronous consent invalidation и optimistic retry proof.
После исправления подтверждённых findings новых подтверждённых findings нет.
Это подготовка к внешнему review, не его автоматический PASS.

Real Telegram WebView/device, hosted CI, stage/prod не проверялись. IANA calculations
используют Intl runtime браузера; контрольные London/Apia cases сопоставлены с JVM.
Новый UI требует новой metadata и packaged server; при старом API запись fail-closed.
Commit/push/PR/merge/deploy/live data mutations не выполнялись.

## 8. Один следующий шаг

Повторный независимый focused review календарной дельты и нового manifest.
