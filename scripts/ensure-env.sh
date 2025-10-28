#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$ROOT_DIR"

sample=".env.sample"
dotenv=".env"
dotenv_local=".env.local"
envenv="env.env"

if [[ ! -f "$sample" ]]; then
  echo "ERROR: $sample not found. Commit .env.sample to the repo." >&2
  exit 1
fi

create_if_missing() {
  local target="$1"
  if [[ ! -f "$target" ]]; then
    cp "$sample" "$target"
    echo "Created $target from $sample"
  fi
}

create_if_missing "$dotenv"
create_if_missing "$envenv"

# .env.local — опциональный, но создадим пустым, если нет
if [[ ! -f "$dotenv_local" ]]; then
  touch "$dotenv_local"
  echo "Created empty $dotenv_local (use for local overrides)"
fi

# Быстрая проверка, что токен ещё не заполнен
if grep -q "__PUT_REAL_TOKEN_HERE__" "$dotenv" || grep -q "__PUT_REAL_TOKEN_HERE__" "$envenv"; then
  echo "WARN: TELEGRAM_BOT_TOKEN is still placeholder in .env/env.env. Please set a real token."
fi

echo "Env files are ready."