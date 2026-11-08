# CLB-owner-team: локальный handoff

## P2 независимого review: изоляция screenshot artifacts, 2026-11-08

**OWNER_TEAM_P2_FIX: PASS. READY_FOR_FOCUSED_REVIEW: YES.** Независимый review
основного кандидата подтвердил один P2: два screenshot write-target обходили
Playwright outputDir. Остальные проверенные контракты не получили подтверждённых
P0/P1/P2. Обе записи теперь используют `test.info().outputPath('team-dark.png')` /
`test.info().outputPath('team-light-320.png')`. Existing per-test/project naming и
randomUUID outputDir обеспечивают изоляцию тестов и запусков; абсолютный старый
каталог из spec удалён. Assertions, production, V075 и staff-контракт не менялись.

Первый полный прогон на пустой H2 дополнительно воспроизвёл verification error:
старый `selectSpace` искал «Назад», хотя неизменённый ClubProfile показывает
«Организация и другие заведения». Исправлено только имя кнопки в helper
`onboarding-integrated.spec.ts`, без изменения assertions/проверяемого поведения.
После этого на чистой H2 Chromium **4/4**, WebKit **4/4**; повтор staff E2E
**1/1 + 1/1**. TypeScript, полный ESLint (`--max-warnings 0`), diff/link/scope
checks PASS. Focused self-review не обнаружил дополнительных дефектов fix.

Все browser runs выполнялись с OS sandbox, запрещающим запись в старый evidence
без chmod его файлов. Probe подтвердил EPERM, затем старое дерево **75/75**
совпало по inventory/bytes/SHA-256/mode/mtime. Первые шесть staff screenshots
сохранили hash/mtime; повтор создал четыре новых. Всего десять screenshots в пяти
разных автоматических Playwright run roots (включая начальный диагностический
прогон), все внутри нового evidence. Исходный manifest не переписывался.

Новые результаты, exact narrow diff и manifest: `/private/tmp/clb-owner-team-p2-fix-20261108T135231Z`.
Все прежние **53** пути кандидата сохранены; delta этого исправления — два specs
и два ops-документа. Остальные bytes, включая production/dist/migrations,
совпадают с первоначальным manifest. Оба packaged app-bot JAR совпадают с прежним
SHA-256; JVM/PG/static/security/Vitest/packaging evidence переиспользовано для
неизменённых inputs. Новая сборка/deploy и тяжёлые повторные наборы не запускались.
Следующий шаг — независимый focused review этого fix; commit/push/PR не выполнены.

## Verdict и граница

**OWNER_CABINET_STATUS: READY_FOR_REVIEW.** Принятый staff-контракт реализован
сквозным локальным срезом: persistence → защищённые API → Mini App → packaged
Ktor/H2 browser journey. Это готовность кандидата конфигурационного кабинета к
независимой проверке, не опубликованная версия, не runtime acceptance и не
завершённость всего продукта. Утверждение владельца от 2026-11-08 устранило
прежний product blocker; исторический checkpoint предыдущего этапа сохранён.

## Идентичность и разрешения

- `koteev-m/clubs_bot`; существующий зарегистрированный worktree
  `/private/tmp/clb-owner-team-worktree-20261108`, ветка
  `codex/clb-owner-team-20261108`, HEAD/base
  `81c2822e6a3d6d53e863e106d4a34cf24720ac8a`. Новый worktree/clone не создавался.
- Read-only identity guard с task-id `CLB-owner-team`, expected-head и scopes
  `miniapp`, `app-bot`, `core-data`, `docs`: **PROJECT_IDENTITY_OK**.
  Active instruction chain принадлежит clubs_bot; active foreign instructions = 0.
  Guard и его правила не менялись. Применены инструкции основного checkout,
  worktree и contextual product/security/build rules; foreign instructions не загружались.
