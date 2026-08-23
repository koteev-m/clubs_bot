package com.example.bot.routes

import com.example.bot.audit.StandardAuditAction
import com.example.bot.data.audit.AuditLogTable
import com.example.bot.data.security.Role
import com.example.bot.data.support.TicketsTable
import com.example.bot.support.TicketStatus
import com.example.bot.testing.createInitData
import com.example.bot.testing.withInitData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class SupportLifecycleRoutesTest : SupportAdminRoutesFixture() {
    @Test
    fun `support request parser rethrows cancellation`() =
        runBlocking {
            val cancellation = CancellationException("guest request cancelled")

            val thrown =
                assertFailsWith<CancellationException> {
                    receiveSupportRequestOrNull<Unit> { throw cancellation }
                }

            assertEquals(cancellation.message, thrown.message)
        }

    @Test
    fun `guest messages keep active states and resume resolved with one private audit`() =
        withSupportAdminApp { context ->
            val telegramId = 17_001L
            val ownerUserId = insertUser(context.database, context.userRepository, telegramId, "lifecycle-owner")
            val clubId = insertClub(context.database, "Guest Lifecycle Club")
            val newTicketId = createTicket(context, clubId, ownerUserId, "new initial")
            val inProgressTicketId = createTicket(context, clubId, ownerUserId, "in progress initial")
            val resolvedTicketId = createTicket(context, clubId, ownerUserId, "resolved initial")
            seedTicketStatus(context.database, inProgressTicketId, TicketStatus.IN_PROGRESS)
            seedTicketStatus(context.database, resolvedTicketId, TicketStatus.RESOLVED)

            assertEquals(HttpStatusCode.OK, postGuestMessage(telegramId, newTicketId, "new follow-up").status)
            assertEquals(
                HttpStatusCode.OK,
                postGuestMessage(telegramId, inProgressTicketId, "in progress follow-up").status,
            )
            val privateBody = "PRIVATE_RESOLVED_GUEST_BODY"
            val privateAttachments = "PRIVATE_RESOLVED_GUEST_ATTACHMENTS"
            val resumedResponse =
                postGuestMessage(
                    telegramId = telegramId,
                    ticketId = resolvedTicketId,
                    text = privateBody,
                    attachments = privateAttachments,
                )

            assertEquals(HttpStatusCode.OK, resumedResponse.status)
            resumedResponse.assertNoStoreHeaders()
            assertFalse(resumedResponse.bodyAsText().contains(privateBody))
            assertFalse(resumedResponse.bodyAsText().contains(privateAttachments))
            assertEquals(TicketStatus.NEW, context.supportService.getTicket(newTicketId)?.status)
            assertEquals(TicketStatus.IN_PROGRESS, context.supportService.getTicket(inProgressTicketId)?.status)
            val resumed = assertNotNull(context.supportService.getTicket(resolvedTicketId))
            assertEquals(TicketStatus.IN_PROGRESS, resumed.status)
            listOf(newTicketId, inProgressTicketId, resolvedTicketId).forEach { ticketId ->
                assertEquals(2L, messageCount(context.database, ticketId))
            }
            assertEquals(0L, supportAuditCount(context.database, newTicketId))
            assertEquals(0L, supportAuditCount(context.database, inProgressTicketId))
            val audit = singleSupportAudit(context.database, resolvedTicketId)
            assertEquals(StandardAuditAction.SUPPORT_STATUS_CHANGE.value, audit.action)
            assertEquals(clubId, audit.clubId)
            assertEquals(ownerUserId, audit.actorUserId)
            assertEquals(Role.GUEST.name, audit.actorRole)
            assertEquals("""{"old_status":"resolved","new_status":"in_progress"}""", audit.metadataJson)
            assertFalse(audit.metadataJson.contains(privateBody))
            assertFalse(audit.metadataJson.contains(privateAttachments))
        }

    @Test
    fun `closed legacy and unsupported tickets reject guest messages without writes`() =
        withSupportAdminApp { context ->
            val telegramId = 17_101L
            val ownerUserId = insertUser(context.database, context.userRepository, telegramId, "rejected-owner")
            val clubId = insertClub(context.database, "Rejected Guest Lifecycle Club")
            val closedTicketId = createTicket(context, clubId, ownerUserId, "closed initial")
            seedTicketStatus(context.database, closedTicketId, TicketStatus.CLOSED)

            val closedResponse = postGuestMessage(telegramId, closedTicketId, "closed follow-up")

            assertEquals(HttpStatusCode.Conflict, closedResponse.status)
            assertEquals("support_ticket_closed", closedResponse.errorCode())
            assertEquals(TicketStatus.CLOSED, context.supportService.getTicket(closedTicketId)?.status)
            assertEquals(1L, messageCount(context.database, closedTicketId))
            assertEquals(0L, supportAuditCount(context.database, closedTicketId))

            listOf(TicketStatus.OPENED, TicketStatus.ANSWERED).forEach { status ->
                val ticketId = createTicket(context, clubId, ownerUserId, "$status initial")
                seedTicketStatus(context.database, ticketId, status)
                val before = assertNotNull(context.supportService.getTicket(ticketId))

                val response = postGuestMessage(telegramId, ticketId, "$status follow-up")

                response.assertGenericInvalidState()
                assertEquals(before, context.supportService.getTicket(ticketId))
                assertEquals(1L, messageCount(context.database, ticketId))
                assertEquals(0L, supportAuditCount(context.database, ticketId))
            }

            val unsupportedTicketId = createTicket(context, clubId, ownerUserId, "unsupported initial")
            transaction(context.database) {
                exec("ALTER TABLE tickets DROP CONSTRAINT tickets_status_check")
                assertEquals(
                    1,
                    TicketsTable.update({ TicketsTable.id eq unsupportedTicketId }) {
                        it[TicketsTable.status] = "unsupported"
                    },
                )
            }

            postGuestMessage(telegramId, unsupportedTicketId, "unsupported follow-up").assertGenericInvalidState()
            assertEquals("unsupported", rawStatus(context.database, unsupportedTicketId))
            assertEquals(1L, messageCount(context.database, unsupportedTicketId))
            assertEquals(0L, supportAuditCount(context.database, unsupportedTicketId))
        }

    private suspend fun ApplicationTestBuilder.postGuestMessage(
        telegramId: Long,
        ticketId: Long,
        text: String,
        attachments: String? = null,
    ): HttpResponse =
        client.post("/api/support/tickets/$ticketId/messages") {
            withInitData(createInitData(userId = telegramId))
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    GuestMessageBody.serializer(),
                    GuestMessageBody(text = text, attachments = attachments),
                ),
            )
        }

    private fun supportAuditCount(
        database: Database,
        ticketId: Long,
    ): Long =
        transaction(database) {
            AuditLogTable
                .selectAll()
                .where {
                    (AuditLogTable.entityType eq "SUPPORT_TICKET") and
                        (AuditLogTable.entityId eq ticketId)
                }.count()
        }

    private fun singleSupportAudit(
        database: Database,
        ticketId: Long,
    ): StoredAudit =
        transaction(database) {
            AuditLogTable
                .selectAll()
                .where {
                    (AuditLogTable.entityType eq "SUPPORT_TICKET") and
                        (AuditLogTable.entityId eq ticketId)
                }.single()
                .let { row ->
                    StoredAudit(
                        clubId = requireNotNull(row[AuditLogTable.clubId]),
                        actorUserId = requireNotNull(row[AuditLogTable.actorUserId]),
                        actorRole = requireNotNull(row[AuditLogTable.actorRole]),
                        action = row[AuditLogTable.action],
                        metadataJson = requireNotNull(row[AuditLogTable.metadataJson]),
                    )
                }
        }

    private fun rawStatus(
        database: Database,
        ticketId: Long,
    ): String =
        transaction(database) {
            TicketsTable
                .select(TicketsTable.status)
                .where { TicketsTable.id eq ticketId }
                .single()[TicketsTable.status]
        }

    @kotlinx.serialization.Serializable
    private data class GuestMessageBody(
        val text: String,
        val attachments: String? = null,
    )

    private data class StoredAudit(
        val clubId: Long,
        val actorUserId: Long,
        val actorRole: String,
        val action: String,
        val metadataJson: String,
    )
}
