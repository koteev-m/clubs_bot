# Управление клубом и подготовка — локальный handoff

> Продолжение 2026-11-09: внешний independent review прежнего manifest подтвердил
> два P2. Исправления и текущая готовность к повторному focused review описаны в
> [отдельном handoff](owner-manager-p2-fix.md). Ниже сохранён исторический отчёт
> исходного 69-path кандидата; прежний READY_FOR_REVIEW не является новым verdict
> независимого review или разрешением публикации.

Дата: 2026-11-09. Новая задача `CLB-owner-manager-ux` без нового номерного CLB/DEC.
Репозиторий `koteev-m/clubs_bot`, base и проверенный `origin/main`
`16761e43887036688028358524847db1af0e5326`, merged PR #566.
Branch `codex/owner-manager-ux-20261109`, зарегистрированный worktree
`/private/tmp/clb-owner-manager-ux-20261109`.
Основной checkout и все чужие изменения/worktrees сохранены.

## Результат и граница

**OWNER_MANAGER_UX_STATUS: READY_FOR_REVIEW.** Главный сквозной local outcome
реализован и проверен. Это готовность локального кандидата к пользовательской приёмке.
Это локальная реализация. Commit, push, PR, merge, deploy, stage/prod, реальные
данные, Environment/Secrets и другие проекты не затрагивались. Успешный staging
deploy PR #566 сообщён пользователем, независимо не проверялся и не доказывает
runtime текущих локальных bytes.

`PROJECT_IDENTITY_OK`, expected-head PASS; active repo/task instructions —
Clubs Bot, active foreign instructions = 0. Guard не менялся. Contextual preflight:
product README/spec/source/decisions/roadmap/as-built/gaps, staff-контракт,
клиентское подключение, grants/invitations/commands/audit и configuration surfaces.
`CONCEPT_SOURCE.md` сохранён byte-for-byte.

## Реализованный сценарий

После создания непубличного клуба владелец выбирает ответственного, свою настройку
или назначение позже. Создатель и владение сохраняются. Обзор клуба отделён от
настроек и технической проверки готовности. Он показывает подтверждённую публичность,
ближайшую ночь с фактическим интервалом/формированием, подготовку, ответственного,
переход к сотрудникам и настройки. Финансовые показатели/операционные отчёты не
выдумываются: источник пока не подключён, UI честно сообщает об отсутствии данных.

Ответственный получает задачи по конфигурации в той же Mini App: профиль,
календарь, залы/зоны/схемы/столы, booking rules и разрешённые finance/module settings.
Read/write определяется текущими active grants; read-only пакет не получает запись.
Сохранённые настройки проверяются в существующих формах и отдельной готовности.
Сотрудник передаёт результат; владелец принимает либо возвращает с причиной.
Принятие подготовки не публикует клуб и не открывает guest booking.

## Делегирование и безопасность

GET/PATCH `/api/client/clubs/{id}/preparation` использует additive V076 H2/PostgreSQL.
Шесть durable состояний: unassigned, invitation_pending, in_progress, submitted,
accepted, returned. Назначение существующего сотрудника допускается только после
подтверждённого admission и при полном действующем CLUB_ADMIN.

Новое приглашение атомарно связывает задачу с существующим staff invitation:
одноразовый secret/hash → signed Telegram claim → отдельная сверка кода владельцем
→ owner confirmation. До последнего действия grants не выдаются. Это временный
пакет конфигурации, а не миграция к новой технической роли управляющего. Нет
team.manage, операций с деньгами, ownership, application.manage, legacy/GLOBAL.
Частичная выдача полного пакета запрещена. Прежние staff packages и assignments
не изменены; UI показывает коды пакетов и их фактическое назначение.

Каждая операция/replay заново проверяет actor, client, club, active membership,
mapping/grants и assignment/admission; owner authority — immutable creator плюс
существующие grants. Общий client lock сериализует выдачу/отзыв/переходы. Optimistic
version, durable idempotency и audit входят в ту же transaction. Audit failure
откатывает также приглашение/подтверждение. Старый admission после revoke/fresh
admission сам ответственность не восстанавливает. Снятие задачи не меняет grants;
UI предлагает отдельный отзыв доступа/приглашения в команде.

