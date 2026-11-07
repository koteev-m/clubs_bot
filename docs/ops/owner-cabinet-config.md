# Настройки кабинета владельца: локальный handoff

## Идентичность и границы

- Техническая метка задачи: `CLB-owner-cabinet-config`. Это не новый зарегистрированный номер CLB.
- Repository: `koteev-m/clubs_bot`.
- Проверенная исходная ревизия и актуальный при preflight `origin/main`: `019793d187cf5e95672308bf2d696e73cf376677`.
- Локальная ветка: `codex/owner-cabinet-config`.
- Отдельный worktree: `/private/tmp/clb-owner-cabinet-config`.
- `PROJECT_IDENTITY_OK`; активные инструкции относятся к `clubs_bot`, active foreign instructions = 0. Чужие изменения исходного checkout в `AGENTS.md` и `docs/ops/PROJECT_STATUS.md` сохранены.
- Verdict: **PARTIAL_BLOCKED**. Безопасные независимые разделы реализованы и проверены локально. Полная активация дополнительных модулей и конфигурация распределения депозитов остаются за перечисленными ниже незакрытыми контрактами. Кандидат готов к независимому review; это не разрешение публикации или runtime readiness.
- Успешный staging deploy исходного SHA сообщён пользователем; новый runtime в этой задаче не проверяется и не изменяется.

Разрешены локальные исходники, тесты, disposable databases, сборка и документация. Commit, push, PR, merge, deploy, stage/prod и реальные клиентские данные исключены. Не меняются immutable-поля профиля, принятые финансовые правила, публичность клуба и guest booking cutover. Не подключаются реальные Managed Bots, платежи, AI и операционные роли сотрудников.

## Продуктовое основание

Основание — [порядок чтения product docs](../product/README.md), immutable [CONCEPT_SOURCE §15](../product/CONCEPT_SOURCE.md), [PRODUCT_SPEC](../product/PRODUCT_SPEC.md), [решения](../product/OPEN_DECISIONS.md), [roadmap](../product/PRODUCT_ROADMAP.md), [AS_BUILT](../product/AS_BUILT.md) и [gap register](../product/CONCEPT_CODE_GAP.md). Настоящий документ описывает локальную реализацию и ограничения, не принимает новые продуктовые решения.

Затронуты существующие требования `ONB-004`, `ONB-006`, `ONB-007`, `FIN-004`, `FIN-005`, `LOY-002` и клиентская граница `DEC-040`/`DEC-041`. Полнота legacy backend не означает готовность модуля в новом кабинете.

## Инвентаризация и переиспользование

