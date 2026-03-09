CREATE OR REPLACE FUNCTION trg_prevent_table_deposit_rewrite()
RETURNS TRIGGER AS
$$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        RAISE EXCEPTION 'table_deposit_immutable'
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_table_deposit_rewrite ON table_deposits;
CREATE TRIGGER trg_prevent_table_deposit_rewrite
    BEFORE UPDATE OR DELETE ON table_deposits
    FOR EACH ROW
EXECUTE FUNCTION trg_prevent_table_deposit_rewrite();

CREATE OR REPLACE FUNCTION trg_guard_table_deposit_operations_shift_close()
RETURNS TRIGGER AS
$$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        RAISE EXCEPTION 'table_deposit_operation_immutable'
            USING ERRCODE = 'P0001';
    END IF;

    PERFORM prevent_table_deposit_mutation_after_shift_close(NEW.club_id, NEW.night_start_utc);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_table_deposit_operations_shift_close ON table_deposit_operations;
CREATE TRIGGER trg_guard_table_deposit_operations_shift_close
    BEFORE INSERT OR UPDATE OR DELETE ON table_deposit_operations
    FOR EACH ROW
EXECUTE FUNCTION trg_guard_table_deposit_operations_shift_close();

CREATE OR REPLACE FUNCTION trg_guard_table_deposit_operation_allocations_shift_close()
RETURNS TRIGGER AS
$$
DECLARE
    v_club_id BIGINT;
    v_night_start_utc TIMESTAMPTZ;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        RAISE EXCEPTION 'table_deposit_operation_allocation_immutable'
            USING ERRCODE = 'P0001';
    END IF;

    SELECT tdo.club_id, tdo.night_start_utc
    INTO v_club_id, v_night_start_utc
    FROM table_deposit_operations tdo
    WHERE tdo.id = NEW.operation_id
    LIMIT 1;

    IF v_club_id IS NOT NULL AND v_night_start_utc IS NOT NULL THEN
        PERFORM prevent_table_deposit_mutation_after_shift_close(v_club_id, v_night_start_utc);
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_table_deposit_operation_allocations_shift_close ON table_deposit_operation_allocations;
CREATE TRIGGER trg_guard_table_deposit_operation_allocations_shift_close
    BEFORE INSERT OR UPDATE OR DELETE ON table_deposit_operation_allocations
    FOR EACH ROW
EXECUTE FUNCTION trg_guard_table_deposit_operation_allocations_shift_close();