Submit сохраняет hash актуальных версий профиля, календаря, залов/столов/зон/планов,
booking rules, финансового шаблона и modules. Изменения после submit блокируют
устаревшее принятие. Изменённый принятый результат можно передать повторно.
Frontend fences защищают late responses, read revoke/readmission, смену клуба,
same-version изменение authority и assignee access. Unknown command сохраняет
body/key через foreground и блокирует уход до выяснения результата; подтверждённый
отказ доступа сохраняет возможность выйти из недоступного клуба.

## Календарь

Default «Предстоящие ночи», отдельно «Постоянное расписание» и «Особые даты».
Business date сохранена; дни открытия/закрытия выбираются явно, а итог показан
обычным текстом с реальными датами. Например, пятничная ночь открывается в субботу
00:00 и закрывается в субботу 08:00. Недельные правила, IANA TZ и приоритет
exception > holiday > weekly не изменены.

Перед отдельными save rules/materialize показываются результат и предупреждение
о защищённых связанных данных. Существующие server guards/versions продолжают
отклонять конфликт, прошлую/связанную/пересекающуюся ночь. Брони, HOLD, события,
публичность и связанные записи не создаются и не изменяются автоматически.
DST overlap требует явного подтверждения первого canonical вхождения; выбрать
второе вхождение прежний backend contract не позволяет. UI объясняет границу и
предлагает другие часы. DST gap в новом редакторе блокируется с предложением
выбрать существующее время; прежний server resolver и другие callers не менялись.

## Продуктовая модель и будущая миграция

