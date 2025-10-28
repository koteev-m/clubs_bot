package com.example.bot.availability

import com.example.bot.policy.CutoffPolicy
import com.example.bot.time.Club
import com.example.bot.time.ClubException
import com.example.bot.time.ClubHoliday
import com.example.bot.time.ClubHour
import com.example.bot.time.OperatingRulesResolver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import testing.RequiresDocker
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.ceil

@RequiresDocker
@Tag("it")
@Testcontainers
class AvailabilityPerfTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun assumeDocker() {
            val dockerAvailable =
                try {
                    DockerClientFactory.instance().client()
                    true
                } catch (_: Throwable) {
                    false
                }
            assumeTrue(dockerAvailable, "Docker is not available on this host; skipping IT.")
        }
    }

    @Test
    fun `performance within limits`() {
        PostgreSQLContainer<Nothing>("postgres:15-alpine")
            .use { it.start() }
        val eventStart = Instant.parse("2025-05-02T19:00:00Z")
        val eventEnd = eventStart.plusSeconds(6 * 3600)
        val tables =
            (1..500).map { id ->
                Table(id.toLong(), "T$id", "Z", 4, 100, true)
            }

        val repo =
            object : AvailabilityRepository {
                override suspend fun findClub(clubId: Long) = Club(1, "Europe/Moscow")

                override suspend fun listClubHours(clubId: Long) =
                    listOf(
                        ClubHour(DayOfWeek.FRIDAY, LocalTime.of(22, 0), LocalTime.of(6, 0)),
                    )

                override suspend fun listHolidays(
                    clubId: Long,
                    from: LocalDate,
                    to: LocalDate,
                ) = emptyList<ClubHoliday>()

                override suspend fun listExceptions(
                    clubId: Long,
                    from: LocalDate,
                    to: LocalDate,
                ) = emptyList<ClubException>()

                override suspend fun listEvents(
                    clubId: Long,
                    from: Instant,
                    to: Instant,
                ) = emptyList<com.example.bot.time.Event>()

                override suspend fun findEvent(
                    clubId: Long,
                    startUtc: Instant,
                ) = com.example.bot.time.Event(
                    1,
                    1,
                    eventStart,
                    eventEnd,
                )

                override suspend fun listTables(clubId: Long) = tables

                override suspend fun listActiveHoldTableIds(
                    eventId: Long,
                    now: Instant,
                ) = emptySet<Long>()

                override suspend fun listActiveBookingTableIds(eventId: Long) = emptySet<Long>()
            }

        val resolver = OperatingRulesResolver(repo)
        val service = AvailabilityService(repo, resolver, CutoffPolicy())

        val durations = mutableListOf<Long>()
        repeat(20) {
            val start = System.nanoTime()
            runBlocking { service.listFreeTables(1, eventStart) }
            val end = System.nanoTime()
            durations += (end - start) / 1_000_000 // ms
        }

        val sorted = durations.sorted()
        val index = ceil(sorted.size * 0.95).toInt() - 1
        val p95 = sorted[index]
        assertTrue(p95 < 30, "p95=$p95 ms")
    }
}
