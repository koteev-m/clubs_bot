package com.example.bot.routes

import com.example.bot.data.finance.ShiftReportRepository
import com.example.bot.data.finance.WorkingNightShiftMaintenance
import com.example.bot.workers.launchWorkingNightMaintenance
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

open class WorkingNightShiftMaintenanceTest : ClientOnboardingRoutesTestBase() {
    @Test
    fun `restart recovers missed shifts once without opening a future or unprepared night`() =
        database { source ->
            runBlocking {
                source.seedClub(CLUB)
                source.seedClub(OTHER_CLUB)
                source.markWorkingClub(CLUB)
                source.seedNight(CLUB, 9_111, "2030-05-13", prepared = true)
                source.seedNight(CLUB, 9_112, "2030-05-14", prepared = true)
                source.seedNight(CLUB, 9_113, "2030-05-15", prepared = true)
                source.seedNight(OTHER_CLUB, 9_114, "2030-05-14", prepared = false)
                source.execute("UPDATE clubs SET timezone='Europe/Moscow' WHERE id=$CLUB")
                val repository = ShiftReportRepository(Database.connect(source), clock)
                assertEquals(null, repository.getByClubAndNight(CLUB, nightStart("2030-05-15")))
                val first = WorkingNightShiftMaintenance(source, clock)
                val afterRestart = WorkingNightShiftMaintenance(source, clock)
                listOf(async { first.refresh() }, async { afterRestart.refresh() }).awaitAll()

                assertEquals("2", source.scalar("SELECT COUNT(*) FROM shift_reports"))
                assertEquals("2", source.scalar("SELECT COUNT(*) FROM audit_log WHERE action='AUTO_OPEN'"))
                val missed = checkNotNull(repository.getByClubAndNight(CLUB, nightStart("2030-05-13")))
                assertTrue(repository.close(missed.id, CLUB, clock.instant()))

                WorkingNightShiftMaintenance(source, clock).refresh()
                assertEquals(
                    "CLOSED",
                    source.scalar("SELECT status FROM shift_reports WHERE id=${missed.id}"),
                )
                assertEquals("2", source.scalar("SELECT COUNT(*) FROM shift_reports"))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM shift_reports WHERE status='DRAFT'"))
                assertEquals("2", source.scalar("SELECT COUNT(*) FROM audit_log WHERE action='AUTO_OPEN'"))
            }
        }

    @Test
    fun `application start opens the current shift without a request`() =
        database { source ->
            source.seedClub(CLUB)
            source.markWorkingClub(CLUB)
            source.execute("UPDATE client_working_clubs SET scheduled_through='2030-05-14' WHERE club_id=$CLUB")
            source.seedNight(CLUB, 9_111, "2030-05-14", prepared = true)
            testApplication {
                application { launchWorkingNightMaintenance(source, clock) }
                startApplication()
                withTimeout(5_000) {
                    while (source.scalar("SELECT COUNT(*) FROM shift_reports") == "0") delay(10)
                }
            }
            assertEquals("1", source.scalar("SELECT COUNT(*) FROM audit_log WHERE action='AUTO_OPEN'"))
        }

    @Test
    fun `audit failure rolls back one night while other shifts open and retry recovers it`() =
        database { source ->
            runBlocking {
                source.seedClub(CLUB)
                source.markWorkingClub(CLUB)
                source.seedNight(CLUB, 9_111, "2030-05-13", prepared = true)
                source.seedNight(CLUB, 9_112, "2030-05-14", prepared = true)
                source.execute(
                    "ALTER TABLE audit_log ADD CONSTRAINT reject_autostart " +
                        "CHECK (action <> 'AUTO_OPEN' OR night_id <> 9111)",
                )
                val maintenance = WorkingNightShiftMaintenance(source, clock)
                val reports = ShiftReportRepository(Database.connect(source), clock)
                assertFailsWith<SQLException> { maintenance.refresh() }
                assertEquals(null, reports.getByClubAndNight(CLUB, nightStart("2030-05-13")))
                assertEquals("DRAFT", reports.getByClubAndNight(CLUB, nightStart("2030-05-14"))?.status?.name)
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM audit_log WHERE action='AUTO_OPEN'"))
                source.execute("ALTER TABLE audit_log DROP CONSTRAINT reject_autostart")

                maintenance.refresh()
                assertEquals("2", source.scalar("SELECT COUNT(*) FROM shift_reports WHERE status='DRAFT'"))
                assertEquals("2", source.scalar("SELECT COUNT(*) FROM audit_log WHERE action='AUTO_OPEN'"))
            }
        }

