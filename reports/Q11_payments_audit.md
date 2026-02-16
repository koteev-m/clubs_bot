# Q11 — Payments Audit (Bot Payments: idempotency, pre_checkout, reconciliation)

## Scope
Аудит выполнен **без изменений кода**.
Проверены:
- создание инвойса (сумма/валюта/metadata);
- `pre_checkout_query` (повторные проверки);
- `successful_payment` (идемпотентность/дубли);
- cancel/refund и связь с HOLD;
- reconciliation: платеж ↔ бронь ↔ деньги/депозиты ↔ смена;
- разделение «офлайн услуга» vs «цифровая».

---

## 1) Карта платежного флоу (as-is)

## 1.1 Invoice / initiation
1. `POST /api/confirm` вызывает `booking.payments.PaymentsService.startConfirmation(...)`.
2. Для `PROVIDER_DEPOSIT` и `STARS_DIGITAL` создаётся запись `payments` со статусом `INITIATED`.
3. Возвращается `PendingPayment(invoice)` с `invoiceId/payload/totalMinor/currency`.

Факты:
- payload инвойса сейчас = random UUID (без явной доменной metadata типа club/table/booking).
- `bookingId` в `createInitiated(...)` здесь пишется `null`.

## 1.2 Telegram Bot Payments handlers
Есть отдельный `PaymentsHandlers`:
- `sendInvoice(...)` формирует `SendInvoice`;
- `handlePreCheckout(...)` всегда отвечает `ok` (без повторной бизнес-проверки);
- `handleSuccessfulPayment(...)` ищет payment по payload и ставит `CAPTURED`.

## 1.3 Finalize/cancel/refund HTTP
Есть сервисные роуты:
- finalize: `/api/clubs/{clubId}/bookings/finalize`;
- cancel/refund: `/api/clubs/{clubId}/bookings/{bookingId}/cancel|refund`.

Внутри:
- idempotency через `Idempotency-Key` + `payments.idempotency_key` / `payment_actions.idempotency_key`;
- `refund`/`cancel` пишут action-лог в `payment_actions`.

## 1.4 Reconciliation
Модель БД допускает связь `table_deposits.payment_id -> payments.id` (nullable FK) и агрегации по ночи/смене.
Это теоретическая база для reconciliation, но фактическая склейка не везде принудительная.

---

## 2) Проверка по чек-листу

## 2.1 Создание инвойса (сумма/валюта/metadata)
### Что хорошо
- В `booking.payments.PaymentsService` сумма считается детерминированно:
  - provider: `minDeposit * guests * 100` в minor;
  - stars: `minDeposit * guests` в `XTR` (без *100).
- Валюта выставляется явно (`policy.currency` / `XTR`).

### Проблемы
- Metadata в payload минимальная (UUID), без защищённой привязки к booking/table/club.
- При initiate платежа `bookingId = null`, что ослабляет сквозную трассируемость платежа к брони на старте.

## 2.2 `pre_checkout_query` (повторная проверка стола/суммы)
- В `handlePreCheckout` нет повторной валидации доступности стола/актуальности суммы/статуса HOLD.
- Всегда отправляется `AnswerPreCheckoutQuery(query.id())` без `errorMessage`.

**Вывод:** критичный пробел в anti-race/anti-stale проверках перед списанием.

## 2.3 `successful_payment` (идемпотентность и дубли)
### Что есть
- По payload ищется запись и ставится `CAPTURED`.
- На уровне polling есть dedup update-id.

### Риски
- Нет проверки provider `charge_id`/`telegram_payment_charge_id` как уникального ключа против дублей.
- В demo-варианте `external_id` генерируется random UUID при каждом успешном апдейте.
- Повторный `successful_payment` может перезаписать `external_id` (без детерминированной защиты).

## 2.4 Рефанды/отмена/снятие HOLD при провале оплаты
### Что есть
- `cancel/refund` имеют idempotency по `Idempotency-Key` и `payment_actions`.
- Есть worker refund outbox (поддерживает `payment.refunded` и `booking.cancelled`).

### Пробелы
- В `pre_checkout`/`successful_payment` ветке нет явного сценария «оплата провалилась → release HOLD».
- `cancel/refund` путь не показывает обязательной интеграции с refund outbox event (`payment.refunded`) в одном транзакционном потоке.
- Реальный provider refund асинхронно есть, но склейка «refund request ↔ booking/payment state machine» не полная.

## 2.5 Reconciliation: payment ↔ booking ↔ money ops ↔ shift
### Что есть
- Схема поддерживает `payments`, `payment_actions`, а также `table_deposits.payment_id`.
- В инвариантах описан reconciliation через `payment_id` при наличии значения.

### Чего не хватает
- Нет строгого требования, что каждый платёжный capture обязательно связывается с `table_deposits.payment_id`.
- В finalize-сервисе есть запись в `payments` с `amount_minor=0` и `currency="N/A"` для miniapp-finalize, что затрудняет финансовую сверку.
- Статус `NO_PAYMENT` возвращается кодом finalize, но не входит в DB check-constraint статусов таблицы `payments`.

## 2.6 Offline vs digital
- В доменной модели есть разделение `PROVIDER_DEPOSIT` vs `STARS_DIGITAL`.
- Но end-to-end enforcement (разные правила reconciliation, разные обязательные поля внешнего чека, отчётность по смене) реализован частично.

---

## 3) Риски потери денег / двойного списания

## P0
1. **`pre_checkout_query` без повторной проверки бизнес-инвариантов** (стол/HOLD/сумма/доступность). Возможен чардж по устаревшему состоянию.
2. **Отсутствие устойчивой идемпотентности по провайдерскому charge-id в `successful_payment`**: нет уникальной защиты от повторных provider updates.
3. **Несоответствие finalize-статусов и DB-check (`NO_PAYMENT` не разрешён в `payments.status`)** — риск падений/рассинхронизации статуса.

## P1
1. **Слабая metadata payload (UUID без доменной подписи/контекста)** — сложнее forensic и anti-fraud.
2. **Неполная сквозная склейка `payments ↔ table_deposits ↔ shift report`** — reconciliation зависит от nullable-связей и дисциплины интеграции.
3. **`amount_minor=0`/`currency=N/A` в miniapp finalize** ухудшает финансовую верификацию и отчётность.
4. **Маршрутизация webhook/polling платежных апдейтов не выглядит полностью подключённой к прод-роутингу callback-ов** (риск «интеграция есть в коде, но не в runtime-цепочке»).

---

## 4) Что отсутствует

1. Обязательная pre-checkout валидация:
   - table/HOLD ещё активны;
   - сумма/валюта совпадают с server-side расчётом;
   - booking не ушёл в конфликтный статус.

2. Идемпотентность по provider charge ID:
   - unique индекс/таблица receipt;
   - reject/ignore duplicate provider updates.

3. Единый state machine для платежа:
   - INITIATED → PENDING → CAPTURED/DECLINED → REFUNDED;
   - без ad-hoc статусов вне DB constraints.

4. Жёсткая reconciliation-цепочка:
   - обязательный link capture -> `table_deposits.payment_id`;
   - автоматические отчёты расхождений `payments` vs `table_deposits` vs `shift_reports`.

5. Чёткое разделение offline/digital в учёте:
   - отдельные обязательные поля чека/внешнего ID;
   - отдельные правила попадания в «общую выручку».

6. Явная эксплуатационная документация по Bot Payments callback pipeline:
   - где именно обрабатываются `pre_checkout_query` и `successful_payment` в runtime;
   - какие retry/idempotency guarantees.

---

## 5) Короткий итог
Платёжная подсистема в репозитории частично реализована и содержит важные кирпичи (idempotency-key, payment_actions, refund worker, schema для reconciliation), но для production-уровня Bot Payments не хватает критичных защит на pre-checkout/successful_payment и строгой сквозной сверки денег с операционными/сменными сущностями.
