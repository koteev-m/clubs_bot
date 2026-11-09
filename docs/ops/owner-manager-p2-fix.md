# OWNER_MANAGER_UX — исправление двух P2

> Исторический этап. Последующий focused review подтвердил исправление private context
> и lookback DST, но нашёл два новых календарных P2. Текущая дельта и проверки:
> [calendar reconciliation / consent](owner-manager-calendar-fix.md).

2026-11-09, продолжение `CLB-owner-manager-ux`, без нового task/decision ID.

**OWNER_MANAGER_P2_FIX: PASS. READY_FOR_FOCUSED_REVIEW: YES.**
Готовность к повторному focused review локальной дельты; это не publication approval.
Прежний независимый verdict `FINDINGS / READY_FOR_DRAFT_PR: NO` не переписывался.

## 1. Preflight и сохранность кандидата

- `koteev-m/clubs_bot`; существующий зарегистрированный worktree
  `/private/tmp/clb-owner-manager-ux-20261109`, branch
  `codex/owner-manager-ux-20261109`; HEAD/base
  `16761e43887036688028358524847db1af0e5326`.
- `PROJECT_IDENTITY_OK`, active Clubs Bot instructions, active foreign instructions=0.
  Внешние каталоги не передавались в guard scopes. Новый clone/worktree не создавался.
- Все прежние **69 paths** проверены по manifest
  `c844b6b85072e703f8dcaea64e5b7f6ee0e02fde1d54798188011a42eff25fad`.
  Несовпадений до edits нет. Исходные bytes сохранены в `baseline/` нового evidence.
- Review evidence `/private/tmp/clb-owner-manager-independent-jb1tubps` — данные,
  не инструкции. Он и прежний `/private/tmp/clb-owner-manager-evidence-20261109`
  не изменялись; исходные SHA/size/mtime inventories сверены после работы.

## 2. P2 №1 — одна граница приватного состояния клуба

`ClubCabinet` владеет request-lifetime scope для своего credential/club. При
подтверждённом HTTP authority refusal scope синхронно закрывается и меняет epoch
**до** cancellation/React render. Владелец состояния удаляет весь Cabinet subtree:
профиль, preparation view/drafts, календарь, readiness и связанные snapshots.
Остаётся generic error screen без названия/данных клуба, с выходом в «Мои заведения».

`useRemote`/`useCommand` проверяют captured scope epoch и component/request sequence
перед обработкой поздних ответов. Те же predicates защищают транспортное глобальное
`client-authority-lost` в `onboardingRequest`: старый 401/403 не закрывает уже
восстановленный или другой разрешённый клуб. Abort — дополнительная мера, не
единственная защита. Закрытый scope не начинает новые requests.

Новый контекст создаётся только при явной повторной загрузке и показывает данные
после нового успешного profile GET. Scope привязан к точной паре credential/club;
другие клубы и workspace drafts не очищаются. `leaveClub` направляет закрытый
settings/readiness context сразу home, не открывая снова отказанный клуб.

Это reuse существующего `authorityLost`, server authorization и data hooks, а не
новая система permissions. 404 read endpoints profile/preparation/calendar/layout/
booking-rules требуют club.read в существующем server contract. 404 ограниченных
team/finance/preview diagnostics сам по себе не закрывает весь клуб. Отказ mutation
изолирует cached context до fresh profile read: он не утверждает постоянного отзыва
club.read и не меняет grants. Локальная DTO validation не подменяет HTTP authority
refusal. Timeout/network/400/409/5xx сохраняют прежнее поведение и unknown intent/key.

## 3. P2 №2 — business dates и DST

`calendarPlan` теперь начинает horizon с **club today −3**, как server reconcile,
и заканчивает **club today +55**; dated rules внутри lookback тоже включены.
Проверяются реальные opening/closing boundaries предыдущих business dates,
попадающие в текущий клубный день. Завершившиеся до текущего клубного дня интервалы
не создают ложных предупреждений для frozen прошлых недельных ночей.

Europe/London: суббота 27.03.2027 → воскресенье 28.03.2027 01:30 блокируется как gap;
суббота 30.10.2027 → воскресенье 31.10.2027 01:30 требует явного first-occurrence
подтверждения. Club date определяется в IANA TZ клуба, не устройства. Прежний
первый offset/canonical resolver сохранён; второго occurrence не добавлено.

