package com.example.bot.routes

import com.example.bot.audit.StandardAuditAction
import com.example.bot.data.audit.AuditLogTable
import com.example.bot.data.security.PermissionCodes
import com.example.bot.data.security.Role
import com.example.bot.data.support.SupportReplyDeliveriesTable
import com.example.bot.data.support.TicketMessagesTable
import com.example.bot.data.support.TicketsTable
import com.example.bot.support.SupportReplyDeliveryFailureCode
import com.example.bot.support.SupportReplyDeliveryStatus
import com.example.bot.support.TicketSenderType
import com.example.bot.support.TicketStatus
import com.example.bot.telegram.SupportCallbacks
import com.example.bot.testing.createInitData
import com.example.bot.testing.withInitData
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

class SupportReplyDeliveryRoutesTest : SupportAdminRoutesFixture() {
    @Test
    fun `delivery intent persistence failure rolls back reply and never starts telegram send`() =
        withSupportAdminApp { context ->
            val scenario = prepareScenario(context, suffix = "intent-failure")
            transaction(context.database) {
                exec(
                    "ALTER TABLE support_reply_deliveries ADD CONSTRAINT $INTENT_FAILURE_CONSTRAINT " +
                        "CHECK (ticket_id <> ${scenario.ticketId} OR status <> 'pending')",
                )
            }

            val response = postReply(scenario.actorTelegramId, scenario.ticketId, INTENT_FAILURE_REPLY)

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            response.assertNoStoreHeaders()
            val body = response.bodyAsText()
            assertEquals("internal_error", errorCode(body))
            assertFalse(body.contains(INTENT_FAILURE_CONSTRAINT))
            assertFalse(body.contains(INTENT_FAILURE_REPLY))
            assertTrue(context.telegramSender.requests.isEmpty())
            transaction(context.database) {
                assertEquals(
                    TicketStatus.NEW.wire,
                    TicketsTable
                        .selectAll()
                        .where { TicketsTable.id eq scenario.ticketId }
                        .single()[TicketsTable.status],
                )
                assertEquals(
                    0L,
                    TicketMessagesTable
                        .selectAll()
                        .where {
                            (TicketMessagesTable.ticketId eq scenario.ticketId) and
                                (TicketMessagesTable.senderType eq TicketSenderType.AGENT.wire)
                        }.count(),
                )
                assertEquals(
                    0L,
                    SupportReplyDeliveriesTable
                        .selectAll()
                        .where { SupportReplyDeliveriesTable.ticketId eq scenario.ticketId }
                        .count(),
                )
                assertTrue(
                    AuditLogTable
                        .selectAll()
                        .where { AuditLogTable.entityId eq scenario.ticketId }
                        .none { row ->
                            row[AuditLogTable.action] == StandardAuditAction.SUPPORT_REPLY.value ||
                                row[AuditLogTable.action] == StandardAuditAction.SUPPORT_STATUS_CHANGE.value
                        },
                )
            }
        }