- Прежние профильные UX/security изменения сохранены; исходный patch и manifest
  сохранены отдельно в evidence. Старый clone/ветки/worktrees не удалялись.
  Чужие изменения `AGENTS.md`/`PROJECT_STATUS.md` основного checkout сохранены.
- Только local changes, disposable DB/tests/docs. Индекс пуст; commit, push, PR,
  merge, deploy и реальные данные не затрагивались. Сообщение о staging deploy
  не использовано как независимо проверенный runtime.

## Что работает

1. В выбранном клубе доступна «Команда»: состав, текущие активные permissions,
   membership/access status, pending и исторические приглашения. Настройка команды
   добавлена в «Подготовку клуба» как сохранённое необязательное состояние.
2. Только immutable creator клуба с активными client/membership/mapping и exact
   `client.club.read` + `client.club.team.manage` получает GET/POST team. Название
   роли, membership, username, body userId/clientId и legacy OWNER/GLOBAL не
   заменяют authority. Подмена клубов/назначений отклоняется сервером.
3. Создание выдаёт один раз Telegram-ссылку: 32 случайных байта, SHA-256 hash в БД,
   семь суток. Можно скопировать/поделиться или выделить ссылку вручную. После
   неизвестного результата replay использует тот же body/key; receipt не
   повторяет secret и объясняет необходимость нового приглашения.
4. Первый signed Telegram claim фиксирует кандидата и выдаёт comparison code.
   До отдельного owner confirmation grants/membership клуба не создаются.
   Владелец вводит код и подтверждает сверку исходного личного чата.
5. Confirmation атомарно создаёт либо переиспользует active membership, заменяет
   пакет exact grants, фиксирует assignment/result/audit. Inactive membership,
   отозванную платформой, клубный владелец восстановить не может. Весь пакет
   обязан помещаться в текущие права выдающего; частичная выдача исключена.
6. Роль меняется с preview полного пакета, причиной и явным подтверждением.
   Disable прекращает все client grants конкретного сотрудника в этом клубе
   после commit; membership, другие клубы, история и audit сохраняются.
   Новый допуск требует нового invitation/claim/owner confirmation. Старые ссылки,
   включая ранее выпущенные неиспользованные, доступ не возвращают.
7. Optimistic versions, durable idempotency, client lock, transactional audit,
   fresh authorization/replay и HTTP authority fences защищают конфликт,
   конкурентное принятие, отзыв и поздние ответы. Unknown-команда сохраняет
   intent/key. Ошибки/expiry/недостаток прав объясняются без декоративных действий.

## Матрица и пользовательский маршрут

Все коды ниже имеют префикс `client.club.`; все staff grants строго CLUB scope.

| Роль | Явно выдаваемый пакет |
|---|---|
| Host / ENTRY_MANAGER | `read` |
| MANAGER | `read`, `profile.write`, `layout.write`, `calendar.write`, `booking_rules.write` |
| CLUB_ADMIN | Весь MANAGER + `finance.read`, `finance.write`, `modules.read`, `modules.write` |
| Владелец | Существующие подтверждённые grants; отдельно законно выданный `team.manage` |

Роль — metadata, не authority. Нет `client.application.manage`, `user_roles`,
GLOBAL, QR/check-in, денежных/операционных permissions или наследования сети.
Другие исторические/целевые роли не удалены.

Владелец: «Мои клубы» → пространство → клуб → «Команда» → роль и получатель →
ссылка → кандидат → код/сверка чата → подтверждение → сотрудники → preview/
причина/смена роли → отключение → новое приглашение при необходимости.

Сотрудник: Telegram-ссылка → signed account confirmation → код/ожидание →
отдельное подтверждение владельца → повторное открытие Mini App → только
разрешённый клуб и разделы. Host просматривает конфигурацию; Manager сохраняет
профиль/залы/календарь/правила; Club Admin также доступные финансовые шаблоны и
module settings. Сервер повторяет проверки каждого action.

