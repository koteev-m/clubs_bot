# Блок 9 — Геймификация и лояльность (аудит соответствия)

## 1) Какие сущности / таблицы / правила есть

### 1.1 Сущности и хранилище

1. **Базовые настройки геймификации по клубу**
   - `club_gamification_settings`: `stamps_enabled`, `early_enabled`, `badges_enabled`, `prizes_enabled`, `contests_enabled`, `tables_loyalty_enabled`, `early_window_minutes`.
   - См. `core-data/src/main/resources/db/migration/postgresql/V035__gamification_schema.sql:1-13`, `core-data/src/main/kotlin/com/example/bot/data/gamification/GamificationTables.kt:7-19`.

2. **Бейджи и выдача бейджей**
   - `badges` (условия и пороги), `user_badges` (факт выдачи).
   - В `badges` есть `name_ru`, `condition_type`, `threshold`, `window_days`; в `user_badges` — `fingerprint` + уникальные ограничения.
   - См. `V035__gamification_schema.sql:15-45`, `GamificationTables.kt:21-58`.

3. **Призы и «лестница наград»**
   - `prizes`, `reward_ladder_levels`, `reward_coupons`.
   - Используется модель «уровни по метрике/порогу → выдача купона».
   - См. `V035__gamification_schema.sql:46-104`, `GamificationTables.kt:60-124`.

4. **Основа метрик (штампы/ранние/столы)**
   - `club_visits` + `operational_night_overrides`.
   - Визиты содержат флаги `is_early`, `has_table`; early-cutoff может задаваться на ночь.
   - См. `core-data/src/main/kotlin/com/example/bot/data/visits/VisitTables.kt:7-40`.

### 1.2 Движок правил

1. **Начисление запускается по созданию визита**
   - `GamificationEngine.onVisitCreated(...)` считает метрики и выдаёт:
     - бейджи (при достижении порогов);
     - купоны (по reward ladder).
   - См. `core-domain/src/main/kotlin/com/example/bot/gamification/GamificationEngine.kt:17-126`.

2. **Метрики, которые реально используются в правилах**
   - `VISITS` (штампы), `EARLY_VISITS`, `TABLE_NIGHTS`.
   - См. `core-domain/src/main/kotlin/com/example/bot/gamification/GamificationTypes.kt:5-37`, `GamificationEngine.kt:165-190`.

3. **Лояльность по столам**
   - Реализована через метрику `TABLE_NIGHTS`, опирается на `club_visits.has_table`.
   - См. `VisitRepositories.kt:204-209`, `GamificationEngine.kt:140-141,178`.

4. **Ранний приход (настраиваемый по клубу/ночи)**
   - Базово: `early_window_minutes` в club settings.
   - Переопределение на конкретную ночь: `operational_night_overrides.early_cutoff_at`.
   - Применение в check-in flow: берётся override, иначе club setting.
   - См. `AdminGamificationRoutes.kt:247-299`, `VisitTables.kt:7-15`, `CheckinServiceImpl.kt:1083-1087`.

---

## 2) Как обеспечена “одна отметка” и отсутствие двойного начисления

### 2.1 Одна отметка (check-in/visit)

1. **Один check-in на subject**
   - Таблица `checkins` имеет уникальность `(subject_type, subject_id)`.
   - См. `core-data/src/main/resources/db/migration/postgresql/V017__guest_list_invites_checkins.sql:70-83`.

2. **Один визит в клуб/ночь на пользователя**
   - `club_visits` имеет `uniqueIndex("uq_club_visits_club_night_user", clubId, nightStartUtc, userId)`.
   - `VisitRepository.tryCheckIn(...)` делает `insertIgnore` и возвращает существующую запись, если дубль.
   - См. `VisitTables.kt:34-36`, `VisitRepositories.kt:139-167`.

3. **Начисление геймификации только при новом визите**
   - В `CheckinServiceImpl.attemptVisitGamification(...)` движок вызывается только когда `visitResult.created == true`.
   - Если визит уже есть, начисление не повторяется.
   - См. `CheckinServiceImpl.kt:1120-1124`.

### 2.2 Антидубли в выдаче наград

1. **Бейджи**
   - `user_badges`: уникальность по `fingerprint` и по `(club_id, user_id, badge_id)`.
   - `UserBadgeRepository.tryEarn(...)` использует `insertIgnore`.
   - См. `V035__gamification_schema.sql:31-41`, `GamificationTables.kt:53-56`, `GamificationRepositories.kt:152-174`.

