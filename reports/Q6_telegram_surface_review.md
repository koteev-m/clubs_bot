# Q6 — Telegram Surface & Bot API Compliance Review

## Executive summary

- В рантайме подключён webhook-роут `/telegram/webhook` с проверкой `X-Telegram-Bot-Api-Secret-Token`, но без встроенного dedup/replay-контроля update_id; при этом более жёсткий `/webhook` + `WebhookSecurity` существует в коде, но не подтверждён как активный путь в `Application.module()`. (`app-bot/src/main/kotlin/com/example/bot/Application.kt:473-476`, `app-bot/src/main/kotlin/com/example/bot/routes/TelegramWebhookRoutes.kt:17-50`, `app-bot/src/main/kotlin/com/example/bot/webhook/WebhookRoutes.kt:28-63`)
- Текущий `/telegram/webhook` выполняет `onUpdate(update)` синхронно до ответа `200 OK`, что повышает риск таймаутов/ретраев Telegram при тяжёлой бизнес-логике или деградации БД/внешних API. (`app-bot/src/main/kotlin/com/example/bot/routes/TelegramWebhookRoutes.kt:42-49`)
- Лимит `callback_data` (1..64 bytes) в критичных местах реализован: `SupportCallbacks`, invitation callbacks, OTT-токены и короткие кодеки (`base36/base64url`) поддерживают компактный payload. (`app-bot/src/main/kotlin/com/example/bot/telegram/SupportCallbacks.kt:3-19`, `app-bot/src/main/kotlin/com/example/bot/telegram/InvitationTelegramHandler.kt:23-24`, `app-bot/src/main/kotlin/com/example/bot/telegram/ott/OneTimeTokenStore.kt:104-117`, `app-bot/src/main/kotlin/com/example/bot/guestlists/StartParamGuestListCodec.kt:49-58`, `app-bot/src/main/kotlin/com/example/bot/promo/PromoAttributionService.kt:15-71`)
- Темы supergroup (topics) поддерживаются через `message_thread_id` как при чтении callback-контекста, так и при отправке ops-уведомлений по категориям; покрытие выглядит частичным (нет гарантии, что все send-paths Telegram используют thread-aware API). (`app-bot/src/main/kotlin/com/example/bot/telegram/CallbackQueryHelpers.kt:11-16`, `app-bot/src/main/kotlin/com/example/bot/telegram/TelegramClient.kt:37-46`, `app-bot/src/main/kotlin/com/example/bot/notifications/TelegramOperationalNotificationService.kt:266-276`)
- Mini App initData HMAC-валидация реализована корректно (Telegram algorithm), но плагин допускает fallback initData из query/body на части маршрутов, что расширяет attack surface относительно «header-only» модели для чувствительных операций. (`core-security/src/main/kotlin/com/example/bot/security/auth/InitDataValidator.kt:27-94`, `app-bot/src/main/kotlin/com/example/bot/plugins/InitDataAuth.kt:146-199`, `app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt:112`)
- В Bot Payments отсутствует pre-checkout re-validation: `handlePreCheckout` всегда отвечает OK; обработка successful payment зависит от репозитория и не демонстрирует явный идемпотентный guard на adapter-уровне. (`app-bot/src/main/kotlin/com/example/bot/telegram/PaymentsHandlers.kt:49-61`)

## Что реализовано и где

### 1) Webhook (secret / обработка / dedup)

- Проверка секрета заголовка включена в `/telegram/webhook` при заданном `expectedSecret`. (`app-bot/src/main/kotlin/com/example/bot/routes/TelegramWebhookRoutes.kt:25-31`)
- Парсинг update и обработка ошибок handler присутствуют; endpoint всегда старается вернуть `200 OK` после обработки. (`app-bot/src/main/kotlin/com/example/bot/routes/TelegramWebhookRoutes.kt:34-49`)
- В системе есть отдельный hardened plugin `WebhookSecurity`:
  - secret/content-type/max body checks;
  - извлечение `update_id`;
  - dedup через `WebhookUpdateDedupRepository.mark(updateId)`;
  - suspicious IP tracking.
  (`core-security/src/main/kotlin/com/example/bot/security/webhook/WebhookSecurityPlugin.kt:55-169`, `core-security/src/main/kotlin/com/example/bot/security/webhook/WebhookSecurityPlugin.kt:181-198`)
