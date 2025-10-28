package com.example.bot.security

import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.plugins.installRbacIfEnabled
import com.example.bot.plugins.rbacSampleRoute
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk

class RbacFeatureFlagTest : FunSpec({
    val auditRepo = mockk<AuditLogRepository>(relaxed = true)
    coEvery { auditRepo.log(any(), any(), any(), any(), any(), any(), any()) } returns 1L

    fun configFor(enabled: Boolean): MapApplicationConfig =
        MapApplicationConfig("app.RBAC_ENABLED" to enabled.toString())

    test("rbac route returns 403 without role and 200 with role when enabled") {
        val users =
            mapOf(
                1L to User(id = 1L, telegramId = 1L, username = "admin"),
                2L to User(id = 2L, telegramId = 2L, username = "guest"),
            )
        val roles = mapOf(1L to setOf(Role.MANAGER))
        val clubs = emptyMap<Long, Set<Long>>()

        testApplication {
            environment {
                config = configFor(true)
            }
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = InMemoryUserRepository(users)
                    userRoleRepository = InMemoryUserRoleRepository(roles, clubs)
                    auditLogRepository = auditRepo
                    principalExtractor = { call ->
                        call.request.header("X-Telegram-Id")?.toLongOrNull()?.let { TelegramPrincipal(it, null) }
                    }
                }
                installRbacIfEnabled()
                rbacSampleRoute()
            }

            val forbidden =
                client.get("/api/secure/ping") {
                    header("X-Telegram-Id", "2")
                }
            forbidden.status shouldBe HttpStatusCode.Forbidden

            val allowed =
                client.get("/api/secure/ping") {
                    header("X-Telegram-Id", "1")
                }
            allowed.status shouldBe HttpStatusCode.OK
        }
    }

    test("rbac route is absent when flag disabled") {
        testApplication {
            environment {
                config = configFor(false)
            }
            application {
                install(ContentNegotiation) { json() }
                installRbacIfEnabled()
                rbacSampleRoute()
            }

            val response =
                client.get("/api/secure/ping")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }
}) {
    private class InMemoryUserRepository(private val users: Map<Long, User>) : UserRepository {
        override suspend fun getByTelegramId(id: Long): User? = users[id]
    }

    private class InMemoryUserRoleRepository(
        private val roles: Map<Long, Set<Role>>,
        private val clubs: Map<Long, Set<Long>>,
    ) : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = roles[userId] ?: emptySet()

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = clubs[userId] ?: emptySet()
    }
}
