# Release rollback runbook

## Цель

Стандартизировать решение: делаем `forward-fix` или откатываемся (`rollback`/`PITR`) при проблемах после релиза.

## Decision tree

1. **Проверка готовности релиза**
   - `/ready` = `200` → переходим к функциональным smoke-проверкам.
   - `/ready` != `200` > 60 секунд → релиз считается неуспешным.
2. **Начались quiesce/migration?**
   - Нет → старый app не изменён; исправляем image/publication/config и повторяем release.
   - Да → pre-migration image запускать запрещено. Retry допускается только с тем же или новым schema-compatible image.
3. **Новый image не проходит readiness/health?**
   - Выполняем forward-fix и повторяем полный quiesced release; автоматического rollback нет.
   - Если безопасный forward-fix невозможен, app остаётся остановленным либо на новом image и инцидент эскалируется.
4. **Затронуты данные (потеря/коррупция/необратимые миграции)?**
   - Нет → делаем `forward-fix` и повторный релиз.
   - Да → запускаем процедуру `PITR` по `docs/dr.md`.

## Когда выбирать forward-fix

Выбираем `forward-fix`, если одновременно верно:
- schema-compatible image с исправлением готов и проходит локальные smoke-проверки;
- схема БД консистентна, необратимого изменения данных нет;
- есть быстрый, проверяемый фикс < 30 минут.

## Когда выбирать PITR

Выбираем PITR, если есть хотя бы один пункт:
- необратимая ошибка миграции/данных;
- массовая порча критичных таблиц;
- schema-compatible forward-fix не может безопасно восстановить сервис и требуется восстановление данных.

## Минимальный протокол восстановления

1. Зафиксировать время инцидента и tag неуспешного релиза.
2. Зафиксировать применённую Flyway version, verified image digest/revision и сохранённые в maintenance lock `migration_image_digest`/`migration_image_id`. Они должны совпадать с final app image reference/ID.
3. Не удалять stale remote maintenance lock без проверки owner/phase, состояния migration/app containers и schema history. В фазе `migrating` сначала установить, завершилась ли Flyway transaction, и только затем решать вопрос о retry/cleanup.
4. Проверить, что `docker-compose.override.yml` остаётся managed-файлом и его digest совпадает с
   `image_digest` текущего maintenance lock, а revision — с `expected_revision`. До этой проверки не запускать
   ни base Compose, ни сохранённый «последний рабочий» image: после V056 он может быть schema-incompatible.
5. После forward-fix подтвердить `/ready` и `/health`, сохранить application logs и реконструированные canonical `migration-safe:v=1` events вместе со schema-history evidence. Unknown/malformed/duplicate/out-of-order raw output является protocol failure и не разрешает выход из maintenance. Не пересылать в CI полный migration-container output и не включать raw Flyway/JDBC/exception logging; при fail-closed maintenance он доступен только ограниченному оператору как mode `0600` файл на host.
6. Открыть postmortem-задачу с причиной и корректирующими действиями.