| Возможность | Существующее evidence и переиспользование | Адаптер нового кабинета / оставшийся пробел | Настройка и ежедневная работа |
|---|---|---|---|
| Залы, зоны и физические столы | `halls`, `hall_zones`, `hall_tables`; [AdminHallsDbRepository](../../core-data/src/main/kotlin/com/example/bot/data/admin/AdminHallsDbRepository.kt), [AdminTablesRepository](../../core-domain/src/main/kotlin/com/example/bot/layout/AdminTablesRepository.kt). Клиентский layout уже связывает физический стол с canonical booking table и хранит координаты/изображение. | [ClubLayoutZones](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubLayoutZones.kt), [ClubLayoutTables](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubLayoutTables.kt), [ClientLayoutRoutes](../../app-bot/src/main/kotlin/com/example/bot/routes/ClientLayoutRoutes.kt) и [ClubZones](../../miniapp/src/modules/client/ClubZones.tsx) добавляют создание/переименование/безопасное удаление зоны и назначение стола зоне через прежний layout transaction. | Структура зала — настройка. Посадка/освобождение стола, stop-sales и работа с депозитом — отдельные интерфейсы персонала. |
| Типы браслетов | [AdminFinanceTemplateRoutes](../../app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceTemplateRoutes.kt), [ShiftReportTemplateRepository](../../core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportRepositories.kt), `club_bracelet_types`. | [ClubFinanceOperations](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubFinanceOperations.kt) и [ClubFinance](../../miniapp/src/modules/client/ClubFinance.tsx) используют те же identities, названия, enabled и order_index; добавлены client authorization, version, durable command и audit. | Справочник браслетов — настройка. Учёт проданных/выданных браслетов в смене не подключается. |
| Группы и статьи выручки | Те же template routes/repository; `club_report_templates`, `club_revenue_groups`, `club_revenue_articles`; существующие `include_in_total` и `show_separately`. [FinanceShiftScreen](../../miniapp/src/modules/admin/pages/FinanceShiftScreen.tsx) относится к операционной смене. | Новый клиентский адаптер сохраняет canonical template, связанные группы/статьи, порядок и флаги. Нет конкурирующего финансового хранилища и нет перехода в legacy shell. | Состав будущего отчёта — настройка. Суммы, закрытие смены, сверка, корректировки и депозитные операции не открываются. |
| Категории распределения депозитов | Есть append-only deposit operations и allocations; [DEC-031](../product/OPEN_DECISIONS.md) принимает направление canonical club categories и snapshots. | Стартовый каталог и table/deposit configuration slice ещё не согласованы. Статьи выручки не подменяют allocation categories. | Настройка справочника требует отдельного контракта; запись/корректировка денежной операции — отдельные полномочия. |
| Gamification settings, stamps, early arrival | `club_gamification_settings`; [GamificationSettingsRepository](../../core-data/src/main/kotlin/com/example/bot/data/gamification/GamificationRepositories.kt), [admin adapter](../../core-data/src/main/kotlin/com/example/bot/data/gamification/AdminGamificationRepositories.kt), [GamificationEngine](../../core-domain/src/main/kotlin/com/example/bot/gamification/GamificationEngine.kt). `early_window_minutes` читается при создании visit; ночное переопределение имеет приоритет. | [ClubModuleSettingsOperations](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubModuleSettingsOperations.kt) читает canonical flags и сохраняет только время раннего прихода. Runtime-включение блокируется незакрытым `DEC-013`. | Подготовка общего правила времени — настройка. Check-in, создание visit и начисление — операционный контур. `stamps_enabled=false` не означает отключение записи истории посещений. |
| Бейджи и призы | Есть `badges`, `prizes`, `reward_ladder_levels`, `user_badges`, `reward_coupons`; [AdminGamificationRoutes](../../app-bot/src/main/kotlin/com/example/bot/routes/AdminGamificationRoutes.kt) и engine поддерживают правила/пороги и выдачу. | Новый кабинет показывает сохранённые флаги и ограничение. Нет согласованной безопасной модели активации и отдельного draft authoring в этом блоке; `DEC-013` остаётся открытым. | Каталог и пороги относятся к будущей настройке. Выдача, погашение и ручные награды не подключаются. |
| Розыгрыши | В таблице settings есть `contests_enabled`, но нет потребляющего его полноценного raffle aggregate, периода и условий. | Модуль показан недоступным; `DEC-022` требует определения условий. Декоративного переключателя нет. | Создание условий относится к будущей настройке; запуск и подведение итогов не реализованы. |
| Лояльность по столам | Engine умеет метрику `TABLE_NIGHTS` и threshold rewards. | Это не полная накопительная программа клуба. Нужны её семантика и безопасная модель включения; `DEC-013` остаётся открытым. | Конфигурация программы и фактическое начисление/использование не смешиваются. |
| Музыка и границы контента | [AdminMusicRoutes](../../app-bot/src/main/kotlin/com/example/bot/routes/AdminMusicRoutes.kt), [MusicRoutes](../../app-bot/src/main/kotlin/com/example/bot/routes/MusicRoutes.kt), [music repositories](../../core-data/src/main/kotlin/com/example/bot/data/music/Repositories.kt): items/assets, published_at/is_active, playlists/interactions. Public catalogue выбирает опубликованный контент; mapped client clubs исключены существующим containment. | Per-club settings включения и moderation policy в persistence/service отсутствуют. `DEC-014`, права DJ, moderation/rights contract не заменяются полем musicEnabled. Музыка показана недоступной. | Общая политика — будущая настройка; загрузка/редактирование/публикация контента — работа DJ/уполномоченного персонала. Открытие раздела ничего не публикует. |
| Client grants и авторизация | [OnboardingSql](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/OnboardingSql.kt), [JdbcClientOnboardingRepository](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/JdbcClientOnboardingRepository.kt), [ClientContextAuth](../../app-bot/src/main/kotlin/com/example/bot/plugins/ClientContextAuth.kt). | Четыре exact permission для finance/modules; прежний layout.write для зон. Legacy OWNER/GLOBAL не получают клиентские права. | Настройки владельца не предоставляют ролей и операционных полномочий персонала. |
| Навигация и подготовка клуба | [ClientAccess](../../miniapp/src/modules/client/ClientAccess.tsx), [ClubProfile](../../miniapp/src/modules/client/ClubProfile.tsx), [ClubSetup](../../miniapp/src/modules/client/ClubSetup.tsx), общий [Shell](../../miniapp/src/modules/client/OnboardingShared.tsx). | Разделы открываются из конкретного клуба, сохраняют его контекст, общий внутренний возврат и Telegram BackButton. ClubSetup читает подтверждённые настройки, а не наличие frontend-компонентов. | Настроенная конфигурация не означает публикацию клуба, готовность гостевого runtime или разрешение принимать гостей. |

