package com.example.bot.time

import com.example.bot.availability.AvailabilityRepository
import com.example.bot.availability.Table
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class OperatingRulesResolverTest {
    private val zoneId = ZoneId.of("Europe/Moscow")
    private val testClock = Clock.fixed(Instant.parse("2025-04-30T00:00:00Z"), ZoneId.of("UTC"))

    @Test
    fun `holiday inherits boundary from exception`() {
        val date = LocalDate.of(2025, 5, 4)
        val repo =
            FakeAvailabilityRepository(
                club = Club(1, zoneId.id),
                hours = listOf(ClubHour(DayOfWeek.SUNDAY, LocalTime.of(22, 0), LocalTime.of(6, 0))),
                exceptions = listOf(ClubException(date, true, null, LocalTime.of(4, 0))),
                holidays = listOf(ClubHoliday(date, true, null, LocalTime.of(3, 0))),
            )

        val slots = resolve(repo, date, date)

        assertEquals(1, slots.size)
        val slot = slots.single()
        assertEquals(NightSource.HOLIDAY, slot.source)
        assertEquals(LocalTime.of(22, 0), slot.openLocal.toLocalTime())
        assertEquals(LocalTime.of(3, 0), slot.closeLocal.toLocalTime())
    }

    @Test
    fun `holiday opens night without base hours`() {
        val date = LocalDate.of(2025, 5, 1)
        val repo =
            FakeAvailabilityRepository(
                club = Club(1, zoneId.id),
                holidays =
                    listOf(
                        ClubHoliday(date, true, LocalTime.of(18, 0), LocalTime.of(23, 0)),
                    ),
            )

        val slots = resolve(repo, date, date)

        assertEquals(1, slots.size)
        val slot = slots.single()
        assertEquals(NightSource.HOLIDAY, slot.source)
        assertEquals(LocalTime.of(18, 0), slot.openLocal.toLocalTime())
        assertEquals(LocalTime.of(23, 0), slot.closeLocal.toLocalTime())
    }

    @Test
    fun `holiday with single override and no base keeps night closed`() {
        val date = LocalDate.of(2025, 5, 2)
        val repo =
            FakeAvailabilityRepository(
                club = Club(1, zoneId.id),
                holidays =
                    listOf(
                        ClubHoliday(date, true, LocalTime.of(18, 0), null),
                    ),
            )

        val slots = resolve(repo, date, date)

        assertTrue(slots.isEmpty())
    }

    @Test
    fun `overnight window closes on next day`() {
        val date = LocalDate.of(2025, 5, 3)
        val repo =
            FakeAvailabilityRepository(
                club = Club(1, zoneId.id),
                hours = listOf(ClubHour(DayOfWeek.SATURDAY, LocalTime.of(22, 0), LocalTime.of(4, 0))),
            )

        val slots = resolve(repo, date, date)

        val slot = slots.single()
        assertEquals(date.plusDays(1), slot.closeLocal.toLocalDate())
        assertTrue(slot.eventEndUtc.isAfter(slot.eventStartUtc))
    }

    private fun resolve(
        repository: AvailabilityRepository,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): List<NightSlot> {
        val resolver = OperatingRulesResolver(repository, testClock)
        val from = fromDate.minusDays(1).atStartOfDay(zoneId).toInstant()
        val to = toDate.plusDays(1).atStartOfDay(zoneId).toInstant()
        return runBlocking { resolver.resolve(1, from, to) }
    }
}

private class FakeAvailabilityRepository(
    private val club: Club,
    private val hours: List<ClubHour> = emptyList(),
    private val holidays: List<ClubHoliday> = emptyList(),
    private val exceptions: List<ClubException> = emptyList(),
) : AvailabilityRepository {
    override suspend fun findClub(clubId: Long): Club? = club

    override suspend fun listClubHours(clubId: Long): List<ClubHour> = hours

    override suspend fun listHolidays(
        clubId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<ClubHoliday> = holidays.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }

    override suspend fun listExceptions(
        clubId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<ClubException> = exceptions.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }

    override suspend fun listEvents(
        clubId: Long,
        from: Instant,
        to: Instant,
    ): List<Event> = emptyList()

    override suspend fun findEvent(
        clubId: Long,
        startUtc: Instant,
    ): Event? = null

    override suspend fun listTables(clubId: Long): List<Table> = emptyList()

    override suspend fun listActiveHoldTableIds(
        eventId: Long,
        now: Instant,
    ): Set<Long> = emptySet()

    override suspend fun listActiveBookingTableIds(eventId: Long): Set<Long> = emptySet()
}
