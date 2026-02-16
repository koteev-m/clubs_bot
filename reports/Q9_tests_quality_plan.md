# Q9 — Аудит тестового покрытия и план качества

## Executive summary

- В репозитории уже есть рабочий «скелет» пирамиды тестирования: unit-тесты, интеграционные тесты с PostgreSQL/Testcontainers и локальные e2e/smoke сценарии на Ktor test host. Примеры: `BookingServiceIT`, `GuestListInvitationCheckinE2ETest`, `OperatingRulesResolverIT`, `RatePolicyTest`.
- CI запускает unit + integration контуры с разными профилями (`./gradlew clean test` и `./gradlew test -PrunIT=true`) и публикует артефакты отчётов/JaCoCo, но coverage gate как «порог в %» пока не enforced.
- Критичные сценарии по HOLD/confirm/check-in/финансам/календарю покрыты частично или хорошо, однако именно **гонки под параллельной нагрузкой** (двойной скан, параллельные денежные операции, freeze vs update) в основном закрыты точечно, а не системно.
- Property-based тестов (jqwik/kotest-property) и единого e2e набора по всем 17 блокам не видно; это основной разрыв перед MVP gate.

---

## 1) Что есть сейчас

### 1.1 CI/Gradle тестовый контур

- `build.yml` запускает lint/detekt, unit tests и `clean build`: `.github/workflows/build.yml:52-68`.
- `tests.yml` разделяет unit и integration jobs, для integration использует Postgres service и `-PrunIT=true`: `.github/workflows/tests.yml:254-323`.
- В `app-bot` тесты переключают миграции H2/PG через `runIT`: `app-bot/build.gradle.kts:45-50`.
- В `core-data` и `core-domain` IT выделяются через JUnit tags (`it`) и `runIT` property: `core-data/build.gradle.kts:133-140`, `core-domain/build.gradle.kts:41-49`.

### 1.2 Unit/Integration примеры по критичным зонам

#### Бронирования (HOLD / CONFIRM / идемпотентность)

- Параллельный confirm проверяется отдельным IT: `app-bot/src/test/kotlin/com/example/bot/booking/BookingServiceIT.kt:79-111`.
- Идемпотентность confirm проверяется в том же IT: `app-bot/src/test/kotlin/com/example/bot/booking/BookingServiceIT.kt:114-174`.
- Роуты и security-обвязка booking проверяются в `SecuredBookingRoutesTest`: `app-bot/src/test/kotlin/com/example/bot/routes/SecuredBookingRoutesTest.kt:1-61`.
- Квоты/лимиты для promoter hold/confirm закрыты в `BookingWithQuotaTest`: `app-bot/src/test/kotlin/com/example/bot/routes/BookingWithQuotaTest.kt:69-141`.

#### Check-in (включая повторный скан/дубликат)

- Host-checkin маршруты, RBAC и payload-контракты: `app-bot/src/test/kotlin/com/example/bot/routes/HostCheckinRoutesTest.kt:73-220`.
- Защита от дубликатов на уровне сервиса (already used / unique violation): `core-data/src/test/kotlin/com/example/bot/data/checkin/CheckinServiceTest.kt:93-182`.
- Сквозной e2e сценарий приглашение→check-in: `app-bot/src/test/kotlin/com/example/bot/routes/GuestListInvitationCheckinE2ETest.kt:115-220`.

#### Финансы и денежные операции

- Route-тесты операций стола/депозита (включая повторную посадку): `app-bot/src/test/kotlin/com/example/bot/routes/AdminTableOpsRoutesTest.kt:73-227`.
- Закрытие смены и защита от повторного close: `core-data/src/test/kotlin/com/example/bot/data/finance/ShiftReportRepositoryTest.kt:59-120`.
- Freeze/invalid_state в API при CLOSED отчёте: `app-bot/src/test/kotlin/com/example/bot/routes/AdminFinanceShiftRoutesTest.kt:109-152`.

#### Календарь/время

- Ночи через сутки, override правил и holiday/exception: `core-domain/src/test/kotlin/com/example/bot/time/OperatingRulesResolverTest.kt:16-104`, `core-domain/src/test/kotlin/com/example/bot/time/OperatingRulesResolverIT.kt:16-43`.

#### Коммуникации/лимиты

