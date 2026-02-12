# 00 — Risks First Look

## 1) CI governance gaps

1. **Часть workflow без `permissions`** (риск избыточных default permissions в GHA).
   - Примеры: `build.yml`, `lint.yml`, `static-check.yml` (build.yml:1-66; lint.yml:1-24; static-check.yml:1-34).

2. **Часть workflow без `timeout-minutes`** (риск «зависших» раннеров/затрат).
   - Примеры: `lint.yml`, `static-check.yml` (lint.yml:1-24; static-check.yml:1-34).

3. **Lint в CI non-blocking (`|| true`)** — дефекты стиля/детекта не блокируют PR в этом workflow.
   - `./gradlew ktlintCheck detekt -x test || true` (lint.yml:23).

## 2) Supply-chain / artifact hygiene

4. **В репозитории отслеживается `miniapp/dist`** (build artifacts в VCS могут устаревать и конфликтовать с reproducible builds).
   - Dist файлы явно копируются в backend ресурсы при сборке (app-bot/build.gradle.kts:134-153).
   - `.gitignore` не игнорирует `miniapp/dist` (игнорируется только `miniapp/node_modules/`) (.gitignore:19-20).

## 3) Secrets / sensitive data quick scan

5. **Явных hardcoded production secrets не обнаружено** в просмотренных конфигурациях; секреты представлены как пустые ENV placeholders.
   - `.env.example` содержит переменные `DATABASE_PASSWORD`, `TELEGRAM_BOT_TOKEN`, `BOT_TOKEN`, `TELEGRAM_WEBHOOK_SECRET`, `QR_SECRET` без значений (.env.example:44,63,65,80,91).

6. **Compose использует env interpolation для секретов**, но дефолты для DB (`botuser/botpass`) остаются в локальном профиле.
   - `POSTGRES_PASSWORD: ${DB_PASS:-botpass}` (docker-compose.yml:8-10).

## 4) Test/quality coverage first look

7. **Покрытие quality-инструментов хорошее**, но фрагментировано по нескольким workflow и есть дубли (build + tests + lint + static-check).
   - Отдельные workflow: build/tests/lint/static-check (build.yml:1-66; tests.yml:1-341; lint.yml:1-24; static-check.yml:1-34).

---

## Быстрые рекомендации (без правок кода в этом проходе)
- Привести все workflows к единому baseline: `permissions`, `timeout-minutes`, единые policy checks.
- Решить policy для `miniapp/dist` (source-of-truth: хранить в VCS vs собирать строго в CI).
- Оставить `lint.yml` информативным, но добавить blocking-проверку в обязательный status check (если ещё не обязателен `static-check`/`build`).
