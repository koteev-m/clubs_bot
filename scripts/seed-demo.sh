#!/usr/bin/env bash
set -euo pipefail

: "${DATABASE_URL:?DATABASE_URL is required}"
: "${DATABASE_USER:?DATABASE_USER is required}"
: "${DATABASE_PASSWORD:?DATABASE_PASSWORD is required}"

# Разбор DATABASE_URL вида jdbc:postgresql://host:port/dbname
uri="${DATABASE_URL#jdbc:}"
hostportdb="${uri#postgresql://}"
hostport="${hostportdb%/*}"
db="${hostportdb##*/}"
host="${hostport%:*}"
port="${hostport#*:}"

export PGPASSWORD="${DATABASE_PASSWORD}"

echo "[seed-demo] Apply seeds to ${db} at ${host}:${port} as ${DATABASE_USER}"
psql -h "${host}" -p "${port}" -U "${DATABASE_USER}" -d "${db}" -v ON_ERROR_STOP=1 -f scripts/seed-demo.sql