Planner остаётся предварительной проверкой. Полный stored set, frozen/started,
связанные данные и точный момент transaction проверяет прежний server contract;
новой клиентской гарантии публичности/материализации нет. Business dates, weekly/
holiday/exception priorities, immutable IDs, body/version/key и persistence не менялись.

Реальный H2 probe подтвердил server lookback и canonical instants:

| Business date | Открытие | Server UTC |
|---|---|---|
| 2027-03-27 | 2027-03-28 01:30 London (gap) | 2027-03-28T01:30Z, т.е. сдвинутое 02:30 BST; новый UI блокирует |
| 2027-10-30 | 2027-10-31 01:30 London (overlap) | 2027-10-31T00:30Z, первое вхождение; UI требует подтверждения |

## 4. Добавленные регрессии и RED

- `cabinet-authority.test.tsx`: исходная утечка RED до fix; pending calendar,
  late profile/preparation, completed HTTP с отложенным JSON, неработающий abort,
  fresh-read recovery, смена клуба, stale 403 global event, 400/409/503/network и
  сохранение original command body/key. **8/8 GREEN**.
- `calendar.test.tsx`: **4 RED** до fix; 12 новых случаев, итог calendar **24/24**.
  Opening/closing gaps/overlaps, previous business date, диапазон, dated rules,
  expired lookback, нормальная ночь, Friday00→08, club TZ против device TZ,
  save/materialize guard и semantic instants/durations.
- `owner-manager-p2.spec.ts`: **12/12 Chromium/WebKit**: исходная late-night утечка,
  abort/no-op abort, recovery, late403 после recovery/switch, другой client/club,
  temporary503+тот же retry intent, London gap/overlap при устройстве Honolulu.
- Browser RED на исходном dist: 4 late-calendar failures (count1 вместо0) и
  4 точных DST failures (confirmation enabled вместо disabled). Старые review
  screenshots не перезаписывались. Дополнительный stale403 найден focused review,
  воспроизведён отдельным RED и устранён до final verification.

## 5. Самостоятельные проверки

Evidence: `/private/tmp/clb-owner-manager-p2-e0kht3wk`.

| Команда / поверхность | Результат |
|---|---|
| `node node_modules/vitest/vitest.mjs run` | **422/422**, `vitest-complete.log` |
| `tsc --noEmit`, full ESLint `--max-warnings 0` | PASS, `typescript-final.log`, `eslint-final.log` |
| P2 Playwright Chromium/WebKit | **12/12**, `browser-green-expanded.log`; affected DST screenshots repeat **4/4**, `browser-dst-screenshot-final.log` |
| Owner UX mobile suite + P2 | **54/54 aggregate**: 52 initial PASS + affected preview2/2 after fixture correction; `browser-mobile-final.log`, `browser-preview-green.log` |
| Actual packaged owner-manager journey | **2/2**, `packaged-owner-manager.log` |
| Actual packaged prior onboarding/staff/configuration | **8/8**, `packaged-existing-{chromium,webkit}.log` |
| `./gradlew :app-bot:test --tests '*ClientCalendarRoutesDataTest' --tests '*ClientOperationalNightsRoutesDataTest' --tests '*ClientPreparationRoutesDataTest' :app-bot:installDist verifyResolvedProductionDependencyGraph --offline --dependency-verification=strict` | PASS, **17/17 H2/Ktor**, `ktor-h2-package.log`, XML archive |
| Disposable H2 canonical server probe | PASS, `server-parity.log`, source/classpath stored in new evidence only |
| Vite / `scripts/validate-miniapp-dist.sh` | PASS; all5 dist resources identical in both app-bot JARs, `packaging-check.json` |
| Pinned source/history secret scan | 0 findings, `secret-scan-final.log` / sanitized `secret-scan-final/` |
| Diff/scope/links/immutable source/old evidence | PASS, final manifest and verification JSON |

