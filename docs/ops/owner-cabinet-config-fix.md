# Owner cabinet configuration — P2 replay fix

**OWNER_CABINET_CONFIG_FIX_READY** для ограниченного независимого review.

Техническая метка: `CLB-owner-cabinet-config`, продолжение существующего кандидата,
не новый зарегистрированный номер CLB. Worktree `/private/tmp/clb-owner-cabinet-config`,
ветка `codex/owner-cabinet-config`, HEAD/base
`019793d187cf5e95672308bf2d696e73cf376677`. `PROJECT_IDENTITY_OK`.
Начальный кандидат совпал со всеми 67 записями сохранённого manifest; индекс пуст.
Предыдущие продуктовые ограничения `PARTIAL_BLOCKED` остаются в силе.

## Root cause и исправление

Подтверждённый пользователем результат внешнего review:
`OWNER_CABINET_CONFIG_REVIEW_FINDINGS / FIXES_REQUIRED`, один P2.
`ClubLayout` после успешного `api.retry()` увеличивал `refresh`, включённый в
React key `ClubZones`. Перемонтирование уничтожало все локальные формы,
включая не участвовавшие в команде черновики названий и назначений столов.

Key зон теперь связан с клубом и залом. Replay команды зон не перемонтирует
ни зоны, ни редактор физического стола. Обычные replay-команды legacy editor
сохраняют прежнее обновление редактора, но не сбрасывают зоны.

В retained command хранится callback только отправившей её формы. Он вызывается
после проверки текущих прав, версии и принятия серверного snapshot. Key и HTTP
payload при replay остаются прежними. Callback завершает только тот же объект
черновика в ещё смонтированной форме; старые ответы и результаты другого зала
не могут очистить или пометить сохранённым чужой черновик.

Каждая изменённая форма хранит значение, исходное значение и исходную версию.
Неизменённые поля отображают текущий серверный snapshot. Получение свежих данных
не переопределяет базовую версию черновика: устаревшая запись получает conflict.
«Отменить изменения» сбрасывает только выбранную форму, не пишет в БД и позволяет
начать новую правку от подтверждённой версии. Отзыв чтения размонтирует приватные
формы; отзыв только записи блокирует редактирование, сохраняя разрешённое чтение.

Production delta ограничена [ClubLayout](../../miniapp/src/modules/client/ClubLayout.tsx),
[ClubZones](../../miniapp/src/modules/client/ClubZones.tsx) и
[layout.api](../../miniapp/src/modules/client/layout.api.ts). HTTP API, production
backend, финансовые правила, зависимости и V074 не менялись.

## Воспроизведение и проверки

Evidence: `/private/tmp/clb-owner-config-fix-evidence`; начальные manifest/patch/archive
сохранены с префиксом `before-`. Узкая финальная дельта, SHA-256 и список файлов
передаются в этом же каталоге.

- **RED до исправления:** `node node_modules/vitest/vitest.mjs run src/modules/client/zones-replay.test.tsx`;
  `repro-before.log`, exit 1. Expected `Черновик A`, actual `Зона A` после успешного
  повтора создания B. Тест использует настоящий ClubLayout, ClubZones и HTTP adapter;
  замоканы HTTP и независимый редактор изображения.
- **GREEN:** профильные zones/replay/adapter **33/33**, затем весь Vitest **372/372**,
  48 файлов; `zones-expanded.log`, `vitest-final.log`.
- Семь новых сценариев проверяют параллельные черновики, неизвестный исход,
  создание/переименование/назначение через replay, тот же payload/key,
  отсутствие ложного сохранения, исходные версии после replay/GET, conflict,
  локальную отмену, ordinary save, revoke/restore, смену зала/клуба и поздний ответ
  после более нового snapshot. Прежние adapter tests дополнены проверками, что
  confirmation не срабатывает при отзыве прав и вызывается ровно один раз.
- `node node_modules/typescript/bin/tsc --noEmit` и
  `node node_modules/eslint/bin/eslint.js . --ext .js,.jsx,.ts,.tsx,.cjs,.mjs --max-warnings 0` — PASS.
- `CI=1 node node_modules/@playwright/test/cli.js test --config playwright.owner-ux.config.ts --reporter=dot`
  — **34/34**, Chromium/WebKit, мобильные layout/navigation/theme regressions.
