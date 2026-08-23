package com.example.bot.routes

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.bot.data.audit.AuditLogRepositoryImpl
import com.example.bot.data.clubs.ClubsDbRepository
import com.example.bot.data.security.ExposedUserRolePermissionRepository
import com.example.bot.data.security.ExposedUserRoleRepository
import com.example.bot.data.security.PermissionCode
import com.example.bot.data.security.PermissionCodes
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.support.SupportRepository
import com.example.bot.data.support.SupportServiceImpl
import com.example.bot.data.support.TicketMessagesTable
import com.example.bot.data.support.TicketsTable
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.installJsonErrorPages
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.support.StaffSupportReadService
import com.example.bot.support.SupportService
import com.example.bot.support.SupportServiceError
import com.example.bot.support.SupportServiceResult
import com.example.bot.support.TicketStatus
import com.example.bot.support.TicketTopic
import com.example.bot.testing.createInitData
import com.example.bot.testing.withInitData
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.response.BaseResponse
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respondText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
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

private const val SUPPORT_STAFF_CANCELLATION_PROPAGATED_HEADER = "X-Support-Staff-Cancellation-Propagated"

class SupportAdminRoutesTest : SupportAdminRoutesFixture() {
    @Test
    fun `admin list tickets ok`() = withSupportAdminApp { context ->
        val adminTelegramId = 201L
        val adminUserId = insertUser(context.database, context.userRepository, adminTelegramId, "admin")
        val clubId = insertClub(context.database, "Admin Club")
        val roleAssignmentId = insertRoleAssignment(context.database, adminUserId, Role.CLUB_ADMIN, clubId)
        grantPermission(context.database, roleAssignmentId, PermissionCodes.SUPPORT_VIEW)

        val ownerUserId = insertUser(context.database, context.userRepository, 202L, "guest")
        val ticketId = createTicket(context, clubId, ownerUserId)

        val response =
            client.get("/api/support/tickets?clubId=$clubId") {
                withInitData(createInitData(userId = adminTelegramId))
            }

        assertEquals(HttpStatusCode.OK, response.status)
        response.assertNoStoreHeaders()
        val items = json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertTrue(items.any { it.jsonObject["id"]!!.jsonPrimitive.long == ticketId })
    }

    @Test
    fun `manager with matching view assignment lists permitted clubs tickets and complete detail`() =
        withSupportAdminApp { context ->
            assertStaffCanRead(context, Role.MANAGER, telegramId = 211L)
        }

    @Test
    fun `club admin with matching view assignment lists permitted clubs tickets and complete detail`() =
        withSupportAdminApp { context ->
            assertStaffCanRead(context, Role.CLUB_ADMIN, telegramId = 221L)
        }