Существующие профиль, календарь/ночи, залы/зоны/столы, правила, private preview,
финансовые шаблоны, ранний приход и модули сохранены. Packaged regression
проверяет сохранение и повторное открытие конфигурации, конфликт/replay и отзыв.
DEC-013 (entrance-first/rewards), DEC-014 (DJ/music policy), DEC-022 (raffle
aggregate/conditions), DEC-031 (initial allocation catalogue/configuration slice)
сохраняют прежние ограничения. Эти решения не блокируют принятый staff-срез;
публичность и guest runtime, деньги/депозиты/смены, музыка, награды, розыгрыши и
Managed Bots не включены. Заполненные формы не означают готовность публичного запуска.

## Переиспользование и persistence

Использованы users, client memberships/mappings/club grants, существующие
client_invitations, client_commands, audit_log, client onboarding namespace и
fresh Telegram authorization. Legacy AdminShell не является staff API.
`client_club_staff` хранит assignment/role/version/revocation metadata, а не
параллельную authority. V075 для H2/PostgreSQL расширяет прежние приглашения,
добавляет team_version/constraints/FKs и bounded bootstrap team.manage только
активному уникальному immutable creator без восстановления grant tombstones.

UI использует существующие Shell, useRemote/useCommand/configuration hook,
Telegram BackButton/history, light/dark theme и ClubSetup. Нет отдельного auth
framework, permission catalogue или нового кабинета администратора.

## Проверки и evidence

Локальный evidence: `/private/tmp/clb-owner-team-staff-evidence`.
`candidate-manifest.json`, `candidate.patch`, `changed-files.txt`,
`final-checks.json`, XML archives и sanitized logs фиксируют bytes и результаты.
Исходные UX evidence сохранены отдельно; токены не включены в handoff/manifest.

| Проверка / команда | Результат |
|---|---|
| `project-identity-guard.py --task-id CLB-owner-team --expected-head 81c2822e6a3d6d53e863e106d4a34cf24720ac8a` с четырьмя scopes/instruction path | PROJECT_IDENTITY_OK |
| Targeted H2/Ktor staff/onboarding authorization, затем final delta checks | PASS: core-data 103/103 + app-bot 2/2 |
| `./gradlew test` + ordinary coverage/static/security/package tasks, strict/offline | 1483 passed, 1 skipped; `ordinary-verified.log`, XML archive |
| `CLB_CLIENT_TEST_PG_URL=… ./gradlew test -PrunIT=true --offline` | 1879 passed, 11 skipped; PostgreSQL 16 disposable, `postgres-full-final.log`, XML archive |
| H2/PostgreSQL clean migrate + V074→V075 bootstrap/tombstones/constraints | PASS в repository/IT и packaged H2; 73 миграции до V075 |
| `./gradlew coverageGate detektGate ktlintCheck :app-bot:checkLogsPolicy verifyResolvedProductionDependencyGraph :app-bot:installDist -PrunIT=true --offline --dependency-verification=strict` | PASS; `final-gates.log`; новые и изменённые Kotlin production/test sources без новых suppressions/baseline |
| `node node_modules/vitest/vitest.mjs run --pool=forks --maxWorkers=2 --minWorkers=1` | 385/385, 50 файлов |
| TypeScript `tsc --noEmit`; ESLint всего miniapp с `--max-warnings 0` | PASS |
| Vite build; `bash scripts/validate-miniapp-dist.sh` | PASS; dist и оба app-bot JAR идентичны, 5 ресурсов |
| Mobile Playwright `playwright.owner-ux.config.ts` | 42/42 Chromium/WebKit; profile revoke/stale responses, Telegram Back, 390/320 px, light/dark |
| Реальная fixture на packaged Ktor/JDBC/H2; external `playwright.onboarding.config.ts` в Chromium и WebKit | 8/8: полный onboarding, lost response/reissue, owner configuration, новый staff lifecycle |
| Production-dist mock onboarding Chromium | 10/10 |
| `SECRET_SCAN_OUTPUT_DIR=… bash scripts/verify.sh secret-scan` | Результат pinned source/history scan в `secret-final.log` / `secret-final` |
| Diff/links/anchors/scope/immutable concept + final manifest | Результаты в `final-checks.json` |

