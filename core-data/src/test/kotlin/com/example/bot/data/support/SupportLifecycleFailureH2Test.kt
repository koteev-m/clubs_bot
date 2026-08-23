package com.example.bot.data.support

import com.example.bot.data.security.PermissionCodes
import com.example.bot.data.security.Role
import com.example.bot.support.TicketStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

class SupportLifecycleFailureH2Test {
    private lateinit var fixture: SupportMutationH2Fixture

    @BeforeEach
    fun setUp() {
        fixture = SupportMutationH2Fixture()
    }

    @AfterEach
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `audit failure rolls back every lifecycle mutation and partial audits with detail free errors`() =
        runBlocking {
            val clubId = fixture.insertClub("Audit rollback")
            val guestId = fixture.insertUser("audit-rollback-guest")
            val takeActor =
                fixture.insertAuthorizedActor(
                    Role.MANAGER,
                    clubId,
                    PermissionCodes.SUPPORT_STATUS_MANAGE,
                )
            val replyActor = fixture.insertAuthorizedActor(Role.CLUB_ADMIN, clubId, PermissionCodes.SUPPORT_REPLY)
            val takeId = fixture.createTicket(clubId, guestId, "take-audit-failure").ticket.id
            val replyId = fixture.createTicket(clubId, guestId, "reply-audit-failure").ticket.id
            val resolveId = fixture.createTicket(clubId, guestId, "resolve-audit-failure").ticket.id
            val guestResumeId = fixture.createTicket(clubId, guestId, "guest-audit-failure").ticket.id
            val closeId = fixture.createTicket(clubId, guestId, "close-audit-failure").ticket.id
            fixture.seedStatus(resolveId, TicketStatus.IN_PROGRESS)
            fixture.seedStatus(guestResumeId, TicketStatus.RESOLVED)
            fixture.seedStatus(closeId, TicketStatus.RESOLVED)
            val takeBefore = fixture.snapshot(takeId)
            val replyBefore = fixture.snapshot(replyId)
            val resolveBefore = fixture.snapshot(resolveId)
            val guestResumeBefore = fixture.snapshot(guestResumeId)
            val closeBefore = fixture.snapshot(closeId)
            fixture.rejectStatusAuditInserts()

            val takeResult = fixture.mutationService().assign(takeId, takeActor)
            val replyResult =
                fixture.mutationService().reply(
                    ticketId = replyId,
                    agentUserId = replyActor,
                    text = "rollback-private-body",
                    attachments = "rollback-private-attachments",
                )
            val resolveResult = fixture.mutationService().resolve(resolveId, takeActor)
            val guestResumeResult =
                fixture.mutationService().addGuestMessage(
                    ticketId = guestResumeId,
                    userId = guestId,
                    text = "rollback-private-guest-body",
                    attachments = "rollback-private-guest-attachments",
                )
            val closeResult = fixture.mutationService().close(closeId, takeActor)

            takeResult.assertDetailFreePersistenceFailure()
            replyResult.assertDetailFreePersistenceFailure()
            resolveResult.assertDetailFreePersistenceFailure()
            guestResumeResult.assertDetailFreePersistenceFailure()
            closeResult.assertDetailFreePersistenceFailure()
            assertEquals(takeBefore, fixture.snapshot(takeId))
            assertEquals(replyBefore, fixture.snapshot(replyId))
            assertEquals(resolveBefore, fixture.snapshot(resolveId))
            assertEquals(guestResumeBefore, fixture.snapshot(guestResumeId))
            assertEquals(closeBefore, fixture.snapshot(closeId))
            listOf(takeId, replyId, resolveId, guestResumeId, closeId).forEach { ticketId ->
                assertEquals(0L, fixture.supportAuditCount(ticketId))
            }
        }

    @Test
    fun `staff mutation rethrows cancellation and does not swallow JVM errors`() {
        val clubId = fixture.insertClub("Mutation cancellation")
        val guestId = fixture.insertUser("cancellation-guest")
        val actorId =
            fixture.insertAuthorizedActor(
                Role.MANAGER,
                clubId,
                PermissionCodes.SUPPORT_STATUS_MANAGE,
            )
        val cancellationTicket = runBlocking { fixture.createTicket(clubId, guestId, "cancel-take") }.ticket.id
        val resolveTicket = runBlocking { fixture.createTicket(clubId, guestId, "cancel-resolve") }.ticket.id
        val guestTicket = runBlocking { fixture.createTicket(clubId, guestId, "cancel-guest") }.ticket.id
        val closeTicket = runBlocking { fixture.createTicket(clubId, guestId, "cancel-close") }.ticket.id
        val errorTicket = runBlocking { fixture.createTicket(clubId, guestId, "error-take") }.ticket.id
        fixture.seedStatus(resolveTicket, TicketStatus.IN_PROGRESS)
        fixture.seedStatus(guestTicket, TicketStatus.RESOLVED)
        fixture.seedStatus(closeTicket, TicketStatus.RESOLVED)
        val cancellationBefore = fixture.snapshot(cancellationTicket)
        val resolveBefore = fixture.snapshot(resolveTicket)
        val guestBefore = fixture.snapshot(guestTicket)
        val closeBefore = fixture.snapshot(closeTicket)
        val errorBefore = fixture.snapshot(errorTicket)
        val cancellation = CancellationException("cancel-support-mutation")

        val thrownCancellation =
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    fixture.serviceWithClock(ThrowingClock(cancellation)).assign(cancellationTicket, actorId)
                }
            }
        assertEquals(cancellation.message, thrownCancellation.message)

        listOf<suspend () -> Unit>(
            {
                fixture.serviceWithClock(ThrowingClock(cancellation)).resolve(resolveTicket, actorId)
            },
            {
                fixture.serviceWithClock(ThrowingClock(cancellation)).addGuestMessage(
                    guestTicket,
                    guestId,
                    "cancel-private-guest",
                    null,
                )
            },
            {
                fixture.serviceWithClock(ThrowingClock(cancellation)).close(closeTicket, actorId)
            },
        ).forEach { mutation ->
            val thrown =
                assertThrows(CancellationException::class.java) {
                    runBlocking { mutation() }
                }
            assertEquals(cancellation.message, thrown.message)
        }

        val fatal = AssertionError("fatal-support-mutation")
        val thrownError =
            assertThrows(AssertionError::class.java) {
                runBlocking {
                    fixture.serviceWithClock(ThrowingClock(fatal)).assign(errorTicket, actorId)
                }
            }
        assertEquals(fatal.message, thrownError.message)
        assertEquals(cancellationBefore, fixture.snapshot(cancellationTicket))
        assertEquals(resolveBefore, fixture.snapshot(resolveTicket))
        assertEquals(guestBefore, fixture.snapshot(guestTicket))
        assertEquals(closeBefore, fixture.snapshot(closeTicket))
        assertEquals(errorBefore, fixture.snapshot(errorTicket))
    }
}
