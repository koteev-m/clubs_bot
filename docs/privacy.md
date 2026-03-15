# Privacy go-live (P0/P1)

## Guest list API и export

- Поле телефона в `/api/guest-lists` и `/api/guest-lists/export` теперь по умолчанию маскируется (`+******123`).
- Полный номер доступен только для high-privilege ролей (`OWNER`, `GLOBAL_ADMIN`, `HEAD_MANAGER`) и только при `includeSensitive=true`.
- Любой запрос с `includeSensitive=true` обязан содержать `reason`, иначе возвращается `400`.
- Попытки доступа с `includeSensitive=true` пишутся в `audit_log` (granted/denied + reason).

## Ops/HQ уведомления

- В HQ-уведомлениях о новой брони больше не отправляется `qrSecret`.
- Вместо этого отправляется безопасный `Ref` (короткая ссылка на booking id), по которому сотрудник открывает детали в админке.