## Контракты локального кандидата

### Зоны

Зоны выдаются с layout конкретного зала. Создание и переименование используют текущую `layout_revision`; имена нормализуются, совпадение имени в одном зале даёт `zone_name_conflict`. Назначение существующего стола принимает zone ID и проверяет принадлежность тому же залу.

Удаление разрешается только для пустой зоны, если она не последняя. Проверка учитывает и неактивные столы: `zone_in_use` не позволяет молча потерять их принадлежность; `last_zone` не позволяет удалить последнюю зону. Создание нового типа soft-disable для зон не вводится. При назначении сохраняются identity физического стола, связь с booking table, существующие брони, история и координаты. Действия проходят через прежние layout grants, версии, receipt и audit.

### Финансовый шаблон

`GET /api/client/clubs/{id}/finance/template` читает текущие браслеты, группы и статьи. `POST` на тот же URL принимает команду `create`, `update`, `disable` либо `reorder` с `entity` и `expectedVersion`. Для изменяющей команды необходим `Idempotency-Key`.

Статью можно связать только с активной группой того же клуба. Отключение группы с активными статьями блокируется `finance_group_in_use`; identities и история не удаляются. Изменение флагов действует на конфигурацию шаблона, не переписывает сохранённые суммы и отчёты. Распределение депозитов остаётся отдельным недоступным пунктом с конкретным основанием в `DEC-031`.

### Дополнительные модули

`GET /api/client/clubs/{id}/modules-settings` возвращает `clubId`, `clientId`, положительную `version`, `canWrite`, `settingsPresent`, `earlyWindowMinutes`, фактически сохранённые flags и доступность модулей. GET не seed-ит строку и не принимает синтетический admin default `stampsEnabled=true` за сохранённую настройку.

`PATCH` принимает только `expectedVersion` и nullable целое неотрицательное `earlyWindowMinutes`, плюс `Idempotency-Key`. Изменение допускается только для непубличного клуба. Существующие flags сохраняются. При первой явной записи все flags записываются `false`, что сохраняет прежнее отсутствие выдачи engine при отсутствующей settings row. Сохранение не вызывает engine, не выдаёт награды и не меняет контент.

Статусы разделяют подготовленное правило, отключённый сохранённый флаг, требуемую настройку и недоступную возможность. «Правило настроено» относится к времени раннего прихода и не подтверждает готовность модуля лояльности. Сохранённый true-флаг также не считается доказательством готовности для гостей. Изменение flags, управление наградами, запуск розыгрыша, включение или публикация музыки отсутствуют в API этого блока.

Для canonical template/settings используется JDBC adapter внутри существующей onboarding transaction. Прямой вызов legacy Exposed writer, открывающего отдельную transaction, разорвал бы атомарность authority check, записи, durable receipt и audit. Переиспользуется существующая модель хранения; отдельная система финансовой или модульной конфигурации не создаётся.

## Авторизация и сохранность

