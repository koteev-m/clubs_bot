#!/usr/bin/env bash
set -euo pipefail

# 1) Окружение
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEV_ENV_FILE="$SCRIPT_DIR/dev-env.sh"
if [ ! -f "$DEV_ENV_FILE" ]; then
  echo "[run-local] Missing ignored local environment file: scripts/dev-env.sh" >&2
  echo "[run-local] Copy scripts/dev-env.example.sh and fill the local copy." >&2
  exit 2
fi
# shellcheck disable=SC1090
source "$DEV_ENV_FILE"

# 2) Убьём любой процесс, занявший порт
if lsof -tiTCP:"${PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "[run-local] Killing process on port ${PORT}…"
  kill -15 "$(lsof -tiTCP:"${PORT}" -sTCP:LISTEN)" || true
  sleep 1
  kill -9 "$(lsof -tiTCP:"${PORT}" -sTCP:LISTEN)" 2>/dev/null || true
fi

# 3) Сборка
./gradlew :app-bot:installDist

# 4) Старт
exec java \
  -Dconfig.file=./app-bot/src/main/resources/application-dev.conf \
  -Dio.ktor.development=true \
  -Dktor.deployment.port="${PORT}" \
  -cp "./app-bot/build/install/app-bot/lib/*" \
  io.ktor.server.netty.EngineMain
