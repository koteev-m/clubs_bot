ALTER TABLE "${flyway:defaultSchema}".payment_actions
    ADD COLUMN IF NOT EXISTS refund_fingerprint_version integer,
    ADD COLUMN IF NOT EXISTS refund_request_mode varchar(32),
    ADD COLUMN IF NOT EXISTS refund_request_amount_minor bigint,
    ADD COLUMN IF NOT EXISTS refund_result_amount_minor bigint,
    ADD COLUMN IF NOT EXISTS refund_source_kind varchar(32);

ALTER TABLE "${flyway:defaultSchema}".payment_actions
    ADD CONSTRAINT payment_actions_refund_fingerprint_check
    CHECK (
        CASE
            WHEN refund_fingerprint_version IS NULL THEN
                refund_request_mode IS NULL
                AND refund_request_amount_minor IS NULL
            WHEN action = 'REFUND'
                AND refund_fingerprint_version = 1
                AND refund_request_mode = 'EXPLICIT'
                THEN refund_request_amount_minor IS NOT NULL
                    AND refund_request_amount_minor >= 0
            WHEN action = 'REFUND'
                AND refund_fingerprint_version = 1
                AND refund_request_mode = 'ALL_REMAINING'
                THEN refund_request_amount_minor IS NULL
            ELSE false
        END
    ),
    ADD CONSTRAINT payment_actions_refund_result_amount_check
    CHECK (
        refund_result_amount_minor IS NULL
        OR (
            action = 'REFUND'
            AND status = 'OK'
            AND refund_result_amount_minor >= 0
        )
    ),
    ADD CONSTRAINT payment_actions_refund_source_kind_check
    CHECK (
        CASE
            WHEN refund_source_kind IS NULL THEN
                refund_result_amount_minor IS NULL
                OR refund_result_amount_minor = 0
            WHEN refund_source_kind = 'ATOMIC_ACTION' THEN
                action = 'REFUND'
                AND status = 'OK'
                AND refund_result_amount_minor IS NOT NULL
                AND refund_result_amount_minor > 0
                AND refund_fingerprint_version IS NOT NULL
                AND refund_fingerprint_version = 1
            WHEN refund_source_kind = 'LEGACY_ACTION' THEN
                action = 'REFUND'
                AND status = 'OK'
                AND refund_result_amount_minor IS NOT NULL
                AND refund_result_amount_minor > 0
                AND refund_fingerprint_version IS NULL
                AND refund_request_mode IS NULL
                AND refund_request_amount_minor IS NULL
            ELSE false
        END
    ),
    ADD CONSTRAINT payment_actions_typed_refund_terminal_check
    CHECK (
        CASE
            WHEN action = 'REFUND'
                AND refund_fingerprint_version IS NOT NULL
                AND status = 'OK'
                THEN refund_result_amount_minor IS NOT NULL
                    AND refund_result_amount_minor >= 0
                    AND (
                        (
                            refund_result_amount_minor = 0
                            AND refund_source_kind IS NULL
                        )
                        OR (
                            refund_result_amount_minor > 0
                            AND refund_source_kind = 'ATOMIC_ACTION'
                        )
                    )
            ELSE true
        END
    ),
    ADD CONSTRAINT payment_actions_refund_source_tuple_unique
    UNIQUE (id, booking_id, refund_source_kind, action, status, refund_result_amount_minor);

ALTER TABLE "${flyway:defaultSchema}".payments
    ADD CONSTRAINT payments_refund_source_tuple_unique
    UNIQUE (id, booking_id, status, amount_minor);

CREATE INDEX IF NOT EXISTS payments_booking_idx
    ON "${flyway:defaultSchema}".payments (booking_id);

CREATE TABLE "${flyway:defaultSchema}".booking_refund_reconciliation (
    booking_id uuid PRIMARY KEY,
    has_atomic_action_source boolean NOT NULL DEFAULT false,
    has_legacy_action_source boolean NOT NULL DEFAULT false,
    has_payment_status_source boolean NOT NULL DEFAULT false,
    blocked_reason varchar(64),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT booking_refund_reconciliation_reason_check
        CHECK (
            blocked_reason IS NULL
            OR blocked_reason IN (
                'MALFORMED_LEGACY_REFUND_ACTION',
                'INVALID_REFUNDED_PAYMENT_AMOUNT',
                'AMBIGUOUS_LEGACY_REFUND_SOURCES'
            )
        )
);

