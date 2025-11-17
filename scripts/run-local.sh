#!/usr/bin/env bash
set -euo pipefail

# 1) Окружение
source "$(dirname "$0")/dev-env.sh"

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