    @Test
    fun `confirmed delivery waits for one send using persisted text authoritative recipient and rating keyboard`() =
        withSupportAdminApp { context ->
            val scenario = prepareScenario(context, suffix = "happy")
            val sendStarted = CompletableDeferred<Unit>()
            val releaseSend = CompletableDeferred<Unit>()
            context.telegramSender.sendBehavior = { request ->
                sendStarted.complete(Unit)
                releaseSend.await()
                context.telegramSender.response
            }

            coroutineScope {
                val responseDeferred =
                    async {
                        postReply(
                            telegramId = scenario.actorTelegramId,
                            ticketId = scenario.ticketId,
                            text = "  $PERSISTED_REPLY  ",
                        )
                    }

                sendStarted.await()
                assertFalse(responseDeferred.isCompleted)
                assertEquals(1, context.telegramSender.requests.size)
                releaseSend.complete(Unit)
                val response = responseDeferred.await()

                assertEquals(HttpStatusCode.OK, response.status)
                response.assertNoStoreHeaders()
                val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(
                    setOf(
                        "ticketId",
                        "clubId",
                        "replyMessageId",
                        "replyCreatedAt",
                        "ticketStatus",
                        "deliveryStatus",
                    ),
                    payload.keys,
                )
                assertEquals(scenario.ticketId, payload.getValue("ticketId").jsonPrimitive.long)
                assertEquals(scenario.clubId, payload.getValue("clubId").jsonPrimitive.long)
                assertEquals("in_progress", payload.getValue("ticketStatus").jsonPrimitive.content)
                assertEquals("delivered", payload.getValue("deliveryStatus").jsonPrimitive.content)

                val request = context.telegramSender.requests.single() as SendMessage
                assertEquals(scenario.ownerTelegramId.toString(), request.parameters["chat_id"].toString())
                assertEquals(
                    "Ответ от клуба «$CLUB_NAME»\n\n$PERSISTED_REPLY",
                    request.parameters["text"],
                )
                val markup = request.parameters["reply_markup"] as InlineKeyboardMarkup
                val callbacks = markup.inlineKeyboard().flatMap { it.asList() }.map { it.callbackData }
                assertEquals(
                    listOf(
                        SupportCallbacks.buildRate(scenario.ticketId, up = true),
                        SupportCallbacks.buildRate(scenario.ticketId, up = false),
                    ),
                    callbacks,
                )

                val replyMessageId = payload.getValue("replyMessageId").jsonPrimitive.long
                assertEquals(PERSISTED_REPLY, persistedMessageText(context.database, replyMessageId))
                val delivery = delivery(context.database, scenario.ticketId)
                assertEquals(replyMessageId, delivery.replyMessageId)
                assertEquals(SupportReplyDeliveryStatus.DELIVERED.wire, delivery.status)
                assertEquals(null, delivery.failureCode)
                assertEquals(1, resultAudits(context.database, scenario.ticketId).size)
                assertResultAudit(
                    database = context.database,
                    scenario = scenario,
                    replyMessageId = replyMessageId,
                    result = SupportReplyDeliveryStatus.DELIVERED,
                    failureCode = null,
                )
            }
        }

