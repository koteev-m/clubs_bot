#!/usr/bin/env bash
set -euo pipefail

signal="${1:-TERM}"  # можно передать KILL, если очень упрямые

echo ">> Останавливаю дочерние приложения (ApplicationKt) сигналом $signal"
pkill -$signal -f "com.example.bot.ApplicationKt" || true

echo ">> Останавливаю Gradle run-задачи точечным паттерном (WrapperMain.*:app-bot:run)"
pkill -$signal -f "org.gradle.wrapper.GradleWrapperMain.*:app-bot:run" || true

# На всякий случай — по процесс-группам (надёжно убивает wrapper + дочерние)
echo ">> Контрольный останов по PGID для всех GradleWrapperMain :app-bot:run"
for pid in $(pgrep -f "org.gradle.wrapper.GradleWrapperMain.*:app-bot:run" || true); do
  pgid=$(ps -o pgid= -p "$pid" | tr -d ' ')
  if [[ -n "${pgid}" ]]; then
    echo "   - kill -$signal -${pgid} (PGID)"
    kill -$signal -${pgid} || true
  fi
done

# Корректно останавливаем все gradle-демоны
echo ">> ./gradlew --stop (если проект под рукой)"
if command -v ./gradlew >/dev/null 2>&1; then
  ./gradlew --stop || true
fi

sleep 1
echo "== Остатки после остановки =="
pgrep -fal "org.gradle.wrapper.GradleWrapperMain.*:app-bot:run" || echo "wrapper'ов нет"
pgrep -fal "com.example.bot.ApplicationKt" || echo "ApplicationKt нет"