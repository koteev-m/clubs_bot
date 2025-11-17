#!/usr/bin/env bash
set -euo pipefail

: "${TELEGRAM_BOT_TOKEN:?set TELEGRAM_BOT_TOKEN}"
: "${OWNER_TELEGRAM_ID:?set OWNER_TELEGRAM_ID}"
: "${PUBLIC_URL:?set PUBLIC_URL}"

TG_API="https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}"

echo "[update-ui] PUBLIC_URL=${PUBLIC_URL}"

curl -sS -X POST "${TG_API}/setChatMenuButton" \
  -H "Content-Type: application/json" \
  -d @- <<JSON | jq .
{"menu_button":{"type":"web_app","text":"Открыть меню","web_app":{"url":"${PUBLIC_URL}/ui/checkin"}}}
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
        {"text":"Забронировать стол (команда)","callback_data":"book_cmd"},
        {"text":"Мои брони (команда)","callback_data":"mybookings_cmd"}
      ],
      [
        {"text":"Mini App: Check-in","web_app":{"url":"${PUBLIC_URL}/ui/checkin"}}
      ],
      [
        {"text":"Mini App: Waitlist","web_app":{"url":"${PUBLIC_URL}/ui/waitlist"}}
      ],
      [
        {"text":"Mini App: Guest List","web_app":{"url":"${PUBLIC_URL}/ui/guest-list"}}
      ]
    ]
  }
}
JSON

echo "[update-ui] done"