Staff contract tests выполняются на H2 и настоящем PostgreSQL: A1/A2/client B,
недостаток прав выдающего, self escalation, одноразовый secret/replay, expiry и
отзыв, concurrent claims/confirmations, rollback audit/commands/grants, inactive
membership, staff restore containment, DB constraints и migration bootstrap.
Отдельный PG regression блокирует profile GET на client lock и подтверждает,
что после committed revoke старый snapshot не разрешает чтение. Ktor test host
проверяет реальные HTTP contracts, trusted identity, spoofing и старую сессию.

Пропуски не маскируются: один прежний GuestFlowStartTest отключён в исходном коде
(не тот command handler); десять NativeSupportPostgresLifecycleIT /
SupportTicketCreationNativePostgresIT требуют отдельные native support PG env,
не запрашивались этим scope. Обычные staff PostgreSQL IT выполнены через Docker.
Полный JVM повторно без нужды не запускался: после source changes выполнен
полный PG, финальный packaging input вызвал нужный Gradle rerun зависимых tasks.

## Focused self-review и исправленные дефекты

- Старое неиспользованное приглашение могло повторно допустить после disable:
  добавлен persisted invitation fence, regression на H2/PostgreSQL.
- Profile GET мог читать RR snapshot до committed revoke: общий client lock и
  READ_COMMITTED; настоящий PostgreSQL blocking regression.
- Profile permission projection терял `booking_rules.write`: исправлена проекция
  и проверен точный Manager package.
- UI терял предупреждение о one-time link после secretless replay: сохранён
  missing-link receipt; истёкшее/недоступное действие требует нового выбора.
- Прежние profile HTTP capability epochs, unknown intent, навигация и exit после
  read denial сохранены и проверены; старые ответы не возвращают permissions.
- Full packaged run выявил устаревшие test assumptions: единственный клуб
  открывается сразу, несколько memberships требуют выбора пространства. Тесты
  теперь явно выбирают нужное пространство, проверки persistence/revoke сохранены.
- Node 24 Vitest threads crash произошёл после успешных assertions; тот же набор
  повторён в forks с exit 0, без исключения тестов. Stale ktlint cache reports
  пересозданы `--rerun-tasks --no-build-cache`, без изменения baseline/gates.
- Случайное форматирование несвязанных Kotlin files полностью отменено; только
  разрешённые изменённые файлы остались в diff. Собственные disposable failure
  contexts санитизированы; secret не записывается product logs/audit/storage.

## Unverified и риски

- Настоящий Telegram WebView/physical iPhone и реальные share/openTelegramLink не
  проверены; browser проверяет signed synthetic Telegram identities, ссылки,
  clipboard fallback, Back/theme events. Человеческую сверку исходного чата
  нельзя автоматизировать: UI требует явное owner confirmation.
- Hosted CI и stage/prod не запускались, серверная миграция не применялась.
  Локальные migration/concurrency/security results не подменяют release gates.
- Десять отдельных native support IT и один прежний disabled тест перечислены
  выше. Независимый review основного кандидата завершён; ожидается focused review P2 fix.
- Прежние Vite chunk-size/Browserslist и Gradle deprecation warnings сохранены;
  зависимости, security thresholds и gates не ослаблялись.
- Disposable fixture/PG завершены после проверок; старые worktrees и сервисы
  других проектов не затрагивались.

## Один следующий шаг

Провести независимый focused review P2 fix по новому manifest/narrow diff и
evidence изоляции артефактов. Публикация потребует отдельного разрешения.

## Изменённые файлы

