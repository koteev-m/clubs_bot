package com.example.bot.routes

import com.example.bot.data.finance.ClubRevenueArticle
import com.example.bot.data.finance.ShiftReportTemplateRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AdminFinanceTemplateRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val telegramId = 777L

    @BeforeTest
    fun setUp() {
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = telegramId) }
    }

    @AfterTest
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `create revenue article defaults include_in_total to false`() = withApp { deps ->
        val created =
            ClubRevenueArticle(
                id = 10,
                clubId = 1,
                groupId = 20,
                name = "Бар",
                enabled = true,
                includeInTotal = false,
                showSeparately = false,
                orderIndex = 0,
                createdAt = Instant.parse("2024-06-01T20:00:00Z"),
                updatedAt = Instant.parse("2024-06-01T20:00:00Z"),
            )
        coEvery {
            deps.templateRepository.createRevenueArticle(
                clubId = 1,
                groupId = 20,
                name = "Бар",
                includeInTotal = false,
                showSeparately = false,
                orderIndex = 0,
            )
        } returns created

        val response =
            client.post("/api/admin/clubs/1/finance/template/revenue-articles") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "groupId":20,
                    "name":"Бар"
                }""",
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("false", body["includeInTotal"]!!.jsonPrimitive.content)
        coVerify(exactly = 1) {
            deps.templateRepository.createRevenueArticle(
                clubId = 1,
                groupId = 20,
                name = "Бар",
                includeInTotal = false,
                showSeparately = false,
                orderIndex = 0,
            )
        }
    }

    @Test
    fun `template endpoints return forbidden for non owner role`() = withApp(roles = setOf(Role.MANAGER)) { _ ->
        val response =
            client.get("/api/admin/clubs/1/finance/template") {
                header("X-Telegram-Init-Data", "init")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    private fun withApp(
        roles: Set<Role> = setOf(Role.OWNER),
        clubIds: Set<Long> = setOf(1),
        block: suspend ApplicationTestBuilder.(Deps) -> Unit,
    ) {
        val deps = buildDeps()
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(roles, clubIds)
                    auditLogRepository = mockk(relaxed = true)
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }
                adminFinanceTemplateRoutes(
                    templateRepository = deps.templateRepository,
                    botTokenProvider = { "test" },
                )
            }

            block(this, deps)
        }
    }

    private fun buildDeps(): Deps =
        Deps(
            templateRepository = mockk(),
        )

    private class StubUserRepository : UserRepository {
        override suspend fun getByTelegramId(id: Long): User? = User(id = 1, telegramId = id, username = "tester")

        override suspend fun getById(id: Long): User? = User(id = id, telegramId = id, username = "tester")
    }

    private class StubUserRoleRepository(
        private val roles: Set<Role>,
        private val clubs: Set<Long>,
    ) : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = roles

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = clubs
    }

    private data class Deps(
        val templateRepository: ShiftReportTemplateRepository,
    )
}
