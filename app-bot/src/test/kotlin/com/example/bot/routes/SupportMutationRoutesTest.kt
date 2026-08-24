package com.example.bot.routes

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.bot.data.security.PermissionCodes
import com.example.bot.data.security.Role
import com.example.bot.data.support.TicketMessagesTable
import com.example.bot.data.support.TicketsTable
import com.example.bot.support.SupportService
import com.example.bot.support.TicketStatus
import com.example.bot.testing.createInitData
import com.example.bot.testing.withInitData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SupportMutationRoutesTest : SupportLifecycleMutationRoutesFixture() {
    @Test
    fun `unsupported stored states return bounded invalid state without writes`() =
        withSupportAdminApp { context ->
            val telegramId = 9_901L
            val actorUserId = insertUser(context.database, context.userRepository, telegramId, "unsupported-actor")
            val clubId = insertClub(context.database, "Unsupported State Club")
            val assignmentId = insertRoleAssignment(context.database, actorUserId, Role.MANAGER, clubId)
            grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_REPLY)
            grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)
            val guestUserId = insertUser(context.database, context.userRepository, 9_902L, "unsupported-guest")
            val takeTicketId = createTicket(context, clubId, guestUserId)
            val replyTicketId = createTicket(context, clubId, guestUserId)
            val resolveTicketId = createTicket(context, clubId, guestUserId)
            val closeTicketId = createTicket(context, clubId, guestUserId)
            transaction(context.database) {
                exec("ALTER TABLE tickets DROP CONSTRAINT tickets_status_check")
                listOf(takeTicketId, replyTicketId, resolveTicketId, closeTicketId).forEach { ticketId ->
                    TicketsTable.update({ TicketsTable.id eq ticketId }) {
                        it[TicketsTable.status] = "unsupported"
                    }
                }
            }

            supportAssign(telegramId, takeTicketId).assertGenericInvalidState()
            supportReply(telegramId, replyTicketId).assertGenericInvalidState()
            supportResolve(telegramId, resolveTicketId).assertGenericInvalidState()
            supportClose(telegramId, closeTicketId).assertGenericInvalidState()

            transaction(context.database) {
                listOf(takeTicketId, replyTicketId, resolveTicketId, closeTicketId).forEach { ticketId ->
                    val row = TicketsTable.selectAll().where { TicketsTable.id eq ticketId }.single()
                    assertEquals("unsupported", row[TicketsTable.status])
                    assertEquals(null, row[TicketsTable.lastAgentId])
                }
            }
            listOf(takeTicketId, replyTicketId, resolveTicketId, closeTicketId).forEach { ticketId ->
                assertEquals(1L, messageCount(context.database, ticketId))
                assertEquals(0L, auditCount(context.database, ticketId))
            }
        }

    @Test
    fun `staff mutation cancellation propagates to the application boundary`() {
        val cancellation = CancellationException("cancelled support take")
        val cancellingService = mockk<SupportService>()
        coEvery { cancellingService.assign(any(), any()) } throws cancellation

        withSupportAdminApp(
            supportServiceFactory = { cancellingService },
            installCancellationMarker = true,
        ) { context ->
            val telegramId = 10_001L
            val actorUserId = insertUser(context.database, context.userRepository, telegramId, "cancellation-actor")
            val clubId = insertClub(context.database, "Cancellation Mutation Club")
            val assignmentId = insertRoleAssignment(context.database, actorUserId, Role.MANAGER, clubId)
            grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)
            val guestUserId = insertUser(context.database, context.userRepository, 10_002L, "cancellation-guest")
            val ticketId = createTicket(context, clubId, guestUserId)

            val response = supportAssign(telegramId, ticketId)

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("cancellation_propagated", response.bodyAsText())
            response.assertNoStoreHeaders()
            assertEquals(TicketStatus.NEW, context.supportService.getTicket(ticketId)?.status)
            assertEquals(1L, messageCount(context.database, ticketId))
            assertEquals(0L, auditCount(context.database, ticketId))
        }
    }

    @Test
    fun `manager and club admin take and reply with exact mutation permissions`() =
        withSupportAdminApp { context ->
            listOf(Role.MANAGER, Role.CLUB_ADMIN).forEachIndexed { index, role ->
                val telegramId = 10_100L + index
                val actorUserId = insertUser(context.database, context.userRepository, telegramId, "actor-$role")
                val clubId = insertClub(context.database, "$role Mutation Club")
                val assignmentId = insertRoleAssignment(context.database, actorUserId, role, clubId)
                grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_VIEW)
                grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_REPLY)
                grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)
                val capabilitiesResponse =
                    client.get("/api/support/staff/clubs") {
                        withInitData(createInitData(userId = telegramId))
                    }
                assertEquals(HttpStatusCode.OK, capabilitiesResponse.status)
                val capability =
                    json
                        .parseToJsonElement(capabilitiesResponse.bodyAsText())
                        .jsonArray
                        .single()
                        .jsonObject
                assertTrue(capability.getValue("canReply").jsonPrimitive.boolean)
                assertTrue(capability.getValue("canTakeInWork").jsonPrimitive.boolean)
                assertTrue(capability.getValue("canManageStatus").jsonPrimitive.boolean)
                val guestUserId =
                    insertUser(
                        context.database,
                        context.userRepository,
                        telegramId + 1_000,
                        "guest-$role",
                    )
                val takeTicketId = createTicket(context, clubId, guestUserId)
                val replyTicketId = createTicket(context, clubId, guestUserId)

                val takeResponse = supportAssign(telegramId, takeTicketId)
                assertEquals(HttpStatusCode.OK, takeResponse.status)
                takeResponse.assertNoStoreHeaders()
                val takePayload = json.parseToJsonElement(takeResponse.bodyAsText()).jsonObject
                assertEquals("in_progress", takePayload.getValue("status").jsonPrimitive.content)
                val takenTicket = assertNotNull(context.supportService.getTicket(takeTicketId))
                assertEquals(TicketStatus.IN_PROGRESS, takenTicket.status)
                assertEquals(actorUserId, takenTicket.lastAgentId)
                assertEquals(1L, messageCount(context.database, takeTicketId))

                val replyText = "  Persisted staff reply from $role  "
                val replyResponse = postReply(telegramId, replyTicketId, replyText, attachments = "[]")
                assertEquals(HttpStatusCode.OK, replyResponse.status)
                replyResponse.assertNoStoreHeaders()
                val replyRaw = replyResponse.bodyAsText()
                val replyPayload = json.parseToJsonElement(replyRaw).jsonObject
                assertEquals(
                    setOf(
                        "ticketId",
                        "clubId",
                        "replyMessageId",
                        "replyCreatedAt",
                        "ticketStatus",
                        "deliveryStatus",
                    ),
                    replyPayload.keys,
                )
                assertEquals(replyTicketId, replyPayload.getValue("ticketId").jsonPrimitive.long)
                assertEquals(clubId, replyPayload.getValue("clubId").jsonPrimitive.long)
                assertNotNull(replyPayload["replyMessageId"]?.jsonPrimitive?.long)
                assertTrue(
                    replyPayload
                        .getValue("replyCreatedAt")
                        .jsonPrimitive
                        .content
                        .isNotBlank(),
                )
                assertEquals("in_progress", replyPayload.getValue("ticketStatus").jsonPrimitive.content)
                assertEquals("delivered", replyPayload.getValue("deliveryStatus").jsonPrimitive.content)
                assertFalse(replyRaw.contains("ownerUserId"))
                assertFalse(replyRaw.contains("Persisted staff reply"))
                assertFalse(replyRaw.contains("attachments"))

                val repliedTicket = assertNotNull(context.supportService.getTicket(replyTicketId))
                assertEquals(TicketStatus.IN_PROGRESS, repliedTicket.status)
                assertEquals(actorUserId, repliedTicket.lastAgentId)
                val messages = storedMessages(context.database, replyTicketId)
                assertEquals(2, messages.size)
                assertEquals("agent", messages.last().senderType)
                assertEquals("Persisted staff reply from $role", messages.last().text)
                assertEquals("[]", messages.last().attachments)
            }
        }

    @Test
    fun `view reply and status permissions remain independent`() =
        withSupportAdminApp { context ->
            val clubId = insertClub(context.database, "Independent Mutation Club")
            val guestUserId = insertUser(context.database, context.userRepository, 11_000L, "independent-guest")

            val viewTelegramId = 11_001L
            val viewUserId = insertUser(context.database, context.userRepository, viewTelegramId, "view-only")
            val viewAssignmentId = insertRoleAssignment(context.database, viewUserId, Role.MANAGER, clubId)
            grantPermission(context.database, viewAssignmentId, PermissionCodes.SUPPORT_VIEW)
            val viewTicketId = createTicket(context, clubId, guestUserId)
            assertForbiddenMutation(supportAssign(viewTelegramId, viewTicketId))
            assertForbiddenMutation(supportReply(viewTelegramId, viewTicketId))
            assertForbiddenMutation(supportStatus(viewTelegramId, viewTicketId, "in_progress"))
            assertForbiddenMutation(supportResolve(viewTelegramId, viewTicketId))
            assertForbiddenMutation(supportClose(viewTelegramId, viewTicketId))
            assertEquals(TicketStatus.NEW, context.supportService.getTicket(viewTicketId)?.status)
            assertEquals(1L, messageCount(context.database, viewTicketId))

            val capabilitiesResponse =
                client.get("/api/support/staff/clubs") {
                    withInitData(createInitData(userId = viewTelegramId))
                }
            assertEquals(HttpStatusCode.OK, capabilitiesResponse.status)
            capabilitiesResponse.assertNoStoreHeaders()
            val capability =
                json
                    .parseToJsonElement(capabilitiesResponse.bodyAsText())
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(
                setOf("id", "name", "canReply", "canTakeInWork", "canManageStatus"),
                capability.keys,
            )
            assertFalse(capability.getValue("canReply").jsonPrimitive.boolean)
            assertFalse(capability.getValue("canTakeInWork").jsonPrimitive.boolean)
            assertFalse(capability.getValue("canManageStatus").jsonPrimitive.boolean)

            val replyTelegramId = 11_002L
            val replyUserId = insertUser(context.database, context.userRepository, replyTelegramId, "reply-only")
            val replyAssignmentId = insertRoleAssignment(context.database, replyUserId, Role.CLUB_ADMIN, clubId)
            grantPermission(context.database, replyAssignmentId, PermissionCodes.SUPPORT_REPLY)
            val replyTicketId = createTicket(context, clubId, guestUserId)
            assertEquals(HttpStatusCode.OK, supportReply(replyTelegramId, replyTicketId).status)
            assertForbiddenMutation(supportAssign(replyTelegramId, replyTicketId))
            assertForbiddenMutation(supportStatus(replyTelegramId, replyTicketId, "closed"))
            assertForbiddenMutation(supportResolve(replyTelegramId, replyTicketId))
            assertForbiddenMutation(supportClose(replyTelegramId, replyTicketId))

            val statusTelegramId = 11_003L
            val statusUserId = insertUser(context.database, context.userRepository, statusTelegramId, "status-only")
            val statusAssignmentId = insertRoleAssignment(context.database, statusUserId, Role.MANAGER, clubId)
            grantPermission(context.database, statusAssignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)
            val takeTicketId = createTicket(context, clubId, guestUserId)
            val statusReplyTicketId = createTicket(context, clubId, guestUserId)
            val genericStatusTicketId = createTicket(context, clubId, guestUserId)
            assertEquals(HttpStatusCode.OK, supportAssign(statusTelegramId, takeTicketId).status)
            assertForbiddenMutation(supportReply(statusTelegramId, statusReplyTicketId))
            supportStatus(statusTelegramId, genericStatusTicketId, "closed").assertGenericInvalidState()
            assertEquals(TicketStatus.NEW, context.supportService.getTicket(genericStatusTicketId)?.status)
            assertEquals(0L, auditCount(context.database, genericStatusTicketId))
        }

    @Test
    fun `denied roles global scope split assignment and wrong user cannot compose authority`() =
        withSupportAdminApp { context ->
            val clubId = insertClub(context.database, "Denied Mutation Club")
            val guestUserId = insertUser(context.database, context.userRepository, 12_000L, "denied-guest")
            val ticketId = createTicket(context, clubId, guestUserId)
            val deniedRoles =
                listOf(
                    Role.ENTRY_MANAGER,
                    Role.OWNER,
                    Role.GLOBAL_ADMIN,
                    Role.HEAD_MANAGER,
                    Role.PROMOTER,
                    Role.GUEST,
                )

            deniedRoles.forEachIndexed { index, role ->
                val telegramId = 12_100L + index
                val userId = insertUser(context.database, context.userRepository, telegramId, "denied-$role")
                val assignmentId = insertRoleAssignment(context.database, userId, role, clubId)
                grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_REPLY)
                grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)
                assertAllMutationsForbidden(telegramId, ticketId)
            }

            val globalTelegramId = 12_200L
            val globalUserId = insertUser(context.database, context.userRepository, globalTelegramId, "global-manager")
            val globalAssignmentId = insertRoleAssignment(context.database, globalUserId, Role.MANAGER, clubId = null)
            grantPermission(context.database, globalAssignmentId, PermissionCodes.SUPPORT_REPLY)
            grantPermission(context.database, globalAssignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)
            assertAllMutationsForbidden(globalTelegramId, ticketId)

            val splitTelegramId = 12_201L
            val splitUserId = insertUser(context.database, context.userRepository, splitTelegramId, "split-user")
            insertRoleAssignment(context.database, splitUserId, Role.MANAGER, clubId)
            val permissionAssignmentId = insertRoleAssignment(context.database, splitUserId, Role.PROMOTER, clubId)
            grantPermission(context.database, permissionAssignmentId, PermissionCodes.SUPPORT_REPLY)
            grantPermission(context.database, permissionAssignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)
            assertAllMutationsForbidden(splitTelegramId, ticketId)

            val assignedTelegramId = 12_202L
            val assignedUserId =
                insertUser(context.database, context.userRepository, assignedTelegramId, "assigned-user")
            val assignedRoleId = insertRoleAssignment(context.database, assignedUserId, Role.CLUB_ADMIN, clubId)
            grantPermission(context.database, assignedRoleId, PermissionCodes.SUPPORT_REPLY)
            grantPermission(context.database, assignedRoleId, PermissionCodes.SUPPORT_STATUS_MANAGE)
            val wrongTelegramId = 12_203L
            insertUser(context.database, context.userRepository, wrongTelegramId, "wrong-user")
            assertAllMutationsForbidden(wrongTelegramId, ticketId)

            assertEquals(TicketStatus.NEW, context.supportService.getTicket(ticketId)?.status)
            assertEquals(1L, messageCount(context.database, ticketId))
            assertEquals(0L, auditCount(context.database, ticketId))
        }

    @Test
    fun `foreign and missing mutations are externally indistinguishable and do not leak metadata`() =
        withSupportAdminApp { context ->
            val telegramId = 13_001L
            val actorUserId = insertUser(context.database, context.userRepository, telegramId, "club-a-actor")
            val clubA = insertClub(context.database, "Authorized Mutation Club")
            val clubB = insertClub(context.database, "FOREIGN_MUTATION_CLUB_SENTINEL")
            val assignmentId = insertRoleAssignment(context.database, actorUserId, Role.MANAGER, clubA)
            grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_REPLY)
            grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)
            val foreignGuestId =
                insertUser(context.database, context.userRepository, 13_002L, "foreign-mutation-guest")
            val foreignTicketId =
                createTicket(
                    context,
                    clubB,
                    foreignGuestId,
                    text = "FOREIGN_MUTATION_BODY_SENTINEL",
                )
            val missingTicketId = Long.MAX_VALUE
            val supportLogger = LoggerFactory.getLogger("SupportRoutes") as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            supportLogger.addAppender(appender)
            try {
                listOf("assign", "reply", "status", "resolve", "close").forEach { action ->
                    val foreignResponse = callMutation(action, telegramId, foreignTicketId)
                    val missingResponse = callMutation(action, telegramId, missingTicketId)
                    val foreignBody = foreignResponse.bodyAsText()
                    val missingBody = missingResponse.bodyAsText()
                    assertEquals(HttpStatusCode.NotFound, foreignResponse.status, action)
                    assertEquals(HttpStatusCode.NotFound, missingResponse.status, action)
                    assertEquals("support_ticket_not_found", errorCode(foreignBody), action)
                    assertEquals(missingBody, foreignBody, action)
                    assertExternallyIdenticalHeaders(foreignResponse, missingResponse)
                    assertFalse(foreignBody.contains("FOREIGN_"), action)
                    assertFalse(foreignBody.contains(foreignTicketId.toString()), action)
                    assertFalse(foreignBody.contains(clubB.toString()), action)
                }
                val logs = appender.list.joinToString("\n") { it.formattedMessage }
                assertFalse(logs.contains("FOREIGN_MUTATION_CLUB_SENTINEL"))
                assertFalse(logs.contains("FOREIGN_MUTATION_BODY_SENTINEL"))
            } finally {
                supportLogger.detachAppender(appender)
                appender.stop()
            }
            assertEquals(TicketStatus.NEW, context.supportService.getTicket(foreignTicketId)?.status)
            assertEquals(1L, messageCount(context.database, foreignTicketId))
            assertEquals(0L, auditCount(context.database, foreignTicketId))
        }

    @Test
    fun `generic status rejects every current state and target without writes`() =
        withSupportAdminApp { context ->
            val telegramId = 14_001L
            val actorUserId = insertUser(context.database, context.userRepository, telegramId, "generic-status-actor")
            val clubId = insertClub(context.database, "Generic Status Club")
            val assignmentId = insertRoleAssignment(context.database, actorUserId, Role.CLUB_ADMIN, clubId)
            grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)
            val guestUserId = insertUser(context.database, context.userRepository, 14_002L, "generic-status-guest")
            val statuses = TicketStatus.entries

            statuses.forEachIndexed { index, currentStatus ->
                val ticketId = createTicket(context, clubId, guestUserId)
                if (currentStatus != TicketStatus.NEW) {
                    seedTicketStatus(context.database, ticketId, currentStatus)
                }
                val before = assertNotNull(context.supportService.getTicket(ticketId))
                val targetStatus = statuses[(index + 1) % statuses.size]

                supportStatus(telegramId, ticketId, targetStatus.wire).assertGenericInvalidState()

                assertEquals(before, context.supportService.getTicket(ticketId), currentStatus.name)
                assertEquals(1L, messageCount(context.database, ticketId), currentStatus.name)
                assertEquals(0L, auditCount(context.database, ticketId), currentStatus.name)
            }
        }

    @Test
    fun `closed is terminal for every staff mutation and generic status`() =
        withSupportAdminApp { context ->
            val telegramId = 14_500L
            val actorUserId = insertUser(context.database, context.userRepository, telegramId, "terminal-actor")
            val clubId = insertClub(context.database, "Terminal Club")
            val assignmentId = insertRoleAssignment(context.database, actorUserId, Role.CLUB_ADMIN, clubId)
            grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_REPLY)
            grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)
            val guestUserId = insertUser(context.database, context.userRepository, 14_501L, "terminal-guest")
            val ticketId = createTicket(context, clubId, guestUserId)
            seedTicketStatus(context.database, ticketId, TicketStatus.RESOLVED)
            assertEquals(HttpStatusCode.OK, supportClose(telegramId, ticketId).status)
            val before = assertNotNull(context.supportService.getTicket(ticketId))
            val beforeMessages = messageCount(context.database, ticketId)
            val beforeAudits = auditCount(context.database, ticketId)

            supportAssign(telegramId, ticketId).assertGenericInvalidState()
            supportReply(telegramId, ticketId).assertGenericInvalidState()
            supportResolve(telegramId, ticketId).assertGenericInvalidState()
            supportClose(telegramId, ticketId).assertGenericInvalidState()
            supportStatus(telegramId, ticketId, "resolved").assertGenericInvalidState()

            assertEquals(before, context.supportService.getTicket(ticketId))
            assertEquals(beforeMessages, messageCount(context.database, ticketId))
            assertEquals(beforeAudits, auditCount(context.database, ticketId))
        }

    @Test
    fun `invalid reply bodies and every generic status body leave state unchanged`() {
        val routedSupportService = mockk<SupportService>()

        withSupportAdminApp(supportServiceFactory = { routedSupportService }) { context ->
            val telegramId = 15_001L
            val actorUserId = insertUser(context.database, context.userRepository, telegramId, "invalid-body-actor")
            val clubId = insertClub(context.database, "Invalid Body Club")
            val assignmentId = insertRoleAssignment(context.database, actorUserId, Role.MANAGER, clubId)
            grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_REPLY)
            grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)
            val guestUserId = insertUser(context.database, context.userRepository, 15_002L, "invalid-body-guest")
            val ticketId = createTicket(context, clubId, guestUserId)
            val before = assertNotNull(context.supportService.getTicket(ticketId))

            val invalidReplyBodies =
                listOf(
                    "{" to "invalid_json",
                    "{}" to "validation_error",
                    """{"text":"   "}""" to "validation_error",
                    buildJsonObject { put("text", "a".repeat(2_001)) }.toString() to "validation_error",
                )
            invalidReplyBodies.forEach { (body, expectedCode) ->
                val response = postRawMutation("reply", telegramId, ticketId, body)
                assertBoundedBadRequest(response, expectedCode)
            }

            val statusBodySentinel = "GENERIC_STATUS_BODY_SENTINEL"
            val genericStatusBodies =
                listOf(
                    "valid legacy status" to """{"status":"closed"}""",
                    "unknown status" to """{"status":"not_a_status"}""",
                    "malformed JSON" to "{",
                    "empty body" to "",
                    "missing status" to "{}",
                    "additional fields" to """{"status":"closed","ignored":"$statusBodySentinel"}""",
                )
            val supportLogger = LoggerFactory.getLogger("SupportRoutes") as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            supportLogger.addAppender(appender)
            try {
                val envelopes =
                    genericStatusBodies.map { (description, body) ->
                        postRawMutation("status", telegramId, ticketId, body)
                            .assertGenericInvalidState()
                            .also { envelope ->
                                assertFalse(envelope.contains(statusBodySentinel), description)
                            }
                    }
                envelopes.drop(1).forEach { envelope ->
                    assertEquals(envelopes.first(), envelope)
                }
                val logs = appender.list.joinToString("\n") { it.formattedMessage }
                assertFalse(logs.contains(statusBodySentinel))
            } finally {
                supportLogger.detachAppender(appender)
                appender.stop()
            }

            val after = assertNotNull(context.supportService.getTicket(ticketId))
            assertEquals(before, after)
            assertEquals(before.status, after.status)
            assertEquals(before.lastAgentId, after.lastAgentId)
            assertEquals(before.updatedAt, after.updatedAt)
            assertEquals(1L, messageCount(context.database, ticketId))
            assertEquals(0L, auditCount(context.database, ticketId))
        }

        coVerify(exactly = 0) {
            routedSupportService.setStatus(any(), any(), any())
        }
    }

    @Test
    fun `audit insertion failure returns generic error rolls back reply and logs no body`() =
        withSupportAdminApp { context ->
            val telegramId = 16_001L
            val actorUserId = insertUser(context.database, context.userRepository, telegramId, "audit-failure-actor")
            val clubId = insertClub(context.database, "Audit Failure Club")
            val assignmentId = insertRoleAssignment(context.database, actorUserId, Role.CLUB_ADMIN, clubId)
            grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_REPLY)
            val guestUserId = insertUser(context.database, context.userRepository, 16_002L, "audit-failure-guest")
            val ticketId = createTicket(context, clubId, guestUserId)
            val before = assertNotNull(context.supportService.getTicket(ticketId))
            val replySentinel = "PRIVATE_REPLY_BODY_SENTINEL"
            val attachmentSentinel = "PRIVATE_ATTACHMENT_SENTINEL"
            val constraintSentinel = "SENTINEL_SUPPORT_REPLY_AUDIT_FAILURE"
            transaction(context.database) {
                exec(
                    "ALTER TABLE audit_log ADD CONSTRAINT $constraintSentinel " +
                        "CHECK (action <> 'SUPPORT_REPLY')",
                )
            }
            val supportLogger = LoggerFactory.getLogger("SupportRoutes") as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            supportLogger.addAppender(appender)
            try {
                val response = postReply(telegramId, ticketId, replySentinel, attachmentSentinel)
                assertEquals(HttpStatusCode.InternalServerError, response.status)
                response.assertNoStoreHeaders()
                val body = response.bodyAsText()
                assertEquals("internal_error", errorCode(body))
                assertFalse(body.contains(replySentinel))
                assertFalse(body.contains(attachmentSentinel))
                assertFalse(body.contains(constraintSentinel))
                assertFalse(body.contains("SQL", ignoreCase = true))
                assertFalse(body.contains("constraint", ignoreCase = true))
                assertEquals(before, context.supportService.getTicket(ticketId))
                assertEquals(1L, messageCount(context.database, ticketId))
                assertEquals(0L, auditCount(context.database, ticketId))
                assertTrue(context.telegramSender.requests.isEmpty())
                val logs = appender.list.joinToString("\n") { it.formattedMessage }
                assertFalse(logs.contains(replySentinel))
                assertFalse(logs.contains(attachmentSentinel))
                assertFalse(logs.contains(constraintSentinel))
            } finally {
                supportLogger.detachAppender(appender)
                appender.stop()
            }
        }

    private suspend fun ApplicationTestBuilder.postReply(
        telegramId: Long,
        ticketId: Long,
        text: String,
        attachments: String? = null,
    ): HttpResponse =
        client.post("/api/support/tickets/$ticketId/reply") {
            withInitData(createInitData(userId = telegramId))
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("text", text)
                    if (attachments != null) {
                        put("attachments", attachments)
                    }
                }.toString(),
            )
        }

    private suspend fun ApplicationTestBuilder.postRawMutation(
        action: String,
        telegramId: Long,
        ticketId: Long,
        body: String,
    ): HttpResponse =
        client.post("/api/support/tickets/$ticketId/$action") {
            withInitData(createInitData(userId = telegramId))
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.callMutation(
        action: String,
        telegramId: Long,
        ticketId: Long,
    ): HttpResponse =
        when (action) {
            "assign" -> supportAssign(telegramId, ticketId)
            "reply" -> supportReply(telegramId, ticketId)
            "status" -> supportStatus(telegramId, ticketId, "closed")
            "resolve" -> supportResolve(telegramId, ticketId)
            "close" -> supportClose(telegramId, ticketId)
            else -> error("Unsupported test action: $action")
        }

    private suspend fun ApplicationTestBuilder.assertAllMutationsForbidden(
        telegramId: Long,
        ticketId: Long,
    ) {
        assertForbiddenMutation(supportAssign(telegramId, ticketId))
        assertForbiddenMutation(supportReply(telegramId, ticketId))
        assertForbiddenMutation(supportStatus(telegramId, ticketId, "closed"))
        assertForbiddenMutation(supportResolve(telegramId, ticketId))
        assertForbiddenMutation(supportClose(telegramId, ticketId))
    }

    private suspend fun assertForbiddenMutation(response: HttpResponse) {
        assertEquals(HttpStatusCode.Forbidden, response.status)
        response.assertNoStoreHeaders()
        val body = response.bodyAsText()
        assertEquals("support_ticket_forbidden", errorCode(body))
        assertFalse(body.contains("ticketId"))
        assertFalse(body.contains("clubId"))
    }

    private fun storedMessages(
        database: Database,
        ticketId: Long,
    ): List<StoredMessage> =
        transaction(database) {
            TicketMessagesTable
                .selectAll()
                .where { TicketMessagesTable.ticketId eq ticketId }
                .orderBy(TicketMessagesTable.id to SortOrder.ASC)
                .map { row ->
                    StoredMessage(
                        senderType = row[TicketMessagesTable.senderType],
                        text = row[TicketMessagesTable.text],
                        attachments = row[TicketMessagesTable.attachments],
                    )
                }
        }

    private data class StoredMessage(
        val senderType: String,
        val text: String,
        val attachments: String?,
    )
}