- `app-bot/src/main/kotlin/com/example/bot/routes/ClientOnboardingRoutes.kt`
- `app-bot/src/test/kotlin/com/example/bot/routes/ClientBookingRulesRoutesDataTest.kt`
- `app-bot/src/test/kotlin/com/example/bot/routes/ClientCalendarRoutesDataTest.kt`
- `app-bot/src/test/kotlin/com/example/bot/routes/ClientNightEventLinksRoutesDataTest.kt`
- `app-bot/src/test/kotlin/com/example/bot/routes/ClientOnboardingBrowserFixture.kt`
- `app-bot/src/test/kotlin/com/example/bot/routes/ClientOperationalNightsRoutesDataTest.kt`
- `app-bot/src/test/kotlin/com/example/bot/routes/ClientStaffRoutesDataTest.kt`
- `core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientAccessOperations.kt`
- `core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientOnboardingRepository.kt`
- `core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubOnboardingOperations.kt`
- `core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubStaffAccess.kt`
- `core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubTeamOperations.kt`
- `core-data/src/main/kotlin/com/example/bot/data/client/onboarding/DirectClubOperations.kt`
- `core-data/src/main/kotlin/com/example/bot/data/client/onboarding/InvitationOperations.kt`
- `core-data/src/main/kotlin/com/example/bot/data/client/onboarding/JdbcClientOnboardingRepository.kt`
- `core-data/src/main/kotlin/com/example/bot/data/client/onboarding/StaffInvitationOperations.kt`
- `core-data/src/main/resources/db/migration/h2/V075__client_club_staff.sql`
- `core-data/src/main/resources/db/migration/postgresql/V075__client_club_staff.sql`
- `core-data/src/test/kotlin/com/example/bot/data/booking/core/CanonicalBookingH2Test.kt`
- `core-data/src/test/kotlin/com/example/bot/data/client/ClientContextContract.kt`
- `core-data/src/test/kotlin/com/example/bot/data/client/onboarding/ClientOnboardingPostgresIT.kt`
- `core-data/src/test/kotlin/com/example/bot/data/client/onboarding/ClubOnboardingContract.kt`
- `core-data/src/test/kotlin/com/example/bot/data/client/onboarding/ClubStaffContract.kt`
- `core-data/src/test/kotlin/com/example/bot/data/client/onboarding/OnboardingContract.kt`
- `docs/ops/PROJECT_STATUS.md`
- `docs/ops/owner-team-contract.md`
- `docs/ops/owner-team.md`
- `docs/product/AS_BUILT.md`
- `docs/product/CONCEPT_CODE_GAP.md`
- `docs/product/OPEN_DECISIONS.md`
- `docs/product/PRODUCT_ROADMAP.md`
- `docs/product/PRODUCT_SPEC.md`
- `miniapp/dist/assets/PlatformOwnerScreens-B4liM9un.js`
- `miniapp/dist/assets/PlatformOwnerScreens-D8JVGWhH.js`
- `miniapp/dist/assets/index-CsaG6cq3.js`
- `miniapp/dist/assets/index-Deh6eUuJ.js`
- `miniapp/dist/index.html`
- `miniapp/playwright.onboarding.config.ts`
- `miniapp/playwright.owner-ux.config.ts`
- `miniapp/src/modules/client/ClientAccess.tsx`
- `miniapp/src/modules/client/ClubProfile.tsx`
- `miniapp/src/modules/client/ClubSetup.tsx`
- `miniapp/src/modules/client/ClubTeam.tsx`
- `miniapp/src/modules/client/OnboardingShared.tsx`
- `miniapp/src/modules/client/configuration.hooks.ts`
- `miniapp/src/modules/client/onboarding.types.ts`
- `miniapp/src/modules/client/profile-lifetime.test.tsx`
- `miniapp/src/modules/client/staff-invitation.test.tsx`
- `miniapp/src/modules/client/team.test.tsx`
- `miniapp/src/modules/client/team.types.ts`
- `miniapp/tests/onboarding-integrated.spec.ts`
- `miniapp/tests/owner-profile-access.spec.ts`
- `miniapp/tests/owner-team-integrated.spec.ts`
