package com.example.bot.promoter

import com.example.bot.clubs.Event
import com.example.bot.clubs.InMemoryEventsRepository
import com.example.bot.promoter.invites.InMemoryPromoterInviteRepository
import com.example.bot.promoter.invites.PromoterInvite
import com.example.bot.promoter.invites.PromoterInviteStatus
import com.example.bot.promoter.rating.PromoterPeriod
import com.example.bot.promoter.rating.PromoterRatingService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PromoterRatingTest {
    private val now = Instant.parse("2024-06-08T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private data class Fixture(
        val invites: InMemoryPromoterInviteRepository,
        val events: InMemoryEventsRepository,
        val service: PromoterRatingService,
    )

    @Test
    fun `scorecard aggregates week window`() {
        val fixture = fixture()

        val scorecard = fixture.service.scorecardForPromoter(1, PromoterPeriod.WEEK, now)

        assertEquals("week", scorecard.period)
        assertEquals(6, scorecard.invited)
        assertEquals(3, scorecard.arrivals)
        assertEquals(2, scorecard.noShows)
        assertEquals(0.6, scorecard.conversionScore, 1e-9)
    }

    @Test
    fun `month window includes older events`() {
        val fixture = fixture()

        val weekScorecard = fixture.service.scorecardForPromoter(1, PromoterPeriod.WEEK, now)
        val monthScorecard = fixture.service.scorecardForPromoter(1, PromoterPeriod.MONTH, now)

        assertEquals(6, weekScorecard.invited)
        assertEquals(10, monthScorecard.invited)
        assertEquals(3, weekScorecard.arrivals)
        assertEquals(7, monthScorecard.arrivals)
        assertEquals(2, weekScorecard.noShows)
        assertEquals(2, monthScorecard.noShows)
        assertEquals(0.6, weekScorecard.conversionScore, 1e-9)
        assertEquals(7.0 / 9.0, monthScorecard.conversionScore, 1e-9)
    }

    @Test
    fun `rating sorts by conversion then arrivals then promoterId`() {
        val fixture = fixture()

        val rating = fixture.service.ratingForClub(clubId = 1, period = PromoterPeriod.WEEK, page = 1, size = 10, now = now)

        assertEquals(7, rating.total)
        assertEquals(listOf(2L, 1L, 4L, 5L, 6L, 7L, 3L), rating.items.map { it.promoterId })
        assertEquals(1.0, rating.items[0].conversionScore, 1e-9)
        assertEquals(0.6, rating.items[1].conversionScore, 1e-9)
        assertEquals(5, rating.items[2].arrivals)
        assertEquals(3, rating.items[3].arrivals)
        assertEquals(1, rating.items[4].arrivals)
        assertEquals(1, rating.items[5].arrivals)
    }

    @Test
    fun `rating paginates deterministically`() {
        val fixture = fixture()

        val firstPage = fixture.service.ratingForClub(1, PromoterPeriod.WEEK, page = 2, size = 2, now = now)
        assertEquals(listOf(4L, 5L), firstPage.items.map { it.promoterId })
        assertEquals(7, firstPage.total)

        val secondPage = fixture.service.ratingForClub(1, PromoterPeriod.WEEK, page = 3, size = 2, now = now)
        assertEquals(listOf(6L, 7L), secondPage.items.map { it.promoterId })

        val lastPage = fixture.service.ratingForClub(1, PromoterPeriod.WEEK, page = 4, size = 2, now = now)
        assertEquals(listOf(3L), lastPage.items.map { it.promoterId })
    }

    @Test
    fun `no shows excluded for ongoing events`() {
        val eventsRepository = InMemoryEventsRepository(
            listOf(
                Event(
                    id = 1,
                    clubId = 1,
                    startUtc = now.minus(Duration.ofDays(2)),
                    endUtc = now.minus(Duration.ofDays(2)).plus(Duration.ofHours(1)),
                    title = null,
                    isSpecial = false,
                ),
                Event(
                    id = 2,
                    clubId = 1,
                    startUtc = now.plus(Duration.ofHours(1)),
                    endUtc = now.plus(Duration.ofHours(3)),
                    title = null,
                    isSpecial = false,
                ),
            ),
            updatedAt = now,
        )
        val inviteRepository = InMemoryPromoterInviteRepository()

        inviteRepository.save(
            PromoterInvite(
                id = inviteRepository.nextId(),
                promoterId = 1,
                clubId = 1,
                eventId = 1,
                guestName = "Past",
                guestCount = 2,
                status = PromoterInviteStatus.CONFIRMED,
                issuedAt = now.minus(Duration.ofDays(3)),
                openedAt = null,
                confirmedAt = null,
                arrivedAt = null,
                noShowAt = null,
                revokedAt = null,
            ),
        )

        inviteRepository.save(
            PromoterInvite(
                id = inviteRepository.nextId(),
                promoterId = 1,
                clubId = 1,
                eventId = 2,
                guestName = "Future",
                guestCount = 3,
                status = PromoterInviteStatus.CONFIRMED,
                issuedAt = now,
                openedAt = null,
                confirmedAt = null,
                arrivedAt = null,
                noShowAt = null,
                revokedAt = null,
            ),
        )

        val service = PromoterRatingService(inviteRepository, eventsRepository, clock)

        val metrics = service.scorecardForPromoter(1, PromoterPeriod.MONTH, now)

        assertEquals(2, metrics.invited)
        assertEquals(0, metrics.arrivals)
        assertEquals(2, metrics.noShows)
        assertEquals(0.0, metrics.conversionScore, 1e-9)
    }

    @Test
    fun `empty aggregates return zeros`() {
        val eventsRepository = InMemoryEventsRepository(emptyList(), updatedAt = now)
        val inviteRepository = InMemoryPromoterInviteRepository()
        val service = PromoterRatingService(inviteRepository, eventsRepository, clock)

        val scorecard = service.scorecardForPromoter(99, PromoterPeriod.WEEK, now)
        assertEquals(0, scorecard.invited)
        assertEquals(0, scorecard.arrivals)
        assertEquals(0, scorecard.noShows)
        assertEquals(0.0, scorecard.conversionScore, 1e-9)

        val rating = service.ratingForClub(1, PromoterPeriod.WEEK, page = 1, size = 10, now = now)
        assertEquals(0, rating.total)
        assertEquals(emptyList<Long>(), rating.items.map { it.promoterId })
    }

    @Test
    fun `invites with missing events are ignored`() {
        val eventsRepository = InMemoryEventsRepository(
            listOf(
                Event(
                    id = 1,
                    clubId = 1,
                    startUtc = now.minus(Duration.ofDays(1)),
                    endUtc = now.minus(Duration.ofDays(1)).plus(Duration.ofHours(2)),
                    title = null,
                    isSpecial = false,
                ),
            ),
            updatedAt = now,
        )
        val inviteRepository = InMemoryPromoterInviteRepository()

        inviteRepository.save(
            PromoterInvite(
                id = inviteRepository.nextId(),
                promoterId = 1,
                clubId = 1,
                eventId = 1,
                guestName = "Valid",
                guestCount = 2,
                status = PromoterInviteStatus.ARRIVED,
                issuedAt = now.minus(Duration.ofDays(2)),
                openedAt = null,
                confirmedAt = null,
                arrivedAt = now.minus(Duration.ofDays(1)).plusSeconds(600),
                noShowAt = null,
                revokedAt = null,
            ),
        )

        inviteRepository.save(
            PromoterInvite(
                id = inviteRepository.nextId(),
                promoterId = 1,
                clubId = 1,
                eventId = 999,
                guestName = "Missing",
                guestCount = 5,
                status = PromoterInviteStatus.ARRIVED,
                issuedAt = now.minus(Duration.ofDays(2)),
                openedAt = null,
                confirmedAt = null,
                arrivedAt = now.minus(Duration.ofDays(1)),
                noShowAt = null,
                revokedAt = null,
            ),
        )

        val service = PromoterRatingService(inviteRepository, eventsRepository, clock)

        val scorecard = service.scorecardForPromoter(1, PromoterPeriod.WEEK, now)

        assertEquals(2, scorecard.invited)
        assertEquals(2, scorecard.arrivals)
        assertEquals(0, scorecard.noShows)
        assertEquals(1.0, scorecard.conversionScore, 1e-9)
    }

    private fun fixture(): Fixture {
        val events = listOf(
            Event(
                id = 1,
                clubId = 1,
                startUtc = now.minus(Duration.ofDays(2)),
                endUtc = now.minus(Duration.ofDays(2)).plus(Duration.ofHours(2)),
                title = null,
                isSpecial = false,
            ),
            Event(
                id = 2,
                clubId = 1,
                startUtc = now.minus(Duration.ofHours(1)),
                endUtc = now.plus(Duration.ofHours(2)),
                title = null,
                isSpecial = false,
            ),
            Event(
                id = 3,
                clubId = 1,
                startUtc = now.minus(Duration.ofDays(10)),
                endUtc = now.minus(Duration.ofDays(10)).plus(Duration.ofHours(3)),
                title = null,
                isSpecial = false,
            ),
        )
        val eventsRepository = InMemoryEventsRepository(events, updatedAt = now)
        val inviteRepository = InMemoryPromoterInviteRepository()

        fun addInvite(
            promoterId: Long,
            eventId: Long,
            guestCount: Int,
            status: PromoterInviteStatus,
            arrivedAt: Instant? = null,
            revokedAt: Instant? = null,
        ) {
            val issuedAt = events.first { it.id == eventId }.startUtc.minus(Duration.ofHours(1))
            inviteRepository.save(
                PromoterInvite(
                    id = inviteRepository.nextId(),
                    promoterId = promoterId,
                    clubId = 1,
                    eventId = eventId,
                    guestName = "Guest",
                    guestCount = guestCount,
                    status = status,
                    issuedAt = issuedAt,
                    openedAt = null,
                    confirmedAt = null,
                    arrivedAt = arrivedAt,
                    noShowAt = null,
                    revokedAt = revokedAt,
                ),
            )
        }

        // Promoter 1: mix of arrived, pending, future event and revoked invite
        addInvite(promoterId = 1, eventId = 1, guestCount = 3, status = PromoterInviteStatus.ARRIVED, arrivedAt = events[0].startUtc.plusSeconds(600))
        addInvite(promoterId = 1, eventId = 1, guestCount = 2, status = PromoterInviteStatus.ISSUED)
        addInvite(promoterId = 1, eventId = 2, guestCount = 1, status = PromoterInviteStatus.CONFIRMED)
        addInvite(promoterId = 1, eventId = 1, guestCount = 2, status = PromoterInviteStatus.REVOKED, revokedAt = now.minusSeconds(100))
        addInvite(promoterId = 1, eventId = 3, guestCount = 4, status = PromoterInviteStatus.ARRIVED, arrivedAt = events[2].startUtc.plusSeconds(300))

        // Promoter 2: perfect conversion
        addInvite(promoterId = 2, eventId = 1, guestCount = 2, status = PromoterInviteStatus.ARRIVED, arrivedAt = events[0].startUtc.plusSeconds(100))
        addInvite(promoterId = 2, eventId = 1, guestCount = 2, status = PromoterInviteStatus.ARRIVED, arrivedAt = events[0].startUtc.plusSeconds(200))

        // Promoter 3: no arrivals
        addInvite(promoterId = 3, eventId = 1, guestCount = 4, status = PromoterInviteStatus.ISSUED)

        // Promoter 4: tie breaker by arrivals
        addInvite(promoterId = 4, eventId = 1, guestCount = 5, status = PromoterInviteStatus.ARRIVED, arrivedAt = events[0].startUtc.plusSeconds(50))
        addInvite(promoterId = 4, eventId = 1, guestCount = 5, status = PromoterInviteStatus.CONFIRMED)

        // Promoter 5: same conversion as #4 but fewer arrivals
        addInvite(promoterId = 5, eventId = 1, guestCount = 3, status = PromoterInviteStatus.ARRIVED, arrivedAt = events[0].startUtc.plusSeconds(70))
        addInvite(promoterId = 5, eventId = 1, guestCount = 3, status = PromoterInviteStatus.ISSUED)

        // Promoter 6/7: identical metrics to test deterministic ordering
        addInvite(promoterId = 6, eventId = 1, guestCount = 1, status = PromoterInviteStatus.ARRIVED, arrivedAt = events[0].startUtc.plusSeconds(80))
        addInvite(promoterId = 6, eventId = 1, guestCount = 1, status = PromoterInviteStatus.ISSUED)

        addInvite(promoterId = 7, eventId = 1, guestCount = 1, status = PromoterInviteStatus.ARRIVED, arrivedAt = events[0].startUtc.plusSeconds(90))
        addInvite(promoterId = 7, eventId = 1, guestCount = 1, status = PromoterInviteStatus.ISSUED)

        val service = PromoterRatingService(inviteRepository, eventsRepository, clock)
        return Fixture(inviteRepository, eventsRepository, service)
    }
}
