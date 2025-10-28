package com.example.bot.availability

import com.example.bot.time.Club
import com.example.bot.time.ClubException
import com.example.bot.time.ClubHoliday
import com.example.bot.time.ClubHour
import com.example.bot.time.NightSource
import com.example.bot.time.OperatingRulesResolver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.time.ZoneId

@RequiresDocker
@Tag("it")
@Testcontainers
class AvailabilityCalendarTest {
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
    fun `holiday override and base hours`() {
        PostgreSQLContainer<Nothing>("postgres:15-alpine")
            .use { it.start() }
        val repo =
            object : AvailabilityRepository {
                override suspend fun findClub(clubId: Long) = Club(1, "Europe/Moscow")

                override suspend fun listClubHours(clubId: Long) =
                    listOf(
                        ClubHour(DayOfWeek.FRIDAY, LocalTime.of(22, 0), LocalTime.of(6, 0)),
                        ClubHour(DayOfWeek.SATURDAY, LocalTime.of(22, 0), LocalTime.of(6, 0)),
                    )

                override suspend fun listHolidays(
                    clubId: Long,
                    from: LocalDate,
                    to: LocalDate,
                ) = listOf(
                    ClubHoliday(
                        LocalDate.of(2025, 5, 4),
                        true,
                        LocalTime.of(22, 0),
                        LocalTime.of(3, 0),
                    ),
                )

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
                ) = null

                override suspend fun listTables(clubId: Long) = emptyList<Table>()

                override suspend fun listActiveHoldTableIds(
                    eventId: Long,
                    now: Instant,
                ) = emptySet<Long>()

                override suspend fun listActiveBookingTableIds(eventId: Long) = emptySet<Long>()
            }

        val resolver = OperatingRulesResolver(repo)
        val from = LocalDate.of(2025, 5, 2).atStartOfDay(ZoneId.of("UTC")).toInstant()
        val to = LocalDate.of(2025, 5, 6).atStartOfDay(ZoneId.of("UTC")).toInstant()
        val slots = runBlocking { resolver.resolve(1, from, to) }

        assertEquals(3, slots.size)
        assertAll(
            { assertEquals(NightSource.WEEKEND_RULE, slots[0].source) },
            { assertEquals(NightSource.WEEKEND_RULE, slots[1].source) },
            { assertEquals(NightSource.HOLIDAY, slots[2].source) },
        )
        assertEquals(LocalTime.of(22, 0), slots[2].openLocal.toLocalTime())
        assertEquals(LocalTime.of(3, 0), slots[2].closeLocal.toLocalTime())
    }

    @Test
    fun `exception closes night`() {
        PostgreSQLContainer<Nothing>("postgres:15-alpine")
            .use { it.start() }
        val repo =
            object : AvailabilityRepository {
                override suspend fun findClub(clubId: Long) = Club(1, "Europe/Moscow")

                override suspend fun listClubHours(clubId: Long) =
                    listOf(
                        ClubHour(DayOfWeek.SATURDAY, LocalTime.of(22, 0), LocalTime.of(6, 0)),
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
                ) = listOf(
                    ClubException(LocalDate.of(2025, 5, 3), false, null, null),
                )

                override suspend fun listEvents(
                    clubId: Long,
                    from: Instant,
                    to: Instant,
                ) = emptyList<com.example.bot.time.Event>()

                override suspend fun findEvent(
                    clubId: Long,
                    startUtc: Instant,
                ) = null

                override suspend fun listTables(clubId: Long) = emptyList<Table>()

                override suspend fun listActiveHoldTableIds(
                    eventId: Long,
                    now: Instant,
                ) = emptySet<Long>()

                override suspend fun listActiveBookingTableIds(eventId: Long) = emptySet<Long>()
            }
        val resolver = OperatingRulesResolver(repo)
        val from = LocalDate.of(2025, 5, 2).atStartOfDay(ZoneId.of("UTC")).toInstant()
        val to = LocalDate.of(2025, 5, 5).atStartOfDay(ZoneId.of("UTC")).toInstant()
        val slots = runBlocking { resolver.resolve(1, from, to) }
        assertEquals(0, slots.size)
    }
}
