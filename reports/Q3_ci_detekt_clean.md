# Q3 — CI/CD и quality gates (Clean/Detekt/Tooling) аудит

## 1) Что есть сейчас

### 1.1 CI pipeline (GitHub Actions) — реальные workflow/job'ы

В репозитории используется GitHub Actions с несколькими независимыми workflow:

- `build.yml` — основной build pipeline (lint/detekt/dependency guard/test/build).  
  Ссылки: `.github/workflows/build.yml:1-68`.
- `tests.yml` — unit + integration + container smoke + guard checks (динамические версии/репозитории/plugins/catalogs) + upload артефактов отчётов.  
  Ссылки: `.github/workflows/tests.yml:16-310`, `.github/workflows/tests.yml:246-252`, `.github/workflows/tests.yml:339-344`.
- `static-check.yml` — отдельный Detekt run с SARIF upload.  
  Ссылки: `.github/workflows/static-check.yml:1-49`.
- `build-static.yml` — `formatAll`, `staticCheck`, `build` + artifact upload (detekt/ktlint/tests).  
  Ссылки: `.github/workflows/build-static.yml:17-68`.
- `lint.yml` — быстрый lint (`ktlintCheck detekt -x test`, сейчас с `|| true`).  
  Ссылки: `.github/workflows/lint.yml:1-23`.
- `security-scan.yml` — Trivy fs scan (SARIF + artifact).  
  Ссылки: `.github/workflows/security-scan.yml:1-49`.
- `docker-publish.yml` — build/push image + cosign/sbom/provenance + Trivy image scan.  
  Ссылки: `.github/workflows/docker-publish.yml:148-182`.
- `db-migrate.yml` — CI-managed Flyway migrate (`flywayMigrate`) с secrets.  
  Ссылки: `.github/workflows/db-migrate.yml:21-60`.
- `ci-guards.yml` — guardrails на workflow pinning (uses@40char SHA).  
  Ссылки: `.github/workflows/ci-guards.yml:14-66`.
- `commitlint.yml` — проверка commit message policy.  
  Ссылки: `.github/workflows/commitlint.yml:11-37`.

Также присутствуют `smoke.yml`, `container-smoke.yml`, `dependency-drift.yml`, `docker-image.yml`, `release.yml`, `deploy-ssh.yml` для smoke/deploy/release/supply-chain сценариев.

### 1.2 Gradle quality tooling (фактические задачи)

Обнаружены и доступны:

- `clean`, `test`, `check`
- `detekt`, `detektTest`
- `ktlintCheck`, `ktlintFormat`
- агрегаторы: `staticCheck`, `formatAll`, `dependencyGuard`

Ссылки: `build.gradle.kts:169-266`, `build.gradle.kts:159-162`.

Отсутствуют как задачи в проекте:

- `spotlessCheck` / `spotlessApply`
- `jacocoTestReport` (как Gradle task)
- `dependencyCheckAnalyze` (OWASP Dependency-Check)
- `gitleaks` task

(проверено через `./gradlew help --task ...`).

### 1.3 Pre-commit / hooks

Есть локальные git hooks в `.githooks/`:

- `pre-commit` запускает `./gradlew -q formatAll` и `./gradlew -q staticCheck`.  
  Ссылки: `.githooks/pre-commit:1-4`, `.githooks/pre-commit.cmd:1-5`.

Замечание: hook существует, но не видно обязательной автоматической установки `core.hooksPath` в репо-скриптах/CI, т.е. покрытие реально зависит от локальной настройки разработчика.

### 1.4 Dependency/security scanning

Есть:

- Trivy fs scan (`security-scan.yml`) и Trivy image scan (`docker-publish.yml`), SARIF upload в code scanning.  
  Ссылки: `.github/workflows/security-scan.yml:19-49`, `.github/workflows/docker-publish.yml:148-182`.
- SBOM/provenance/signature verify (cosign + anchore sbom-action) в image pipeline.  
  Ссылки: `.github/workflows/docker-publish.yml:105-146`.
- Dependency drift report (versions plugin).  
  Ссылки: `.github/workflows/dependency-drift.yml:1-46`.

Не найдено:

- OWASP dependency-check/SCA gate на JVM dependencies.
- Специализированный secret scanner (gitleaks/trufflehog) как отдельный gate.

---

## 2) Что запускается локально и в CI

### 2.1 Локально (факт запуска в этой проверке)

#### Команда: `./gradlew clean test check`
- **Результат:** **FAIL**.
- Причины фейла:
  - `:app-bot:ktlintMainSourceSetCheck` — множество style violations.
  - `:app-bot:detekt` — `Analysis failed with 318 weighted issues`.
- Итого: quality gate `check` в текущем состоянии не проходит.

#### Команда: `./gradlew test`
- **Результат:** **PASS** (`BUILD SUCCESSFUL`).
- Тестовый контур в целом исполним и зелёный, но присутствуют предупреждения deprecation в compile/test phase.