CREATE TABLE "${flyway:defaultSchema}".payment_refunds (
    id bigserial PRIMARY KEY,
    booking_id uuid NOT NULL,
    source_kind varchar(32) NOT NULL,
    action_id bigint,
    source_payment_id uuid,
    source_action text,
    source_status text NOT NULL,
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT payment_refunds_source_shape_check
        CHECK ((
            (
                source_kind IN ('ATOMIC_ACTION', 'LEGACY_ACTION')
                AND action_id IS NOT NULL
                AND source_payment_id IS NULL
                AND source_action IS NOT NULL
                AND source_action = 'REFUND'
                AND source_status = 'OK'
            )
            OR (
                source_kind = 'PAYMENT_STATUS'
                AND action_id IS NULL
                AND source_payment_id IS NOT NULL
                AND source_action IS NULL
                AND source_status = 'REFUNDED'
            )
        ) IS TRUE),
    CONSTRAINT payment_refunds_action_source_fk
        FOREIGN KEY (
            action_id,
            booking_id,
            source_kind,
            source_action,
            source_status,
            amount_minor
        )
        REFERENCES "${flyway:defaultSchema}".payment_actions (
            id,
            booking_id,
            refund_source_kind,
            action,
            status,
            refund_result_amount_minor
        ),
    CONSTRAINT payment_refunds_payment_source_fk
        FOREIGN KEY (source_payment_id, booking_id, source_status, amount_minor)
        REFERENCES "${flyway:defaultSchema}".payments (id, booking_id, status, amount_minor)
);

CREATE UNIQUE INDEX payment_refunds_action_idx
    ON "${flyway:defaultSchema}".payment_refunds (action_id)
    WHERE action_id IS NOT NULL;

CREATE UNIQUE INDEX payment_refunds_source_payment_idx
    ON "${flyway:defaultSchema}".payment_refunds (source_payment_id)
    WHERE source_payment_id IS NOT NULL;

CREATE INDEX payment_refunds_booking_idx
    ON "${flyway:defaultSchema}".payment_refunds (booking_id);

CREATE OR REPLACE FUNCTION "${flyway:defaultSchema}".payment_refund_block_booking(
    target_booking_id uuid,
    target_reason varchar
) RETURNS void
LANGUAGE plpgsql
SET search_path = pg_catalog, "${flyway:defaultSchema}"
AS $$
BEGIN
    INSERT INTO "${flyway:defaultSchema}".booking_refund_reconciliation AS reconciliation (
        booking_id,
        blocked_reason,
        updated_at
    ) VALUES (
        target_booking_id,
        target_reason,
        CURRENT_TIMESTAMP
    )
    ON CONFLICT (booking_id) DO UPDATE
    SET blocked_reason = COALESCE(
            reconciliation.blocked_reason,
            EXCLUDED.blocked_reason
        ),
        updated_at = CURRENT_TIMESTAMP;
END;
$$;

CREATE OR REPLACE FUNCTION "${flyway:defaultSchema}".payment_refund_prepare_legacy_action()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, "${flyway:defaultSchema}"
AS $$
BEGIN
    IF NEW.action = 'REFUND'
        AND NEW.status = 'OK'
        AND NEW.refund_fingerprint_version IS NULL
        AND NEW.refund_request_mode IS NULL
        AND NEW.refund_request_amount_minor IS NULL
        AND NEW.refund_source_kind IS NULL
    THEN
        IF NEW.reason ~ '^-?[0-9]{1,19}$' THEN
            IF NEW.reason::numeric <= 0 THEN
                NEW.refund_result_amount_minor := NULL;
                NEW.refund_source_kind := NULL;
            ELSIF NEW.reason::numeric <= 9223372036854775807 THEN
                NEW.refund_result_amount_minor := NEW.reason::bigint;
                NEW.refund_source_kind := 'LEGACY_ACTION';
            ELSE
                NEW.refund_result_amount_minor := NULL;
                NEW.refund_source_kind := NULL;
            END IF;
        ELSE
            NEW.refund_result_amount_minor := NULL;
            NEW.refund_source_kind := NULL;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_actions_prepare_legacy_refund
BEFORE INSERT OR UPDATE ON "${flyway:defaultSchema}".payment_actions
FOR EACH ROW
EXECUTE FUNCTION "${flyway:defaultSchema}".payment_refund_prepare_legacy_action();

