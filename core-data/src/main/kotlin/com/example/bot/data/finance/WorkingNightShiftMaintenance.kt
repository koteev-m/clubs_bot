package com.example.bot.data.finance

import com.example.bot.data.booking.core.lockBookingAdmission
import com.example.bot.data.client.onboarding.OnboardingSql
import com.example.bot.data.client.onboarding.automaticWorkingClub
import com.example.bot.data.client.onboarding.instant
import com.example.bot.data.client.onboarding.lockLinkedCanonicalNight
import com.example.bot.data.client.onboarding.long
import com.example.bot.data.client.onboarding.updateLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

/** Opens shifts for prepared nights that have started, including those missed during downtime. */
class WorkingNightShiftMaintenance(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun refresh(): Unit =
        withContext(dispatcher) {
            val horizon = clock.instant()
            var afterNightId = 0L
            var failure: SQLException? = null
            do {
                val candidates = dueNights(horizon, afterNightId)
                candidates.forEach { candidate ->
                    ensureActive()
                    try {
                        open(candidate)
                    } catch (candidateFailure: SQLException) {
                        // The candidate has rolled back. Continue other nights before reporting the failed tick.
                        if (failure == null) failure = candidateFailure
                    }
                }
                afterNightId = candidates.lastOrNull()?.nightId ?: afterNightId
            } while (candidates.size == BATCH_SIZE)
            failure?.let { throw it }
        }

    private fun dueNights(
        now: Instant,
        afterNightId: Long,
    ): List<DueNight> =
        dataSource.connection.use { connection ->
            val sql = OnboardingSql(connection)
            if (!sql.workingOperationsSchema) return@use emptyList()
            sql
                .rows(
                    """
                    SELECT n.id,n.client_id,n.club_id,n.start_at
                    FROM client_working_night_events w
                    JOIN club_operational_nights n ON n.id=w.night_id AND n.club_id=w.club_id
                    JOIN clients c ON c.id=n.client_id
                    WHERE n.start_at<=? AND n.id>? AND n.status='SCHEDULED'
                      AND c.is_active=TRUE AND c.archived_at IS NULL
                      AND EXISTS (SELECT 1 FROM booking_cutover_fences f
                          WHERE f.club_id=w.club_id AND f.canonical_night_id=w.night_id)
                      AND NOT EXISTS (SELECT 1 FROM shift_reports s
                          WHERE s.club_id=n.club_id AND s.night_start_utc=n.start_at)
                    ORDER BY n.id LIMIT $BATCH_SIZE
                    """.trimIndent(),
                    now,
                    afterNightId,
                ).map { DueNight(it.long("id"), it.long("client_id"), it.long("club_id"), it.instant("start_at")) }
        }

    private fun open(candidate: DueNight) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            var committed = false
            try {
                openLocked(OnboardingSql(connection), candidate)
                connection.commit()
                committed = true
            } finally {
                // Closing an unfinished JDBC transaction has driver-specific behavior.
                // Roll back explicitly and leave committed work unchanged.
                if (!committed) connection.rollback()
            }
        }
    }

    private fun openLocked(
        sql: OnboardingSql,
        candidate: DueNight,
    ) {
        val owner =
            sql.row(
                "SELECT id FROM clients WHERE id=? AND is_active=TRUE AND archived_at IS NULL" + sql.updateLock(),
                candidate.clientId,
            )
        if (owner == null || !sql.lockBookingAdmission(candidate.clubId)) return
        val club = sql.row("SELECT id FROM clubs WHERE id=?" + sql.updateLock(), candidate.clubId)
        if (club != null) sql.openPreparedShift(candidate.clubId, candidate.start, clock.instant())
    }

    private data class DueNight(
        val nightId: Long,
        val clientId: Long,
        val clubId: Long,
        val start: Instant,
    )

    private companion object {
        const val BATCH_SIZE = 100
    }
}

/** Caller locks the client, booking admission and club in that order, before the shift row. */
internal fun OnboardingSql.openPreparedShift(
    clubId: Long,
    start: Instant,
    now: Instant,
): Boolean {
    if (start > now) return false
    return preparedShiftNight(clubId, start)?.let { night ->
        insertPreparedShift(clubId, night, start, now)
        true
    } ?: false
}

private fun OnboardingSql.preparedShiftNight(
    clubId: Long,
    start: Instant,
): PreparedShiftNight? =
    row(
        """
        SELECT n.id,n.client_id,n.timezone,w.event_id
        FROM client_working_night_events w
        JOIN club_operational_nights n ON n.id=w.night_id AND n.club_id=w.club_id
        JOIN clients c ON c.id=n.client_id
        WHERE w.club_id=? AND n.start_at=? AND n.status='SCHEDULED'
          AND c.is_active=TRUE AND c.archived_at IS NULL
          AND EXISTS (SELECT 1 FROM booking_cutover_fences f
              WHERE f.club_id=w.club_id AND f.canonical_night_id=w.night_id)
        """.trimIndent(),
        clubId,
        start,
    )?.takeIf { automaticWorkingClub(it.long("client_id"), clubId) }?.let { candidate ->
        val eventId = candidate.long("event_id")
        lockLinkedCanonicalNight(clubId, eventId, candidate["timezone"])
            ?.takeIf { it.id == candidate.long("id") && it.legacyStart == start }
            ?.let { PreparedShiftNight(it.id, eventId) }
    }

private fun OnboardingSql.insertPreparedShift(
    clubId: Long,
    night: PreparedShiftNight,
    start: Instant,
    now: Instant,
) {
    val conflict =
        if (connection.metaData.databaseProductName == "PostgreSQL") {
            " ON CONFLICT (club_id,night_start_utc) DO NOTHING"
        } else {
            " ON CONFLICT DO NOTHING"
        }
    val inserted =
        update(
            "INSERT INTO shift_reports(club_id,night_start_utc,status,created_at,updated_at) " +
                "VALUES (?,?,'DRAFT',?,?)" + conflict,
            clubId,
            start,
            now,
            now,
        )
    if (inserted == 1) {
        val report =
            checkNotNull(row("SELECT id FROM shift_reports WHERE club_id=? AND night_start_utc=?", clubId, start))
        update(
            """
            INSERT INTO audit_log(created_at,club_id,night_id,entity_type,entity_id,action,fingerprint,metadata_json)
            VALUES (?,?,?,'SHIFT_REPORT',?,'AUTO_OPEN',?,'{}')
            """.trimIndent(),
            now,
            clubId,
            night.eventId,
            report.long("id"),
            "client-finance-auto-open-$clubId-${night.id}",
        )
    }
}

private data class PreparedShiftNight(
    val id: Long,
    val eventId: Long,
)
