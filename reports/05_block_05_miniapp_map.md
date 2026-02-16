# Аудит Блока 5 — Mini App: структура и UX

## 1) Где в коде находится Mini App / WebApp

В репозитории есть **несколько параллельных контуров** mini app/web UI:

1. `installBookingWebApp()` — встроенный HTML/JS UI для бронирования и check-in (`/ui/checkin`, `/api/bookings*`).  
   `app-bot/src/main/kotlin/com/example/bot/web/BookingWebAppRoutes.kt:345-560`
2. `installWebUi()` — заглушки `/ui/waitlist` и `/ui/guest-list`.  
   `app-bot/src/main/kotlin/com/example/bot/plugins/WebUiPlugin.kt:23-64`
3. `webapp/entry/*` — статический entry-checkin фронт (сканер QR) в ресурсах.  
   `app-bot/src/main/resources/webapp/entry/index.html:1-29`, `.../app.js:1-127`
4. Есть отдельные модули `webAppRoutes()`/`miniAppModule()` для SPA-статики (`/app`), но в основном `Application.module()` они **не подключены** (подключён именно `installBookingWebApp`).  
   `app-bot/src/main/kotlin/com/example/bot/routes/WebAppRoutes.kt:17-49`, `app-bot/src/main/kotlin/com/example/bot/miniapp/MiniAppModule.kt:13-29`, `app-bot/src/main/kotlin/com/example/bot/Application.kt:171-174`

Итог: единой реализованной «витрины mini app» как цельного продукта нет; есть набор API + несколько UI-контуров и заглушек.

---

## 2) Карта экранов “как в коде”

## 2.1 Guest surface

### A. Telegram UI (основной guest fallback/UX)
- Меню: `Выбрать клуб`, `Мои брони`, `Задать вопрос`, `Музыка`.  
  `app-bot/src/main/kotlin/com/example/bot/telegram/Keyboards.kt:22-37`
- По факту в `MenuCallbacksHandler` обработаны только:
  - `menu:clubs` (выбор клуба/ночи/стола/гостей + hold/confirm/finalize),
  - `menu:bookings` (мои брони + cancel/show).  
  `app-bot/src/main/kotlin/com/example/bot/telegram/MenuCallbacksHandler.kt:88-180`
- Явной обработки `menu:ask` и `menu:music` в этом обработчике нет (несоответствие клавиатуре).  
  `app-bot/src/main/kotlin/com/example/bot/telegram/Keyboards.kt:32-36`, `.../MenuCallbacksHandler.kt:88-180`

### B. Mini App / HTTP API для гостя
- `/api/me/bookings`, `/api/bookings/{id}/qr`, `/api/bookings/{id}/ics` (мои брони / Night Pass QR / .ics).  
  `app-bot/src/main/kotlin/com/example/bot/routes/MeBookingsRoutes.kt:61-219`
- `/api/clubs`, `/api/events` — каталог клубов/событий (используется в web UI).  
  `app-bot/src/main/kotlin/com/example/bot/web/BookingWebAppRoutes.kt:350-383`
- `/api/music/*` — музыка/сеты/плейлисты/mixtape.  
  `app-bot/src/main/kotlin/com/example/bot/routes/MusicRoutes.kt:114-207`
- `/api/support/*` — тикеты “задать вопрос”.  
  `app-bot/src/main/kotlin/com/example/bot/routes/SupportRoutes.kt:127-260`

### C. Guest Web screens (HTML)
- `/ui/checkin` — страница бронирования/выбора стола + “мои брони” (legacy HTML в Kotlin-файле).  
  `app-bot/src/main/kotlin/com/example/bot/web/BookingWebAppRoutes.kt:347-560`
- `/ui/waitlist` и `/ui/guest-list` — явные заглушки (placeholder-экраны).  
  `app-bot/src/main/kotlin/com/example/bot/plugins/WebUiPlugin.kt:25-63`

## 2.2 Staff surface

- `/api/host/checkin` (+ `/scan`, `/search`) — staff check-in/scanner/search, MiniApp auth + RBAC (ENTRY_MANAGER/MANAGER/CLUB_ADMIN).  
  `app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt:105-120,154-187`
- `/api/host/entrance` — агрегаты/сводка входа.  
  `app-bot/src/main/kotlin/com/example/bot/routes/HostEntranceRoutes.kt:30-34`
- `/api/host/checklist` — чеклист смены host.  
  `app-bot/src/main/kotlin/com/example/bot/routes/HostChecklistRoutes.kt:46-50`

## 2.3 Manager surface

- `/api/admin/.../analytics` — отчёты/метрики.  
  `app-bot/src/main/kotlin/com/example/bot/routes/AdminAnalyticsRoutes.kt:130-137`
- `/api/admin/.../finance` — финансы смены/закрытие.  
  `app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceShiftRoutes.kt:200-207,267-267`
- `/api/admin/.../tables`, `/table-ops` — конфиг/операционка столов.  
  `app-bot/src/main/kotlin/com/example/bot/routes/AdminTablesRoutes.kt:109-114`, `app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt:131-137`
- `/api/admin/.../clubs`, `/halls`, `/gamification`, `/music` — настройка клуба/залов/модулей контента.  
  `app-bot/src/main/kotlin/com/example/bot/routes/AdminClubsRoutes.kt:64-69`, `AdminHallsRoutes.kt:67-72`, `AdminGamificationRoutes.kt:194-199`, `AdminMusicRoutes.kt:163-166`

---