- Но активная wiring в `Application.module()` указывает на `telegramWebhookRoutes`, а не на `webhookRoute(...)` с `WebhookSecurity`. (`app-bot/src/main/kotlin/com/example/bot/Application.kt:473-476`, `app-bot/src/main/kotlin/com/example/bot/webhook/WebhookRoutes.kt:28-63`)

### 2) Callback data (1..64 bytes)

- Явный лимит 64 bytes и helper `fits(...)` в `SupportCallbacks`. (`app-bot/src/main/kotlin/com/example/bot/telegram/SupportCallbacks.kt:5-19`)
- Invitation callbacks имеют константу `CALLBACK_MAX_BYTES = 64`. (`app-bot/src/main/kotlin/com/example/bot/telegram/InvitationTelegramHandler.kt:23-24`)
- Для callback/deep-link широко применяются короткие токены:
  - OTT-токены (`<=64`);
  - start param guest list (`token.length > 64 => reject`);
  - promo token codec (`MAX_TOKEN_LENGTH = 64`).
  (`app-bot/src/main/kotlin/com/example/bot/telegram/ott/OneTimeTokenStore.kt:104-117`, `app-bot/src/main/kotlin/com/example/bot/guestlists/StartParamGuestListCodec.kt:49-58`, `app-bot/src/main/kotlin/com/example/bot/promo/PromoAttributionService.kt:15-17`, `app-bot/src/main/kotlin/com/example/bot/promo/PromoAttributionService.kt:46-52`)

### 3) Topics (message_thread_id)

- При callback извлекается thread context (`messageThreadId`). (`app-bot/src/main/kotlin/com/example/bot/telegram/CallbackQueryHelpers.kt:11-16`)
- В `TelegramClient.sendMessage` поддерживается `threadId`. (`app-bot/src/main/kotlin/com/example/bot/telegram/TelegramClient.kt:37-46`)
- Ops routing маппит доменные категории в конкретные thread id клуба. (`app-bot/src/main/kotlin/com/example/bot/notifications/TelegramOperationalNotificationService.kt:266-276`)

### 4) Deep links / attribution

- Start payload для guest list подписан HMAC и ограничен 64 символами. (`app-bot/src/main/kotlin/com/example/bot/guestlists/StartParamGuestListCodec.kt:10-16`, `app-bot/src/main/kotlin/com/example/bot/guestlists/StartParamGuestListCodec.kt:57-75`)
- Promo attribution использует короткий токен + UTM поля (`utm_source/medium/campaign/content`) и устойчивое извлечение токена из query/fragment/path. (`app-bot/src/main/kotlin/com/example/bot/promo/PromoAttributionService.kt:17-20`, `app-bot/src/main/kotlin/com/example/bot/promo/PromoAttributionService.kt:219-235`, `app-bot/src/main/kotlin/com/example/bot/promo/PromoAttributionService.kt:259-294`)

### 5) Mini App permissions/initData

- HMAC-валидация initData реализована корректно и учитывает auth_date окна. (`core-security/src/main/kotlin/com/example/bot/security/auth/InitDataValidator.kt:27-94`)
- Плагин auth поддерживает header/query/body extraction (body опционально). (`app-bot/src/main/kotlin/com/example/bot/plugins/InitDataAuth.kt:146-199`)
- Для host check-in body fallback отключён (`allowInitDataFromBody = false`), что соответствует stricter mode. (`app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt:112`)
- Явных Bot API сценариев `request_contact` / `request_write_access` в серверной части не обнаружено (вероятно отсутствуют либо реализованы в клиентском слое Mini App). (`rg -n "request_contact|request_write_access|write_access" app-bot/src/main/kotlin core-security/src/main/kotlin`)

