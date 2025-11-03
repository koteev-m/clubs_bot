# SEC-02 Log Audit

## Risk Map

| Location | Log context | Sensitive handling | Status |
| --- | --- | --- | --- |
| `app-bot/src/main/kotlin/com/example/bot/routes/CheckinRoutes.kt` | `checkin.scan` / `checkin.by_name` warnings and infos use only `clubId`, `listId`, `entryId` | No QR payload, invite token, phone or name data is emitted | keep (safe) |
| `app-bot/src/main/kotlin/com/example/bot/telegram/MenuCallbacksHandler.kt` | `ui.mybookings.*` warnings during list/show/cancel flows | Logs include booking IDs and page numbers only; Telegram user identifiers removed | keep (safe) |
| `app-bot/src/main/kotlin/com/example/bot/telegram/bookings/MyBookingsService.kt` | `mybookings.cancel` info logs | Use internal `userId` instead of external Telegram handles; only booking/user IDs remain | keep (safe) |
| `core-security/src/main/kotlin/com/example/bot/security/webhook/WebhookSecurityPlugin.kt` | Suspicious webhook diagnostics | IP addresses excluded from log message; only reason/details retained with MDC context | keep (safe) |
| `app-bot/src/main/kotlin/com/example/bot/plugins/RateLimitPlugin.kt` | Rate-limit block warnings | IP and subject keys removed; retains path, request and host metadata only | keep (safe) |

> All retained identifiers (clubId, listId, entryId, bookingId, userId, status) rely on MDC-provided `request_id` / `actor_id` for full traceability without exposing PII.
