package com.example.bot.promoter.invites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PromoterInviteServiceTest {
    private val clock = Clock.fixed(Instant.parse("2024-06-10T10:00:00Z"), ZoneOffset.UTC)
    private lateinit var service: PromoterInviteService
    private lateinit var repository: PromoterInviteRepository

    @Before
    fun setUp() {
        repository = InMemoryPromoterInviteRepository()
        service = PromoterInviteService(repository, clock)
    }

    @Test
    fun `issue invite sets issued status and timeline`() {
        val invite = issue()

        assertEquals("issued", invite.status)
        assertEquals(1, invite.timeline.size)
        assertEquals("issued", invite.timeline.first().type)
        assertEquals("2024-06-10T10:00:00Z", invite.timeline.first().at)
    }

    @Test
    fun `revoke invite happy path`() {
        val issued = issue()

        val result = service.revokeInvite(promoterId = issued.promoterId, inviteId = issued.id)

        assertTrue(result is PromoterInviteService.RevokeResult.Success)
        val revoked = (result as PromoterInviteService.RevokeResult.Success).invite
        assertEquals("revoked", revoked.status)
        assertTrue(revoked.timeline.any { it.type == "revoked" })
    }

    @Test
    fun `revoke invite forbidden for foreign promoter`() {
        val issued = issue()

        val result = service.revokeInvite(promoterId = 999, inviteId = issued.id)

        assertTrue(result is PromoterInviteService.RevokeResult.Forbidden)
    }

    @Test
    fun `revoke invite invalid state after arrival`() {
        val issued = issue()
        val marked = service.markArrivedById(issued.id)
        assertTrue(marked)

        val result = service.revokeInvite(promoterId = issued.promoterId, inviteId = issued.id)

        assertTrue(result is PromoterInviteService.RevokeResult.InvalidState)
    }

    @Test
    fun `mark arrived is idempotent`() {
        val issued = issue()

        val first = service.markArrivedById(issued.id)
        val second = service.markArrivedById(issued.id)

        assertTrue(first)
        assertTrue(second)
        val updated = repository.findById(issued.id)!!
        assertEquals(PromoterInviteStatus.ARRIVED, updated.status)
        assertEquals(Instant.parse("2024-06-10T10:00:00Z"), updated.arrivedAt)
    }

    @Test
    fun `mark arrived fails for revoked invite`() {
        val issued = issue()
        service.revokeInvite(promoterId = issued.promoterId, inviteId = issued.id)

        val marked = service.markArrivedById(issued.id)

        assertFalse(marked)
        val snapshot = repository.findById(issued.id)!!
        assertEquals(PromoterInviteStatus.REVOKED, snapshot.status)
    }

    @Test
    fun `timeline is sorted by instants`() {
        val issuedAt = Instant.parse("2024-06-10T10:00:00Z")
        val confirmedAt = Instant.parse("2024-06-10T11:00:00Z")
        val arrivedAt = Instant.parse("2024-06-10T12:00:00Z")
        val revokedAt = Instant.parse("2024-06-10T13:00:00Z")
        val invite =
            PromoterInvite(
                id = 99,
                promoterId = 7,
                clubId = 1,
                eventId = 100,
                guestName = "Timeline",
                guestCount = 2,
                status = PromoterInviteStatus.REVOKED,
                issuedAt = issuedAt,
                openedAt = null,
                confirmedAt = confirmedAt,
                arrivedAt = arrivedAt,
                noShowAt = null,
                revokedAt = revokedAt,
            )
        repository.save(invite)

        val view = service.listInvites(promoterId = 7, eventId = 100).first()

        assertEquals(listOf("issued", "confirmed", "arrived", "revoked"), view.timeline.map { it.type })
    }

    private fun issue(): PromoterInviteView =
        service.issueInvite(
            promoterId = 7,
            clubId = 1,
            eventId = 100,
            guestName = "Guest",
            guestCount = 2,
        )
}