### 6) Rate limiting / retries / backpressure

- `NotifySender` реализует dedup-key, retries, backoff, учёт `429 Retry-After`, глобальные и per-chat permit checks. (`app-bot/src/main/kotlin/com/example/bot/telegram/NotifySender.kt:160-267`)
- `DefaultRatePolicy` использует token buckets с глобальным и per-chat лимитом + TTL cleanup buckets. (`core-domain/src/main/kotlin/com/example/bot/notifications/RatePolicy.kt:107-174`)
- `OutboxWorker` имеет retry/backoff/jitter, но обрабатывает batch последовательно (forEach), что ограничивает throughput при burst-рассылках. (`app-bot/src/main/kotlin/com/example/bot/workers/OutboxWorker.kt:50-67`, `app-bot/src/main/kotlin/com/example/bot/workers/OutboxWorker.kt:111-138`)
- Кампании в `NotifyRoutes` держатся в in-memory `ConcurrentHashMap` (`CampaignService`), без persistence для multi-instance/failover. (`app-bot/src/main/kotlin/com/example/bot/routes/NotifyRoutes.kt:67-113`)

### 7) Payments

- pre_checkout updates учитываются в polling allowed updates. (`app-bot/src/main/kotlin/com/example/bot/polling/PollingMain.kt:12-20`)
- `handlePreCheckout` подтверждает запрос без повторной проверки доступности/цены/статуса заказа. (`app-bot/src/main/kotlin/com/example/bot/telegram/PaymentsHandlers.kt:49-53`)
- `handleSuccessfulPayment` маппит payload в запись и маркирует CAPTURED; явный idempotency guard не показан на уровне handler. (`app-bot/src/main/kotlin/com/example/bot/telegram/PaymentsHandlers.kt:56-61`)

## Несоответствия и риски (P0/P1/P2)

| Severity | Несоответствие | Impact | Где | Как воспроизвести (минимум) | Рекомендация |
|---|---|---|---|---|---|
| P0 | Активный webhook path не использует hardened dedup/security plugin | replay/duplicate update может повторно триггерить state transitions (booking/checkin/payments) | `Application.module()` + `TelegramWebhookRoutes` vs `WebhookRoutes/WebhookSecurity` | отправить один и тот же update payload несколько раз на `/telegram/webhook` | унифицировать на один production webhook endpoint с `WebhookSecurity` (secret+update_id dedup+payload guards) |
| P0 | `pre_checkout_query` всегда OK без re-validation | риск списания/подтверждения платежа для невалидного/устаревшего заказа | `PaymentsHandlers.handlePreCheckout` | инициировать оплату, параллельно сделать booking invalid/cancelled, затем pre-checkout всё равно ok | перед `AnswerPreCheckoutQuery` повторно валидировать доступность/сумму/статус и возвращать errorMessage |
| P1 | Webhook handler синхронно обрабатывает update до ACK | рост latency webhook, Telegram retries, шторм дублей в пике | `TelegramWebhookRoutes` | добавить искусственную задержку в доменной обработке; наблюдать рост retry | early-ack + async dispatch (очередь/outbox/worker), SLA response time < 1s |
| P1 | initData extraction допускает query/body fallback | расширение surface для leak/replay при ошибочной конфигурации маршрутов | `InitDataAuth.extractInitData*` | отправить initData в query/body на маршруты с `allowInitDataFromBody=true` | для прод-операций enforce header-only, query/body только для dev/test |
| P1 | CampaignService in-memory | потеря состояния кампаний после restart, inconsistency в multi-instance | `NotifyRoutes.CampaignService` | создать кампанию, перезапустить инстанс | вынести кампании в БД + optimistic locking + audit trail |
| P2 | Thread routing централизован не для всех send-paths | сообщения могут идти «в чат без темы», ухудшая операционку | `TelegramClient.sendMessage`, `TelegramOperationalNotificationService` | проверить разные handlers: часть может вызывать `send(request)` без threadId | ввести единый message dispatch facade с обязательным thread policy |
| P2 | Нет явных server-side flows для `request_contact` / `request_write_access` | неполное соответствие требованиям продуктового UX/permissions | поиск по серверному коду | проверить user onboarding сценарии в проде | формализовать contract: где и как запрашиваются контакт/право write access, добавить в checklist и тесты |

