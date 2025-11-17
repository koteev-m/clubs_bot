#!/usr/bin/env bash
set -euo pipefail

# --- Обязательные секреты (заполните своими значениями) ---
export TELEGRAM_BOT_TOKEN='8295829972:AAG7VtgI8k5rWEcAA4K503iMOK47ghHpazs'
export OWNER_TELEGRAM_ID='7446417641'

# --- Чаты HQ/Клубы ---
export HQ_CHAT_ID='-1002693051031'

export CLUB1_NAME='Mix'
export CLUB1_CHAT_ID='-1003032666045'
export CLUB1_GENERAL_TOPIC_ID='1'
export CLUB1_BOOKINGS_TOPIC_ID='2'
export CLUB1_LISTS_TOPIC_ID='3'
export CLUB1_QA_TOPIC_ID='4'
export CLUB1_MEDIA_TOPIC_ID='5'
export CLUB1_SYSTEM_TOPIC_ID='7'

export CLUB2_NAME='Osobnyak'
export CLUB2_CHAT_ID='-1003015873542'
export CLUB2_GENERAL_TOPIC_ID='1'
export CLUB2_BOOKINGS_TOPIC_ID='2'
export CLUB2_LISTS_TOPIC_ID='4'
export CLUB2_QA_TOPIC_ID='5'
export CLUB2_MEDIA_TOPIC_ID='6'
export CLUB2_SYSTEM_TOPIC_ID='7'

export CLUB3_NAME='Internal3'
export CLUB3_CHAT_ID='-1003082862964'
export CLUB3_GENERAL_TOPIC_ID='1'
export CLUB3_BOOKINGS_TOPIC_ID='2'
export CLUB3_LISTS_TOPIC_ID='3'
export CLUB3_QA_TOPIC_ID='4'
export CLUB3_MEDIA_TOPIC_ID='5'
export CLUB3_SYSTEM_TOPIC_ID='6'

export CLUB4_NAME='NN'
export CLUB4_CHAT_ID='-1002988144234'
export CLUB4_GENERAL_TOPIC_ID='1'
export CLUB4_BOOKINGS_TOPIC_ID='2'
export CLUB4_LISTS_TOPIC_ID='3'
export CLUB4_QA_TOPIC_ID='4'
export CLUB4_MEDIA_TOPIC_ID='5'
export CLUB4_SYSTEM_TOPIC_ID='6'

# --- База данных ---
export DATABASE_URL='jdbc:postgresql://127.0.0.1:15432/clubs'
export DATABASE_USER='clubs'
export DATABASE_PASSWORD='clubs'

# --- Режим бота ---
export TELEGRAM_USE_POLLING='true'

# --- Порт приложения ---
export PORT='8085'

# --- Не задаём FLYWAY_LOCATIONS, даём MigrationsPlugin выбрать по JDBC ---
unset FLYWAY_LOCATIONS || true

echo "[dev-env] Окружение загружено."