- Fresh verified Telegram identity; активные client и membership; точная запись `client_clubs`; индивидуальные grants.
- Зоны: `client.club.read` плюс `client.club.layout.write` для записи.
- Финансы: `client.club.read` и `client.club.finance.read`; для записи дополнительно `client.club.finance.write`.
- Модули: `client.club.read` и `client.club.modules.read`; для записи дополнительно `client.club.modules.write`.
- Actor lock сериализует одинаковые команды, client lock согласует изменения с lifecycle/revoke и редакторами. Версия защищает от перезаписи более нового состояния.
- Durable replay повторно проверяет текущие права до выдачи результата. Revoke не обходится старым ключом или исторической ролью.
- Изменение canonical данных, версия, command receipt и audit фиксируются одной transaction; ошибка audit/receipt откатывает запись.
- [useClubConfiguration](../../miniapp/src/modules/client/configuration.hooks.ts) ограждает ответы прежней authority epoch, учитывает отзыв только права записи и не откатывает более свежую конфигурацию. Неизвестная команда сохраняет key/payload для явного повтора, в том числе после перехода в read-only; read сам по себе не повторяет запись.
- Legacy admin/public APIs не открываются клиенту; [PrivateClubContainment](../../app-bot/src/main/kotlin/com/example/bot/plugins/PrivateClubContainment.kt) и [ClientClubContainment](../../core-data/src/main/kotlin/com/example/bot/data/privacy/ClientClubContainment.kt) сохраняют границу mapped clubs.

## Миграция и затронутые поверхности

Новая additive migration — [PostgreSQL V074](../../core-data/src/main/resources/db/migration/postgresql/V074__client_club_configuration.sql) и [H2 V074](../../core-data/src/main/resources/db/migration/h2/V074__client_club_configuration.sql). Существующая `V073__booking_cutover_fences.sql` не меняется.

`V074` добавляет version metadata в canonical `club_report_templates` и `club_gamification_settings`, четыре permission codes и допустимые command result types. Исторические данные, суммы, identities и поля settings сохраняются. Upgrade выдаёт новые exact grants только единственному зафиксированному создателю непубличного клуба с активными client/membership/mapping и всеми прежними configuration grants. Существующая строка конкретного permission, включая неактивную, считается tombstone и не создаётся повторно. Legacy roles и другие сотрудники не получают автоматический доступ. Новое создание клуба записывает собственные exact grants в прежней transaction.

Изменённые поверхности: onboarding enums/dispatcher/grants и client routes; layout zones/view/table assignment; canonical finance/module adapters; ClientAccess/Profile/Setup и новые финансовая/модульная страницы; изолированная client-only theme и общие browser theme helpers; ingress allowlist; профильные unit/data/browser tests; сгенерированный Mini App dist после сборки. Точный итоговый список — `/private/tmp/clb-owner-config-evidence/changed-files.txt`; SHA-256 каждого изменённого файла — `candidate-manifest.json`, полный diff — `candidate.patch` в том же каталоге. Индекс Git остаётся пустым. Сгенерированные asset filenames заменены результатом финального Vite build.

## Проверки и evidence

Evidence: `/private/tmp/clb-owner-config-evidence`. Все результаты относятся к локальному diff поверх указанного base, а не к deployed revision. `candidate-manifest.json` фиксирует итоговые пути и SHA-256, `candidate.patch` включает tracked и untracked исходники без изменения индекса. XML сохранены отдельно для ordinary и PostgreSQL runs.

