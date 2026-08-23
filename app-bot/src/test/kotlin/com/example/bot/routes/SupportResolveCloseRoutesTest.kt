package com.example.bot.routes

import com.example.bot.data.audit.AuditLogTable
import com.example.bot.data.security.PermissionCodes
import com.example.bot.data.security.Role
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class SupportResolveCloseRoutesTest : SupportLifecycleMutationRoutesFixture() {
    @Test
    fun `manager and club admin resolve and close with minimal lifecycle responses`() =
        withSupportAdminApp { context ->
            listOf(Role.MANAGER, Role.CLUB_ADMIN).forEachIndexed { index, role ->
                val telegramId = 10_500L + index
                val actorUserId = insertUser(context.database, context.userRepository, telegramId, "lifecycle-$role")
                val clubId = insertClub(context.database, "$role Lifecycle Club")
                val assignmentId = insertRoleAssignment(context.database, actorUserId, role, clubId)
                grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)
                val guestUserId =
                    insertUser(context.database, context.userRepository, telegramId + 100, "lifecycle-guest-$role")
                val ticketId = createTicket(context, clubId, guestUserId)
                seedTicketStatus(context.database, ticketId, TicketStatus.IN_PROGRESS)

                val resolveResponse = supportResolve(telegramId, ticketId)

                assertEquals(HttpStatusCode.OK, resolveResponse.status)
                resolveResponse.assertNoStoreHeaders()
                val resolveRaw = resolveResponse.bodyAsText()
                val resolvePayload = json.parseToJsonElement(resolveRaw).jsonObject
                assertEquals(setOf("id", "clubId", "topic", "status", "updatedAt"), resolvePayload.keys)
                assertEquals(ticketId, resolvePayload.getValue("id").jsonPrimitive.long)
                assertEquals(clubId, resolvePayload.getValue("clubId").jsonPrimitive.long)
                assertEquals("resolved", resolvePayload.getValue("status").jsonPrimitive.content)
                assertFalse(resolveRaw.contains("userId"))
                assertFalse(resolveRaw.contains("lastAgentId"))
                assertFalse(resolveRaw.contains("bookingId"))
                assertFalse(resolveRaw.contains("listEntryId"))
                assertFalse(resolveRaw.contains("fingerprint"))

                val closeResponse = supportClose(telegramId, ticketId)

                assertEquals(HttpStatusCode.OK, closeResponse.status)
                closeResponse.assertNoStoreHeaders()
                val closePayload = json.parseToJsonElement(closeResponse.bodyAsText()).jsonObject
                assertEquals(setOf("id", "clubId", "topic", "status", "updatedAt"), closePayload.keys)
                assertEquals("closed", closePayload.getValue("status").jsonPrimitive.content)
                val closed = assertNotNull(context.supportService.getTicket(ticketId))
                assertEquals(TicketStatus.CLOSED, closed.status)
                assertEquals(actorUserId, closed.lastAgentId)
                assertEquals(3L, auditCount(context.database, ticketId))
                assertEquals(1L, messageCount(context.database, ticketId))
            }
        }

    @Test
    fun `resolve confirmation is mandatory and close ignores its request body`() =
        withSupportAdminApp { context ->
            val telegramId = 10_700L
            val actorUserId = insertUser(context.database, context.userRepository, telegramId, "confirmation-actor")
            val clubId = insertClub(context.database, "Confirmation Club")
            val assignmentId = insertRoleAssignment(context.database, actorUserId, Role.MANAGER, clubId)
            grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)
            val guestUserId = insertUser(context.database, context.userRepository, 10_701L, "confirmation-guest")
            val ticketId = createTicket(context, clubId, guestUserId)
            seedTicketStatus(context.database, ticketId, TicketStatus.IN_PROGRESS)
            val before = assertNotNull(context.supportService.getTicket(ticketId))

            listOf(
                "{" to "invalid_json",
                "" to "invalid_json",
                "{}" to "validation_error",
                """{"confirmed":false}""" to "validation_error",
                """{"confirmed":null}""" to "validation_error",
            ).forEach { (body, expectedCode) ->
                assertBoundedBadRequest(supportResolve(telegramId, ticketId, body), expectedCode)
                assertEquals(before, context.supportService.getTicket(ticketId))
                assertEquals(0L, auditCount(context.database, ticketId))
            }

            assertEquals(HttpStatusCode.OK, supportResolve(telegramId, ticketId).status)
            val closeResponse = supportClose(telegramId, ticketId, body = "{")
            assertEquals(HttpStatusCode.OK, closeResponse.status)
            val closePayload = json.parseToJsonElement(closeResponse.bodyAsText()).jsonObject
            assertEquals("closed", closePayload["status"]!!.jsonPrimitive.content)
            assertEquals(TicketStatus.CLOSED, context.supportService.getTicket(ticketId)?.status)
            assertEquals(3L, auditCount(context.database, ticketId))
        }
}

open class SupportLifecycleMutationRoutesFixture : SupportAdminRoutesFixture() {
    protected suspend fun ApplicationTestBuilder.supportResolve(
        telegramId: Long,
        ticketId: Long,
        body: String = """{"confirmed":true}""",
    ): HttpResponse =
        client.post("/api/support/tickets/$ticketId/resolve") {
            withInitData(createInitData(userId = telegramId))
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    protected suspend fun ApplicationTestBuilder.supportClose(
        telegramId: Long,
        ticketId: Long,
        body: String? = null,
    ): HttpResponse =
        client.post("/api/support/tickets/$ticketId/close") {
            withInitData(createInitData(userId = telegramId))
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    protected suspend fun assertBoundedBadRequest(
        response: HttpResponse,
        expectedCode: String,
    ) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        response.assertNoStoreHeaders()
        val body = response.bodyAsText()
        assertEquals(expectedCode, errorCode(body))
        assertFalse(body.contains("TicketStatus"))
        assertFalse(body.contains("Exception"))
        assertFalse(body.contains("support_messages"))
        assertFalse(body.contains("SQL", ignoreCase = true))
    }

    protected fun auditCount(
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
}
