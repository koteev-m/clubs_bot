package com.example.bot.data.support

import com.example.bot.audit.StandardAuditAction
import com.example.bot.data.audit.AuditLogRepositoryImpl
import com.example.bot.data.security.PermissionCodes
import com.example.bot.data.security.Role
import com.example.bot.support.SupportServiceError
import com.example.bot.support.SupportServiceResult
import com.example.bot.support.TicketSenderType
import com.example.bot.support.TicketStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Testcontainers
import testing.RequiresDocker
import java.time.Clock
import java.util.concurrent.CyclicBarrier

@RequiresDocker
@Tag("it")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupportResolvedLifecycleIT : SupportMutationPostgresITFixture() {
    @Test
    fun `two concurrent resolves produce one success one invalid state and one status audit`() =
        runBlocking {
            val ownerUserId = insertUser(8_800_820_051L)
            val actorUserId = insertUser(8_800_820_052L)
            val clubId = insertClub("Support concurrent resolve")
            val assignmentId = insertAssignment(actorUserId, Role.MANAGER, "CLUB", clubId)
            grantPermission(assignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)

            val repository = SupportRepository(database, fixedClock)
            val ticket = createTicket(repository, clubId, ownerUserId, "resolve initial", null)
            seedStatus(ticket.id, TicketStatus.IN_PROGRESS)
            val firstService = SupportServiceImpl(SupportRepository(database, fixedClock))
            val secondService = SupportServiceImpl(SupportRepository(database, fixedClock))
            val barrier = CyclicBarrier(2)

            val results =
                coroutineScope {
                    listOf(
                        async(concurrencyDispatcher) {
                            barrier.await()
                            firstService.resolve(ticket.id, actorUserId)
                        },
                        async(concurrencyDispatcher) {
                            barrier.await()
                            secondService.resolve(ticket.id, actorUserId)
                        },
                    ).awaitAll()
                }

            assertEquals(1, results.count { it is SupportServiceResult.Success })
            assertEquals(
                1,
                results.count {
                    it is SupportServiceResult.Failure && it.error == SupportServiceError.InvalidState
                },
            )
            val freshTicket = SupportRepository(database, Clock.systemUTC()).findTicket(ticket.id)
            assertEquals(TicketStatus.RESOLVED, freshTicket?.status)
            assertEquals(actorUserId, freshTicket?.lastAgentId)
            assertEquals(1L, messageCount(ticket.id))
            val audits = AuditLogRepositoryImpl(database).listForClub(clubId, 100, 0).forTicket(ticket.id)
            assertEquals(1, audits.size)
            assertStatusAudit(audits.single(), TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED)
            assertAuditEnvelope(audits.single(), clubId, actorUserId, Role.MANAGER, ticket.id)
        }

    @Test
    fun `reply first persists then resolves while resolve first makes later reply invalid`() =
        runBlocking {
            val ownerUserId = insertUser(8_800_820_061L)
            val actorUserId = insertUser(8_800_820_062L)
            val clubId = insertClub("Support reply resolve ordering")
            val assignmentId = insertAssignment(actorUserId, Role.CLUB_ADMIN, "CLUB", clubId)
            grantPermission(assignmentId, PermissionCodes.SUPPORT_REPLY)
            grantPermission(assignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)
            val repository = SupportRepository(database, fixedClock)
            val service = SupportServiceImpl(repository)

            val replyFirst = createTicket(repository, clubId, ownerUserId, "reply first initial", null)
            seedStatus(replyFirst.id, TicketStatus.IN_PROGRESS)
            val reply = success(service.reply(replyFirst.id, actorUserId, REPLY_BEFORE_RESOLVE_BODY, null))
            assertEquals(TicketSenderType.AGENT, reply.replyMessage.senderType)
            assertEquals(REPLY_BEFORE_RESOLVE_BODY, reply.replyMessage.text)
            assertEquals(TicketStatus.RESOLVED, success(service.resolve(replyFirst.id, actorUserId)).status)
            assertEquals(2L, messageCount(replyFirst.id))
            val replyFirstAudits =
                AuditLogRepositoryImpl(database).listForClub(clubId, 100, 0).forTicket(replyFirst.id)
            assertEquals(1, replyFirstAudits.withAction(StandardAuditAction.SUPPORT_REPLY).size)
            val replyFirstStatus = replyFirstAudits.withAction(StandardAuditAction.SUPPORT_STATUS_CHANGE).single()
            assertStatusAudit(replyFirstStatus, TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED)
            assertEquals(TicketStatus.RESOLVED, SupportRepository(database).findTicket(replyFirst.id)?.status)

            val resolveFirst = createTicket(repository, clubId, ownerUserId, "resolve first initial", null)
            seedStatus(resolveFirst.id, TicketStatus.IN_PROGRESS)
            assertEquals(TicketStatus.RESOLVED, success(service.resolve(resolveFirst.id, actorUserId)).status)
            assertFailure(
                service.reply(resolveFirst.id, actorUserId, REPLY_AFTER_RESOLVE_BODY, null),
                SupportServiceError.InvalidState,
            )
            assertEquals(1L, messageCount(resolveFirst.id))
            val resolveFirstAudits =
                AuditLogRepositoryImpl(database).listForClub(clubId, 100, 0).forTicket(resolveFirst.id)
            assertEquals(0, resolveFirstAudits.withAction(StandardAuditAction.SUPPORT_REPLY).size)
            val resolveFirstStatus =
                resolveFirstAudits.withAction(StandardAuditAction.SUPPORT_STATUS_CHANGE).single()
            assertStatusAudit(resolveFirstStatus, TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED)
            assertEquals(TicketStatus.RESOLVED, SupportRepository(database).findTicket(resolveFirst.id)?.status)

            val serializedAudits = (replyFirstAudits + resolveFirstAudits).joinToString("|") { it.metadata.toString() }
            assertFalse(serializedAudits.contains(REPLY_BEFORE_RESOLVE_BODY))
            assertFalse(serializedAudits.contains(REPLY_AFTER_RESOLVE_BODY))
        }

    @Test
    fun `two concurrent closes produce one success one invalid state and exact close status audits`() =
        runBlocking {
            val ownerUserId = insertUser(8_800_820_071L)
            val actorUserId = insertUser(8_800_820_072L)
            val clubId = insertClub("Support concurrent close")
            val assignmentId = insertAssignment(actorUserId, Role.CLUB_ADMIN, "CLUB", clubId)
            grantPermission(assignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)

            val repository = SupportRepository(database, fixedClock)
            val ticket = createTicket(repository, clubId, ownerUserId, "close initial", null)
            seedStatus(ticket.id, TicketStatus.RESOLVED)
            val firstService = SupportServiceImpl(SupportRepository(database, fixedClock))
            val secondService = SupportServiceImpl(SupportRepository(database, fixedClock))
            val barrier = CyclicBarrier(2)

            val results =
                coroutineScope {
                    listOf(
                        async(concurrencyDispatcher) {
                            barrier.await()
                            firstService.close(ticket.id, actorUserId)
                        },
                        async(concurrencyDispatcher) {
                            barrier.await()
                            secondService.close(ticket.id, actorUserId)
                        },
                    ).awaitAll()
                }

            assertEquals(1, results.count { it is SupportServiceResult.Success })
            assertEquals(
                1,
                results.count {
                    it is SupportServiceResult.Failure && it.error == SupportServiceError.InvalidState
                },
            )
            val freshTicket = SupportRepository(database, Clock.systemUTC()).findTicket(ticket.id)
            assertEquals(TicketStatus.CLOSED, freshTicket?.status)
            assertEquals(actorUserId, freshTicket?.lastAgentId)
            assertEquals(1L, messageCount(ticket.id))
            val audits = AuditLogRepositoryImpl(database).listForClub(clubId, 100, 0).forTicket(ticket.id)
            assertEquals(2, audits.size)
            val closeAudit = audits.withAction(StandardAuditAction.SUPPORT_CLOSE).single()
            assertCloseAudit(closeAudit)
            assertAuditEnvelope(closeAudit, clubId, actorUserId, Role.CLUB_ADMIN, ticket.id)
            val statusAudit = audits.withAction(StandardAuditAction.SUPPORT_STATUS_CHANGE).single()
            assertStatusAudit(statusAudit, TicketStatus.RESOLVED, TicketStatus.CLOSED)
            assertAuditEnvelope(statusAudit, clubId, actorUserId, Role.CLUB_ADMIN, ticket.id)
        }

    @Test
    fun `guest first resumes and blocks close while close first stays terminal and blocks guest`() =
        runBlocking {
            val ownerUserId = insertUser(8_800_820_081L)
            val actorUserId = insertUser(8_800_820_082L)
            val clubId = insertClub("Support guest close ordering")
            val assignmentId = insertAssignment(actorUserId, Role.MANAGER, "CLUB", clubId)
            grantPermission(assignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)
            val repository = SupportRepository(database, fixedClock)
            val service = SupportServiceImpl(repository)

            val guestFirst = createTicket(repository, clubId, ownerUserId, "guest first initial", null)
            seedStatus(guestFirst.id, TicketStatus.RESOLVED)
            val guestMessage =
                success(
                    service.addGuestMessage(
                        ticketId = guestFirst.id,
                        userId = ownerUserId,
                        text = GUEST_BEFORE_CLOSE_BODY,
                        attachments = GUEST_BEFORE_CLOSE_ATTACHMENT,
                    ),
                )
            assertEquals(TicketSenderType.GUEST, guestMessage.senderType)
            assertFailure(service.close(guestFirst.id, actorUserId), SupportServiceError.InvalidState)
            assertEquals(TicketStatus.IN_PROGRESS, SupportRepository(database).findTicket(guestFirst.id)?.status)
            assertEquals(2L, messageCount(guestFirst.id))
            val guestFirstAudits =
                AuditLogRepositoryImpl(database).listForClub(clubId, 100, 0).forTicket(guestFirst.id)
            assertEquals(0, guestFirstAudits.withAction(StandardAuditAction.SUPPORT_CLOSE).size)
            val guestStatusAudit =
                guestFirstAudits.withAction(StandardAuditAction.SUPPORT_STATUS_CHANGE).single()
            assertStatusAudit(guestStatusAudit, TicketStatus.RESOLVED, TicketStatus.IN_PROGRESS)
            assertAuditEnvelope(guestStatusAudit, clubId, ownerUserId, Role.GUEST, guestFirst.id)
            assertFalse(guestStatusAudit.metadata.toString().contains(GUEST_BEFORE_CLOSE_BODY))
            assertFalse(guestStatusAudit.metadata.toString().contains(GUEST_BEFORE_CLOSE_ATTACHMENT))

            val closeFirst = createTicket(repository, clubId, ownerUserId, "close first initial", null)
            seedStatus(closeFirst.id, TicketStatus.RESOLVED)
            assertEquals(TicketStatus.CLOSED, success(service.close(closeFirst.id, actorUserId)).status)
            assertFailure(
                service.addGuestMessage(
                    ticketId = closeFirst.id,
                    userId = ownerUserId,
                    text = GUEST_AFTER_CLOSE_BODY,
                    attachments = null,
                ),
                SupportServiceError.TicketClosed,
            )
            assertEquals(TicketStatus.CLOSED, SupportRepository(database).findTicket(closeFirst.id)?.status)
            assertEquals(1L, messageCount(closeFirst.id))
            val closeFirstAudits =
                AuditLogRepositoryImpl(database).listForClub(clubId, 100, 0).forTicket(closeFirst.id)
            assertEquals(2, closeFirstAudits.size)
            assertCloseAudit(closeFirstAudits.withAction(StandardAuditAction.SUPPORT_CLOSE).single())
            assertStatusAudit(
                closeFirstAudits.withAction(StandardAuditAction.SUPPORT_STATUS_CHANGE).single(),
                TicketStatus.RESOLVED,
                TicketStatus.CLOSED,
            )
            assertFalse(closeFirstAudits.joinToString("|") { it.metadata.toString() }.contains(GUEST_AFTER_CLOSE_BODY))
        }

    @Test
    fun `audit insertion failure rolls back resolve guest resume close and every partial audit`() =
        runBlocking {
            val ownerUserId = insertUser(8_800_820_011L)
            val actorUserId = insertUser(8_800_820_012L)
            val clubId = insertClub("Support resolved lifecycle rollback")
            val assignmentId = insertAssignment(actorUserId, Role.MANAGER, "CLUB", clubId)
            grantPermission(assignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)
            val repository = SupportRepository(database, fixedClock)
            val service = SupportServiceImpl(repository)

            val resolveTicket = createTicket(repository, clubId, ownerUserId, "rollback resolve", null)
            seedStatus(resolveTicket.id, TicketStatus.IN_PROGRESS)
            val resolveBefore = requireNotNull(repository.findTicket(resolveTicket.id))
            installStatusAuditFailureConstraint()

            assertDetailFreePersistenceFailure(
                service.resolve(resolveTicket.id, actorUserId),
                AUDIT_FAILURE_CONSTRAINT,
                "audit_log",
            )
            assertEquals(resolveBefore, repository.findTicket(resolveTicket.id))
            assertEquals(1L, messageCount(resolveTicket.id))
            assertEquals(0L, auditCount(resolveTicket.id))

            dropAuditFailureConstraint()
            val guestResumeTicket = createTicket(repository, clubId, ownerUserId, "rollback guest", null)
            seedStatus(guestResumeTicket.id, TicketStatus.RESOLVED)
            val guestBefore = requireNotNull(repository.findTicket(guestResumeTicket.id))
            installStatusAuditFailureConstraint()

            assertDetailFreePersistenceFailure(
                service.addGuestMessage(
                    ticketId = guestResumeTicket.id,
                    userId = ownerUserId,
                    text = ROLLBACK_GUEST_BODY,
                    attachments = ROLLBACK_GUEST_ATTACHMENT,
                ),
                AUDIT_FAILURE_CONSTRAINT,
                ROLLBACK_GUEST_BODY,
                ROLLBACK_GUEST_ATTACHMENT,
            )
            assertEquals(guestBefore, repository.findTicket(guestResumeTicket.id))
            assertEquals(1L, messageCount(guestResumeTicket.id))
            assertEquals(0L, auditCount(guestResumeTicket.id))

            dropAuditFailureConstraint()
            val closeTicket = createTicket(repository, clubId, ownerUserId, "rollback close", null)
            seedStatus(closeTicket.id, TicketStatus.RESOLVED)
            val closeBefore = requireNotNull(repository.findTicket(closeTicket.id))
            installStatusAuditFailureConstraint()

            assertDetailFreePersistenceFailure(
                service.close(closeTicket.id, actorUserId),
                AUDIT_FAILURE_CONSTRAINT,
                "audit_log",
            )
            assertEquals(closeBefore, repository.findTicket(closeTicket.id))
            assertEquals(1L, messageCount(closeTicket.id))
            assertEquals(0L, auditCount(closeTicket.id))
        }

    private companion object {
        const val REPLY_BEFORE_RESOLVE_BODY = "reply before resolve"
        const val REPLY_AFTER_RESOLVE_BODY = "reply after resolve must stay absent"
        const val GUEST_BEFORE_CLOSE_BODY = "guest before close"
        const val GUEST_BEFORE_CLOSE_ATTACHMENT = "guest-before-close-attachment"
        const val GUEST_AFTER_CLOSE_BODY = "guest after close must stay absent"
        const val ROLLBACK_GUEST_BODY = "rollback private guest message"
        const val ROLLBACK_GUEST_ATTACHMENT = "rollback-private-guest-attachment"
    }
}
