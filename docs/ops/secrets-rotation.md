# Ротация секретов (prod/stage)

## Что ротируем

- `TELEGRAM_BOT_TOKEN`
- `WEBHOOK_SECRET` / секреты webhook-подписи
- `DATABASE_PASSWORD`
- любые `*_SECRET`, `*_KEY`, `qrSecret`

GHCR deploy не использует долгоживущий environment secret: workflow передаёт
job-scoped `GITHUB_TOKEN` с `packages: read`, который истекает автоматически.

## GHCR rollout gate

Переход на встроенный `github.token` выполняется только в таком порядке:

<!-- capability-rollout-order:start -->
1. [CAPABILITY_POLICY_COMMIT] Capability policy reviewed and committed.
2. [DEPENDENCY_REMEDIATION_COMMIT] Fix all 22 HIGH findings in a separate commit; they remain blocking until then.
3. [PUSH_BOTH_COMMITS] Push both commits without rewriting history.
4. [HOSTED_PR_CI_GREEN] Hosted PR CI completed successfully.
5. [MERGE_PR] Merge the PR only after hosted PR CI is green.
6. [GHCR_ACTIONS_READ_CONFIRMED] Confirm GHCR Manage Actions Read access.
7. [STAGE_DEPLOY_GITHUB_TOKEN_GREEN] Complete a successful stage deployment through `github.token`.
8. [RETIRE_LEGACY_GHCR_CREDENTIALS] Only after successful step 7: [DELETE_GHCR_TOKEN] delete `GHCR_TOKEN`; [DELETE_UNUSED_GHCR_USERNAME] delete `GHCR_USERNAME` if unused; [REVOKE_LEGACY_PAT] revoke legacy `PAT`; [CLEAN_REMOTE_DOCKER_CREDENTIALS] complete `REMOTE_DOCKER_CREDENTIAL_CLEANUP` by inspecting and cleaning the remote Docker config and temporary directories.
<!-- capability-rollout-order:end -->

GitHub settings меняются только вручную после подтверждения rollout gate.

## Базовый порядок ротации

1. **Подготовка**
   - Создать новый секрет в источнике истины (GitHub Environments/Vault).
   - Проверить, что новый секрет не попадает в логи и не используется в query-string.
2. **Двойное окно (если возможно)**
   - Для webhook/токенов включить период, когда валидны старый и новый секрет.
3. **Переключение**
   - Обновить секреты в `stage`.
   - Выполнить smoke: `/ready`, webhook, критичный пользовательский поток.
   - Повторить для `prod`.
4. **Отзыв старого секрета**
   - Явно деактивировать старый секрет у провайдера.
5. **Проверка**
   - Убедиться, что нет ошибок аутентификации/подписи.
   - Проверить алерты и audit-log.

## Чек-лист безопасности

- Не логировать `initData`, `Idempotency-Key`, токены и секреты.
- Не передавать секреты через URL/query.
- Любые исключения фиксировать в документации и тестах.