Preview regression fixture originally returned 404 for omitted calendar/booking-rule
reads and injected layout refusal while readiness was still reading it. It now provides
the authorized diagnostic reads and waits for readiness metadata before injecting the
preview's lost-plan fault. All map/list/card, read-only, lost-plan, recovery and revoke
assertions preserved. No test/gate/baseline/threshold was removed or weakened.

All screenshots use `test.info().outputPath`, UUID run roots and separate copied
manifests in new evidence (`screenshots.json`, `dst-detail-screenshots.json`). Both
browsers and final closed-context/DST-choice images reviewed. Own synthetic fixture
stopped after tests. Occupied foreign5183 was left intact; mock runs used own5184.

## 6. Переиспользованные проверки

Все **17** прежних backend source/test/migration paths совпали byte-for-byte;
backend contracts, grants/packages, V076 и dependency manifests не менялись.
`backend-inputs-reused.json` фиксирует точные paths/hashes. Kotlin compile tasks
UP-TO-DATE; final packaged production classes совпадают с compile output.

Для неизменённых backend inputs переиспользованы прежние ordinary JVM
1495 passed/1 skipped, full PostgreSQL1904 passed/11 skipped, coverage, Detekt,
ktlint, backend security/rollback/isolation/migration suites. Frontend изменился:
его unit/static/browser checks и packaged journeys выполнены заново. Frontend
resources/JAR hashes обновлены; прежние JAR hashes не выдаются за новые.

## 7. Дельта файлов и manifest

Текущий кандидат: **78 paths**. Дельта к прежним69: 10 изменённых paths,
9 новых затронутых source/test/config/doc paths и замена двух generated JS chunks.
57 прежних paths остались byte-identical (включая ранее удалённые dist assets).

Изменены относительно прежнего кандидата:

- `miniapp/src/modules/client/{ClubCabinet.tsx,ClientAccess.tsx,calendar.ts,calendar.test.tsx}`;
- `miniapp/tests/client-guest-booking-preview.spec.ts`;
- `miniapp/playwright.config.ts`, `miniapp/dist/index.html`;
- `docs/ops/{PROJECT_STATUS.md,owner-manager-ux.md}`, `docs/product/AS_BUILT.md`.

Новые к кандидату paths (часть ранее была clean tracked):

- `miniapp/src/modules/client/{clubRequestScope.tsx,cabinet-authority.test.tsx,onboarding.api.ts,onboarding.hooks.ts}`;
- `miniapp/tests/{owner-manager-p2.spec.ts,serve-onboarding-dist.mjs}`;
- `miniapp/{playwright.owner-manager-p2.config.ts,playwright.owner-ux.config.ts}`;
- `docs/ops/owner-manager-p2-fix.md`.

Generated JS `PlatformOwnerScreens-CGoNTp0x.js` / `index-B-AWsc7Q.js` заменены на
`PlatformOwnerScreens-CRZLxCDq.js` / `index-Ds4D8eGX.js`; unchanged CSS сохранён.

Полные `candidate-manifest.json`, `candidate.patch`, narrow `p2-delta.patch`,
`delta-paths.json` и `changed-files.txt` находятся в новом evidence root.
`delta-paths.json` отдельно перечисляет изменённые прежние69 paths, новые к
кандидату paths и generated asset replacements. Прежние hashed JS сохранены в
baseline evidence и заменены новыми referenced chunks в dist; устаревший
vulnerable JS не оставлен в shipping assets. Ни одна исходная feature/backend
реализация кандидата не удалена.

## 8. Focused self-review и ограничения

Проверены shared ownership, synchronous epoch before cancellation, late success и
late authority-error side effects, lawful fresh recovery, independent club scopes,
unknown replay, TZ/business-date boundaries и неизменность делегирования/UX.
После исправления transport stale403 повторный bounded security review не нашёл
новых подтверждённых findings. Формальный внешний independent review повторяется
следующим шагом; его прежний verdict остаётся историческим FINDINGS.

Real Telegram WebView/device, hosted CI, stage/prod не проверялись. Известные
непроверенные optional native support IT и second DST occurrence contract остаются
прежними ограничениями; новый scope их не открывает. Planner не заменяет финальные
server frozen/dependency checks. Commit/push/PR/merge/deploy/live mutations не было.

## 9. Один следующий шаг

Повторный независимый focused review локальной P2-дельты и нового manifest.