- Notify routes + campaign lifecycle: `app-bot/src/test/kotlin/com/example/bot/NotifyRoutesTest.kt:56-133`.
- Rate limiting policy покрыт unit-тестом токен-бакетов и 429 cooldown: `core-testing/src/test/kotlin/com/example/bot/notifications/RatePolicyTest.kt:9-32`.
- Outbox admin API/метрики покрыты route-тестами: `app-bot/src/test/kotlin/com/example/bot/routes/OutboxAdminRoutesTest.kt:72-140`, `app-bot/src/test/kotlin/com/example/bot/routes/OutboxAdminMetricsTest.kt:40-155`.

### 1.3 Инфраструктура integration-тестов

- Общий контейнерный PG base: `core-testing/src/test/kotlin/com/example/notifications/support/PgContainer.kt:5-13`.
- Прямое использование `PostgreSQLContainer` в music IT: `core-testing/src/test/kotlin/com/example/bot/music/MigrationTest.kt:15-35`, `core-testing/src/test/kotlin/com/example/bot/music/RepositoryTest.kt:22-84`.

---

## 2) Пробелы и риски покрытия (P0/P1/P2)

| Priority | Пробел | Impact | Где видно |
|---|---|---|---|
| P0 | Нет системного concurrency-suite для гонок HOLD→CONFIRM с несколькими пользователями/таблицами/ретраями | Риск нарушения инварианта «нельзя два HOLD / двойное бронирование» при пике | Есть точечный тест `parallel confirm`, но нет матрицы конфликтов: `BookingServiceIT.kt:79-111` |
| P0 | Нет явных race-тестов «два хоста сканируют одного гостя одновременно» на уровне API+DB | Риск дубля check-in или недетерминированных DENIED/ARRIVED | Сервисная ветка unique violation есть, но без массового parallel сценария: `CheckinServiceTest.kt:160-182` |
| P0 | Freeze после закрытия смены не покрыт параллельным конфликтом close vs update | Риск post-close изменений финансов | Есть sequential проверки invalid_state/second close: `ShiftReportRepositoryTest.kt:59-120`, `AdminFinanceShiftRoutesTest.kt:109-152` |
| P1 | Нет обязательного coverage threshold (jacoco verification gate) | Возможен регресс покрытия без красного CI | В CI есть upload jacoco artifacts, но не gate: `.github/workflows/tests.yml:60-66` |
| P1 | Нет единого e2e-regression набора для 17 блоков по бизнес-инвариантам | Риск «зелёных unit при сломанном пользовательском флоу» | E2E есть точечно (guest list/check-in), не по всем блокам: `GuestListInvitationCheckinE2ETest.kt:115-220` |
| P1 | Не видно pre-commit hooks для локального enforcement test/lint/security | Неоднородное качество до попадания в CI | В репозитории нет `.pre-commit-config.yaml`/`lefthook.yml`/`.husky/` |
| P2 | Нет property-based тестов для календаря/валидаторов/статусных переходов | Слабая проверка крайних комбинаций входных данных | В build/test зависимостях и тестах не видно property framework |
| P2 | Нет сценарных тестов burst-рассылок с проверкой backpressure/очереди | Риск деградации при массовых кампаниях | Есть unit на rate policy, но нет burst integration benchmark: `RatePolicyTest.kt:9-32` |

---

## 3) Test matrix по 17 блокам

Статусы:
- **GREEN** — заметное покрытие уже есть.
- **YELLOW** — частично есть, нужен добор.
- **RED** — почти нет подтверждённых тестов на уровень блока.

