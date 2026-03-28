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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals

class AvailabilityServiceTest {
    private class FakeRepo : AvailabilityRepository {
        var findEventCalls = 0
        var findClubCalls = 0
        var listTablesCalls = 0
        var listHoldCalls = 0
        var listBookingCalls = 0

        override suspend fun findClub(clubId: Long) =
            Club(clubId, "Europe/Berlin").also { findClubCalls += 1 }

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
        ) =
            Event(
                id = 42,
                clubId = clubId,
                startUtc = startUtc,
                endUtc = startUtc.plus(Duration.ofHours(8)),
                isSpecial = true,
            ).also { findEventCalls += 1 }

        override suspend fun listTables(clubId: Long) =
            listOf(
                Table(id = 10, number = "10", zone = "Main", capacity = 4, minDeposit = 1000, active = true),
            ).also { listTablesCalls += 1 }

        override suspend fun listActiveHoldTableIds(
            eventId: Long,
            now: Instant,
        ) = emptySet<Long>().also { listHoldCalls += 1 }

        override suspend fun listActiveBookingTableIds(eventId: Long) = emptySet<Long>().also { listBookingCalls += 1 }
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

    @Test
    fun `list open nights cache stores full list and applies limit on response`() =
        runTest {
            val clock = MutableClock(Instant.parse("2024-03-28T12:00:00Z"), ZoneOffset.UTC)
            val repo = FakeRepo()
            val registry = SimpleMeterRegistry()
            val service =
                AvailabilityService(
                    repository = repo,
                    rulesResolver = OperatingRulesResolver(repo, clock),
                    cutoffPolicy = CutoffPolicy(),
                    clock = clock,
                    nightsTtl = Duration.ofSeconds(10),
                    meterRegistry = registry,
                )

            val first = service.listOpenNights(1, limit = 1)
            val second = service.listOpenNights(1, limit = 2)

            assertEquals(1, first.size)
            assertEquals(2, second.size)
            assertEquals(1.0, registry.get("availability.cache.miss").tag("operation", "nights").counter().count())
            assertEquals(1.0, registry.get("availability.cache.hit").tag("operation", "nights").counter().count())
            assertEquals(1L, registry.get("availability.load.latency").tag("operation", "nights").timer().count())
        }

    @Test
    fun `list free tables uses ttl cache and metrics`() =
        runTest {
            val clock = MutableClock(Instant.parse("2024-03-28T12:00:00Z"), ZoneOffset.UTC)
            val repo = FakeRepo()
            val registry = SimpleMeterRegistry()
            val service =
                AvailabilityService(
                    repository = repo,
                    rulesResolver = OperatingRulesResolver(repo, clock),
                    cutoffPolicy = CutoffPolicy(),
                    clock = clock,
                    tablesTtl = Duration.ofSeconds(10),
                    meterRegistry = registry,
                )
            val startUtc = Instant.parse("2024-03-29T22:00:00Z")

            service.listFreeTables(1, startUtc)
            service.listFreeTables(1, startUtc)

            assertEquals(1, repo.findClubCalls)
            assertEquals(1, repo.findEventCalls)
            assertEquals(1, repo.listTablesCalls)
            assertEquals(1.0, registry.get("availability.cache.miss").tag("operation", "tables").counter().count())
            assertEquals(1.0, registry.get("availability.cache.hit").tag("operation", "tables").counter().count())
            assertEquals(1L, registry.get("availability.load.latency").tag("operation", "tables").timer().count())
        }

    @Test
    fun `invalidating tables cache forces repository reload`() =
        runTest {
            val clock = MutableClock(Instant.parse("2024-03-28T12:00:00Z"), ZoneOffset.UTC)
            val repo = FakeRepo()
            val service =
                AvailabilityService(
                    repository = repo,
                    rulesResolver = OperatingRulesResolver(repo, clock),
                    cutoffPolicy = CutoffPolicy(),
                    clock = clock,
                    tablesTtl = Duration.ofSeconds(10),
                )
            val startUtc = Instant.parse("2024-03-29T22:00:00Z")

            service.listFreeTables(1, startUtc)
            service.invalidateTables(1, startUtc)
            service.listFreeTables(1, startUtc)

            assertEquals(2, repo.findClubCalls)
            assertEquals(2, repo.findEventCalls)
            assertEquals(2, repo.listTablesCalls)
        }

    private class MutableClock(
        private var current: Instant,
        private val zoneId: ZoneId,
    ) : Clock() {
        override fun getZone(): ZoneId = zoneId

        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}