- Сквозной packaged Ktor/H2 scenario расширен потерей настоящего ответа сохранения B,
  параллельными черновиками A/назначения стола, точным повтором, конфликтом старой
  версии, перечитыванием, отменой и последующим успешным сохранением.
  **2/2 PASS**, Chromium/WebKit: `integrated.sh`, `browser-integrated-chromium.log`,
  `browser-integrated-webkit.log`. Первая попытка browser test остановилась из-за
  неверно вложенного test locator кнопки отмены; locator исправлен без изменения
  production-кода или ослабления проверок, `browser-locator-error.log` сохранён.
- `node node_modules/vite/bin/vite.js build`, `bash ../scripts/validate-miniapp-dist.sh dist`,
  `./gradlew :app-bot:installDist --dependency-verification=strict --console=plain` — PASS.
  Пять ресурсов dist сравниваются с обоими JAR, core-data JAR — с installed copy;
  `package-parity.json` фиксирует SHA-256.
- `git diff --check` и проверка относительных ссылок нового handoff — PASS.
  Финальный source/history secret scan: `secret-scan.log`, sanitized `secret/`.

Изменённые файлы этой итерации: три production-файла выше;
`miniapp/src/modules/client/zones-replay.test.tsx`, `zones.test.tsx`, `layout.api.test.ts`;
`miniapp/tests/onboarding-integrated.spec.ts`; `ClubFinanceContract.kt`;
этот handoff и `PROJECT_STATUS.md`; сгенерированные Mini App dist assets/index.
Точный перечень относительно начального кандидата — `fix-changed-files.txt`,
дельта — `fix-delta.patch`, SHA-256 — `fix-manifest.json` в каталоге evidence.

## Закрытый финансовый отчёт

Один новый inherited contract в
[ClubFinanceContract](../../core-data/src/test/kotlin/com/example/bot/data/client/onboarding/ClubFinanceContract.kt)
использует настоящие canonical repositories: создаёт draft, записывает исторические
значения и браслеты, переводит report в CLOSED, затем через owner configuration
переименовывает статью, меняет её order/flags и отключает статьи/используемую категорию.

Повторное чтение CLOSED-отчёта идентично исходному; суммы 12345 minor units в итоге
и 6789 в отдельном показателе, исторические имена/порядок/флаги статей и количество
браслетов сохраняются. Отключённая категория остаётся доступна canonical display
projection, её groupId/name и групповой итог корректны. Попытка updateDraft закрытого
отчёта отклоняется. Новых финансовых операций в кабинете не появилось.

- H2: `./gradlew :core-data:test --tests '*ClientOnboardingRepositoryTest.closed finance report*'` — **1/1 PASS**.
- Disposable PostgreSQL 16: `CLB_CLIENT_TEST_PG_URL=jdbc:postgresql://127.0.0.1:64775/clb_client_test ./gradlew :core-data:test -PrunIT=true --tests '*ClientOnboardingPostgresIT.closed finance report*' :core-data:ktlintCheck :core-data:detektTest --console=plain` — **1/1 PASS**, ktlint/Detekt PASS.
- Точные команды, XML, hash теста и ограничения: `finance/summary.json`.
  Созданный тестовый контейнер удалён; реальные данные не использовались.

Реального нарушения исторической сохранности не обнаружено. Названия категорий и
браслетов в существующей модели — текущие справочные значения, не исторические
snapshots. Тест доказывает отключение используемой категории и snapshot статьи;
он не заявляет сохранение старого имени категории после её переименования.

## Review, границы и непроверенное

Независимый read-only focused review трёх production-файлов относительно начального
архива не обнаружил actionable findings; `finance/frontend-replay-review.json`
фиксирует scope и SHA-256. Финансовый тест отдельно проверен основным агентом.

Полный тяжёлый JVM/coverage/PostgreSQL набор не повторялся: production backend,
миграции и их inputs не менялись. Предыдущие результаты остаются evidence для
совпадающих bytes; новый test-only сценарий выполнен на обеих БД и прошёл профильные
static gates. Vite сохраняет предупреждение о крупном bundle, пороги не менялись.

Физический iPhone/Telegram WebView, hosted CI, staging/prod и реальные данные не
проверялись. Исходники других worktree не менялись. Commit, push, PR, merge и deploy
не выполнялись. Единственный следующий шаг — ограниченный независимый review P2
исправления и нового evidence закрытого финансового отчёта.