    @Test
    fun `telegram rejection returns bounded 502 while persisted reply failed state and audit remain readable`() =
        withSupportAdminApp { context ->
            val scenario = prepareScenario(context, suffix = "rejected")
            context.telegramSender.response =
                mockk<BaseResponse> {
                    every { isOk } returns false
                    every { description() } returns RAW_TELEGRAM_DETAIL
                }

            val response = postReply(scenario.actorTelegramId, scenario.ticketId, REJECTED_REPLY)

            assertEquals(HttpStatusCode.BadGateway, response.status)
            response.assertNoStoreHeaders()
            val body = response.bodyAsText()
            assertEquals("support_delivery_failed", errorCode(body))
            assertFalse(body.contains(RAW_TELEGRAM_DETAIL))
            assertFalse(body.contains(REJECTED_REPLY))
            assertEquals(1, context.telegramSender.requests.size)
            val delivery = delivery(context.database, scenario.ticketId)
            assertEquals(SupportReplyDeliveryStatus.FAILED.wire, delivery.status)
            assertEquals(SupportReplyDeliveryFailureCode.TELEGRAM_REJECTED.wire, delivery.failureCode)
            val replyMessage = latestAgentMessage(context.database, scenario.ticketId)
            assertEquals(REJECTED_REPLY, replyMessage.text)
            assertEquals(delivery.replyMessageId, replyMessage.id)
            assertResultAudit(
                context.database,
                scenario,
                replyMessage.id,
                SupportReplyDeliveryStatus.FAILED,
                SupportReplyDeliveryFailureCode.TELEGRAM_REJECTED,
            )

            val refresh =
                client.get("/api/support/tickets/${scenario.ticketId}") {
                    withInitData(createInitData(userId = scenario.actorTelegramId))
                }
            assertEquals(HttpStatusCode.OK, refresh.status)
            val thread = json.parseToJsonElement(refresh.bodyAsText()).jsonObject
            val agent =
                thread
                    .getValue("messages")
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it.getValue("senderType").jsonPrimitive.content == TicketSenderType.AGENT.wire }
            assertEquals(REJECTED_REPLY, agent.getValue("text").jsonPrimitive.content)
            assertEquals("failed", agent.getValue("deliveryStatus").jsonPrimitive.content)
        }

    @Test
    fun `transport exception returns unconfirmed 502 with one result audit and no delivered claim`() =
        withSupportAdminApp { context ->
            val scenario = prepareScenario(context, suffix = "transport")
            context.telegramSender.sendBehavior = { throw IllegalStateException(RAW_TRANSPORT_DETAIL) }

            val response = postReply(scenario.actorTelegramId, scenario.ticketId, TRANSPORT_REPLY)

            assertEquals(HttpStatusCode.BadGateway, response.status)
            response.assertNoStoreHeaders()
            val body = response.bodyAsText()
            assertEquals("support_delivery_unconfirmed", errorCode(body))
            assertFalse(body.contains(RAW_TRANSPORT_DETAIL))
            assertFalse(body.contains("delivered", ignoreCase = true))
            assertEquals(1, context.telegramSender.requests.size)
            val delivery = delivery(context.database, scenario.ticketId)
            assertEquals(SupportReplyDeliveryStatus.UNCONFIRMED.wire, delivery.status)
            assertEquals(SupportReplyDeliveryFailureCode.TRANSPORT_ERROR.wire, delivery.failureCode)
            assertEquals(TRANSPORT_REPLY, persistedMessageText(context.database, delivery.replyMessageId))
            assertResultAudit(
                context.database,
                scenario,
                delivery.replyMessageId,
                SupportReplyDeliveryStatus.UNCONFIRMED,
                SupportReplyDeliveryFailureCode.TRANSPORT_ERROR,
            )
        }

    @Test
    fun `terminal audit failure after completed attempt returns 500 and leaves observable sending state`() =
        withSupportAdminApp { context ->
            val scenario = prepareScenario(context, suffix = "audit-failure")
            transaction(context.database) {
                exec(
                    "ALTER TABLE audit_log ADD CONSTRAINT $RESULT_AUDIT_FAILURE_CONSTRAINT " +
                        "CHECK (action <> '${StandardAuditAction.SUPPORT_DELIVERY_RESULT.value}')",
                )
            }

            val response = postReply(scenario.actorTelegramId, scenario.ticketId, AUDIT_FAILURE_REPLY)

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            response.assertNoStoreHeaders()
            val body = response.bodyAsText()
            assertEquals("internal_error", errorCode(body))
            assertFalse(body.contains(RESULT_AUDIT_FAILURE_CONSTRAINT))
            assertFalse(body.contains(AUDIT_FAILURE_REPLY))
            assertFalse(body.contains("delivered", ignoreCase = true))
            assertEquals(1, context.telegramSender.requests.size)
            val delivery = delivery(context.database, scenario.ticketId)
            assertEquals(SupportReplyDeliveryStatus.SENDING.wire, delivery.status)
            assertEquals(null, delivery.failureCode)
            assertEquals(AUDIT_FAILURE_REPLY, persistedMessageText(context.database, delivery.replyMessageId))
            assertTrue(resultAudits(context.database, scenario.ticketId).isEmpty())
        }

    @Test
    fun `unconfigured client and unavailable recipient are definite failures without telegram request`() =
        withSupportAdminApp { context ->
            val unconfigured = prepareScenario(context, suffix = "unconfigured")
            context.telegramSender.isConfigured = false

            val unconfiguredResponse =
                postReply(unconfigured.actorTelegramId, unconfigured.ticketId, "unconfigured reply")

            assertEquals(HttpStatusCode.BadGateway, unconfiguredResponse.status)
            assertEquals("support_delivery_failed", unconfiguredResponse.errorCode())
            assertTrue(context.telegramSender.requests.isEmpty())
            assertEquals(
                SupportReplyDeliveryFailureCode.CLIENT_UNAVAILABLE.wire,
                delivery(context.database, unconfigured.ticketId).failureCode,
            )
            val unconfiguredDelivery = delivery(context.database, unconfigured.ticketId)
            assertResultAudit(
                context.database,
                unconfigured,
                unconfiguredDelivery.replyMessageId,
                SupportReplyDeliveryStatus.FAILED,
                SupportReplyDeliveryFailureCode.CLIENT_UNAVAILABLE,
            )

            context.telegramSender.isConfigured = true
            val unavailable = prepareScenario(context, suffix = "recipient")
            setTelegramUserId(context.database, unavailable.ownerUserId, null)

            val unavailableResponse =
                postReply(unavailable.actorTelegramId, unavailable.ticketId, "unavailable reply")

            assertEquals(HttpStatusCode.BadGateway, unavailableResponse.status)
            assertEquals("support_delivery_failed", unavailableResponse.errorCode())
            assertTrue(context.telegramSender.requests.isEmpty())
            assertEquals(
                SupportReplyDeliveryFailureCode.RECIPIENT_UNAVAILABLE.wire,
                delivery(context.database, unavailable.ticketId).failureCode,
            )
            val unavailableDelivery = delivery(context.database, unavailable.ticketId)
            assertResultAudit(
                context.database,
                unavailable,
                unavailableDelivery.replyMessageId,
                SupportReplyDeliveryStatus.FAILED,
                SupportReplyDeliveryFailureCode.RECIPIENT_UNAVAILABLE,
            )
        }

    @Test
    fun `delivery cancellation propagates and never produces HTTP success`() =
        withSupportAdminApp(installCancellationMarker = true) { context ->
            val scenario = prepareScenario(context, suffix = "canceled")
            val cancellation = CancellationException("delivery cancellation sentinel")
            context.telegramSender.sendBehavior = { throw cancellation }

            val response = postReply(scenario.actorTelegramId, scenario.ticketId, "canceled reply")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("true", response.headers[SUPPORT_CANCELLATION_HEADER])
            assertEquals("cancellation_propagated", response.bodyAsText())
            assertEquals(1, context.telegramSender.requests.size)
            val delivery = delivery(context.database, scenario.ticketId)
            assertTrue(
                delivery.status in
                    setOf(
                        SupportReplyDeliveryStatus.UNCONFIRMED.wire,
                        SupportReplyDeliveryStatus.SENDING.wire,
                    ),
            )
            assertFalse(response.bodyAsText().contains("delivered", ignoreCase = true))
            if (delivery.status == SupportReplyDeliveryStatus.UNCONFIRMED.wire) {
                assertEquals(SupportReplyDeliveryFailureCode.CANCELED.wire, delivery.failureCode)
                assertEquals(1, resultAudits(context.database, scenario.ticketId).size)
            }
        }

    private suspend fun prepareScenario(
        context: TestContext,
        suffix: String,
    ): RouteScenario {
        val actorTelegramId = 7_100_000L + suffix.hashCode().toUInt().toLong()
        val ownerTelegramId = actorTelegramId + 1L
        val actorUserId = insertUser(context.database, context.userRepository, actorTelegramId, "actor-$suffix")
        val clubId = insertClub(context.database, CLUB_NAME)
        val assignmentId = insertRoleAssignment(context.database, actorUserId, Role.CLUB_ADMIN, clubId)
        grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_VIEW)
        grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_REPLY)
        val ownerUserId = insertUser(context.database, context.userRepository, ownerTelegramId, "owner-$suffix")
        val ticketId = createTicket(context, clubId, ownerUserId, "question-$suffix")
        return RouteScenario(
            actorTelegramId = actorTelegramId,
            actorUserId = actorUserId,
            ownerTelegramId = ownerTelegramId,
            ownerUserId = ownerUserId,
            clubId = clubId,
            ticketId = ticketId,
        )
    }

    private suspend fun ApplicationTestBuilder.postReply(
        telegramId: Long,
        ticketId: Long,
        text: String,
    ): HttpResponse =
        client.post("/api/support/tickets/$ticketId/reply") {
            withInitData(createInitData(userId = telegramId))
            contentType(ContentType.Application.Json)
            setBody("""{"text":"$text"}""")
        }

    private fun delivery(
        database: Database,
        ticketId: Long,
    ): StoredRouteDelivery =
        transaction(database) {
            SupportReplyDeliveriesTable
                .selectAll()
                .where { SupportReplyDeliveriesTable.ticketId eq ticketId }
                .single()
                .let { row ->
                    StoredRouteDelivery(
                        replyMessageId = row[SupportReplyDeliveriesTable.replyMessageId],
                        status = row[SupportReplyDeliveriesTable.status],
                        failureCode = row[SupportReplyDeliveriesTable.failureCode],
                    )
                }
        }

    private fun latestAgentMessage(
        database: Database,
        ticketId: Long,
    ): StoredRouteMessage =
        transaction(database) {
            TicketMessagesTable
                .selectAll()
                .where {
                    (TicketMessagesTable.ticketId eq ticketId) and
                        (TicketMessagesTable.senderType eq TicketSenderType.AGENT.wire)
                }.orderBy(TicketMessagesTable.id to SortOrder.DESC)
                .limit(1)
                .single()
                .let { row ->
                    StoredRouteMessage(
                        id = row[TicketMessagesTable.id],
                        text = row[TicketMessagesTable.text],
                    )
                }
        }

    private fun persistedMessageText(
        database: Database,
        messageId: Long,
    ): String =
        transaction(database) {
            TicketMessagesTable
                .selectAll()
                .where { TicketMessagesTable.id eq messageId }
                .single()[TicketMessagesTable.text]
        }

    private fun resultAudits(
        database: Database,
        ticketId: Long,
    ): List<JsonObject> =
        transaction(database) {
            AuditLogTable
                .selectAll()
                .where {
                    (AuditLogTable.entityId eq ticketId) and
                        (AuditLogTable.action eq StandardAuditAction.SUPPORT_DELIVERY_RESULT.value)
                }.map { row ->
                    json.parseToJsonElement(row[AuditLogTable.metadataJson]).jsonObject
                }
        }

    private fun assertResultAudit(
        database: Database,
        scenario: RouteScenario,
        replyMessageId: Long,
        result: SupportReplyDeliveryStatus,
        failureCode: SupportReplyDeliveryFailureCode?,
    ) {
        transaction(database) {
            val row =
                AuditLogTable
                    .selectAll()
                    .where {
                        (AuditLogTable.entityId eq scenario.ticketId) and
                            (AuditLogTable.action eq StandardAuditAction.SUPPORT_DELIVERY_RESULT.value)
                    }.single()
            assertEquals(scenario.clubId, row[AuditLogTable.clubId])
            assertEquals(scenario.actorUserId, row[AuditLogTable.actorUserId])
            assertEquals(Role.CLUB_ADMIN.name, row[AuditLogTable.actorRole])
            val metadata = json.parseToJsonElement(row[AuditLogTable.metadataJson]).jsonObject
            val expectedKeys =
                if (failureCode == null) {
                    setOf("result", "reply_message_id")
                } else {
                    setOf("result", "reply_message_id", "failure_code")
                }
            assertEquals(expectedKeys, metadata.keys)
            assertEquals(result.wire, metadata.getValue("result").jsonPrimitive.content)
            assertEquals(replyMessageId, metadata.getValue("reply_message_id").jsonPrimitive.long)
            failureCode?.let {
                assertEquals(it.wire, metadata.getValue("failure_code").jsonPrimitive.content)
            }
            val rendered = row[AuditLogTable.metadataJson]
            assertFalse(rendered.contains(scenario.ownerTelegramId.toString()))
            assertFalse(rendered.contains(PERSISTED_REPLY))
            assertFalse(rendered.contains(RAW_TELEGRAM_DETAIL))
        }
    }

    private fun setTelegramUserId(
        database: Database,
        userId: Long,
        telegramUserId: Long?,
    ) {
        transaction(database) {
            assertEquals(
                1,
                RouteDeliveryUsersTable.update({ RouteDeliveryUsersTable.id eq userId }) {
                    it[RouteDeliveryUsersTable.telegramUserId] = telegramUserId
                },
            )
        }
    }

    private data class RouteScenario(
        val actorTelegramId: Long,
        val actorUserId: Long,
        val ownerTelegramId: Long,
        val ownerUserId: Long,
        val clubId: Long,
        val ticketId: Long,
    )

    private data class StoredRouteDelivery(
        val replyMessageId: Long,
        val status: String,
        val failureCode: String?,
    )

    private data class StoredRouteMessage(
        val id: Long,
        val text: String,
    )

    private companion object {
        const val CLUB_NAME = "Truthful Delivery Club"
        const val PERSISTED_REPLY = "Persisted truthful reply"
        const val REJECTED_REPLY = "Persisted rejected reply"
        const val TRANSPORT_REPLY = "Persisted transport reply"
        const val AUDIT_FAILURE_REPLY = "Persisted audit failure reply"
        const val INTENT_FAILURE_REPLY = "Persisted intent failure reply"
        const val RAW_TELEGRAM_DETAIL = "RAW_TELEGRAM_DESCRIPTION_SENTINEL"
        const val RAW_TRANSPORT_DETAIL = "RAW_TRANSPORT_EXCEPTION_SENTINEL"
        const val RESULT_AUDIT_FAILURE_CONSTRAINT = "support_route_reject_delivery_result_audit"
        const val INTENT_FAILURE_CONSTRAINT = "support_route_reject_delivery_intent"
        const val SUPPORT_CANCELLATION_HEADER = "X-Support-Staff-Cancellation-Propagated"
    }
}

private object RouteDeliveryUsersTable : Table("users") {
    val id = long("id")
    val telegramUserId = long("telegram_user_id").nullable()

    override val primaryKey = PrimaryKey(id)
}