| Проверка | Фактическая команда / evidence | Результат |
|---|---|---|
| Identity и scope | `python3 -I -S -B scripts/project-identity-guard.py --task-id CLB-owner-cabinet-config --expected-head 019793d187cf5e95672308bf2d696e73cf376677 --scope docs/ops --scope miniapp --scope core-data --scope app-bot --instruction-path /Users/maksimmartynov/IdeaProjects/clubs_bot/AGENTS.md`; `git diff --check` | `PROJECT_IDENTITY_OK`, без foreign instructions; финальный scope/links record в evidence |
| Целевой Ktor/H2 | `./gradlew :core-data:test --tests '*ClientOnboardingRepositoryTest' :app-bot:test --tests '*ClientFinanceRoutesDataTest' --tests '*ClientModuleSettingsRoutesDataTest' --tests '*ClientLayoutRoutesDataTest'`; `targeted-migration-fixed.log` | 106 passed, 0 skipped; далее подтверждены полным ordinary и PG наборами |
| Полный ordinary JVM и обязательные gates | `./gradlew test coverageGate detektGate ktlintCheck :app-bot:checkLogsPolicy verifyResolvedProductionDependencyGraph :app-bot:installDist --dependency-verification=strict --console=plain`; `ordinary-final.log`, `ordinary-counts.json`, `ordinary-results.zip` | **1469 passed, 1 skipped**; coverage, Detekt, ktlint, log policy, strict production dependency graph и packaging PASS |
| Полный disposable PostgreSQL 16 | `CLB_CLIENT_TEST_PG_URL=jdbc:postgresql://127.0.0.1:52294/clb_client_test ./gradlew test -PrunIT=true --console=plain`; `postgres-full.log`, `postgres-counts.json`, `postgres-results.zip` | **1852 passed, 11 skipped**; 0 failures/errors. Собственный loopback-only disposable container и Testcontainers; stage/prod не используются |
| Frontend | Из `miniapp`: `node node_modules/vitest/vitest.mjs run`; `node node_modules/typescript/bin/tsc --noEmit`; `node node_modules/eslint/bin/eslint.js . --ext .js,.jsx,.ts,.tsx,.cjs,.mjs --max-warnings 0`; `vitest-theme.log`, `tsc-theme.log`, `eslint-theme.log` | **365/365**, 47 файлов; TypeScript и ESLint PASS |
| Мобильные регрессии | `CI=1 node node_modules/@playwright/test/cli.js test --config playwright.owner-ux.config.ts --reporter=dot`; `browser-mobile-theme-final.log` | **34/34** Chromium/WebKit; 390/320 px, фактическое событие Telegram light/dark, контраст текста минимум 4.5, доступные поля и возврат |
| Сквозной настоящий backend | Локальный `ClientOnboardingBrowserFixture` на упакованном JAR и H2; `integrated.sh` запускает `playwright.onboarding.config.ts --browser chromium/webkit --grep 'cabinet layout'`; `browser-integrated-*.log` | **2/2 PASS**. Profile → zones → financial template → early rule → ClubSetup, повторное открытие, сохранённые значения, foreign tenant 404, revoke, внутренний/Telegram BackButton; фактическая смена темы и контраст |
| Mini App и упаковка | `node node_modules/vite/bin/vite.js build`; `bash ../scripts/validate-miniapp-dist.sh dist`; `./gradlew :app-bot:installDist --dependency-verification=strict --console=plain`; `vite-theme.log`, `dist-theme.log`, `package-theme.log`, `package-parity.json` | PASS; 5 файлов dist идентичны ресурсам `webapp/app/react/` в build и installed JAR, 10 сравнений; core-data JAR также идентичен installed copy |
| Ingress | `python3 -I -B scripts/tests/check_client_onboarding_ingress.py --caddy /private/tmp/clb-owner-config-evidence/caddy`; `ingress-final.log` | PASS: exact PATCH allowlist, запрещённые пути/методы, no-store/no-referrer и безопасная ошибка upstream. Native Caddy проверен по официальному SHA-512 |
| Secret scan | `SECRET_SCAN_OUTPUT_DIR=/private/tmp/clb-owner-config-evidence/secret-final bash scripts/verify.sh secret-scan`; `secret-final.log` | **PASS**, source 0 findings, history 682 commits / 0 findings; sanitized evidence |
| Независимый review | `independent-review.json`, `independent-theme-review.json` | Рецензенты проверили чужие участки. Findings закрыты; production bytes и scope привязаны к SHA-256 |

