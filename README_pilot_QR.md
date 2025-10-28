# Pilot QR & Mini App integration

## Environment variables
- `TELEGRAM_BOT_TOKEN` — bot token used to validate Telegram init data (never log or expose it).
- `QR_SECRET` — HMAC secret for QR signatures generated for guest list entries.

## Mini App entry point
- Static bundle is served from `GET /webapp/entry/*`.
- Open `https://<host>/webapp/entry?clubId=<id>` inside Telegram via a WebApp button to launch the mini application.

## Check-in API
- `POST /api/clubs/{clubId}/checkin/scan` accepts a QR payload and marks the guest as arrived.
- The request **must** include an `X-Telegram-Init-Data` header; it is verified by `InitDataAuthPlugin` before RBAC is applied.

## RBAC & scoping
- Allowed roles: `ENTRY_MANAGER`, `CLUB_ADMIN`, `MANAGER`.
- The handler is wrapped with `authorize(...)` and `clubScoped(Own)` to ensure operators work only within their club.

## Rate limiting
- Check-in requests are covered by the subject rate limiter (see `RateLimitPlugin` configuration) to protect against repeated scans.
