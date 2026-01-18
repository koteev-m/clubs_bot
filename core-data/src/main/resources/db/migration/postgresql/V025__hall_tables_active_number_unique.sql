CREATE UNIQUE INDEX ux_hall_tables_active_number
    ON hall_tables (hall_id, table_number)
    WHERE is_active = true;
