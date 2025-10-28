#!/usr/bin/env bash
set -Eeuo pipefail

# 0) Окружение
if [[ ! -f ./env.local ]]; then
  echo "env.local не найден. Создай файл и повтори."
  exit 1
fi
set -a; source ./env.local; set +a

echo "[1/4] Поднимаю заглушку refund-провайдера (если не поднята)..."
if ! docker ps --format '{{.Names}}' | grep -q '^refund-stub$'; then
  docker rm -f refund-stub >/dev/null 2>&1 || true
  docker run -d --rm -p 18080:80 --name refund-stub ealen/echo-server:latest >/dev/null
fi

echo "[2/4] Очищаю запущенные gradle/ktor процессы и порт ${PORT}..."
pkill -f 'gradle.*app-bot|EngineMain' >/dev/null 2>&1 || true
# ВАЖНО: не падать, если на порту никто не слушает
(lsof -nP -iTCP:"${PORT}" -sTCP:LISTEN -t 2>/dev/null | xargs -r kill -9) || true

echo "[3/4] Останавливаю демона Gradle..."
./gradlew --stop >/dev/null 2>&1 || true

echo "[4/4] Стартую приложение..."
exec ./gradlew :app-bot:run
