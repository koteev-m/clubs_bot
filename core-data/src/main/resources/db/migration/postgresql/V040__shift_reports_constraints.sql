DROP INDEX IF EXISTS idx_shift_reports_club_night;

ALTER TABLE shift_reports
    ADD CONSTRAINT chk_shift_reports_people_women_non_negative CHECK (people_women >= 0);

ALTER TABLE shift_reports
    ADD CONSTRAINT chk_shift_reports_people_men_non_negative CHECK (people_men >= 0);

ALTER TABLE shift_reports
    ADD CONSTRAINT chk_shift_reports_people_rejected_non_negative CHECK (people_rejected >= 0);

ALTER TABLE shift_report_bracelets
    ADD CONSTRAINT chk_shift_report_bracelets_count_non_negative CHECK (count >= 0);

ALTER TABLE shift_report_revenue_entries
    ADD CONSTRAINT chk_shift_report_revenue_entries_amount_minor_non_negative CHECK (amount_minor >= 0);