## 3) Сопоставление с требуемой картой Блока 5

Требование (из AGENTS):
- Гость: карта, календарь, мои брони, Night Pass, мои ночи, музыка, вопрос, уведомления.
- Персонал: вход/сканер, столы.
- Управленцы: настройки/отчёты/контент.
- Фолбэки в чат.

### 3.1 Гость

| Раздел | Статус | Где в коде |
|---|---|---|
| Карта | **Нет** | Явного guest-map экрана/роута не найдено |
| Календарь | **Частично** | Есть list событий/ночей API (`/api/events`, `/api/clubs/{id}/nights`), но единый календарный mini app экран не найден. `BookingWebAppRoutes.kt:360-383`, `AvailabilityApiRoutes.kt:39-75` |
| Мои брони | **Есть** | `/api/me/bookings` + Telegram my bookings. `MeBookingsRoutes.kt:64-117`, `MenuCallbacksHandler.kt:99-145` |
| Night Pass | **Есть** | QR endpoint `/api/bookings/{id}/qr` + Telegram fallback `/qr`. `MeBookingsRoutes.kt:122-217`, `TelegramGuestFallbackHandler.kt:61-101` |
| Мои ночи | **Частично** | По сути через upcoming bookings; отдельного “my nights” раздела нет. `MeBookingsRoutes.kt:64-103` |
| Музыка | **Есть (API)** | `/api/music/*`, но явного связанного mini app экрана не найдено. `MusicRoutes.kt:114-207` |
| Задать вопрос | **Есть** | `/api/support/tickets*` + Telegram fallback `/ask`. `SupportRoutes.kt:127-244`, `TelegramGuestFallbackHandler.kt:73-77,194-230` |
| Уведомления | **Частично** | Есть outbox/ops/telegram отправка и worker, но отдельного guest mini app раздела уведомлений не найдено |

### 3.2 Персонал

| Раздел | Статус | Где в коде |
|---|---|---|
| Вход/сканер | **Есть** | `/api/host/checkin`, `/scan`, `/search`; есть отдельный entry webapp JS со сканером. `HostCheckinRoutes.kt:105-187`, `resources/webapp/entry/app.js:53-112` |
| Столы | **Частично** | Операции по столам есть в admin table ops API, но явного отдельного staff mini app UI-экрана “столы” не найдено. `AdminTableOpsRoutes.kt:136-164,383-383` |

### 3.3 Управленцы

| Раздел | Статус | Где в коде |
|---|---|---|
| Настройки | **Есть (API)** | `/api/admin` набор роутов (clubs/halls/tables/gamification/music). |
| Отчёты | **Есть (API)** | `/api/admin/clubs/{clubId}/analytics`, финансы смены. |
| Контент | **Есть (API)** | admin music, stories, club/hall settings. |

Комментарий: по управленцам в коде сильный API-контур, но не найден единый front mini app shell с навигацией по этим разделам.

---

## 4) Фолбэки в чат при сбоях

## Реализовано
1. `TelegramGuestFallbackHandler` обрабатывает `/qr`, `/my`, `/ask`, и подсказывает открыть mini app при проблемах пользователя/регистрации.  
   `app-bot/src/main/kotlin/com/example/bot/telegram/TelegramGuestFallbackHandler.kt:60-77,82-109,244-257`
2. `MenuCallbacksHandler` при ошибках state/token/session отправляет сообщения в чат (`sessionExpired`, `unknownError`, `holdExpired`).  
   `app-bot/src/main/kotlin/com/example/bot/telegram/MenuCallbacksHandler.kt:111-126,191-221,597-633`
3. В entry webapp есть UX fallback на ошибки сети/QR-сканера (`Запустите через Telegram`, `Сеть недоступна`).  
   `app-bot/src/main/resources/webapp/entry/app.js:29-35,61-64,100-103`

## Ограничения
- Фолбэки распределены между несколькими хендлерами/страницами; централизованной деградационной матрицы для mini app UX в Блоке 5 не найдено.

---

## 5) Недостающие экраны / сценарии

## Missing (нет)
1. Единый mini app frontend с навигацией по разделам гостя “карта / календарь / мои ночи / уведомления”.
2. Явный guest-раздел “уведомления” (история/центр уведомлений).
3. Явный раздел “карта” (club map) для гостя.
4. Единый staff UI раздел “столы” (не только API/админ-операции).

## Partial (частично)
1. Календарь: API есть, но цельного UX-экрана календаря не видно.
2. Музыка: API есть, но нет подтверждённого front-экрана mini app для гостя.
3. “Мои ночи”: частично перекрывается “мои брони”, отдельного раздела нет.
4. Фолбэки в чат есть, но не покрывают единообразно все mini app сценарии.

## Технические разрывы/риски (UX)
1. Раздвоение web-контуров (`installBookingWebApp`, `installWebUi` заглушки, `webapp/entry`, + неподключенные `webAppRoutes/miniAppModule`) создаёт неоднородный UX и сложность поддержки.
2. В Telegram меню есть CTA `menu:ask` и `menu:music`, но в `MenuCallbacksHandler` нет соответствующих веток — кнопки могут “молчать” в этом обработчике.

---

## 6) Краткий вывод

Блок 5 реализован **частично**: backend/API-контуры для guest/staff/manager в значительной степени присутствуют, но полноценная целевая структура Mini App UX (единая карта экранов гостя + staff/manager shell) в коде не прослеживается. На сегодня продукт выглядит как набор API + Telegram fallback + несколько разрозненных web/miniapp заготовок.