#### Команда: `./gradlew detekt ktlintCheck`
- **Результат:** **FAIL**.
- Ключевые ошибки:
  - `:core-domain:detekt` — 30 weighted issues.
  - `:core-data:detekt` — 81 weighted issues.
  - `:app-bot:detekt` — 318 weighted issues.
- Типовые категории: `TooManyFunctions`, `MaxLineLength`, `ReturnCount`, `MagicNumber`, `NestedBlockDepth`.

#### Команда: `./gradlew ktlintCheck`
- **Результат:** **FAIL**.
- Основной стоппер: `:app-bot:ktlintMainSourceSetCheck` (import ordering, function signature formatting, max-line-length, argument wrapping и др.).
- Отчёт путь: `app-bot/build/reports/ktlint/ktlintMainSourceSetCheck/ktlintMainSourceSetCheck.txt`.

#### Проверка наличия задач
- `detekt`, `ktlintCheck`, `ktlintFormat` — есть.
- `spotless*`, `jacocoTestReport`, `dependencyCheckAnalyze` — отсутствуют.

### 2.2 В CI (фактические команды)

- Build workflow запускает `ktlintFormat ktlintCheck detekt`, затем `dependencyGuard`, затем `test`, затем `clean build`.  
  Ссылки: `.github/workflows/build.yml:52-68`.
- Tests workflow запускает unit (`clean test`) и integration (`test -PrunIT=true`) с retry.  
  Ссылки: `.github/workflows/tests.yml:228-244`, `.github/workflows/tests.yml:293-309`.
- Static workflow запускает `detekt` и публикует SARIF.  
  Ссылки: `.github/workflows/static-check.yml:24-41`.
- Lint workflow сейчас **не блокирующий** из-за `|| true` на `ktlintCheck detekt -x test`.  
  Ссылка: `.github/workflows/lint.yml:23`.

---

## 3) Что отсутствует / пробелы

1. Нет `spotless` (как альтернатива/дополнение ktlint-format policy).
2. Нет явного coverage gate (в CI артефакты jacoco грузятся, но jacoco task/gate в Gradle не найден).
3. Нет OWASP dependency-check gate для JVM deps.
4. Нет dedicated secret scanning (gitleaks/trufflehog) gate.
5. Нет обязательной установки pre-commit hooks (локальный hook optional by setup).
6. `lint.yml` не fail-fast (используется `|| true`), поэтому часть quality сигнала «размыта».

---

## 4) Рекомендованный набор quality gates

## Минимум (обязательный baseline)

1. `./gradlew clean test` (unit + compile contract).
2. `./gradlew ktlintCheck detekt` (style + static analysis, blocking).
3. `./gradlew dependencyGuard` (уже есть — оставить blocking).
4. Secret scan в CI (`gitleaks` или `trufflehog`) как blocking на PR.
5. Trivy fs/image scans (уже есть) — оставить blocking на HIGH/CRITICAL.

## Оптимум (production-grade)

1. Включить coverage gate (JaCoCo/Kover) с порогами по модулю (line/branch).
2. Включить SCA для JVM dependencies (OWASP Dependency-Check или аналог) с policy exceptions.
3. Убрать `|| true` из `lint.yml`, оставить отдельный «advisory» workflow только для не-blocking экспериментов.
4. Добавить architecture guard tests (module boundary rules) как часть `check`.
5. Добавить pre-commit bootstrap script (`scripts/setup-hooks.sh`) и validation в CI, что hook-файлы актуальны.

---

## 5) Golden CI template (рекомендация, без внедрения)

```yaml
PR pipeline (blocking):
  - guardrails (pinned actions, no dynamic deps/repos/plugins)
  - ./gradlew clean test
  - ./gradlew ktlintCheck detekt dependencyGuard
  - coverage gate (jacoco/kover verify)
  - secret scan (gitleaks)
  - trivy fs scan

Main/release pipeline:
  - all PR gates
  - integration tests (Testcontainers / Postgres)
  - image build + sbom + cosign sign/verify + provenance
  - trivy image scan
  - db-migrate (manual or release-gated)
```

---

## 6) Команды “одной кнопкой” для разработчика

- Базовая локальная проверка перед коммитом:
  - `./gradlew formatAll staticCheck test`
- Быстрая проверка (без тестов):
  - `./gradlew formatAll staticCheck`
- Полный прогон как в CI build:
  - `./gradlew ktlintFormat ktlintCheck detekt dependencyGuard clean test build`
- Интеграционные тесты:
  - `./gradlew test -PrunIT=true`

Существующие агрегаторы уже поддерживаются проектом: `formatAll`, `staticCheck`.  
Ссылки: `build.gradle.kts:246-266`, `README.md:995`.

---

## 7) Краткий итог

- CI/CD и tooling в репозитории зрелые и насыщенные (multi-workflow, Trivy/SARIF, dependency guards, static checks, supply-chain шаги).
- Основной блокер качества сейчас — **текущее несоответствие кода жёстким detekt/ktlint правилам** (локально и в CI).
- Для выхода на стабильный quality gate baseline нужны: выравнивание lint/static debt, coverage/SCA/secret-scan blocking gates и жёсткая pre-commit/on-PR дисциплина.