Содержательные backend проверки подтверждают tenant/related-object isolation, read/write grants, fresh identity, revoke и replay после него, optimistic versions, конкурентные команды (в том числе два редактора), rollback при ошибке audit, migration eligibility и tombstones. Модульные тесты запрещают изменение flags и проверяют сохранение прежних flags/истории. Zone tests сохраняют физический и booking IDs, координаты и связи, блокируют удаление занятой/последней зоны. Frontend проверяет неизвестный результат с прежним key/payload, отказ и отзыв только записи, старые GET/POST/PATCH ответы, восстановление доступа, ошибки рядом с действием и подтверждённые значения в ClubSetup.

Обнаруженные и исправленные проблемы: коллизия номера новой миграции с существующей V073 (новая получила V074); stale write-only authority на frontend (добавлены epoch fences и регрессии); лишнее восстановление booking-rule grant вне scope (удалено); upgrade tests теперь явно сохраняют проверку V073 и доходят до V074; нативный select WebKit с длинным выбранным текстом выходил за ширину 320 px после увеличения шрифта (ограничена ширина/ellipsis, исходный gate не ослаблен). Предварительные failed logs сохранены, итоговые reruns указаны выше.

В ordinary пропущен существующий `GuestFlowStartTest`; в IT дополнительно 10 opt-in native support PostgreSQL tests, поскольку не задан `CLB_SUPPORT_CREATION_TEST_PG_BIN`. Это не новые пропуски и не исключения из затронутых контрактов. PostgreSQL-проверки нового блока реально выполнены. Gradle/JaCoCo сохраняют прежние предупреждения о четырёх refund/cancel DTO class execution data и configuration-cache/deprecation; обязательные gates прошли. Vite предупреждает о существующем большом bundle; пороги не менялись.

Финальное изменение темы затрагивает только frontend/CSS и ресурсы Mini App: после него повторены frontend, браузеры, Vite/dist и packaging; успешно проверенные backend bytes не менялись, JVM/IT не запускались повторно без основания. Зависимости не обновлялись: использован локальный node_modules из предыдущего checkout с побайтово совпадающим lockfile; production dependency verification остаётся strict.

Disposable контейнер задачи удалён после успешных проверок; локальный browser fixture завершён. Worktree и evidence сохранены для review.

Реальный Telegram WebView/физический iPhone, hosted CI, staging/prod, runtime release и реальные данные не проверены. WebKit с iPhone viewport не является проверкой на физическом устройстве. Эти ограничения не отменяют обязательный release gate. Все настройки остаются закрытыми; открытие раздела и сохранение раннего правила не публикуют контент и не выдают награды.

## Оставшийся кабинет и следующий блок персонала

| Область | Оставшийся результат / зависимость |
|---|---|
| Профиль, layout, календарь, booking rules, закрытый preview | Существующая основа плюс текущая конфигурация; общая сводка показывает подтверждённые значения без заявления полной гостевой готовности. |
| Финансы | Отдельно согласовать стартовый allocation catalogue и configuration slice по принятому `DEC-031`; не выводить его из статей выручки. Денежные операции требуют ролей и отдельного разрешённого блока. |
| Лояльность | Решить `DEC-013`, закрыть вход-before-progress и определить безопасную активацию. Затем authoring бейджей/призов и накопительная программа. Наличие таблиц не закрывает эти зависимости. |
| Розыгрыши | Решить `DEC-022`: условия, период, прогресс и lifecycle; только затем рабочая конфигурация. |
| Музыка | Решить `DEC-014`, права DJ и правила moderation/publication; добавить согласованный per-club settings contract. Guest music, playlists/favourites и дальнейшие расширения сохраняют собственные границы решений. |
| Персонал — следующий отдельный блок | Согласовать и реализовать приглашение/назначение конкретных сотрудников, club scopes, индивидуальные grants, отзыв/восстановление и audit. После этого подключать ограниченные journeys Host/Manager/Promoter/DJ и финансовую роль. Доступ владельца к конфигурации не даёт этих полномочий. |
| Публикация и guest runtime | Отдельный accepted cutover и release readiness, не выводимые из состояния ClubSetup. |
| Managed Bots, платежи, AI | Отдельные будущие outcomes; реальные подключения и операции здесь не выполняются. |

Единственный следующий шаг: независимый review локального кандидата и зафиксированных продуктовых ограничений до любой публикации. Commit, push, PR, merge и deploy не выполнены.