2. **Купоны/награды**
   - `reward_coupons`: уникальность `fingerprint`.
   - `CouponRepository.tryIssue(...)` использует `insertIgnore`.
   - См. `V035__gamification_schema.sql:80-99`, `GamificationTables.kt:119-123`, `GamificationRepositories.kt:318-346`.

3. **Фингерпринты в engine**
   - Для бейджа: `club:user:badge`.
   - Для купона: `club:user:metric:threshold:window`.
   - Это делает выдачу идемпотентной для заданных условий.
   - См. `GamificationEngine.kt:145-159`.

---

## 3) Где конфигурируется по клубам

1. **Админ-роуты по клубу**
   - `PUT /api/admin/clubs/{clubId}/gamification/settings` — флаги модулей + `earlyWindowMinutes`.
   - `PUT /api/admin/clubs/{clubId}/nights/{nightStartUtc}/gamification` — override раннего окна на ночь.
   - `.../gamification/badges`, `.../gamification/prizes`, `.../gamification/ladder-levels` — CRUD правил.
   - См. `app-bot/src/main/kotlin/com/example/bot/routes/AdminGamificationRoutes.kt:198-575`.

2. **Сервисные/репозиторные слои**
   - Считывание/апдейт настроек и CRUD сущностей реализованы в `AdminGamificationRepositoriesImpl` + `GamificationSettingsRepository`/`BadgeRepository`/`PrizeRepository`/`RewardLadderRepository`.
   - См. `core-data/src/main/kotlin/com/example/bot/data/gamification/AdminGamificationRepositories.kt:37-340`, `GamificationRepositories.kt:32-102,119-132,242-283`.

3. **Подключение в DI/Application**
   - Все репозитории/адаптеры/engine зарегистрированы в `BookingModules`.
   - Гостевые и админские роуты подключены в `Application.module()`.
   - См. `app-bot/src/main/kotlin/com/example/bot/di/BookingModules.kt:146-192`, `app-bot/src/main/kotlin/com/example/bot/Application.kt:347,407`.

---

## 4) Сопоставление с критичными требованиями блока 9

| Требование | Статус | Комментарий |
|---|---|---|
| Штампы ночей по клубу | **Implemented** | Метрика `VISITS` по `club_visits` + toggle `stamps_enabled`. |
| Ранний приход: настраиваемое время по клубу/ночи | **Implemented** | `early_window_minutes` + night override `early_cutoff_at`, применяется при check-in. |
| Бейджи с порогами и названиями | **Implemented** | `badges.threshold`, `badges.name_ru`, `window_days`, CRUD в админке. |
| Mystery-upgrade | **Partial/None** | В геймификации не найдено механики mystery-upgrade; есть только `mysteryEligible` у столов layout, но не reward flow. |
| Розыгрыши по условиям (посещения/ранние/столы, период) | **Partial** | Есть `contests_enabled` флаг, но нет сущностей/движка розыгрышей; реализована reward-ladder купоновая схема. |
| Лояльность по столам (накопительная, настраиваемая) | **Partial** | Есть метрика `TABLE_NIGHTS` и toggle `tables_loyalty_enabled`, но нет отдельной самостоятельной программы лояльности/настроек кроме ladder. |
| Начисление только по check-in, защита от дублей | **Implemented** | Начисление запускается через visit в check-in flow и only-on-created + DB unique/idempotency. |

---

## 5) Что отсутствует / недоделано

1. **Mystery-upgrade как отдельная доменная механика**
   - Нет сущностей/таблиц/процесса апгрейда в gamification модулях.

2. **Розыгрыши (contests) по периодам/условиям**
   - Есть только toggle `contests_enabled`, но нет контуров `contest/draw/participants/winners`.

3. **Отдельная программа “лояльность по столам”**
   - Сейчас table loyalty фактически сведена к метрике в reward ladder.
   - Нет отдельного накопительного баланса/уровней/правил списания.

4. **Гостевая выдача “мои ночи/штампы” как отдельный артефакт**
   - В `GET /api/me/clubs/{clubId}/gamification` есть totals/badges/coupons/nextRewards,
   - но отдельной сущности/экрана с явной историей «штампов ночей» в ответе нет.

---

## 6) Краткий вывод

Ядро Блока 9 реализовано на хорошем уровне для сценария **check-in → visit → rules engine → badges/coupons** с реальной защитой от дублей на нескольких слоях (checkin, visits, user_badges, reward_coupons). При этом продуктовые части из спеки про **mystery-upgrade** и **розыгрыши** пока в коде отсутствуют как полноценные доменные модули; а “лояльность по столам” реализована только как одна из метрик в reward-ladder, без отдельной расширенной модели программы.
