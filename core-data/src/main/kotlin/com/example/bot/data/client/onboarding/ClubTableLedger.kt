package com.example.bot.data.client.onboarding

import com.example.bot.data.finance.openPreparedShift
import java.time.Instant
import java.util.UUID

/** Called on the onboarding connection after locking the same shift row as ShiftReportRepository.close. */
internal class ClubTableLedger(
    private val session: OnboardingSession,
    private val clubId: Long,
    private val start: Instant,
) {
    private val sql get() = session.sql
    private val actor get() = checkNotNull(session.userId)

    fun lockOpenShift() {
        val shift =
            lockedShift() ?: run {
                if (sql.workingOperationsSchema &&
                    sql.openPreparedShift(clubId, start, session.now)
                ) {
                    lockedShift()
                } else {
                    null
                }
            }
        if (shift?.get("status") != "DRAFT") {
            fail(if (shift == null) "shift_not_open" else "shift_report_closed")
        }
    }

    private fun lockedShift(): DbRow? =
        sql.row(
            "SELECT status FROM shift_reports WHERE club_id=? AND night_start_utc=? FOR UPDATE",
            clubId,
            start,
        )

    fun capturedBookingPayments(bookingId: UUID): List<DbRow> =
        sql.rows(
            "SELECT p.id,p.amount_minor FROM payments p WHERE p.booking_id=? AND p.status='CAPTURED' " +
                "AND p.amount_minor>0 AND NOT EXISTS (SELECT 1 FROM table_deposit_operations o WHERE " +
                "o.payment_id=p.id) ORDER BY p.id",
            bookingId,
        )

    fun operations(
        sessionId: Long?,
        bookingId: UUID?,
    ): List<DbRow> =
        sql.rows(
            "SELECT o.id,o.amount_minor,o.type,o.received_method,o.received_cash_minor,o.received_card_minor," +
                "o.session_id FROM table_deposit_operations o " +
                "JOIN table_deposits d ON d.id=o.deposit_id AND d.club_id=o.club_id AND " +
                "d.table_session_id=o.session_id " +
                "WHERE o.club_id=? AND o.night_start_utc=? AND (o.session_id=? OR d.booking_id=?) ORDER BY o.id",
            clubId,
            start,
            sessionId,
            bookingId,
        )

    fun record(
        tableId: Long,
        sessionId: Long,
        bookingId: UUID?,
        payment: RecordedPayment,
        topUp: Boolean,
    ): Long {
        val existing =
            sql.row(
                "SELECT id FROM table_deposits WHERE club_id=? AND night_start_utc=? AND " +
                    "table_session_id=? AND table_id=? ORDER BY id LIMIT 1",
                clubId,
                start,
                sessionId,
                tableId,
            )
        val depositId =
            existing?.long("id") ?: sql.insert(
                "INSERT INTO table_deposits(club_id,night_start_utc,table_id," +
                    "table_session_id,booking_id,amount_minor," +
                    "created_at,created_by,updated_at,updated_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
                clubId,
                start,
                tableId,
                sessionId,
                bookingId,
                if (topUp) 0L else payment.amount,
                session.now,
                actor,
                session.now,
                actor,
            )
        val operationId =
            sql.insert(
                "INSERT INTO table_deposit_operations(deposit_id,session_id,club_id," +
                    "night_start_utc,type,amount_minor," +
                    "created_at,actor_id,received_method,received_cash_minor,received_card_minor) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                depositId,
                sessionId,
                clubId,
                start,
                if (topUp) "TOPUP" else "INITIAL",
                payment.amount,
                session.now,
                actor,
                payment.method,
                payment.cashMinor,
                payment.cardMinor,
            )
        payment.parts.forEach { part ->
            sql.update(
                "INSERT INTO table_deposit_operation_allocations(operation_id,category_code," +
                    "amount_minor,category_name_snapshot) " +
                    "VALUES (?,?,?,?)",
                operationId,
                part.code,
                part.amount,
                part.name,
            )
        }
        return operationId
    }
}
