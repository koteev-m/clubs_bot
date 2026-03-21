package com.example.bot.routes

import com.example.bot.data.privacy.PrivacyAdminActor
import com.example.bot.data.privacy.PrivacyAnonymizeResult
import com.example.bot.data.privacy.PrivacyRetentionResult
import com.example.bot.data.privacy.PrivacyService
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.http.ErrorCodes
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AdminPrivacyRoutesTest {
    private val telegramId = 77L
    private val userId = 501L

    @BeforeEach
    fun setUp() {
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = telegramId) }
    }

    @AfterEach
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `unauthorized access does not pass`() = withApp { service ->
        val response =
            client.post("/api/admin/privacy/users/5/anonymize") {
                contentType(ContentType.Application.Json)
                setBody("""{"reason":"cleanup"}""")
            }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        coVerify(exactly = 0) { service.anonymizeUser(any(), any(), any()) }
    }

    @Test
    fun `forbidden access does not pass`() = withApp(roles = setOf(Role.PROMOTER)) { service ->
        val response =
            client.post("/api/admin/privacy/users/5/anonymize") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"reason":"cleanup"}""")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        coVerify(exactly = 0) { service.anonymizeUser(any(), any(), any()) }
    }

    @Test
    fun `successful anonymize uses actor from auth context not request body`() = withApp { service ->
        coEvery {
            service.anonymizeUser(
                5L,
                PrivacyAdminActor(userId = userId, role = Role.OWNER.name),
                "cleanup",
            )
        } returns PrivacyAnonymizeResult(1, 2, 3)

        val response =
            client.post("/api/admin/privacy/users/5/anonymize") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"reason":"cleanup","actorUserId":7,"actorRole":"PROMOTER"}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) {
            service.anonymizeUser(
                5L,
                PrivacyAdminActor(userId = userId, role = Role.OWNER.name),
                "cleanup",
            )
        }
    }

    @Test
    fun `retention run uses actor from auth context`() = withApp { service ->
        coEvery { service.runRetention(PrivacyAdminActor(userId = userId, role = Role.OWNER.name)) } returns
            PrivacyRetentionResult(4)

        val response = client.post("/api/admin/privacy/retention/run") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) { service.runRetention(PrivacyAdminActor(userId = userId, role = Role.OWNER.name)) }
    }

    private fun withApp(
        roles: Set<Role> = setOf(Role.OWNER),
        block: suspend ApplicationTestBuilder.(PrivacyService) -> Unit,
    ) {
        val service = mockk<PrivacyService>(relaxed = true)
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository(userId, telegramId)
                    userRoleRepository = StubUserRoleRepository(roles)
                    auditLogRepository = mockk(relaxed = true)
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }
                adminPrivacyRoutes(service, botTokenProvider = { "test" })
            }
            block(this, service)
        }
    }

    private suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String =
        runCatching {
            Json.parseToJsonElement(bodyAsText()).jsonObject["error"]
                ?.jsonObject?.get("code")
                ?.jsonPrimitive?.content
                ?: Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content
        }.getOrNull().orEmpty()

    private class StubUserRepository(
        private val internalUserId: Long,
        private val telegramId: Long,
    ) : UserRepository {
        override suspend fun getByTelegramId(id: Long): User? =
            User(id = internalUserId, telegramId = id, username = "tester")

        override suspend fun getById(id: Long): User? =
            User(id = id, telegramId = telegramId, username = "tester")
    }

    private class StubUserRoleRepository(private val roles: Set<Role>) : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = roles

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = emptySet()
    }
}