| Блок | Текущее состояние | Что покрыто сейчас | Что добавить в план |
|---|---|---|---|
| 1 RBAC | GREEN | Route-level запреты/скоупы в host/admin booking тестах | Matrix по всем ролям + негативные клубные scope-кейсы |
| 2 Календарь | YELLOW | Resolver unit+IT (overnight/holidays/exceptions) | Property-based генерация дат/TZ и cut-off таблицы |
| 3 Витрина | YELLOW | Есть route тесты клубов, но мало UX-контрактов | Контрактные тесты DTO + сортировки/фильтры |
| 4 Бронирование | YELLOW | Hold/confirm/idempotency/quota и часть race | Parallel matrix: two users, same table/night, retries/double-click |
| 5 Mini App UX | YELLOW | Проверки auth headers/route wiring | Snapshot/API-contract тесты fallback-сценариев |
| 6 Вход/check-in | YELLOW | Host routes + duplicate handling + E2E | Параллельный двойной скан (N>=20), search load-tests |
| 7 Столы | YELLOW | Seat/deposit route tests | Конкурентные доплаты/освобождение/stop-sales undo |
| 8 Финансы смены | YELLOW | Close/freeze sequential checks | Race close vs update + reconciliation invariants |
| 9 Геймификация | YELLOW | Есть отдельные route/service тесты | Идемпотентность начислений на повторный check-in |
| 10 Support | YELLOW | Есть support route tests | SLA/статус-машина тикетов и ownership checks |
| 11 Музыка | YELLOW | Есть IT для migration/repository/routes | Лимиты размеров/стриминг/конкурентные лайки |
| 12 Коммуникации | YELLOW | Notify routes + rate policy + outbox admin | Burst campaigns + queue lag + retry/backoff e2e |
| 13 Аналитика | YELLOW | Есть admin analytics route tests | Golden-dataset regression и сверка агрегатов |
| 14 Регламент | RED | Мало/нет формальных тестов чек-листов | Инварианты «одна отметка» + peak protocol сценарии |
| 15 Админ-настройки | YELLOW | Есть admin layout/finance template тесты | Контракт клонирования/шаблонов и audit trails |
| 16 Сбои/fallback | RED | Точечные smoke тесты | Chaos-lite: degraded mode (QR→search, map→list) |
| 17 Безопасность | YELLOW | Есть проверки initData/callback/checkLogsPolicy | Security regression pack: webhook replay/idempotency keys/PII masking |

---

## 4) Минимальный набор тестов для MVP gate

### 4.1 Обязательные сценарии (должны блокировать merge)

1. **Booking race gate**
   - Parallel hold/confirm на один стол/ночь (2, 5, 20 клиентов).
   - Критерий: не более 1 BOOKED, предсказуемые ошибки у конкурентов.

2. **Check-in duplicate gate**
   - Два сотрудника сканируют один QR одновременно.
   - Критерий: ровно 1 ARRIVED, остальные DENIED(ALREADY_*), без дублей в БД.

3. **Finance freeze gate**
   - Одновременные `close shift` и `update draft`.
   - Критерий: после CLOSED любые изменения отклоняются.

4. **Calendar overnight gate**
   - Ночь через сутки + exception + holiday + TZ клуба.
   - Критерий: корректные open/close окна и cut-off.

5. **Comms rate gate**
   - Burst enqueue 1k сообщений в test окружении (без нагрузочного профиля prod).
   - Критерий: входящие booking/check-in маршруты не блокируются, retry policy соблюдается.

### 4.2 Технический gate (CI)

Минимум:
- `./gradlew clean test --console=plain`
- `./gradlew test -PrunIT=true --console=plain`
- `./gradlew detekt ktlintCheck --console=plain`
- `./gradlew :app-bot:checkLogsPolicy --console=plain`

Оптимум:
- добавить jacoco verification (минимальный порог overall + на критичные пакеты);
- nightly job с расширенным concurrency-suite;
- обязательный flaky-retry policy только для сетевых/контейнерных IT, не для unit.

---

## 5) Практический roadmap улучшения качества тестов (без изменения runtime-кода)

1. **Сконцентрировать P0 race-набор** в отдельном test-suite (`@Tag("race")`) для блоков 4/6/8.
2. **Ввести coverage-gate**: минимум 65% overall и отдельные lower bounds для booking/checkin/finance.
3. **Добавить property-based слой** для календаря, валидации payload и status transitions.
4. **Собрать smoke e2e пакет по 17 блокам** (короткий, <10 мин в CI).
5. **Усилить test fixtures**: общие builders и deterministic clock/ids для снижения flaky.

---

## 6) Команды «одной кнопкой» для разработчика

```bash
# Быстрая локальная проверка качества
./gradlew clean test detekt ktlintCheck :app-bot:checkLogsPolicy --console=plain

# Интеграционные тесты (PostgreSQL профиль)
./gradlew test -PrunIT=true --console=plain

# Точечные проверки критичных сценариев
./gradlew :core-data:test --tests com.example.bot.data.checkin.CheckinServiceTest --console=plain
./gradlew :core-testing:test --tests com.example.bot.notifications.RatePolicyTest --console=plain
```