CREATE OR REPLACE FUNCTION "${flyway:defaultSchema}".payment_refund_track_source()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, "${flyway:defaultSchema}"
AS $$
BEGIN
    INSERT INTO "${flyway:defaultSchema}".booking_refund_reconciliation AS reconciliation (
        booking_id,
        has_atomic_action_source,
        has_legacy_action_source,
        has_payment_status_source,
        updated_at
    ) VALUES (
        NEW.booking_id,
        NEW.source_kind = 'ATOMIC_ACTION',
        NEW.source_kind = 'LEGACY_ACTION',
        NEW.source_kind = 'PAYMENT_STATUS',
        CURRENT_TIMESTAMP
    )
    ON CONFLICT (booking_id) DO UPDATE
    SET blocked_reason = COALESCE(
            reconciliation.blocked_reason,
            CASE
                WHEN NEW.source_kind = 'LEGACY_ACTION'
                    AND reconciliation.has_payment_status_source
                    THEN 'AMBIGUOUS_LEGACY_REFUND_SOURCES'
                WHEN NEW.source_kind = 'PAYMENT_STATUS'
                    AND (
                        reconciliation.has_legacy_action_source
                        OR reconciliation.has_atomic_action_source
                    )
                    THEN 'AMBIGUOUS_LEGACY_REFUND_SOURCES'
                ELSE NULL
            END
        ),
        has_atomic_action_source =
            reconciliation.has_atomic_action_source
            OR EXCLUDED.has_atomic_action_source,
        has_legacy_action_source =
            reconciliation.has_legacy_action_source
            OR EXCLUDED.has_legacy_action_source,
        has_payment_status_source =
            reconciliation.has_payment_status_source
            OR EXCLUDED.has_payment_status_source,
        updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_refunds_track_source
AFTER INSERT ON "${flyway:defaultSchema}".payment_refunds
FOR EACH ROW
EXECUTE FUNCTION "${flyway:defaultSchema}".payment_refund_track_source();

CREATE OR REPLACE FUNCTION "${flyway:defaultSchema}".payment_refund_record_action_source()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, "${flyway:defaultSchema}"
AS $$
BEGIN
    IF NEW.action = 'REFUND'
        AND NEW.status = 'OK'
        AND NEW.refund_source_kind IN ('ATOMIC_ACTION', 'LEGACY_ACTION')
        AND NEW.refund_result_amount_minor > 0
    THEN
        INSERT INTO "${flyway:defaultSchema}".payment_refunds (
            booking_id,
            source_kind,
            action_id,
            source_action,
            source_status,
            amount_minor,
            created_at
        ) VALUES (
            NEW.booking_id,
            NEW.refund_source_kind,
            NEW.id,
            'REFUND',
            'OK',
            NEW.refund_result_amount_minor,
            NEW.created_at
        )
        ON CONFLICT DO NOTHING;
        IF NOT EXISTS (
            SELECT 1
            FROM "${flyway:defaultSchema}".payment_refunds
            WHERE action_id = NEW.id
              AND booking_id = NEW.booking_id
              AND source_kind = NEW.refund_source_kind
              AND source_payment_id IS NULL
              AND source_action = 'REFUND'
              AND source_status = 'OK'
              AND amount_minor = NEW.refund_result_amount_minor
        ) THEN
            RAISE EXCEPTION 'terminal refund action has no matching ledger row'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSIF NEW.action = 'REFUND'
        AND NEW.status = 'OK'
        AND NEW.refund_fingerprint_version IS NULL
        AND NEW.refund_request_mode IS NULL
        AND NEW.refund_request_amount_minor IS NULL
        AND NEW.refund_result_amount_minor IS NULL
    THEN
        PERFORM "${flyway:defaultSchema}".payment_refund_block_booking(
            NEW.booking_id,
            'MALFORMED_LEGACY_REFUND_ACTION'
        );
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_actions_record_refund_source
AFTER INSERT OR UPDATE ON "${flyway:defaultSchema}".payment_actions
FOR EACH ROW
EXECUTE FUNCTION "${flyway:defaultSchema}".payment_refund_record_action_source();

CREATE OR REPLACE FUNCTION "${flyway:defaultSchema}".payment_refund_reject_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, "${flyway:defaultSchema}"
AS $$
BEGIN
    RAISE EXCEPTION 'payment_refunds is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER trg_payment_refunds_append_only
BEFORE UPDATE OR DELETE ON "${flyway:defaultSchema}".payment_refunds
FOR EACH ROW
EXECUTE FUNCTION "${flyway:defaultSchema}".payment_refund_reject_mutation();

