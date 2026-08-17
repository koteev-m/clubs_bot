#!/usr/bin/env bash
set -euo pipefail

: "${TELEGRAM_BOT_TOKEN:?set TELEGRAM_BOT_TOKEN}"
: "${OWNER_TELEGRAM_ID:?set OWNER_TELEGRAM_ID}"
: "${MINI_APP_URL:?set MINI_APP_URL}"

TG_API="https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}"

echo "[update-ui] MINI_APP_URL configured"

curl -sS -X POST "${TG_API}/setChatMenuButton" \
  -H "Content-Type: application/json" \
  -d @- <<JSON | jq .
{"menu_button":{"type":"web_app","text":"Открыть Night Concierge","web_app":{"url":"${MINI_APP_URL}"}}}
JSON

curl -sS -X POST "${TG_API}/sendMessage" \
  -H "Content-Type: application/json" \
  -d @- <<JSON | jq .
{
  "chat_id": "${OWNER_TELEGRAM_ID}",
  "text": "Добро пожаловать! Выберите действие:",
  "reply_markup": {
    "inline_keyboard": [
      [
        {"text":"Открыть Night Concierge","web_app":{"url":"${MINI_APP_URL}"}}
      ]
    ]
  }
}
JSON

echo "[update-ui] done"