## Риски в пике (очереди, блокировки, пятничный burst)

1. **Webhook backlog и ретраи Telegram**: синхронная обработка в `/telegram/webhook` может блокировать быстрый ACK при spikes callback/checkin/payment updates. (`app-bot/src/main/kotlin/com/example/bot/routes/TelegramWebhookRoutes.kt:42-49`)
2. **Burst уведомлений**: есть rate-limit и retry механика, но последовательная обработка outbox batch может накапливать lag при больших рассылках. (`app-bot/src/main/kotlin/com/example/bot/workers/OutboxWorker.kt:50-67`, `app-bot/src/main/kotlin/com/example/bot/telegram/NotifySender.kt:221-230`)
3. **Состояние кампаний и pause/resume**: in-memory кампании не дают устойчивой к failover «паузы маркетинга»; после рестартов/масштабирования состояние расходится. (`app-bot/src/main/kotlin/com/example/bot/routes/NotifyRoutes.kt:67-113`, `app-bot/src/main/kotlin/com/example/bot/routes/NotifyRoutes.kt:233-243`)
4. **Payments consistency under retry**: без pre-checkout re-check и без явной идемпотентности на adapter-слое возможно повторное/неконсистентное подтверждение при дубликатах updates. (`app-bot/src/main/kotlin/com/example/bot/telegram/PaymentsHandlers.kt:49-61`)

## Рекомендации по улучшению (без внедрения)

### Приоритет P0 (сразу)

1. **Свести production webhook к единому hardened entrypoint**:
   - обязательный secret header;
   - max body/content-type checks;
   - update_id dedup (DB-backed);
   - быстрый ACK + async processing.
2. **Pre-checkout validation gate**:
   - проверка статуса брони/HOLD/итоговой суммы/валюты/TTL перед OK;
   - отказ pre_checkout с errorMessage при расхождении.
3. **Идемпотентность successful_payment**:
   - unique constraint/идемпотентный ключ на внешнем payment id/payload;
   - повторный update должен быть no-op.

### Приоритет P1

4. **Ужесточить initData policy**: header-only для privileged маршрутов, query/body fallback выключить в prod по умолчанию.
5. **Перевести CampaignService в persistent storage** (state machine + audit + incident pause flag).
6. **SLO для webhook**: метрики `webhook_ack_latency`, `dedup_conflicts`, `update_process_errors`, алерты при росте retries.

### Приоритет P2

7. **Единый Telegram send facade** с обязательной thread-policy (topic-aware routing где это требуется).
8. **Явный контракт permissions** (`request_contact`, `request_write_access`) и e2e-тесты на сценарии onboarding.
9. **Runbook для incident mode**: «pause marketing», приоритет входа/check-in, ограничение тяжёлых broadcast задач в пике.

## Итоговая оценка соответствия Q6

- **Webhook security**: PARTIAL (secret есть, dedup/replay в активном пути не подтверждён).
- **Callback_data 1..64**: MOSTLY OK (в ключевых местах есть guards и короткие токены).
- **Topics/thread routing**: PARTIAL (есть поддержка и config-map, но не на всех send-путях централизованно).
- **Deep links + attribution**: OK/PARTIAL (короткие токены + UTM; нужно e2e-подтверждение всех start/startapp веток).
- **Mini App initData validation**: OK (HMAC), но policy extraction требует ужесточения.
- **Rate limiting/backpressure**: PARTIAL (механизмы есть, но outbox throughput и campaign durability ограничены).
- **Payments compliance**: PARTIAL→P0 gap (нет pre-checkout revalidation и явной handler-idempotency).