    private fun DataSource.seedClub(id: Long) {
        execute("INSERT INTO clients(id,display_name,is_active) VALUES ($id,'Synthetic business',TRUE)")
        execute("INSERT INTO clubs(id,name,timezone,is_active) VALUES ($id,'Synthetic club','UTC',FALSE)")
        execute("INSERT INTO client_clubs(client_id,club_id) VALUES ($id,$id)")
        execute("INSERT INTO users(id,telegram_user_id) VALUES ($id,${100 + id})")
        execute(
            "INSERT INTO tables(id,club_id,table_number,capacity,min_deposit,active) VALUES ($id,$id,1,4,0,TRUE)",
        )
        execute(
            "INSERT INTO halls(id,club_id,name,geometry_json,geometry_fingerprint) VALUES ($id,$id,'Hall','{}','h')",
        )
        execute("INSERT INTO hall_zones(hall_id,zone_id,name) VALUES ($id,'main','Main')")
        execute(
            "INSERT INTO hall_tables(id,hall_id,table_number,label,capacity,zone_id," +
                "booking_table_id,booking_club_id) VALUES ($id,$id,1,'Table',4,'main',$id,$id)",
        )
    }

    private fun DataSource.markWorkingClub(id: Long) {
        execute("INSERT INTO client_working_clubs(club_id,client_id) VALUES ($id,$id)")
    }

    private fun DataSource.seedNight(
        club: Long,
        night: Long,
        date: String,
        prepared: Boolean,
    ) {
        val start = nightStart(date)
        val end = Instant.parse("${date}T22:00:00Z")
        execute("INSERT INTO events(id,club_id,start_at,end_at) VALUES ($night,$club,'$start','$end')")
        execute(
            "INSERT INTO club_operational_nights(id,client_id,club_id,business_date,timezone," +
                "start_at,end_at,source,status,created_at,updated_at) VALUES " +
                "($night,$club,$club,'$date','UTC','$start','$end','EXCEPTION','SCHEDULED','$start','$start')",
        )
        execute(
            "INSERT INTO canonical_night_event_links(club_id,canonical_night_id,event_id," +
                "legacy_start_at,legacy_end_at) VALUES ($club,$night,$night,'$start','$end')",
        )
        if (prepared) {
            execute("INSERT INTO client_working_night_events(club_id,night_id,event_id) VALUES ($club,$night,$night)")
            execute(
                "INSERT INTO booking_cutover_fences(club_id,canonical_night_id,booking_table_id) " +
                    "VALUES ($club,$night,$club)",
            )
        }
    }

    private fun DataSource.execute(statement: String) {
        connection.use { connection -> connection.createStatement().use { it.executeUpdate(statement) } }
    }

    private companion object {
        const val CLUB = 9_101L
        const val OTHER_CLUB = 9_102L
        val clock: Clock = Clock.fixed(Instant.parse("2030-05-14T21:00:00Z"), ZoneOffset.UTC)

        fun nightStart(date: String): Instant = Instant.parse("${date}T20:00:00Z")
    }
}

@Tag("it")
class WorkingNightShiftMaintenancePostgresIT : WorkingNightShiftMaintenanceTest() {
    override fun databaseAt(
        target: String?,
        block: (DataSource) -> Unit,
    ) = withClientManagerPostgres(target, block)
}
