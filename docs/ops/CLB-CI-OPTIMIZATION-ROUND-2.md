# CLB-CI-OPTIMIZATION-ROUND-2

Кандидат в ветке `codex/clb-ci-optimization-round-2` основан на `5364851c61cae78977faae62fc544b114d2f3033` после PR #575.

## Измерено в GitHub Actions

| Этап #575 | Время | Критический путь |
| --- | ---: | --- |
| Lint на PR | 25:53 | `corrected-import-root` 25:35, затем итоговый Lint |
| Lint после merge | 27:01 | `corrected-import-root` 26:43, затем итоговый Lint |
| Docker Publish после merge | 36:36 | `build-and-push` 35:49, затем Trivy 0:41 |

От открытия Draft PR до завершения Docker Publish прошло **62:55**, из них 36:36 после создания Docker Publish на main. Main Lint выполнялся параллельно с публикацией. [PR #575](https://github.com/koteev-m/clubs_bot/pull/575) · [PR Lint](https://github.com/koteev-m/clubs_bot/actions/runs/45792291360) · [Main Lint](https://github.com/koteev-m/clubs_bot/actions/runs/45796358669) · [Docker Publish](https://github.com/koteev-m/clubs_bot/actions/runs/45796358676)

В шаге Docker-сборки `installDist` занял около 3:01 для AMD64 и 33:46 для ARM64 через QEMU. Нативные PR-задания, включая сборку и Trivy, уже заняли 4:57 на AMD64 и 4:14 на ARM64. У PR-образов другой контракт публикации и подписи. [Лог Docker Publish](https://github.com/koteev-m/clubs_bot/actions/runs/45796358676/job/139827898015) · [Нативный AMD64](https://github.com/koteev-m/clubs_bot/actions/runs/45792291360/job/139814147127) · [Нативный ARM64](https://github.com/koteev-m/clubs_bot/actions/runs/45792291360/job/139814147238)

## Как публикуется проверенный индекс

- AMD64 и ARM64 параллельно работают на `ubuntu-24.04` и `ubuntu-24.04-arm`. Перед сборкой каждое задание сверяет архитектуру runner и checkout с `GITHUB_SHA`. Buildx отправляет манифесты строго по digest. До финальной публикации у образов и индекса нет тега, который можно передать штатному ручному деплою. Формат релизных тегов и OCI labels по-прежнему задаёт тот же pinned metadata action.
- Сборка индекса ждёт успеха двух матричных заданий, получает digest непосредственно из Buildx каждой архитектуры, сверяет номера попыток и объединяет OCI-манифесты вместе с attestations. Cosign подписывает общий digest. Syft сохраняет один CycloneDX SBOM для платформы по умолчанию на AMD64 runner. Отдельное задание проверяет подпись и OCI/SLSA-граф. Для каждой архитектуры verifier сверяет digest из SLSA subject с манифестом, проверяет полный SHA в provenance и OCI revision label, а также ID прогона и попытку сборки. Сохранены проверки самого subject digest, source revision, репозитория, workflow и формата свидетельств.
- Trivy сканирует обе архитектуры того же digest, с прежними порогами и обязательным статусом. Только после успеха SBOM, подписи, SLSA и обоих Trivy-сканов `publish-tags` назначает прежние теги копией проверенного индекса. Digest до и после соответствует одному OCI-объекту. Действующие deploy workflow и release scripts используют прежний образ, тег, digest и метку ревизии.
- Сборки пишут кеши `release-image-amd64` и `release-image-arm64` отдельно. Старый кеш `buildkit` остаётся источником при переключении. PR и smoke могут читать соответствующий кеш публикации, PR пишет только в собственный scope. [Docker Buildx и распределённая сборка](https://docs.docker.com/build/ci/github-actions/multi-platform/) · [Семантика копирования OCI-индекса](https://docs.docker.com/reference/cli/docker/buildx/imagetools/create/)

## Повторные проверки и PostgreSQL

PR JVM-задание проверило синтетический merge `d0d0687eedc11eb55f53621a4265a32b0f2ac5eb`. У него и опубликованного main `5364851c61cae78977faae62fc544b114d2f3033` одно дерево `b2ffb8dce8b3ca868c80fa59919c5b3f0ed58fad` и разные SHA. Повторно шли Mini App, JVM, smoke, corrected-stage, проверки секретов и релизного состояния. PR-образ строился для головы PR и отдельно сканировался. Публикация строит для SHA main с другим контекстом и свидетельствами. [Checkout JVM на PR](https://github.com/koteev-m/clubs_bot/actions/runs/45792291360/job/139814147287) · [Git PR merge](https://github.com/koteev-m/clubs_bot/commit/d0d0687eedc11eb55f53621a4265a32b0f2ac5eb) · [Git main](https://github.com/koteev-m/clubs_bot/commit/5364851c61cae78977faae62fc544b114d2f3033)

У проверок JVM и Mini App есть вычисления, зависящие от дерева, но существующий gate учитывает статус, точный профиль и свежие отчёты текущего запуска. Security scans зависят от внешних данных и Git-истории, а OCI evidence и release checks связываются с идентичностью запуска. Без доверенного контракта передачи артефактов и статуса на main эти задания продолжают выполняться. Содержимое BuildKit можно переиспользовать независимо от результатов проверок.

| PostgreSQL-группа | PR job / Gradle | Main job / Gradle |
| --- | ---: | ---: |
| `application` | 17:54 / 16:09 | 20:59 / 18:59 |
| `data-client` | 15:42 / 14:16 | 18:47 / 17:09 |
| `data-other` | 12:42 / 11:20 | 16:09 / 14:29 |

Разница между временем job и Gradle составляет 1:22–2:00, включая checkout, установку окружения и проверку зависимостей. Эти шаги идут параллельно на отдельных runner. Перенос тестов между двумя `core-data`-группами не ускорил бы `application`, а в обоих полных прогонах Lint ждал `corrected-import-root` дольше, чем PostgreSQL. Матрица, состав тестов и Postgres guards сохранены.

## Ожидаемое время и границы результата

Ориентир **5–10 минут для Docker Publish** основан на измеренных нативных PR-сборках и коротких операциях сборки индекса, подписи и сканирования. Скорость запуска ARM64 runner, холодный кеш, GHCR и базы Trivy могут увеличить это время. При таком ориентире и тех же интервалах до merge время от создания PR до готовности образа составит примерно 31–36 мин. Если операционный порядок требует ещё и зелёного Main Lint для изменения самого CI, этот замер показывает нижнюю границу около 53 минут для всех статусов вместе. Первый замер новой публикации возможен после merge.

При повторе сборки индекса или проверки SLSA задания используют digest и номер попытки исходных сборок из outputs GitHub Actions. Если сборки относятся к разным попыткам, оператор выбирает **Re-run all jobs**.

Финальное обновление тегов использует общую для ветки и версий очередь GitHub Actions. Под блокировкой задание сравнивает свой `GITHUB_RUN_NUMBER` с аннотацией уже опубликованного индекса для каждого тега. Для `main`, `latest`, `vM.m` и `sha-…` сохраняется результат более нового опубликованного запуска этого workflow. Очерёдность определяется номером запуска, включая публикации версий из разных линий. Индексы до этой схемы не содержат аннотации и принимают первое обновление. Повтор старого запуска новой схемы также не отменяет текущую первичную сборку.

Если registry примет лишь часть релизных тегов из-за сетевого сбоя, каждый успевший тег указывает на тот же уже проверенный digest. Запуски, созданные до merge, при повторе исполняют прежний workflow. Для автоматического Production по тегу `v*` сохраняется окно ожидания образа — до 60 попыток с паузой 5 секунд.

## Локальные проверки

- Реальный Docker Buildx на одноразовом loopback registry. При отсутствии ARM64 индекс не создан. При двух входах получены два OCI-манифеста, два привязанных по digest SLSA v1 свидетельства и правильные OCI revision labels. До финального шага список тегов был пуст. Теги `sha-5364851` и `main` разрешились в один digest. Запоздавшая публикация обновила свой отдельный тег и сохранила более новый `main`. Использовались тестовые слои `FROM scratch`. Проверки полномочий GHCR, GitHub OIDC, hosted cache и времени GitHub этим тестом не проверялись.
- 15 тестов Docker Publish и 16 тестов PR image прошли. Включены запрещённые упрощения матрицы, эмуляция, ранние релизные теги, общий записываемый кеш, исключение зависимости от Trivy или provenance и публикация по изменяемому тегу.
- 48 тестов OCI provenance прошли. Один live-тест GHCR остался штатно выключенным. Отрицательные сценарии проверили чужой SHA, другой запуск, попытку и архитектуру, а также сохранённые проверки неполных и дублированных OCI-графов.
- Прошли валидаторы 32 workflow, 14 негативных capability-фикстур, структурный Linux-валидатор деплоя и проверка несовпадения владельца, ревизии или digest. Полные Gradle и исторические release-state suites повторно не запускались.

## Изменённые файлы

| Область | Файлы |
| --- | --- |
| Сборка и кеши | [docker-publish.yml](../../.github/workflows/docker-publish.yml), [docker-image.yml](../../.github/workflows/docker-image.yml), [container-smoke.yml](../../.github/workflows/container-smoke.yml) |
| Свидетельства и контракты | [verify-oci-provenance.py](../../scripts/verify-oci-provenance.py), [validate-workflow-capabilities.rb](../../scripts/validate-workflow-capabilities.rb), [selfcheck-quality-gates.sh](../../scripts/selfcheck-quality-gates.sh) |
| Тесты | [test_docker_publish_workflow.rb](../../scripts/tests/test_docker_publish_workflow.rb), [test_verify_oci_provenance.py](../../scripts/tests/test_verify_oci_provenance.py) |
| Отчёт | Этот отчёт, [PROJECT_STATUS.md](PROJECT_STATUS.md) |