    @Test
    fun `roles and permissions cannot grant support view independently`() =
        withSupportAdminApp { context ->
            val clubId = insertClub(context.database, "Role Matrix Club")
            val guestId = insertUser(context.database, context.userRepository, 231L, "matrix-guest")
            val ticketId = createTicket(context, clubId, guestId)

            listOf(Role.MANAGER, Role.CLUB_ADMIN).forEachIndexed { index, role ->
                val telegramId = 232L + index
                val userId = insertUser(context.database, context.userRepository, telegramId, "missing-view-$role")
                insertRoleAssignment(context.database, userId, role, clubId)
                assertSupportListDenied(telegramId, clubId, ticketId)
            }

            val roleOnlyOwnerTelegramId = 240L
            val roleOnlyOwnerId =
                insertUser(context.database, context.userRepository, roleOnlyOwnerTelegramId, "role-only-owner")
            insertRoleAssignment(context.database, roleOnlyOwnerId, Role.OWNER, clubId = null)
            assertSupportListDenied(roleOnlyOwnerTelegramId, clubId, ticketId)

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
                val telegramId = 250L + index
                val userId = insertUser(context.database, context.userRepository, telegramId, "denied-$role")
                val roleAssignmentId =
                    insertRoleAssignment(
                        database = context.database,
                        userId = userId,
                        role = role,
                        clubId = if (role in setOf(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER)) null else clubId,
                    )
                grantPermission(context.database, roleAssignmentId, PermissionCodes.SUPPORT_VIEW)
                assertSupportListDenied(telegramId, clubId, ticketId)
            }
        }

    @Test
    fun `role in club A and permission on another assignment grant neither club`() =
        withSupportAdminApp { context ->
            val telegramId = 270L
            val userId = insertUser(context.database, context.userRepository, telegramId, "split-authority")
            val clubA = insertClub(context.database, "Split Club A")
            val clubB = insertClub(context.database, "Split Club B")
            insertRoleAssignment(context.database, userId, Role.MANAGER, clubA)
            val promoterAssignment = insertRoleAssignment(context.database, userId, Role.PROMOTER, clubB)
            grantPermission(context.database, promoterAssignment, PermissionCodes.SUPPORT_VIEW)

            assertSupportListDenied(telegramId, clubA, ticketId = null)
            assertSupportListDenied(telegramId, clubB, ticketId = null)
        }

    @Test
    fun `foreign and missing staff detail are indistinguishable and foreign bodies never leak`() =
        withSupportAdminApp { context ->
            val telegramId = 280L
            val userId = insertUser(context.database, context.userRepository, telegramId, "club-a-manager")
            val clubA = insertClub(context.database, "Visible Club")
            val clubB = insertClub(context.database, "FOREIGN_CLUB_SENTINEL")
            val assignmentId = insertRoleAssignment(context.database, userId, Role.MANAGER, clubA)
            grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_VIEW)
            val guestA = insertUser(context.database, context.userRepository, 281L, "guest-a")
            val guestB = insertUser(context.database, context.userRepository, 282L, "guest-b")
            createTicket(context, clubA, guestA)
            val foreignTicketId = createTicket(context, clubB, guestB, text = "FOREIGN_BODY_SENTINEL")

            val supportLogger = LoggerFactory.getLogger("SupportRoutes") as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            supportLogger.addAppender(appender)
            try {
                val clubsResponse =
                    client.get("/api/support/staff/clubs") {
                        withInitData(createInitData(userId = telegramId))
                    }
                assertEquals(HttpStatusCode.OK, clubsResponse.status)
                val clubsBody = clubsResponse.bodyAsText()
                val clubs = json.parseToJsonElement(clubsBody).jsonArray
                assertEquals(listOf(clubA), clubs.map { it.jsonObject["id"]!!.jsonPrimitive.long })
                assertFalse(clubsBody.contains("FOREIGN_"))

                val foreignList =
                    client.get("/api/support/tickets?clubId=$clubB") {
                        withInitData(createInitData(userId = telegramId))
                    }
                assertEquals(HttpStatusCode.Forbidden, foreignList.status)
                assertFalse(foreignList.bodyAsText().contains("FOREIGN_"))

                val foreignDetail =
                    client.get("/api/support/tickets/$foreignTicketId?clubId=$clubA&role=OWNER") {
                        withInitData(createInitData(userId = telegramId))
                    }
                val missingDetail =
                    client.get("/api/support/tickets/${Long.MAX_VALUE}") {
                        withInitData(createInitData(userId = telegramId))
                    }
                val foreignBody = foreignDetail.bodyAsText()
                val missingBody = missingDetail.bodyAsText()
                assertEquals(HttpStatusCode.NotFound, foreignDetail.status)
                assertEquals(HttpStatusCode.NotFound, missingDetail.status)
                assertEquals("support_ticket_not_found", errorCode(foreignBody))
                assertEquals("support_ticket_not_found", errorCode(missingBody))
                assertEquals(missingBody, foreignBody)
                assertExternallyIdenticalHeaders(foreignDetail, missingDetail)
                assertFalse(foreignBody.contains("FOREIGN_"))
                assertFalse(foreignBody.contains(foreignTicketId.toString()))
                assertFalse(foreignBody.contains(clubB.toString()))

                val denialLogs = appender.list.joinToString("\n") { it.formattedMessage }
                assertFalse(denialLogs.contains("FOREIGN_CLUB_SENTINEL"))
                assertFalse(denialLogs.contains("FOREIGN_BODY_SENTINEL"))
            } finally {
                supportLogger.detachAppender(appender)
                appender.stop()
            }
        }

    @Test
    fun `support view reply and status permissions are independent`() =
        withSupportAdminApp { context ->
            val clubId = insertClub(context.database, "Independent Permissions Club")
            val guestId = insertUser(context.database, context.userRepository, 290L, "permission-guest")

            val viewTelegramId = 291L
            val viewUserId = insertUser(context.database, context.userRepository, viewTelegramId, "view-only")
            val viewAssignment = insertRoleAssignment(context.database, viewUserId, Role.MANAGER, clubId)
            grantPermission(context.database, viewAssignment, PermissionCodes.SUPPORT_VIEW)
            val viewTicketId = createTicket(context, clubId, guestId)
            seedTicketStatus(context.database, viewTicketId, TicketStatus.OPENED)
            assertMutationDenied(viewTelegramId, viewTicketId, includeReply = true, includeStatus = true)

            val replyTelegramId = 292L
            val replyUserId = insertUser(context.database, context.userRepository, replyTelegramId, "reply-only")
            val replyAssignment = insertRoleAssignment(context.database, replyUserId, Role.CLUB_ADMIN, clubId)
            grantPermission(context.database, replyAssignment, PermissionCodes.SUPPORT_REPLY)
            val replyTicketId = createTicket(context, clubId, guestId)
            assertSupportListDenied(replyTelegramId, clubId, replyTicketId)
            val replyResponse = supportReply(replyTelegramId, replyTicketId)
            assertEquals(HttpStatusCode.OK, replyResponse.status)
            val replyAssign = supportAssign(replyTelegramId, replyTicketId)
            assertEquals(HttpStatusCode.Forbidden, replyAssign.status)
            val replyStatus = supportStatus(replyTelegramId, replyTicketId, "closed")
            assertEquals(HttpStatusCode.Forbidden, replyStatus.status)

            val statusTelegramId = 293L
            val statusUserId = insertUser(context.database, context.userRepository, statusTelegramId, "status-only")
            val statusAssignment = insertRoleAssignment(context.database, statusUserId, Role.MANAGER, clubId)
            grantPermission(context.database, statusAssignment, PermissionCodes.SUPPORT_STATUS_MANAGE)
            val statusTicketId = createTicket(context, clubId, guestId)
            assertSupportListDenied(statusTelegramId, clubId, statusTicketId)
            val statusReply = supportReply(statusTelegramId, statusTicketId)
            assertEquals(HttpStatusCode.Forbidden, statusReply.status)
            assertEquals(HttpStatusCode.OK, supportAssign(statusTelegramId, statusTicketId).status)
            assertEquals(HttpStatusCode.Conflict, supportStatus(statusTelegramId, statusTicketId, "closed").status)
        }

    @Test
    fun `guest without role cannot list tickets`() = withSupportAdminApp { context ->
        val telegramId = 301L
        insertUser(context.database, context.userRepository, telegramId, "guest")
        val clubId = insertClub(context.database, "Guest Club")

        val response =
            client.get("/api/support/tickets?clubId=$clubId") {
                withInitData(createInitData(userId = telegramId))
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        response.assertNoStoreHeaders()
        assertEquals("support_ticket_forbidden", response.errorCode())
    }

    @Test
    fun `take reply and status remain bounded`() = withSupportAdminApp { context ->
        val adminTelegramId = 401L
        val adminUserId = insertUser(context.database, context.userRepository, adminTelegramId, "admin")
        val clubId = insertClub(context.database, "Support Club")
        val roleAssignmentId = insertRoleAssignment(context.database, adminUserId, Role.MANAGER, clubId)
        grantPermission(context.database, roleAssignmentId, PermissionCodes.SUPPORT_REPLY)
        grantPermission(context.database, roleAssignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)

        val ownerUserId = insertUser(context.database, context.userRepository, 402L, "guest")
        val ticketId = createTicket(context, clubId, ownerUserId)

        val assignResponse =
            client.post("/api/support/tickets/$ticketId/assign") {
                withInitData(createInitData(userId = adminTelegramId))
            }

        assertEquals(HttpStatusCode.OK, assignResponse.status)
        assignResponse.assertNoStoreHeaders()
        val assignPayload = json.parseToJsonElement(assignResponse.bodyAsText()).jsonObject
        assertEquals(ticketId, assignPayload["id"]!!.jsonPrimitive.long)
        assertEquals(clubId, assignPayload["clubId"]!!.jsonPrimitive.long)

        val statusResponse =
            client.post("/api/support/tickets/$ticketId/status") {
                withInitData(createInitData(userId = adminTelegramId))
                contentType(ContentType.Application.Json)
                setBody("""{"status":"answered"}""")
            }

        assertEquals(HttpStatusCode.Conflict, statusResponse.status)
        statusResponse.assertNoStoreHeaders()
        assertEquals("invalid_state", statusResponse.errorCode())

        val replyResponse =
            client.post("/api/support/tickets/$ticketId/reply") {
                withInitData(createInitData(userId = adminTelegramId))
                contentType(ContentType.Application.Json)
                setBody("""{"text":"Reply","attachments":"[]"}""")
            }

        assertEquals(HttpStatusCode.OK, replyResponse.status)
        replyResponse.assertNoStoreHeaders()
        val replyPayload = json.parseToJsonElement(replyResponse.bodyAsText()).jsonObject
        assertEquals(ticketId, replyPayload["ticketId"]!!.jsonPrimitive.long)
        assertEquals(clubId, replyPayload["clubId"]!!.jsonPrimitive.long)
        assertFalse(replyPayload.containsKey("ownerUserId"))
        assertEquals("in_progress", replyPayload["ticketStatus"]!!.jsonPrimitive.content)
        assertNotNull(replyPayload["replyMessageId"]?.jsonPrimitive?.long)
        assertTrue(replyPayload["replyCreatedAt"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `take and first reply accept NEW while generic status remains disabled`() {
        withSupportAdminApp { context ->
            val adminTelegramId = 431L
            val adminUserId = insertUser(context.database, context.userRepository, adminTelegramId, "admin")
            val clubId = insertClub(context.database, "Contained Support Club")
            val roleAssignmentId = insertRoleAssignment(context.database, adminUserId, Role.CLUB_ADMIN, clubId)
            grantPermission(context.database, roleAssignmentId, PermissionCodes.SUPPORT_REPLY)
            grantPermission(context.database, roleAssignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)

            val ownerUserId = insertUser(context.database, context.userRepository, 432L, "guest")
            val assignId = createTicket(context, clubId, ownerUserId)
            val replyId = createTicket(context, clubId, ownerUserId)
            val fromNewId = createTicket(context, clubId, ownerUserId)
            val toNewId = createTicket(context, clubId, ownerUserId)
            seedTicketStatus(context.database, toNewId, TicketStatus.OPENED)
            val fromNewBefore = context.supportService.getTicket(fromNewId)
            val toNewBefore = context.supportService.getTicket(toNewId)

            val assignResponse =
                client.post("/api/support/tickets/$assignId/assign") {
                    withInitData(createInitData(userId = adminTelegramId))
                }
            assertEquals(HttpStatusCode.OK, assignResponse.status)
            assertEquals(TicketStatus.IN_PROGRESS, context.supportService.getTicket(assignId)?.status)

            val replyResponse =
                client.post("/api/support/tickets/$replyId/reply") {
                    withInitData(createInitData(userId = adminTelegramId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"Reply","attachments":"[]"}""")
                }
            assertEquals(HttpStatusCode.OK, replyResponse.status)
            assertEquals(TicketStatus.IN_PROGRESS, context.supportService.getTicket(replyId)?.status)

            val fromNewResponse =
                client.post("/api/support/tickets/$fromNewId/status") {
                    withInitData(createInitData(userId = adminTelegramId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"status":"closed"}""")
                }
            fromNewResponse.assertGenericInvalidState()

            val toNewResponse =
                client.post("/api/support/tickets/$toNewId/status") {
                    withInitData(createInitData(userId = adminTelegramId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"status":"new"}""")
                }
            toNewResponse.assertGenericInvalidState()

            assertEquals(fromNewBefore, context.supportService.getTicket(fromNewId))
            assertEquals(toNewBefore, context.supportService.getTicket(toNewId))
            assertEquals(1L, messageCount(context.database, assignId))
            assertEquals(2L, messageCount(context.database, replyId))
            assertEquals(1L, messageCount(context.database, fromNewId))
            assertEquals(1L, messageCount(context.database, toNewId))
        }
    }

    @Test
    fun `admin status invalid json returns 409 and no-store headers`() = withSupportAdminApp { context ->
        val adminTelegramId = 451L
        val adminUserId = insertUser(context.database, context.userRepository, adminTelegramId, "admin")
        val clubId = insertClub(context.database, "Invalid Json Club")
        val roleAssignmentId = insertRoleAssignment(context.database, adminUserId, Role.MANAGER, clubId)
        grantPermission(context.database, roleAssignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)

        val ownerUserId = insertUser(context.database, context.userRepository, 452L, "guest")
        val ticketId = createTicket(context, clubId, ownerUserId)
        val before = assertNotNull(context.supportService.getTicket(ticketId))

        val response =
            client.post("/api/support/tickets/$ticketId/status") {
                withInitData(createInitData(userId = adminTelegramId))
                contentType(ContentType.Application.Json)
                setBody("{")
            }

        response.assertGenericInvalidState()
        assertEquals(before, context.supportService.getTicket(ticketId))
        assertEquals(1L, messageCount(context.database, ticketId))
    }

    @Test
    fun `admin endpoints without init data return 401 and no-store headers`() = withSupportAdminApp {
        val listResponse = client.get("/api/support/tickets?clubId=1")
        assertEquals(HttpStatusCode.Unauthorized, listResponse.status)
        listResponse.assertNoStoreHeaders()
        assertEquals("unauthorized", listResponse.errorCode())

        val assignResponse = client.post("/api/support/tickets/1/assign")
        assertEquals(HttpStatusCode.Unauthorized, assignResponse.status)
        assignResponse.assertNoStoreHeaders()
        assertEquals("unauthorized", assignResponse.errorCode())

        val statusResponse =
            client.post("/api/support/tickets/1/status") {
                contentType(ContentType.Application.Json)
                setBody("""{"status":"answered"}""")
            }
        assertEquals(HttpStatusCode.Unauthorized, statusResponse.status)
        statusResponse.assertNoStoreHeaders()
        assertEquals("unauthorized", statusResponse.errorCode())

        val replyResponse =
            client.post("/api/support/tickets/1/reply") {
                contentType(ContentType.Application.Json)
                setBody("""{"text":"Reply"}""")
            }
        assertEquals(HttpStatusCode.Unauthorized, replyResponse.status)
        replyResponse.assertNoStoreHeaders()
        assertEquals("unauthorized", replyResponse.errorCode())
    }

    @Test
    fun `admin without club access cannot manage tickets`() = withSupportAdminApp { context ->
        val adminTelegramId = 501L
        val adminUserId = insertUser(context.database, context.userRepository, adminTelegramId, "admin")
        val clubId = insertClub(context.database, "Scoped Club")
        val otherClubId = insertClub(context.database, "Other Scoped Club")
        val roleAssignmentId = insertRoleAssignment(context.database, adminUserId, Role.CLUB_ADMIN, otherClubId)
        grantPermission(context.database, roleAssignmentId, PermissionCodes.SUPPORT_VIEW)
        grantPermission(context.database, roleAssignmentId, PermissionCodes.SUPPORT_REPLY)
        grantPermission(context.database, roleAssignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)

        val ownerUserId = insertUser(context.database, context.userRepository, 502L, "guest")
        val ticketId = createTicket(context, clubId, ownerUserId)

        val listResponse =
            client.get("/api/support/tickets?clubId=$clubId") {
                withInitData(createInitData(userId = adminTelegramId))
            }

        assertEquals(HttpStatusCode.Forbidden, listResponse.status)
        listResponse.assertNoStoreHeaders()
        assertEquals("support_ticket_forbidden", listResponse.errorCode())

        val assignResponse =
            client.post("/api/support/tickets/$ticketId/assign") {
                withInitData(createInitData(userId = adminTelegramId))
            }

        assertEquals(HttpStatusCode.NotFound, assignResponse.status)
        assignResponse.assertNoStoreHeaders()
        assertEquals("support_ticket_not_found", assignResponse.errorCode())

        val statusResponse =
            client.post("/api/support/tickets/$ticketId/status") {
                withInitData(createInitData(userId = adminTelegramId))
                contentType(ContentType.Application.Json)
                setBody("""{"status":"closed"}""")
            }

        assertEquals(HttpStatusCode.NotFound, statusResponse.status)
        statusResponse.assertNoStoreHeaders()
        assertEquals("support_ticket_not_found", statusResponse.errorCode())

        val replyResponse =
            client.post("/api/support/tickets/$ticketId/reply") {
                withInitData(createInitData(userId = adminTelegramId))
                contentType(ContentType.Application.Json)
                setBody("""{"text":"Reply"}""")
            }

        assertEquals(HttpStatusCode.NotFound, replyResponse.status)
        replyResponse.assertNoStoreHeaders()
        assertEquals("support_ticket_not_found", replyResponse.errorCode())
    }

    @Test
    fun `invalid status filter returns 400`() = withSupportAdminApp { context ->
        val adminTelegramId = 601L
        insertUser(context.database, context.userRepository, adminTelegramId, "admin")
        val clubId = insertClub(context.database, "Filter Club")

        val response =
            client.get("/api/support/tickets?clubId=$clubId&status=bad") {
                withInitData(createInitData(userId = adminTelegramId))
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        response.assertNoStoreHeaders()
        assertEquals("validation_error", response.errorCode())
    }

    @Test
    fun `staff reads reject non canonical positive decimal club and ticket ids`() =
        withSupportAdminApp { context ->
            val telegramId = 650L
            insertUser(context.database, context.userRepository, telegramId, "strict-id-reader")
            val invalidIds = listOf("+1", "01", "0", "-1", "%20", "1x", "9223372036854775808")

            invalidIds.forEach { rawId ->
                val listResponse =
                    client.get("/api/support/tickets?clubId=$rawId") {
                        withInitData(createInitData(userId = telegramId))
                    }
                assertEquals(HttpStatusCode.BadRequest, listResponse.status, "clubId=$rawId")
                listResponse.assertNoStoreHeaders()
                assertEquals("validation_error", listResponse.errorCode(), "clubId=$rawId")

                val detailResponse =
                    client.get("/api/support/tickets/$rawId") {
                        withInitData(createInitData(userId = telegramId))
                    }
                assertEquals(HttpStatusCode.BadRequest, detailResponse.status, "ticketId=$rawId")
                detailResponse.assertNoStoreHeaders()
                assertEquals("validation_error", detailResponse.errorCode(), "ticketId=$rawId")
            }
        }

    @Test
    fun `staff read persistence failures return generic internal errors without database detail`() {
        val rawDetail = "SENTINEL ticket_messages_ticket_id_fkey SELECT FROM ticket_messages SQLState 23503"
        val failingService = mockk<StaffSupportReadService>()
        coEvery { failingService.listStaffTicketsForClub(any(), any()) } returns
            SupportServiceResult.Failure(SupportServiceError.PersistenceFailure)
        coEvery { failingService.getStaffTicket(any(), any()) } returns
            SupportServiceResult.Failure(SupportServiceError.PersistenceFailure)

        withSupportAdminApp(staffSupportReadServiceFactory = { failingService }) { context ->
            val telegramId = 660L
            val userId = insertUser(context.database, context.userRepository, telegramId, "failure-reader")
            val clubId = insertClub(context.database, "Failure Reader Club")
            val assignmentId = insertRoleAssignment(context.database, userId, Role.MANAGER, clubId)
            grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_VIEW)

            val responses =
                listOf(
                    client.get("/api/support/tickets?clubId=$clubId") {
                        withInitData(createInitData(userId = telegramId))
                    },
                    client.get("/api/support/tickets/1") {
                        withInitData(createInitData(userId = telegramId))
                    },
                )

            responses.forEach { response ->
                assertEquals(HttpStatusCode.InternalServerError, response.status)
                response.assertNoStoreHeaders()
                val body = response.bodyAsText()
                assertEquals("internal_error", errorCode(body))
                assertFalse(body.contains(rawDetail))
                assertFalse(body.contains("SENTINEL"))
                assertFalse(body.contains("ticket_messages_ticket_id_fkey"))
                assertFalse(body.contains("SELECT FROM", ignoreCase = true))
                assertFalse(body.contains("SQLState", ignoreCase = true))
            }
        }
    }

    @Test
    fun `staff detail cancellation propagates to the application boundary`() {
        val cancellation = CancellationException("cancelled staff support thread read")
        val cancellingService = mockk<StaffSupportReadService>()
        coEvery { cancellingService.getStaffTicket(any(), any()) } throws cancellation

        withSupportAdminApp(
            staffSupportReadServiceFactory = { cancellingService },
            installCancellationMarker = true,
        ) { context ->
            val telegramId = 670L
            val userId = insertUser(context.database, context.userRepository, telegramId, "cancellation-reader")
            val clubId = insertClub(context.database, "Cancellation Reader Club")
            val assignmentId = insertRoleAssignment(context.database, userId, Role.CLUB_ADMIN, clubId)
            grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_VIEW)

            val response =
                client.get("/api/support/tickets/1") {
                    withInitData(createInitData(userId = telegramId))
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("true", response.headers[SUPPORT_STAFF_CANCELLATION_PROPAGATED_HEADER])
            response.assertNoStoreHeaders()
        }
    }

    @Test
    fun `admin without internal user returns forbidden for support tickets`() = withSupportAdminApp { context ->
        val clubId = insertClub(context.database, "Missing User Club")

        val response =
            client.get("/api/support/tickets?clubId=$clubId") {
                withInitData(createInitData(userId = 700L))
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        response.assertNoStoreHeaders()
        assertEquals("forbidden", response.errorCode())
    }
}

open class SupportAdminRoutesFixture {
    protected val json = Json { ignoreUnknownKeys = true }

    protected suspend fun ApplicationTestBuilder.assertStaffCanRead(
        context: TestContext,
        role: Role,
        telegramId: Long,
    ) {
        val userId = insertUser(context.database, context.userRepository, telegramId, "reader-$role")
        val clubId = insertClub(context.database, "$role Read Club")
        val assignmentId = insertRoleAssignment(context.database, userId, role, clubId)
        grantPermission(context.database, assignmentId, PermissionCodes.SUPPORT_VIEW)
        val guestId = insertUser(context.database, context.userRepository, telegramId + 10_000, "reader-guest-$role")
        val ticketId = createTicket(context, clubId, guestId, text = "complete-thread-$role")

        val clubsResponse =
            client.get("/api/support/staff/clubs") {
                withInitData(createInitData(userId = telegramId))
            }
        assertEquals(HttpStatusCode.OK, clubsResponse.status)
        clubsResponse.assertNoStoreHeaders()
        val clubs = json.parseToJsonElement(clubsResponse.bodyAsText()).jsonArray
        assertEquals(1, clubs.size)
        val permittedClub = clubs.single().jsonObject
        assertEquals(setOf("id", "name", "canReply", "canTakeInWork", "canManageStatus"), permittedClub.keys)
        assertEquals(clubId, permittedClub["id"]!!.jsonPrimitive.long)
        assertFalse(permittedClub["canReply"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(permittedClub["canTakeInWork"]!!.jsonPrimitive.content.toBoolean())

        val listResponse =
            client.get("/api/support/tickets?clubId=$clubId&status=new") {
                withInitData(createInitData(userId = telegramId))
            }
        assertEquals(HttpStatusCode.OK, listResponse.status)
        listResponse.assertNoStoreHeaders()
        val items = json.parseToJsonElement(listResponse.bodyAsText()).jsonArray
        assertEquals(listOf(ticketId), items.map { it.jsonObject["id"]!!.jsonPrimitive.long })

        val detailResponse =
            client.get("/api/support/tickets/$ticketId") {
                withInitData(createInitData(userId = telegramId))
            }
        assertEquals(HttpStatusCode.OK, detailResponse.status)
        detailResponse.assertNoStoreHeaders()
        val payload = json.parseToJsonElement(detailResponse.bodyAsText()).jsonObject
        assertEquals(setOf("ticket", "messages"), payload.keys)
        val ticket = payload.getValue("ticket").jsonObject
        assertEquals(setOf("id", "clubId", "topic", "status", "createdAt", "updatedAt"), ticket.keys)
        assertEquals(ticketId, ticket["id"]!!.jsonPrimitive.long)
        assertEquals(clubId, ticket["clubId"]!!.jsonPrimitive.long)
        val messages = payload.getValue("messages").jsonArray
        assertEquals(1, messages.size)
        val initialMessage = messages.single().jsonObject
        assertEquals(setOf("id", "senderType", "text", "attachments", "createdAt"), initialMessage.keys)
        assertEquals("complete-thread-$role", initialMessage["text"]!!.jsonPrimitive.content)
    }

    protected suspend fun ApplicationTestBuilder.assertSupportListDenied(
        telegramId: Long,
        clubId: Long,
        ticketId: Long?,
    ) {
        val response =
            client.get("/api/support/tickets?clubId=$clubId") {
                withInitData(createInitData(userId = telegramId))
            }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        response.assertNoStoreHeaders()
        val body = response.bodyAsText()
        assertEquals("support_ticket_forbidden", errorCode(body))
        if (ticketId != null) {
            assertFalse(body.contains("\"ticketId\""))
            assertFalse(body.contains("ticket_id"))
        }
    }

    protected suspend fun ApplicationTestBuilder.assertMutationDenied(
        telegramId: Long,
        ticketId: Long,
        includeReply: Boolean,
        includeStatus: Boolean,
    ) {
        if (includeReply) {
            assertEquals(HttpStatusCode.Forbidden, supportReply(telegramId, ticketId).status)
        }
        if (includeStatus) {
            assertEquals(HttpStatusCode.Forbidden, supportAssign(telegramId, ticketId).status)
            assertEquals(HttpStatusCode.Forbidden, supportStatus(telegramId, ticketId, "closed").status)
        }
    }

    protected suspend fun ApplicationTestBuilder.supportAssign(
        telegramId: Long,
        ticketId: Long,
    ): HttpResponse =
        client.post("/api/support/tickets/$ticketId/assign") {
            withInitData(createInitData(userId = telegramId))
        }

    protected suspend fun ApplicationTestBuilder.supportStatus(
        telegramId: Long,
        ticketId: Long,
        status: String,
    ): HttpResponse =
        client.post("/api/support/tickets/$ticketId/status") {
            withInitData(createInitData(userId = telegramId))
            contentType(ContentType.Application.Json)
            setBody("""{"status":"$status"}""")
        }

    protected suspend fun ApplicationTestBuilder.supportReply(
        telegramId: Long,
        ticketId: Long,
    ): HttpResponse =
        client.post("/api/support/tickets/$ticketId/reply") {
            withInitData(createInitData(userId = telegramId))
            contentType(ContentType.Application.Json)
            setBody("""{"text":"Reply"}""")
        }

    private data class DbSetup(
        val dataSource: JdbcDataSource,
        val database: Database,
    )

    protected data class TestContext(
        val database: Database,
        val supportService: SupportService,
        val userRepository: TestUserRepository,
        val telegramSender: RecordingTelegramSender,
    )

    protected fun withSupportAdminApp(
        staffSupportReadServiceFactory: (() -> StaffSupportReadService)? = null,
        supportServiceFactory: ((SupportService) -> SupportService)? = null,
        installCancellationMarker: Boolean = false,
        block: suspend ApplicationTestBuilder.(TestContext) -> Unit,
    ) = testApplication {
        val setup = prepareDatabase()
        val supportRepository = SupportRepository(setup.database)
        val realSupportService = SupportServiceImpl(supportRepository)
        val routedStaffSupportReadService = staffSupportReadServiceFactory?.invoke() ?: realSupportService
        val routedSupportService = supportServiceFactory?.invoke(realSupportService) ?: realSupportService
        val userRepository = TestUserRepository()
        val userRoleRepository = ExposedUserRoleRepository(setup.database)
        val userRolePermissionRepository = ExposedUserRolePermissionRepository(setup.database)
        val telegramSender = RecordingTelegramSender()
        application {
            install(ContentNegotiation) { json() }
            if (installCancellationMarker) {
                install(StatusPages) {
                    exception<CancellationException> { call, _ ->
                        call.response.headers.append(SUPPORT_STAFF_CANCELLATION_PROPAGATED_HEADER, "true")
                        call.respondText("cancellation_propagated", status = HttpStatusCode.ServiceUnavailable)
                    }
                }
            } else {
                installJsonErrorPages()
            }
            install(RbacPlugin) {
                this.userRepository = userRepository
                this.userRoleRepository = userRoleRepository
                this.auditLogRepository = AuditLogRepositoryImpl(setup.database)
                principalExtractor = { call ->
                    if (call.attributes.contains(MiniAppUserKey)) {
                        val principal = call.attributes[MiniAppUserKey]
                        TelegramPrincipal(principal.id, principal.username)
                    } else {
                        null
                    }
                }
            }
            supportRoutes(
                supportService = routedSupportService,
                staffSupportReadService = routedStaffSupportReadService,
                userRepository = userRepository,
                userRolePermissionRepository = userRolePermissionRepository,
                clubsRepository = ClubsDbRepository(setup.database),
                sendTelegram = telegramSender.send,
                botTokenProvider = { TEST_BOT_TOKEN },
            )
        }
        block(TestContext(setup.database, realSupportService, userRepository, telegramSender))
    }

    private fun prepareDatabase(): DbSetup {
        val dbName = "support_admin_routes_${UUID.randomUUID()}"
        val dataSource =
            JdbcDataSource().apply {
                setURL("jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1")
                user = "sa"
                password = ""
            }
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/common", "classpath:db/migration/h2")
            .load()
            .migrate()
        val database = Database.connect(dataSource)
        return DbSetup(dataSource = dataSource, database = database)
    }

    protected suspend fun createTicket(
        context: TestContext,
        clubId: Long,
        userId: Long,
        text: String = "Need help",
    ): Long {
        val result =
            context.supportService.createTicket(
                clubId = clubId,
                userId = userId,
                bookingId = null,
                listEntryId = null,
                topic = TicketTopic.OTHER,
                text = text,
                attachments = null,
            )
        assertTrue(result is SupportServiceResult.Success)
        return result.value.ticket.id
    }

    protected fun seedTicketStatus(
        database: Database,
        ticketId: Long,
        status: TicketStatus,
    ) {
        require(status != TicketStatus.NEW)
        transaction(database) {
            val updated =
                TicketsTable.update({ TicketsTable.id eq ticketId }) {
                    it[TicketsTable.status] = status.wire
                }
            assertEquals(1, updated)
        }
    }

    protected fun messageCount(
        database: Database,
        ticketId: Long,
    ): Long =
        transaction(database) {
            TicketMessagesTable
                .selectAll()
                .where { TicketMessagesTable.ticketId eq ticketId }
                .count()
        }

    protected fun insertUser(
        database: Database,
        userRepository: TestUserRepository,
        telegramUserId: Long,
        username: String,
    ): Long {
        val id =
            transaction(database) {
            UsersTable
                .insert {
                    it[UsersTable.telegramUserId] = telegramUserId
                    it[UsersTable.username] = username
                    it[UsersTable.displayName] = username
                    it[UsersTable.phoneE164] = null
                }.resultedValues!!
                .single()[UsersTable.id]
            }
        userRepository.register(id, telegramUserId, username)
        return id
    }

    protected fun insertClub(
        database: Database,
        name: String,
    ): Long =
        transaction(database) {
            ClubsTable
                .insert {
                    it[ClubsTable.name] = name
                    it[ClubsTable.description] = null
                    it[ClubsTable.timezone] = "Europe/Moscow"
                    it[ClubsTable.adminChannelId] = null
                    it[ClubsTable.bookingsTopicId] = null
                    it[ClubsTable.checkinTopicId] = null
                    it[ClubsTable.qaTopicId] = null
                }.resultedValues!!
                .single()[ClubsTable.id]
        }

    protected fun insertRoleAssignment(
        database: Database,
        userId: Long,
        role: Role,
        clubId: Long?,
    ): Long =
        transaction(database) {
            UserRolesTable
                .insert {
                    it[UserRolesTable.userId] = userId
                    it[UserRolesTable.roleCode] = role.name
                    it[UserRolesTable.scopeType] = if (clubId == null) "GLOBAL" else "CLUB"
                    it[UserRolesTable.scopeClubId] = clubId
                }.resultedValues!!
                .single()[UserRolesTable.id]
        }

    protected fun grantPermission(
        database: Database,
        userRoleId: Long,
        permission: PermissionCode,
    ) {
        transaction(database) {
            UserRolePermissionsTable.insert {
                it[UserRolePermissionsTable.userRoleId] = userRoleId
                it[UserRolePermissionsTable.permissionCode] = permission.value
            }
        }
    }

    private object UsersTable : Table("users") {
        val id = long("id").autoIncrement()
        val telegramUserId = long("telegram_user_id")
        val username = text("username").nullable()
        val displayName = text("display_name")
        val phoneE164 = text("phone_e164").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    private object ClubsTable : Table("clubs") {
        val id = long("id").autoIncrement()
        val name = text("name")
        val description = text("description").nullable()
        val timezone = text("timezone")
        val adminChannelId = long("admin_channel_id").nullable()
        val bookingsTopicId = integer("bookings_topic_id").nullable()
        val checkinTopicId = integer("checkin_topic_id").nullable()
        val qaTopicId = integer("qa_topic_id").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    private object UserRolesTable : Table("user_roles") {
        val id = long("id").autoIncrement()
        val userId = long("user_id")
        val roleCode = text("role_code")
        val scopeType = text("scope_type")
        val scopeClubId = long("scope_club_id").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    private object UserRolePermissionsTable : Table("user_role_permissions") {
        val userRoleId = long("user_role_id")
        val permissionCode = text("permission_code")
        override val primaryKey = PrimaryKey(userRoleId, permissionCode)
    }

    protected suspend fun HttpResponse.errorCode(): String {
        val raw = bodyAsText()
        return errorCode(raw)
    }

    protected fun errorCode(raw: String): String {
        val parsed = runCatching { Json.parseToJsonElement(raw).jsonObject.errorCodeOrNull() }.getOrNull()
        val extracted = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.getOrNull(1)
        return parsed ?: extracted ?: raw
    }

    protected suspend fun HttpResponse.assertGenericInvalidState(): String {
        assertEquals(HttpStatusCode.Conflict, status)
        assertNoStoreHeaders()
        val raw = bodyAsText()
        val payload = json.parseToJsonElement(raw).jsonObject
        assertEquals("invalid_state", payload["code"]!!.jsonPrimitive.content)
        assertEquals("409", payload["status"]!!.jsonPrimitive.content)
        assertTrue(payload["message"] == null || payload["message"] == JsonNull)
        assertTrue(payload["details"] == null || payload["details"] == JsonNull)
        assertFalse(raw.contains("InvalidState"))
        assertFalse(raw.contains("TicketStatus"))
        assertFalse(raw.contains("Exception"))
        assertFalse(raw.contains("SQL", ignoreCase = true))
        return raw
    }

    private fun JsonObject.errorCodeOrNull(): String? {
        val code = this["code"] as? JsonPrimitive
        if (code != null) {
            return code.content
        }
        val error = this["error"]
        val nestedCode = ((error as? JsonObject)?.get("code") as? JsonPrimitive)?.content
        val legacyCode = (error as? JsonPrimitive)?.content
        return nestedCode ?: legacyCode
    }

    protected fun HttpResponse.assertNoStoreHeaders() {
        assertEquals("no-store", headers[HttpHeaders.CacheControl])
        assertEquals("X-Telegram-Init-Data", headers[HttpHeaders.Vary])
    }

    protected fun assertExternallyIdenticalHeaders(
        first: HttpResponse,
        second: HttpResponse,
    ) {
        assertEquals(second.headers.names(), first.headers.names())
        second.headers.names().forEach { name ->
            assertEquals(second.headers.getAll(name), first.headers.getAll(name), name)
        }
    }

    protected class TestUserRepository : UserRepository {
        private val usersByTelegramId = mutableMapOf<Long, User>()
        private val usersById = mutableMapOf<Long, User>()

        fun register(
            id: Long,
            telegramUserId: Long,
            username: String,
        ) {
            val user = User(id = id, telegramId = telegramUserId, username = username)
            usersByTelegramId[telegramUserId] = user
            usersById[id] = user
        }

        override suspend fun getByTelegramId(id: Long): User? = usersByTelegramId[id]

        override suspend fun getById(id: Long): User? = usersById[id]
    }

    protected class RecordingTelegramSender {
        val requests = mutableListOf<BaseRequest<*, *>>()

        val send: suspend (BaseRequest<*, *>) -> BaseResponse = { request ->
            requests += request
            mockk()
        }
    }
}
