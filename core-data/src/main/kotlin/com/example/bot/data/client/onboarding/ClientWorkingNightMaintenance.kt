package com.example.bot.data.client.onboarding

import com.example.bot.data.booking.core.lockBookingAdmission
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Clock
import java.time.ZoneId
import javax.sql.DataSource

/** Keeps the saved calendar's existing rolling horizon available without any staff activation action. */
class ClientWorkingNightMaintenance(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun refresh(): Unit =
        withContext(dispatcher) {
            val clubs =
                dataSource.connection.use { connection ->
                    val sql = OnboardingSql(connection)
                    if (!sql.workingOperationsSchema) return@withContext
                    sql.workingClubCandidates()
                }
            clubs.forEach { refreshClub(it.long("client_id"), it.long("club_id")) }
        }

    private fun refreshClub(
        clientId: Long,
        clubId: Long,
    ) {
        try {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    refreshLocked(OnboardingSql(connection), clientId, clubId)
                    connection.commit()
                } finally {
                    // No-op after commit; prevents any partial work on close/pool return.
                    connection.rollback()
                }
            }
        } catch (blocked: OnboardingFailure) {
            log.debug("Cannot roll prepared calendar: {}", blocked.code)
        } catch (_: SQLException) {
            log.warn("Could not roll prepared calendar due to a database error")
        }
    }

    private fun refreshLocked(
        sql: OnboardingSql,
        clientId: Long,
        clubId: Long,
    ) {
        val session = OnboardingSession(sql, OnboardingActor(0, "maintenance"), null, null, clock, "calendar-roll")
        val client = session.client(clientId, lock = true)
        if (!client.active() || client["archived_at"] != null || !sql.lockBookingAdmission(clubId)) return
        val club = session.calendarClub(clubId, lock = true)
        if (club.active()) return
        if (sql.automaticWorkingClub(clientId, clubId)) {
            rollSavedCalendar(session, clientId, clubId, club)
        } else {
            PreexistingClientWorkingClub(sql, clientId, clubId).adopt(session.now)
        }
    }

    private fun rollSavedCalendar(
        session: OnboardingSession,
        clientId: Long,
        clubId: Long,
        club: DbRow,
    ) {
        val sql = session.sql
        val today = session.now.atZone(ZoneId.of(checkNotNull(club["timezone"]))).toLocalDate()
        val marker =
            sql.row(
                "SELECT scheduled_through FROM client_working_clubs WHERE client_id=? AND club_id=?",
                clientId,
                clubId,
            )
        if (marker != null && marker["scheduled_through"] != today.toString()) {
            ClubCalendarOperations(session).rollWorkingCalendar(clientId, clubId, club)
        }
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(ClientWorkingNightMaintenance::class.java)
    }
}
