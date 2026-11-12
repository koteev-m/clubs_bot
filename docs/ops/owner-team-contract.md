# CLB-owner-team: принятый staff-контракт

> Датированное уточнение 2026-11-10: это сохранённый исторический/current wiring
> контракт фиксированных packages и creator-only API. Актуальный target состоит
> из восьми ролей и новой иерархии назначений по
> [PRODUCT_SPEC §29.18](../product/PRODUCT_SPEC.md#business-role-model-20261110).
> Новые назначение роли, delegation, revoke одного assignment и full disable
> требуют отдельного безопасного writer contract. Права и код этого staff flow
> данным продуктовым решением не меняются; старые invitations не конвертируются.

Статус: **ACCEPTED_DECISION**, прямое утверждение владельца продукта 2026-11-08.
Ранее подготовленное предложение PROPOSED и уточнения последующего разбора
приняты целиком. Это уточнение DEC-004/005/040/041, без нового DEC/CLB номера.
Принятие разрешает локальную реализацию в существующем worktree и не доказывает
implementation/runtime readiness. Исходный base: `81c2822e6a3d6d53e863e106d4a34cf24720ac8a`.

## 1. Управление командой

Только подтверждённый сервером создатель данного клуба с активными client,
membership, mapping, `client.club.read` и `client.club.team.manage`. Создатель —
владелец для этого этапа. Передача владения и делегирование исключены. Membership,
название роли и legacy OWNER/GLOBAL не являются authority. Inactive grants не
восстанавливаются автоматически. Отдельный прежний platform restore остаётся
явным и не может подменить staff-приглашение.

## 2. Точная матрица

Все permissions в таблице имеют префикс `client.club.` и действуют только для
конкретной пары client/club и пользователя. Источник прав — активные grants,
не role metadata и не `user_roles`.

| Permission suffix | ENTRY_MANAGER / Host | MANAGER | CLUB_ADMIN | Владелец |
|---|---|---|---|---|
| read | Да | Да | Да | Только существующий подтверждённый grant |
| profile.write | — | Да | Да | То же |
| layout.write | — | Да | Да | То же |
| calendar.write | — | Да | Да | То же |
| booking_rules.write | — | Да | Да | То же |
| finance.read/write | — | — | Да | То же |
| modules.read/write | — | — | Да | То же |
| team.manage | — | — | — | Отдельная законная выдача создателю |

Нет staff-выдачи `client.application.manage`, legacy assignments, GLOBAL,
platform authority или операционных permissions. Host в этом срезе получает
просмотр конфигурации, не QR/check-in. Администратор настраивает финансовый
шаблон и доступные module settings, не деньги/смену/выдачу наград/публикацию.
Promoter, DJ, finance manager и другие целевые роли не удаляются.

## 3. Приглашение

Случайный secret минимум 256 бит, в БД только hash, secret выдаётся один раз.
Принятие в течение семи суток, не срок действия уже выданного доступа.
Сотрудник явно подтверждает подписанный Telegram account. Первый claim фиксирует
кандидата; userId/clientId/username запроса authority не дают. Затем владелец
сверяет код в исходном личном чате и отдельным авторизованным действием
подтверждает выдачу. Код — comparison aid, не credential. До этого клубных grants
нет; preview не provisions identity. Claim может создать только minimal user.
Ошибочный кандидат, expiry, revoked/lost link требуют нового приглашения.

## 4. Membership и выдача

Переиспользуются users, client memberships/mappings/grants, client_commands и
audit_log. Активная membership переиспользуется, отсутствующая создаётся без
client.application.manage. Inactive membership платформы клубный владелец не
восстанавливает. Весь пакет повторно проверяется по текущим правам выдающего;
недостаток хотя бы одного permission отклоняет всю операцию. Mutation, grants,
результат durable command и audit атомарны. Свои назначения, создатель клуба и
владелец клиентского пространства защищены от staff mutations.

## 5. Изменение, отзыв и повторный допуск

Смена роли требует причины, просмотра полного нового пакета и явного
подтверждения. Пакет заменяется атомарно, не накапливается. Disable после commit
прекращает все client club grants сотрудника в этом клубе. Membership, другие
клубы, история и audit сохраняются. Повторный допуск только через новое
приглашение, принятие сотрудником и подтверждение владельцем. Старые ссылки,
включая выпущенные до disable, не восстанавливают доступ. Дополнительные права
показываются как явная новая выдача. Платформенный membership restore не может
вернуть staff grants вместо этого пути.

## 6. Обязательные технические инварианты

Tenant/client/club isolation; fresh signed identity и текущая authority каждого
запроса/replay; optimistic concurrency; idempotency; transactional audit;
сериализация confirm/change/revoke; expiry после ожидания lock; защита от старых
HTTP responses. После commit отзыва новые проверки прежней сессии отказывают.
Уже завершённые действия не отменяются, уже раскрытые данные не отзываются с
устройства. UI очищает отказанный контекст и не восстанавливает права из старых
ответов. Secret/initData/raw Idempotency-Key не логируются и не сохраняются в
optional browser storage. Назначение не предоставляет legacy AdminShell access.

## Сохраняемая граница

Guest booking/cutover, деньги/депозиты/смены, QR/check-in, rewards/contests,
DJ/music, Managed Bots и прочие независимые функции не открываются.
DEC-013/014/022 и initial catalogue/configuration boundary DEC-031 сохраняются.
Команда не является обязательным условием подготовки или публичного запуска.
Предыдущие решения не переписываются; source concept immutable.

## Датированное уточнение совместимости, 2026-11-09

Этот контракт сохраняет исторические названия и точные технические пакеты,
принятые 2026-11-08. Новый основной target catalogue из девяти ролей и процесс
делегирования определены отдельным [принятым уточнением](owner-manager-contract.md)
и [PRODUCT_SPEC §29.17](../product/PRODUCT_SPEC.md#owner-manager-model).
Host/DJ/Админ клуба/Менеджер зала больше не основные target роли, но это не
переименование ENTRY_MANAGER/MANAGER/CLUB_ADMIN, не изменение grants и не удаление
исторических записей. Подготовка может временно использовать весь existing
CLUB_ADMIN как configuration package без team/денег/ownership; он не объявляется
новой технической ролью Управляющего клуба. Прежнее исключение «delegation» выше
сохраняет запрет делегировать team.manage/владение; новое решение разрешает только
отдельную задачу конфигурации в пределах уже принятой authority. Старые grants,
inactive tombstones и двухэтапный invitation не меняются.
