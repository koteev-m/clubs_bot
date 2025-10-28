#!/usr/bin/env bash
set -euo pipefail

# ---------- Параметры со значениями по умолчанию ----------
APP_IMAGE="${APP_IMAGE:-app-bot:smoke}"
APP_NAME="${APP_NAME:-bot_app_smoke}"
PG_NAME="${PG_NAME:-bot_pg_smoke}"
NET_NAME="${NET_NAME:-bot_smoke_net}"

PG_PORT_HOST="${PG_PORT_HOST:-15432}"   # порт на хосте (для psql с Mac)
PG_PORT_CONT="${PG_PORT_CONT:-5432}"    # порт в контейнере postgres
APP_PORT_HOST="${APP_PORT_HOST:-8080}"  # порт на хосте (для curl /health)
APP_PORT_CONT="${APP_PORT_CONT:-8080}"  # порт в контейнере app

# Тестовые ENV (минимум для запуска)
DB_NAME="${DB_NAME:-botdb}"
DB_USER="${DB_USER:-botuser}"
DB_PASS="${DB_PASS:-botpass}"

# Важно: используем имя контейнера Postgres как хост!
DATABASE_URL="jdbc:postgresql://${PG_NAME}:${PG_PORT_CONT}/${DB_NAME}"

# Телеграм токены/флаги — безопасные дефолты для smoke (polling, фиктивный токен)
TELEGRAM_BOT_TOKEN="${TELEGRAM_BOT_TOKEN:-000000:TEST_TOKEN}"
BOT_TOKEN="${BOT_TOKEN:-$TELEGRAM_BOT_TOKEN}"   # Mini App initData = тот же токен
TELEGRAM_USE_POLLING="${TELEGRAM_USE_POLLING:-true}"

APP_PROFILE="${APP_PROFILE:-PROD}"
FLYWAY_ENABLED="${FLYWAY_ENABLED:-true}"
# ГЛАВНЫЙ ФИКС: ограничиваем миграции Postgres-папкой
FLYWAY_LOCATIONS="${FLYWAY_LOCATIONS:-classpath:db/migration/postgresql}"
OWNER_TELEGRAM_ID="${OWNER_TELEGRAM_ID:-0}"

say()   { printf "\033[1;34m>>> %s\033[0m\n" "$*"; }
warn()  { printf "\033[1;33m!!! %s\033[0m\n" "$*"; }
die()   { printf "\033[1;31m✖ %s\033[0m\n" "$*" >&2; exit 1; }

cleanup() {
  say "Cleanup containers"
  docker rm -f "${APP_NAME}" >/dev/null 2>&1 || true
  docker rm -f "${PG_NAME}"  >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ---------- sanity ----------
command -v docker >/dev/null || die "docker not found"
docker info >/dev/null 2>&1 || die "docker daemon not available"

# ---------- Сеть ----------
if ! docker network inspect "${NET_NAME}" >/dev/null 2>&1; then
  say "Create network: ${NET_NAME}"
  docker network create "${NET_NAME}" >/dev/null
fi

# ---------- Сборка образа приложения ----------
say "Build app image: ${APP_IMAGE}"
docker build -t "${APP_IMAGE}" .

# ---------- Поднятие Postgres ----------
say "Run Postgres: ${PG_NAME}"
docker rm -f "${PG_NAME}" >/dev/null 2>&1 || true
docker run -d --name "${PG_NAME}" \
  --network "${NET_NAME}" \
  -e POSTGRES_DB="${DB_NAME}" \
  -e POSTGRES_USER="${DB_USER}" \
  -e POSTGRES_PASSWORD="${DB_PASS}" \
  -p "${PG_PORT_HOST}:${PG_PORT_CONT}" \
  --health-cmd="pg_isready -U ${DB_USER} -d ${DB_NAME}" \
  --health-interval=5s --health-timeout=3s --health-retries=20 \
  postgres:16-alpine >/dev/null

say "Waiting for Postgres to be healthy..."
for i in {1..60}; do
  status="$(docker inspect -f '{{.State.Health.Status}}' "${PG_NAME}" || echo "unknown")"
  if [[ "${status}" == "healthy" ]]; then
    say "Postgres is healthy"
    break
  fi
  sleep 1
  [[ $i -eq 60 ]] && {
    warn "Postgres did not become healthy in time"
    docker logs "${PG_NAME}" || true
    exit 1
  }
done

# ---------- Запуск приложения ----------
say "Run app: ${APP_NAME}"
docker rm -f "${APP_NAME}" >/dev/null 2>&1 || true
docker run -d --name "${APP_NAME}" \
  --network "${NET_NAME}" \
  -p "${APP_PORT_HOST}:${APP_PORT_CONT}" \
  -e APP_PROFILE="${APP_PROFILE}" \
  -e DATABASE_URL="${DATABASE_URL}" \
  -e DATABASE_USER="${DB_USER}" \
  -e DATABASE_PASSWORD="${DB_PASS}" \
  -e FLYWAY_ENABLED="${FLYWAY_ENABLED}" \
  -e FLYWAY_LOCATIONS="${FLYWAY_LOCATIONS}" \
  -e TELEGRAM_BOT_TOKEN="${TELEGRAM_BOT_TOKEN}" \
  -e BOT_TOKEN="${BOT_TOKEN}" \
  -e TELEGRAM_USE_POLLING="${TELEGRAM_USE_POLLING}" \
  -e OWNER_TELEGRAM_ID="${OWNER_TELEGRAM_ID}" \
  "${APP_IMAGE}" >/dev/null

# ---------- Smoke-пробы /health и /ready ----------
say "Probing /health and /ready on http://127.0.0.1:${APP_PORT_HOST}"
for i in {1..90}; do
  if curl -fsS "http://127.0.0.1:${APP_PORT_HOST}/health" >/dev/null 2>&1; then
    if curl -fsS "http://127.0.0.1:${APP_PORT_HOST}/ready"  >/dev/null 2>&1; then
      say "Smoke OK"
      exit 0
    fi
  fi
  sleep 1
done

warn "Smoke test FAILED — dumping logs"
echo "----- app logs -----"
docker logs "${APP_NAME}" || true
echo "----- postgres logs -----"
docker logs "${PG_NAME}" || true
exit 1