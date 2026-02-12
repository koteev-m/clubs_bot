# 00 — Quality Gates

## 1) Локальные проверки (backend / Gradle)

### Базовые агрегаты
- `./gradlew staticCheck` — detekt + detektTest + ktlintCheck по всем Kotlin-модулям (build.gradle.kts:235-250).
- `./gradlew formatAll` — ktlintFormat по всем Kotlin-модулям (build.gradle.kts:252-259).
- `./gradlew dependencyGuard` — защита от legacy stdlib / version drift Ktor / dynamic deps (build.gradle.kts:70-159).
- `./gradlew flywayMigrate` — прокси на `:core-data:flywayMigrate` (build.gradle.kts:261-265).

### Тесты
- `./gradlew test` — JUnit5/Kotest/MockK (app-bot/build.gradle.kts:29-51,111-128).
- Интеграционные тесты: `./gradlew test -PrunIT=true` (build.gradle.kts:229-233; tests.yml:299-307).

### Pre-commit hook
- Локальный hook запускает `formatAll` и `staticCheck` (.githooks/pre-commit:1-4).

## 2) Локальные проверки (Mini App)
- `pnpm lint`, `pnpm lint:ci`, `pnpm typecheck`, `pnpm test`, `pnpm e2e`, `pnpm check` (miniapp/package.json:9-19).

## 3) Что найдено в CI

### Build pipeline
- `build.yml`: `ktlintFormat ktlintCheck detekt`, `dependencyGuard`, `test`, `clean build` (build.yml:52-66).
- Используется pinned SHA для actions + setup-gradle cache-key-prefix с hashFiles (build.yml:25-42).

### Tests pipeline
- `tests.yml`: guard-скрипты (запрет project repositories/dynamic versions), wrapper validation, unit/integration tests с retry и артефактами (tests.yml:30-199,200-341).
- Integration job использует postgres service (tests.yml:261-277).

### Lint / Static
- `lint.yml`: non-blocking lint (`./gradlew ktlintCheck detekt -x test || true`) (lint.yml:1-24).
- `static-check.yml`: отдельный `./gradlew detekt` + SARIF upload (static-check.yml:1-34).

## 4) Вывод по quality gates
- Quality gates присутствуют на трёх уровнях: local pre-commit, Gradle aggregate tasks, GitHub Actions workflows (build.gradle.kts:235-265; .githooks/pre-commit:1-4; .github/workflows/build.yml:1-66).
- Для backend стека доступны lint/format/static analysis/tests/dependency policy/migrations.
