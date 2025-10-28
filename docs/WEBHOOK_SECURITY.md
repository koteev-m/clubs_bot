# Webhook Security

This document describes the production-ready webhook hardening implemented in the bot
service. The goal is to ensure that `/webhook` is resilient against spoofing, resource
exhaustion and replay attacks while providing actionable observability.

## Request validation pipeline

1. **Secret token** – each webhook request must carry
   `X-Telegram-Bot-Api-Secret-Token` that matches the configured secret. Missing or
   incorrect secrets result in `401 Unauthorized` and the source is logged to the
   suspicious IP journal.
2. **HTTP method** – only `POST /webhook` is accepted. Any other method returns
   `405 Method Not Allowed`.
3. **Content-Type** – requests must be `application/json`; other types return
   `415 Unsupported Media Type`.
4. **Body size** – the Netty max-content-length limit is enforced together with a
   manual check of `call.receiveChannel().availableForRead`. Payloads above
   `BotLimits.Webhook.maxPayloadBytes` (1 MiB) return `413 Payload Too Large`.
5. **Body parsing** – empty bodies or JSON documents without `update_id` return
   `400 Bad Request`.
6. **Idempotency / deduplication** – update identifiers are stored in the
   `webhook_update_dedup` table for 24 hours. Duplicates receive `409 Duplicate update`
   and processing stops.
7. **MDC enrichment** – `Idempotency-Key` headers and `update_id` are placed in the MDC
   for downstream logging.

Each anomaly writes a record to the `suspicious_ips` table capturing the remote address,
user agent (when present) and a reason code.

## Database tables

```
+---------------------+
| suspicious_ips      |
|---------------------|
| id BIGINT PK        |
| ip TEXT NOT NULL    |
| user_agent TEXT     |
| reason TEXT NOT NULL|
| details TEXT        |
| created_at TIMESTAMPTZ|
+---------------------+

+-------------------------------+
| webhook_update_dedup          |
|-------------------------------|
| update_id BIGINT PK           |
| first_seen_at TIMESTAMPTZ     |
| last_seen_at TIMESTAMPTZ      |
| duplicate_count INTEGER NOT NULL|
+-------------------------------+
```

`webhook_update_dedup` entries are kept for 24 hours which matches Telegram’s update
retention window. When `duplicate_count` reaches the configured threshold (default: 3)
we also log the source IP as suspicious.

## Network topologies

### A. Public ingress + backend

```
┌────────────┐        ┌─────────────┐        ┌────────────────────┐
│ Telegram   │ 443/TLS│ External    │ 8080   │ Ktor Bot Service    │
│ ingress IP │ ─────▶ │ Ingress/NLB │ ─────▶ │ /webhook + security │
└────────────┘        └─────────────┘        └────────────────────┘
```

The ingress terminates TLS and forwards requests to the bot service. Rate-limiting and
allow-lists can be enforced either at the ingress or at the service level.

### B. Private Bot API server

```
┌────────────┐        ┌────────────────────┐        ┌────────────────────┐
│ Telegram   │ 443/TLS│ Reverse proxy      │ 8443   │ Private Bot API     │
│ ingress IP │ ─────▶ │ inside DMZ         │ ─────▶ │ server (Telegram)   │
└────────────┘        └────────────────────┘        └────────────────────┘
                                            │
                                            │ HTTP(S)
                                            ▼
                                    ┌────────────────────┐
                                    │ Ktor Bot Service    │
                                    │ /webhook + security │
                                    └────────────────────┘
```

This layout keeps the Ktor service inside a private network and only exposes a hardened
reverse proxy.

## TLS and ingress requirements

* Use TLS 1.2+ with modern ciphers; certificates must be trusted by Telegram (either
  public CA or Telegram’s allow-listed internal CA for private deployments).
* `max_webhook_connections` – Telegram recommends at most 40; our default stays at 20 to
  keep concurrency bounded.
* Apply ingress rate limits to mitigate floods (e.g. 5 rps/IP with bursts of 10).
* Optionally configure an allow-list with Telegram’s published IP ranges:
  https://core.telegram.org/bots/webhooks#the-short-version
* Terminate HTTP keep-alive idle connections around 45 seconds to match Netty defaults.

## Environment variables

| Name                   | Description                                             |
|------------------------|---------------------------------------------------------|
| `TELEGRAM_BOT_TOKEN`   | Bot token used for both webhook and polling modes.      |
| `TELEGRAM_USE_POLLING` | `true` enables long polling; otherwise webhook mode.    |
| `WEBHOOK_SECRET_TOKEN` | Secret shared with Telegram’s `setWebhook`. Required when webhook mode is active. |

## Observability

* Suspicious IPs are stored in PostgreSQL/H2 (`suspicious_ips`) and can be exported to
  dashboards or alerting.
* `update_id` and `Idempotency-Key` are attached to MDC, making logs and metrics
  traceable.
* Duplicate updates are short-circuited before reaching domain handlers.

## Failure semantics

| Condition                          | HTTP status | Logged in suspicious IPs |
|-----------------------------------|-------------|----------------------------|
| Missing or invalid secret token   | 401         | `SECRET_MISMATCH`          |
| Unsupported HTTP method           | 405         | `INVALID_METHOD`           |
| Incorrect `Content-Type`          | 415         | `INVALID_CONTENT_TYPE`     |
| Payload exceeds size limit        | 413         | `PAYLOAD_TOO_LARGE`        |
| Empty body / invalid JSON         | 400         | `EMPTY_BODY` / `MALFORMED_JSON` |
| Duplicate `update_id` within 24 h | 409         | `DUPLICATE_UPDATE` (on threshold) |

