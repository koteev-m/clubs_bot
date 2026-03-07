CREATE OR REPLACE FUNCTION prevent_table_deposit_mutation_after_shift_close(
    p_club_id BIGINT,
    p_night_start_utc TIMESTAMPTZ
) RETURNS VOID AS
$$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM shift_reports sr
        WHERE sr.club_id = p_club_id
          AND sr.night_start_utc = p_night_start_utc
          AND sr.status = 'CLOSED'
    ) THEN
        RAISE EXCEPTION 'shift_report_closed'
            USING ERRCODE = 'P0001',
                  DETAIL = format('club_id=%s night_start_utc=%s', p_club_id, p_night_start_utc);
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION trg_guard_table_deposits_shift_close()
RETURNS TRIGGER AS
$$
BEGIN
    PERFORM prevent_table_deposit_mutation_after_shift_close(NEW.club_id, NEW.night_start_utc);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION trg_guard_table_deposit_allocations_shift_close()
RETURNS TRIGGER AS
$$
DECLARE
    v_club_id BIGINT;
    v_night_start_utc TIMESTAMPTZ;
BEGIN
    SELECT td.club_id, td.night_start_utc
    INTO v_club_id, v_night_start_utc
    FROM table_deposits td
    WHERE td.id = COALESCE(NEW.deposit_id, OLD.deposit_id)
    LIMIT 1;

    IF v_club_id IS NOT NULL AND v_night_start_utc IS NOT NULL THEN
        PERFORM prevent_table_deposit_mutation_after_shift_close(v_club_id, v_night_start_utc);
    END IF;

    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_table_deposits_shift_close ON table_deposits;
CREATE TRIGGER trg_guard_table_deposits_shift_close
    BEFORE INSERT OR UPDATE OR DELETE ON table_deposits
    FOR EACH ROW
EXECUTE FUNCTION trg_guard_table_deposits_shift_close();

DROP TRIGGER IF EXISTS trg_guard_table_deposit_allocations_shift_close ON table_deposit_allocations;
CREATE TRIGGER trg_guard_table_deposit_allocations_shift_close
    BEFORE INSERT OR UPDATE OR DELETE ON table_deposit_allocations
    FOR EACH ROW
EXECUTE FUNCTION trg_guard_table_deposit_allocations_shift_close();
