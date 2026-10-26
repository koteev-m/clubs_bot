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

## Ordinary release SSH pin — CLB-91 local preparation, 2026-10-26

The two privileged workflows (`deploy-ssh.yml`, `db-migrate.yml`) require
`SSH_KNOWN_HOSTS` alongside `SSH_USER`, `SSH_HOST`, `SSH_PORT` and `COMPOSE_PATH`
from the selected protected GitHub Environment. Before loading the private key,
`release_ssh_pin.py --validate` admits the pin; the ordinary runner revalidates it
and uses an anonymous read-only descriptor for every session SSH, read-only status
SSH and SCP call. There is no key discovery or user/global SSH config fallback.
The unchanged shared host flock and release FSM remain mandatory.

**Stage pin provisioning is NOT performed or verified by this local task.** A
separately authorized operator must establish the current protection/main-only
policy, compare complete repository/Environment secret names without disclosing
values, eliminate repository-level overlap, and independently verify the exact
stage secret payload before a separately authorized deployment. A correctly named
secret is not evidence that it contains the post-reinstall key.

For `koteev-m/clubs_bot` / `stage` only, provision/verify:

- `SSH_HOST`: exactly `178.20.209.5`; effective `SSH_PORT`: exactly `22`.
- `SSH_KNOWN_HOSTS`: exactly one unquoted record
  `178.20.209.5 ssh-ed25519 <trusted-base64-wire-key>`, with at most one final LF.
  The equivalent `[178.20.209.5]:22` record is admitted and normalized. The full
  decoded 51-byte Ed25519 wire key must have SHA-256 fingerprint
  `SHA256:zpgtCw+yuSj+kjxAzuBb6TYMuEarfKtsLTg0g6htgTk`, accepted by the owner after
  authenticated AEZA VNC verification. Compare retained approved public key material
  offline; never obtain a new trust anchor through keyscan or a live connection.
- No other records, comments, wildcards, aliases, CA/revocation/hashed entries,
  algorithms, malformed encodings or old fingerprint are admitted. Missing/old/
  damaged pin fails before the SSH agent and before any SSH/SCP/deploy.
- Preserve the approved deployment `SSH_USER` and corresponding existing private
  key; root observer access does not establish the protected deployment principal.
  No user, key, permissions, root path or server configuration is provisioned here.

Prod uses its own protected Environment's single exact endpoint/Ed25519 record;
it must not use the stage endpoint or key. No stage fingerprint is a prod default,
no prod endpoint/key is inferred, and this task grants no prod execution authority.
Both profiles enforce strict checking, disable password/keyboard fallback,
user/global configuration, host-key DNS/commands/updates, proxy/local commands,
multiplexed connections and default identity files; the approved action's agent
remains the public-key authentication source. The runner needs Linux procfs and
canonical runner-owned `RUNNER_TEMP`, with no `/tmp` or user-directory fallback.
Do not assume old diagnostic/status channels with historical pins have rotated:
this change covers only the two ordinary-release workflows and their runner.

The owner-approved trust replacement is documented in **unmerged**
[Draft PR #548](https://github.com/koteev-m/clubs_bot/pull/548). This implementation
uses the direct owner decision supplied for CLB-91; it does not claim that the
historical DEC-038 bytes on main or any protected secret were already updated.

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
