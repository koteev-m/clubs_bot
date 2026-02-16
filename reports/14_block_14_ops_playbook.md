# Блок 14 (Регламент смены и чек-листы) — аудит соответствия

## 1) Чек-листы и операционные подсказки в коде

## 1.1 Чек-лист смены (host)

- В коде есть отдельный сервис чек-листа смены `ShiftChecklistService` с фиксированным шаблоном задач:
  - `doors_open`, `qr_scanners_ready`, `staff_briefing`, `soundcheck_host`, `reserve_tables_ready`.
- Тексты задач явно операционные (вход, сканеры, брифинг, звук, резервы).
  Источник: `ShiftChecklistService`.【app-bot/src/main/kotlin/com/example/bot/host/ShiftChecklistService.kt:19-46】

- Есть API для host-персонала:
  - `GET /api/host/checklist` — получить текущее состояние,
  - `POST /api/host/checklist` — отметить пункт done/undone.
- API защищён mini-app auth + RBAC (`ENTRY_MANAGER`, `CLUB_ADMIN`, `OWNER`, `GLOBAL_ADMIN`).
  Источник: `HostChecklistRoutes`.【app-bot/src/main/kotlin/com/example/bot/routes/HostChecklistRoutes.kt:39-133】

## 1.2 Ограничения реализации чек-листа

- Состояние чек-листа in-memory и не персистится между рестартами (явно задокументировано в комментарии сервиса).
- Нет версионируемых шаблонов по клубам/ролям и нет признаков “чек-лист закрыт/подписан”.
  Источник: `ShiftChecklistService`.【app-bot/src/main/kotlin/com/example/bot/host/ShiftChecklistService.kt:7-14】

---

## 2) “Золотые правила” как ограничения/валидации

## 2.1 Дисциплина “одна отметка” и анти-дубли (вход)

- Для check-in в БД есть жёсткий инвариант уникальности: `UNIQUE (subject_type, subject_id)` в таблице `checkins`.
  Это обеспечивает одну отметку на субъект и блокирует дубли.
  【core-data/src/main/resources/db/migration/postgresql/V017__guest_list_invites_checkins.sql:70-82】

- В `CheckinServiceImpl` повторные попытки возвращаются как `AlreadyUsed` / deny reason `ALREADY_USED`, а не создают повторную запись.
  Источник: `CheckinServiceImpl`.【core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt:220-255】【core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt:329-378】【core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt:1178-1184】

## 2.2 Дополнительные операционные валидации

- Для deny-checkin обязателен `denyReason` (валидация в сервисе).
- Терминальные статусы (`DENIED/NO_SHOW/EXPIRED`) и уже отмеченные (`ARRIVED/LATE/CHECKED_IN`) обрабатываются как повтор/запрет при повторной отметке.
  Источник: `CheckinServiceImpl`.【core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt:58-80】【core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt:301-356】

## 2.3 Что не формализовано как “золотые правила”

- Не найден отдельный централизованный модуль/DSL “golden rules” смены с машиночитаемыми политиками (например, peak-mode protocol, аварийный протокол, обязательные шаги перед open/close).
- Чек-лист есть, но enforcement в стиле “нельзя переходить к операции X, пока не закрыт пункт Y” не обнаружен.

---

## 3) Сообщения/шаблоны для операционки

## 3.1 Есть

- Шаблоны операционных уведомлений реализованы в `OpsNotificationRenderer` (человекочитаемые тексты по событиям брони/входа/саппорта/алертов).
  Источник: `OpsNotificationRenderer`.【app-bot/src/main/kotlin/com/example/bot/notifications/OpsNotificationRenderer.kt:9-38】

- Есть отдельный формат сообщения “ответ от клуба” для саппорта (`buildSupportReplyMessage`) с санитизацией имени клуба.
  Источник: `SupportNotificationText`.【app-bot/src/main/kotlin/com/example/bot/support/SupportNotificationText.kt:6-33】

- Для guest-flow есть обширные тексты ошибок/фолбэков (`BotTexts`), но это в большей степени UX-гайды для гостя, а не playbook смены персонала.
  Источник: `BotTexts`.【app-bot/src/main/kotlin/com/example/bot/text/BotTexts.kt:73-216】

## 3.2 Нет/частично

- Не найден отдельный набор шаблонов “регламентных” сообщений смены (пиковая нагрузка, аварийный протокол, golden rules reminder в интерфейсе staff).
- Нет отдельной сущности “операционные подсказки смены” с управлением из админки.

---

## 4) Итог по Блоку 14

## Реализовано

1. Базовый чек-лист смены для host с API и RBAC.
2. Сильные антидубли и дисциплина “одна отметка” в check-in контуре (БД + сервис).
3. Базовые шаблоны операционных уведомлений по событиям.

## Частично

1. Регламент смены представлен локальным чек-листом, но без персистентности и без продвинутого workflow.
2. Операционные тексты есть, но они не покрывают полноценно “peak + аварийный протокол” как отдельный playbook.

## Отсутствует

1. Централизованный движок/модель “золотых правил” смены.
2. Программный enforcement последовательности чек-листа (hard gates между операциями).
3. Полноценный модуль оперативных подсказок/инструкций с управлением контентом.

## Риски

- **P1:** из-за in-memory чек-листа теряется история выполнения/дисциплины после рестарта.
- **P1:** без формализованного аварийного/пикового протокола действия персонала остаются частично на уровне устных договорённостей.
- **P2:** отсутствие hard-gates по шагам чек-листа допускает пропуск критичных процедур перед началом смены.