Датированное принятие 2026-11-09: [контракт](owner-manager-contract.md),
[PRODUCT_SPEC §29.17](../product/PRODUCT_SPEC.md#owner-manager-model),
[OPEN_DECISIONS](../product/OPEN_DECISIONS.md#owner-manager-model-decision),
[roadmap](../product/PRODUCT_ROADMAP.md#owner-manager-sequence).
Девять ролей: владелец сети, владелец клуба, управляющий сети, управляющий клуба,
менеджер, менеджер входа, финансовый менеджер, промоутер, гость.
Host в target catalogue отсутствует; столы относятся к менеджеру, QR/списки/вход —
к менеджеру входа. DJ/старые администраторы и сетевые должности не входят в новый
основной catalogue. Исторические решения сохранены, source concept immutable.

Новая RBAC-миграция не выполнена: нужны отдельная approved permission matrix,
совместимость старых assignments, безопасная миграция/rollback и проверка scopes.
Управляющий сети — только принятая документация: реальные показатели назначенных
клубов и контроль подготовки; network/GLOBAL grants и его рабочий кабинет не созданы.
DEC-006/012/014 и прочие не затронутые scope/finance/music решения не разрешены
через новый перечень названий.

## Проверки и evidence

Per-run evidence: `/private/tmp/clb-owner-manager-evidence-20261109`.
Screenshot paths всегда `test.info().outputPath`, каждый Playwright run имеет UUID.
Старые evidence не перезаписывались. Browser fixtures используют только synthetic
Telegram identity и disposable H2; PostgreSQL 16 — отдельный loopback контейнер и
штатные изолированные testcontainers, без реальных volumes/данных.

| Проверка | Результат / evidence |
|---|---|
| `./gradlew test --offline` | 1495 passed, 1 skipped; `jvm-full-final.log`, `ordinary-results.zip` |
| `CLB_CLIENT_TEST_PG_URL=… ./gradlew test coverageGate detektGate ktlintCheck :app-bot:checkLogsPolicy verifyResolvedProductionDependencyGraph :app-bot:installDist -PrunIT=true --offline --dependency-verification=strict --no-build-cache --no-configuration-cache` | PASS, 1904 passed / 11 skipped; `final-gates-verified.log`, `postgres-final-results.zip`. Тот же invocation с disposable init script дополнительно вывел test runtime classpath для browser fixture |
| Vitest / TypeScript / ESLint | 402/402; frontend typecheck и full ESLint PASS. После восстановления исходного синтетического имени fixture — affected layout 3/3 PASS; `vitest-final-complete.log`, `layout-fixture-final.log`, `typescript-final-complete.log`, `eslint-final-complete.log` |
| Playwright Chromium/WebKit mobile | 42/42; `mobile-final.log`. Календарь дополнительно 24/24 unit. Последняя owner-only дельта отдельно покрыта ниже |
| Actual packaged Ktor/H2 owner-manager | 2/2 на финальном production artifact в Chromium/WebKit; `manager-packaged-screenshots-final.log`, 12 новых PNG |
| Actual packaged Ktor/H2 прежние onboarding/staff/configuration journeys | 8/8 aggregate: Chromium 3/3 неизменённых + affected 1/1 repeat, WebKit 4/4; `legacy-packaged-chromium.log`, `legacy-packaged-chromium-fixed.log`, `legacy-packaged-webkit.log` |
| Vite / dist validator / strict final installDist | PASS; `vite-final-complete.log`, `dist-validator-final.log`, `packaging-final.log`. Все 5 dist files побайтно совпали с обоими app-bot JAR; `packaging-check.json` |
| Caddy exact ingress / log hygiene | PASS: preparation PATCH, invalid suffixes, no-store/no-referrer и canary secret-safe logs; `ingress.log` |
| Pinned source + full available-history secret scan | 0 findings; финальные sanitized SARIF/summary и log — `secret-scan-final/`, `secret-scan-final.log` |
| Identity / immutable source / old migrations / diff / links | PASS; `identity-final.json`, `docs-check.json`, final manifest. Только две новые V076; исторические SQL bytes сохранены |

Итоговый manifest и полный diff находятся в evidence root: `candidate-manifest.json`,
`candidate.patch`, `changed-files.txt`. Это локальные artifacts, не публикация.
Для test scripts используются установленные зависимости существующего Mini App;
package/lockfile не менялись, новых зависимостей нет.

## Screenshots

Финальные реальные Chromium/WebKit screenshots находятся в
`/private/tmp/clb-owner-manager-evidence-20261109/packaged-screenshots-final/{chromium,webkit}`:

- `creation-choice-light-320.png` — три варианта после создания.
- `assignee-tasks-dark-390.png` — задачи ответственного и доступные разделы.
- `saved-calendar-dark-390.png` — пятница: суббота 00:00 → суббота 08:00.
- `owner-review-light-320.png` — результат передан владельцу.
- `owner-accepted-dark-390.png` — подтверждённая приёмка, клуб всё ещё private.
- `owner-results-dark-390.png` — реальная ближайшая ночь, отсутствие отчётов и внимание.

`packaged-screenshots-final/screenshots.json` сохраняет исходные UUID paths и SHA-256.
Дополнительные weekly/special/preview calendar PNG — `calendar/{chromium,webkit}`;
они сделаны до косметической замены legacy Back label. Финальный календарь с
новым Back и интервалом 00:00–08:00 подтверждён packaged screenshots выше.
Изображения просмотрены; переполнений на проверенных 320/390 viewport нет,
контраст текста/темы и touch/Back/draft behavior проверены browser tests.

## Review и исправленные ошибки проверки

Focused self-review и независимый bounded review source/security/navigation
завершены, незакрытых confirmed findings нет. Исправлены: потеря pending intent
на foreground профиля, same-version assignee revoke/late receipt, сохранение
одноразовой ссылки при потере invitation authority, readiness timezone remount,
Back к фактическому same-club parent, stale acceptance и повторная передача
изменённого принятого результата. Последний self-review убрал утверждение владения
на choice URL до server-confirmed creator authority; отдельная staff-URL regression PASS.

Verification errors не объявлялись product defects: пять upgrade fixtures ожидали
одну latest migration после V074 вместо V075+V076; теперь обе проверены отдельно.
PG bool/touch-trigger/порядок строк в synthetic restore comparison нормализованы
адресно. Строгие denied/audit rollback snapshots не ослаблялись. Ошибки тестового
Telegram helper (credential со старым start_param и fragment-only повтор) исправлены;
реальный повторный вход выполняет reload документа. Устаревший ожидаемый Back parent
в одном прежнем integrated spec приведён к новой готовности без удаления assertions.

Случайный module-wide formatter затронул чужие по scope Kotlin files: изменения
полностью восстановлены по exact hash-fenced inventory/backup. Первая широкая
restore-команда была отклонена auto-review; безопасная точная обратимая операция
затем прошла review и выполнена. Незакрытого permission blocker нет. Пересечение
двух ранних Gradle outputs и stale absolute-path ktlint cache не использованы как
финальные evidence; последовательный полный gate и свежий lint прошли заново.

## Непроверенное и ограничения

- Реальные Telegram WebView/физический iPhone, hosted CI, stage/prod и runtime
  deployment текущего кандидата не проверялись.
- 11 штатных skips: прежний отключённый GuestFlowStartTest и 10 отдельно включаемых
  NativeSupport PostgreSQL lifecycle IT. Их gates/условия не менялись.
- Операционные финансовые отчёты/метрики пока не подключены. Финансовый шаблон
  не означает денежные операции. Принятие подготовки не означает публичную готовность.
- Выбор второго DST overlap occurrence требует отдельного расширения существующего
  calendar contract; текущая явная опция — canonical первое вхождение либо другие часы.
- Известные Vite chunk-size/Browserslist, Gradle deprecation и JaCoCo mismatch warnings
  для неизменённых RefundRequest/CancelRequest сохранены; обязательные thresholds/gates
  проходят, suppressions/baselines и зависимости не менялись.
- Synthetic fixtures/контейнеры этого запуска остановлены после проверок; source,
  distribution и evidence остаются локально. Чужие processes/worktrees не очищались.

Один следующий шаг: пользовательская приёмка локального кандидата и screenshots.
Это не permission на commit/push/PR/merge/deploy.


## Изменённые файлы

Всего 69 paths, включая обновлённые dist assets и удаление прежних hashed chunks.

| Path | Статус |
|---|---|
| [Caddyfile](../../Caddyfile) | local candidate |
| [app-bot/src/main/kotlin/com/example/bot/routes/ClientOnboardingRoutes.kt](../../app-bot/src/main/kotlin/com/example/bot/routes/ClientOnboardingRoutes.kt) | local candidate |
| [app-bot/src/test/kotlin/com/example/bot/routes/ClientBookingRulesRoutesDataTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientBookingRulesRoutesDataTest.kt) | local candidate |
| [app-bot/src/test/kotlin/com/example/bot/routes/ClientCalendarRoutesDataTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientCalendarRoutesDataTest.kt) | local candidate |
| [app-bot/src/test/kotlin/com/example/bot/routes/ClientNightEventLinksRoutesDataTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientNightEventLinksRoutesDataTest.kt) | local candidate |
| [app-bot/src/test/kotlin/com/example/bot/routes/ClientOperationalNightsRoutesDataTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientOperationalNightsRoutesDataTest.kt) | local candidate |
| [app-bot/src/test/kotlin/com/example/bot/routes/ClientPreparationPostgresRoutesIT.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientPreparationPostgresRoutesIT.kt) | local candidate |
| [app-bot/src/test/kotlin/com/example/bot/routes/ClientPreparationRoutesDataTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientPreparationRoutesDataTest.kt) | local candidate |
| [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientOnboardingRepository.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientOnboardingRepository.kt) | local candidate |
| [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubPreparationOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubPreparationOperations.kt) | local candidate |
| [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/JdbcClientOnboardingRepository.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/JdbcClientOnboardingRepository.kt) | local candidate |
| [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/StaffInvitationOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/StaffInvitationOperations.kt) | local candidate |
| [core-data/src/main/resources/db/migration/h2/V076__club_preparation.sql](../../core-data/src/main/resources/db/migration/h2/V076__club_preparation.sql) | local candidate |
| [core-data/src/main/resources/db/migration/postgresql/V076__club_preparation.sql](../../core-data/src/main/resources/db/migration/postgresql/V076__club_preparation.sql) | local candidate |
| [core-data/src/test/kotlin/com/example/bot/data/booking/core/CanonicalBookingH2Test.kt](../../core-data/src/test/kotlin/com/example/bot/data/booking/core/CanonicalBookingH2Test.kt) | local candidate |
| [core-data/src/test/kotlin/com/example/bot/data/client/onboarding/ClientOnboardingPostgresIT.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/onboarding/ClientOnboardingPostgresIT.kt) | local candidate |
| [core-data/src/test/kotlin/com/example/bot/data/client/onboarding/ClubPreparationContract.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/onboarding/ClubPreparationContract.kt) | local candidate |
| [core-data/src/test/kotlin/com/example/bot/data/client/onboarding/OnboardingContract.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/onboarding/OnboardingContract.kt) | local candidate |
| [docs/ops/PROJECT_STATUS.md](../../docs/ops/PROJECT_STATUS.md) | local candidate |
| [docs/ops/owner-manager-contract.md](../../docs/ops/owner-manager-contract.md) | local candidate |
| [docs/ops/owner-manager-ux.md](../../docs/ops/owner-manager-ux.md) | local candidate |
| [docs/ops/owner-team-contract.md](../../docs/ops/owner-team-contract.md) | local candidate |
| [docs/product/AS_BUILT.md](../../docs/product/AS_BUILT.md) | local candidate |
| [docs/product/CONCEPT_CODE_GAP.md](../../docs/product/CONCEPT_CODE_GAP.md) | local candidate |
| [docs/product/OPEN_DECISIONS.md](../../docs/product/OPEN_DECISIONS.md) | local candidate |
| [docs/product/PRODUCT_ROADMAP.md](../../docs/product/PRODUCT_ROADMAP.md) | local candidate |
| [docs/product/PRODUCT_SPEC.md](../../docs/product/PRODUCT_SPEC.md) | local candidate |
| [docs/product/README.md](../../docs/product/README.md) | local candidate |
| `miniapp/dist/assets/PlatformOwnerScreens-B4liM9un.js` | deleted stale dist asset |
| [miniapp/dist/assets/PlatformOwnerScreens-CGoNTp0x.js](../../miniapp/dist/assets/PlatformOwnerScreens-CGoNTp0x.js) | local candidate |
| [miniapp/dist/assets/index-B-AWsc7Q.js](../../miniapp/dist/assets/index-B-AWsc7Q.js) | local candidate |
| `miniapp/dist/assets/index-BVO82VUy.css` | deleted stale dist asset |
| [miniapp/dist/assets/index-C_BvyVlU.css](../../miniapp/dist/assets/index-C_BvyVlU.css) | local candidate |
| `miniapp/dist/assets/index-CsaG6cq3.js` | deleted stale dist asset |
| [miniapp/dist/index.html](../../miniapp/dist/index.html) | local candidate |
| [miniapp/playwright.config.ts](../../miniapp/playwright.config.ts) | local candidate |
| [miniapp/playwright.owner-manager.config.ts](../../miniapp/playwright.owner-manager.config.ts) | local candidate |
| [miniapp/src/modules/client/CalendarRulesEditor.tsx](../../miniapp/src/modules/client/CalendarRulesEditor.tsx) | local candidate |
| [miniapp/src/modules/client/ClientAccess.tsx](../../miniapp/src/modules/client/ClientAccess.tsx) | local candidate |
| [miniapp/src/modules/client/ClubBookingRules.tsx](../../miniapp/src/modules/client/ClubBookingRules.tsx) | local candidate |
| [miniapp/src/modules/client/ClubCabinet.tsx](../../miniapp/src/modules/client/ClubCabinet.tsx) | local candidate |
| [miniapp/src/modules/client/ClubCalendar.tsx](../../miniapp/src/modules/client/ClubCalendar.tsx) | local candidate |
| [miniapp/src/modules/client/ClubPreparation.tsx](../../miniapp/src/modules/client/ClubPreparation.tsx) | local candidate |
| [miniapp/src/modules/client/ClubProfile.tsx](../../miniapp/src/modules/client/ClubProfile.tsx) | local candidate |
| [miniapp/src/modules/client/ClubTeam.tsx](../../miniapp/src/modules/client/ClubTeam.tsx) | local candidate |
| [miniapp/src/modules/client/cabinet.css](../../miniapp/src/modules/client/cabinet.css) | local candidate |
| [miniapp/src/modules/client/calendar.css](../../miniapp/src/modules/client/calendar.css) | local candidate |
| [miniapp/src/modules/client/calendar.test.tsx](../../miniapp/src/modules/client/calendar.test.tsx) | local candidate |
| [miniapp/src/modules/client/calendar.ts](../../miniapp/src/modules/client/calendar.ts) | local candidate |
| [miniapp/src/modules/client/club-setup.test.tsx](../../miniapp/src/modules/client/club-setup.test.tsx) | local candidate |
| [miniapp/src/modules/client/configuration.hooks.ts](../../miniapp/src/modules/client/configuration.hooks.ts) | local candidate |
| [miniapp/src/modules/client/layout.test.tsx](../../miniapp/src/modules/client/layout.test.tsx) | local candidate |
| [miniapp/src/modules/client/onboarding-b.test.tsx](../../miniapp/src/modules/client/onboarding-b.test.tsx) | local candidate |
| [miniapp/src/modules/client/owner-ux.test.tsx](../../miniapp/src/modules/client/owner-ux.test.tsx) | local candidate |
| [miniapp/src/modules/client/preparation.test.tsx](../../miniapp/src/modules/client/preparation.test.tsx) | local candidate |
| [miniapp/src/modules/client/preparation.types.ts](../../miniapp/src/modules/client/preparation.types.ts) | local candidate |
| [miniapp/src/modules/client/profile-lifetime.test.tsx](../../miniapp/src/modules/client/profile-lifetime.test.tsx) | local candidate |
| [miniapp/src/modules/client/simplified-onboarding.test.tsx](../../miniapp/src/modules/client/simplified-onboarding.test.tsx) | local candidate |
| [miniapp/src/modules/client/team.types.ts](../../miniapp/src/modules/client/team.types.ts) | local candidate |
| [miniapp/tests/client-booking-rules.spec.ts](../../miniapp/tests/client-booking-rules.spec.ts) | local candidate |
| [miniapp/tests/client-calendar.spec.ts](../../miniapp/tests/client-calendar.spec.ts) | local candidate |
| [miniapp/tests/client-guest-booking-preview.spec.ts](../../miniapp/tests/client-guest-booking-preview.spec.ts) | local candidate |
| [miniapp/tests/onboarding-integrated.spec.ts](../../miniapp/tests/onboarding-integrated.spec.ts) | local candidate |
| [miniapp/tests/owner-manager-integrated.spec.ts](../../miniapp/tests/owner-manager-integrated.spec.ts) | local candidate |
| [miniapp/tests/owner-profile-access.spec.ts](../../miniapp/tests/owner-profile-access.spec.ts) | local candidate |
| [miniapp/tests/owner-setup.spec.ts](../../miniapp/tests/owner-setup.spec.ts) | local candidate |
| [miniapp/tests/owner-team-integrated.spec.ts](../../miniapp/tests/owner-team-integrated.spec.ts) | local candidate |
| [miniapp/tests/owner-ux-map.spec.ts](../../miniapp/tests/owner-ux-map.spec.ts) | local candidate |
| [scripts/tests/check_client_onboarding_ingress.py](../../scripts/tests/check_client_onboarding_ingress.py) | local candidate |
