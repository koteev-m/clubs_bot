package com.example.availability

import com.example.bot.availability.AvailabilityRepository
import com.example.bot.availability.AvailabilityService
import com.example.bot.availability.Table
import com.example.bot.policy.CutoffPolicy
import com.example.bot.time.Club
import com.example.bot.time.ClubException
import com.example.bot.time.ClubHoliday
import com.example.bot.time.ClubHour
import com.example.bot.time.Event
import com.example.bot.time.OperatingRulesResolver
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertEquals

class AvailabilityServiceTest {
    private class FakeRepo : AvailabilityRepository {
        override suspend fun findClub(clubId: Long) = Club(clubId, "Europe/Berlin")

        override suspend fun listClubHours(clubId: Long) =
            listOf(
                ClubHour(DayOfWeek.FRIDAY, LocalTime.of(22, 0), LocalTime.of(6, 0)),
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
        ) = listOf(ClubException(LocalDate.of(2024, 3, 29), false, null, null))

        override suspend fun listEvents(
            clubId: Long,
            from: Instant,
            to: Instant,
        ) = emptyList<Event>()

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

    @Test
    fun `should list upcoming nights skipping closed and handle DST`() =
        runTest {
            val clock = Clock.fixed(Instant.parse("2024-03-28T12:00:00Z"), ZoneOffset.UTC)
            val repo = FakeRepo()
            val resolver = OperatingRulesResolver(repo, clock)
            val service = AvailabilityService(repo, resolver, CutoffPolicy(), clock)

            val nights = service.listOpenNights(1, limit = 2)
            assertEquals(2, nights.size)

            val first = nights[0]
            assertEquals(LocalDateTime.of(2024, 3, 30, 22, 0), first.openLocal)
            assertEquals(LocalDateTime.of(2024, 3, 31, 6, 0), first.closeLocal)
            assertEquals(Duration.ofHours(7), Duration.between(first.eventStartUtc, first.eventEndUtc))

            val second = nights[1]
            assertEquals(LocalDateTime.of(2024, 4, 5, 22, 0), second.openLocal)
            assertEquals(LocalDateTime.of(2024, 4, 6, 6, 0), second.closeLocal)
        }
}
