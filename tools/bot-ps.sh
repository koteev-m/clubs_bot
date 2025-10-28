#!/usr/bin/env bash
set -euo pipefail

echo "== Процессы Gradle :app-bot:run =="
pgrep -fal "org.gradle.wrapper.GradleWrapperMain.*:app-bot:run" || echo "нет"

echo "== Процессы ApplicationKt =="
pgrep -fal "com.example.bot.ApplicationKt" || echo "нет"

echo "== Порт 8082 =="
lsof -nP -iTCP:8082 -sTCP:LISTEN || echo "8082 свободен"