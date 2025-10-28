package com.example.bot.time

import com.example.bot.availability.AvailabilityRepository
import com.example.bot.availability.Table
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Tag("it")
class OperatingRulesResolverIT {
    private val zoneId = ZoneId.of("Europe/Moscow")
    private val testClock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneId.of("UTC"))

    @Test
    fun `holiday inherits from exception and stays open overnight`() {
        val targetDate = LocalDate.of(2025, 1, 7)
        val repository =
            ItAvailabilityRepository(
                club = Club(1, zoneId.id),
                hours = listOf(ClubHour(DayOfWeek.TUESDAY, LocalTime.of(18, 0), LocalTime.of(23, 0))),
                exceptions = listOf(ClubException(targetDate, true, LocalTime.of(19, 0), null)),
                holidays = listOf(ClubHoliday(targetDate, true, null, LocalTime.of(2, 0))),
            )

        val resolver = OperatingRulesResolver(repository, testClock)
        val from = targetDate.minusDays(1).atStartOfDay(zoneId).toInstant()
        val to = targetDate.plusDays(1).atStartOfDay(zoneId).toInstant()

        val slots = runBlocking { resolver.resolve(1, from, to) }

        val slot = slots.single { it.source == NightSource.HOLIDAY }
        assertEquals(targetDate, slot.openLocal.toLocalDate(), "slot starts on target date")
        assertEquals(LocalTime.of(19, 0), slot.openLocal.toLocalTime(), "exception overrides open")
        assertEquals(LocalTime.of(2, 0), slot.closeLocal.toLocalTime(), "holiday overrides close")
        assertEquals(targetDate.plusDays(1), slot.closeLocal.toLocalDate(), "slot closes next day")
    }
}

private class ItAvailabilityRepository(
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