CREATE OR REPLACE FUNCTION "${flyway:defaultSchema}".payment_refund_record_payment_status()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, "${flyway:defaultSchema}"
AS $$
BEGIN
    IF NEW.status = 'REFUNDED'
        AND NEW.booking_id IS NOT NULL
        AND (
            TG_OP = 'INSERT'
            OR OLD.status IS DISTINCT FROM 'REFUNDED'
        )
    THEN
        IF NEW.amount_minor <= 0 THEN
            PERFORM "${flyway:defaultSchema}".payment_refund_block_booking(
                NEW.booking_id,
                'INVALID_REFUNDED_PAYMENT_AMOUNT'
            );
        ELSE
            INSERT INTO "${flyway:defaultSchema}".payment_refunds (
                booking_id,
                source_kind,
                source_payment_id,
                source_status,
                amount_minor,
                created_at
            ) VALUES (
                NEW.booking_id,
                'PAYMENT_STATUS',
                NEW.id,
                'REFUNDED',
                NEW.amount_minor,
                CURRENT_TIMESTAMP
            )
            ON CONFLICT DO NOTHING;
            IF NOT EXISTS (
                SELECT 1
                FROM "${flyway:defaultSchema}".payment_refunds
                WHERE source_payment_id = NEW.id
                  AND booking_id = NEW.booking_id
                  AND source_kind = 'PAYMENT_STATUS'
                  AND action_id IS NULL
                  AND source_action IS NULL
                  AND source_status = 'REFUNDED'
                  AND amount_minor = NEW.amount_minor
            ) THEN
                RAISE EXCEPTION 'refunded payment has no matching ledger row'
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payments_record_refunded_status
AFTER INSERT OR UPDATE OF status ON "${flyway:defaultSchema}".payments
FOR EACH ROW
EXECUTE FUNCTION "${flyway:defaultSchema}".payment_refund_record_payment_status();

UPDATE "${flyway:defaultSchema}".payment_actions
SET refund_result_amount_minor = reason::bigint,
    refund_source_kind = 'LEGACY_ACTION'
WHERE action = 'REFUND'
  AND status = 'OK'
  AND refund_fingerprint_version IS NULL
  AND refund_request_mode IS NULL
  AND refund_request_amount_minor IS NULL
  AND CASE
      WHEN reason ~ '^-?[0-9]{1,19}$'
          THEN CASE
              WHEN reason::numeric <= 0 THEN false
              ELSE reason::numeric <= 9223372036854775807
          END
      ELSE false
  END;

INSERT INTO "${flyway:defaultSchema}".booking_refund_reconciliation AS reconciliation (
    booking_id,
    blocked_reason
)
SELECT DISTINCT
    booking_id,
    'MALFORMED_LEGACY_REFUND_ACTION'
FROM "${flyway:defaultSchema}".payment_actions
WHERE action = 'REFUND'
  AND status = 'OK'
  AND refund_fingerprint_version IS NULL
  AND refund_request_mode IS NULL
  AND refund_request_amount_minor IS NULL
  AND refund_result_amount_minor IS NULL
ON CONFLICT (booking_id) DO UPDATE
SET blocked_reason = COALESCE(
        reconciliation.blocked_reason,
        EXCLUDED.blocked_reason
    ),
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO "${flyway:defaultSchema}".payment_refunds (
    booking_id,
    source_kind,
    source_payment_id,
    source_status,
    amount_minor,
    created_at
)
SELECT
    booking_id,
    'PAYMENT_STATUS',
    id,
    'REFUNDED',
    amount_minor,
    created_at
FROM "${flyway:defaultSchema}".payments
WHERE status = 'REFUNDED'
  AND booking_id IS NOT NULL
  AND amount_minor > 0
ON CONFLICT DO NOTHING;

INSERT INTO "${flyway:defaultSchema}".booking_refund_reconciliation AS reconciliation (
    booking_id,
    blocked_reason
)
SELECT DISTINCT
    booking_id,
    'INVALID_REFUNDED_PAYMENT_AMOUNT'
FROM "${flyway:defaultSchema}".payments
WHERE status = 'REFUNDED'
  AND booking_id IS NOT NULL
  AND amount_minor <= 0
ON CONFLICT (booking_id) DO UPDATE
SET blocked_reason = COALESCE(
        reconciliation.blocked_reason,
        EXCLUDED.blocked_reason
    ),
    updated_at = CURRENT_TIMESTAMP;
