-- core-data/src/main/resources/db/migration/postgresql/V4__payments.sql

-- Целевая схема payments:
--  id uuid (gen_random_uuid()), booking_id uuid, provider text, currency varchar(8),
--  amount_minor bigint NOT NULL,
--  status text CHECK ('INITIATED','PENDING','CAPTURED','REFUNDED','DECLINED'),
--  payload text UNIQUE NOT NULL,
--  external_id text NULL,
--  idempotency_key text UNIQUE NOT NULL,
--  created_at timestamptz DEFAULT now(), updated_at timestamptz DEFAULT now()

-- 1) Если таблицы нет (чистая база, минуя V1) — создаём сразу «правильную» схему
CREATE TABLE IF NOT EXISTS payments (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id         uuid NULL,
    provider           text NOT NULL,
    currency           varchar(8) NOT NULL,
    amount_minor       bigint NOT NULL,
    status             text NOT NULL CHECK (status IN ('INITIATED','PENDING','CAPTURED','REFUNDED','DECLINED')),
    payload            text NOT NULL UNIQUE,
    external_id        text NULL,
    idempotency_key    text NOT NULL UNIQUE,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);

-- 2) Если таблица уже существовала из V1__init.sql — мигрируем до целевой схемы

-- 2.1 Добавляем недостающие колонки (IF NOT EXISTS гарантирует идемпотентность)
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS amount_minor bigint,
    ADD COLUMN IF NOT EXISTS payload text,
    ADD COLUMN IF NOT EXISTS created_at timestamptz,
    ADD COLUMN IF NOT EXISTS updated_at timestamptz;

-- 2.2 Заполняем amount_minor из старого amount (если старый столбец есть и новый пуст)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'payments' AND column_name = 'amount'
    ) THEN
        UPDATE payments
        SET amount_minor = COALESCE(amount_minor, (ROUND((amount::numeric) * 100))::bigint)
        WHERE amount_minor IS NULL;

        -- После переноса можно безопасно убрать старый amount
        ALTER TABLE payments DROP COLUMN amount;
    END IF;
END$$;

-- 2.3 Статус: обновляем CHECK-constraint, чтобы включал 'PENDING'
ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_status_check;
ALTER TABLE payments
    ADD CONSTRAINT payments_status_check
    CHECK (status IN ('INITIATED','PENDING','CAPTURED','REFUNDED','DECLINED'));

-- 2.4 Дефолты и NOT NULL там, где ждёт код
ALTER TABLE payments
    ALTER COLUMN amount_minor SET NOT NULL;

-- payload требуется кодом как NOT NULL + UNIQUE
ALTER TABLE payments
    ALTER COLUMN payload SET NOT NULL;

-- 2.5 Уникальные ключи/индексы (идемпотентно)
-- idempotency_key уже мог быть UNIQUE в V1; на всякий случай создадим индекс, если его нет
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE schemaname = 'public'
          AND indexname = 'uq_payments_idempotency_key'
    ) THEN
        CREATE UNIQUE INDEX uq_payments_idempotency_key ON payments (idempotency_key);
    END IF;
END$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE schemaname = 'public'
          AND indexname = 'uq_payments_payload'
    ) THEN
        CREATE UNIQUE INDEX uq_payments_payload ON payments (payload);
    END IF;
END$$;

-- created_at / updated_at — дефолты now() и NOT NULL для совместимости
ALTER TABLE payments
    ALTER COLUMN created_at SET DEFAULT now(),
    ALTER COLUMN updated_at SET DEFAULT now();

UPDATE payments
SET created_at = COALESCE(created_at, now()),
    updated_at = COALESCE(updated_at, now());

ALTER TABLE payments
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL;