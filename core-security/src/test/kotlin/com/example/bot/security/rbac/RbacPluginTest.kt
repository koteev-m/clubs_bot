package com.example.bot.security.rbac

import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.security.auth.TelegramPrincipal
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RbacPluginTest {
    @Test
    fun `responds 401 when principal missing`() =
        testApplication {
            val auditRepo = relaxedAuditRepo()
            val userRepo = InMemoryUserRepository(mapOf(1L to User(id = 1L, telegramId = 1L, username = "one")))
            val roleRepo = InMemoryUserRoleRepository(mapOf(1L to setOf(Role.MANAGER)), mapOf(1L to setOf(10L)))

            application {
                install(ContentNegotiation) { json() }
                configureTestSecurity(auditRepo, userRepo, roleRepo)
                routing {
                    authorize(Role.MANAGER) {
                        get("/secure") { call.respondText("ok") }
                    }
                }
            }

            val response = client.get("/secure")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            coVerify(exactly = 1) {
                auditRepo.log(null, "http_access", "/secure", null, "access_denied", any(), any())
            }
        }

    @Test
    fun `responds 403 when role missing`() =
        testApplication {
            val auditRepo = relaxedAuditRepo()
            val userRepo = InMemoryUserRepository(mapOf(1L to User(id = 1L, telegramId = 1L, username = "one")))
            val roleRepo = InMemoryUserRoleRepository(emptyMap(), emptyMap())

            application {
                install(ContentNegotiation) { json() }
                configureTestSecurity(auditRepo, userRepo, roleRepo)
                routing {
                    authorize(Role.MANAGER) {
                        get("/secure") { call.respondText("ok") }
                    }
                }
            }

            val response = client.get("/secure") { header("X-Telegram-Id", "1") }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            coVerify(exactly = 1) {
                auditRepo.log(1L, "http_access", "/secure", null, "access_denied", any(), any())
            }
        }

    @Test
    fun `responds 403 when club scope violated`() =
        testApplication {
            val auditRepo = relaxedAuditRepo()
            val userRepo = InMemoryUserRepository(mapOf(1L to User(id = 1L, telegramId = 1L, username = "one")))
            val roleRepo = InMemoryUserRoleRepository(mapOf(1L to setOf(Role.MANAGER)), mapOf(1L to setOf(5L)))

            application {
                install(ContentNegotiation) { json() }
                configureTestSecurity(auditRepo, userRepo, roleRepo)
                routing {
                    authorize(Role.MANAGER) {
                        clubScoped(ClubScope.Own) {
                            get("/clubs/{clubId}/bookings") { call.respondText("nope") }
                        }
                    }
                }
            }

            val response = client.get("/clubs/42/bookings") { header("X-Telegram-Id", "1") }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            coVerify(exactly = 1) {
                auditRepo.log(1L, "http_access", "/clubs/42/bookings", 42L, "access_denied", any(), any())
            }
        }

    @Test
    fun `responds 200 when access granted`() =
        testApplication {
            val auditRepo = relaxedAuditRepo()
            val userRepo = InMemoryUserRepository(mapOf(1L to User(id = 1L, telegramId = 1L, username = "one")))
            val roleRepo = InMemoryUserRoleRepository(mapOf(1L to setOf(Role.MANAGER)), mapOf(1L to setOf(42L)))

            application {
                install(ContentNegotiation) { json() }
                configureTestSecurity(auditRepo, userRepo, roleRepo)
                routing {
                    authorize(Role.MANAGER) {
                        clubScoped(ClubScope.Own) {
                            get("/clubs/{clubId}/bookings") { call.respondText("ok") }
                        }
                    }
                }
            }

            val response = client.get("/clubs/42/bookings") { header("X-Telegram-Id", "1") }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ok", response.bodyAsText())
            coVerify(exactly = 1) {
                auditRepo.log(1L, "http_access", "/clubs/42/bookings", 42L, "access_granted", any(), any())
            }
        }

    private fun relaxedAuditRepo(): AuditLogRepository {
        val repo = mockk<AuditLogRepository>(relaxed = true)
        coEvery { repo.log(any(), any(), any(), any(), any(), any(), any()) } returns 1L
        return repo
    }

    private fun Application.configureTestSecurity(
        auditRepo: AuditLogRepository,
        userRepo: UserRepository,
        roleRepo: UserRoleRepository,
    ) {
        install(RbacPlugin) {
            userRepository = userRepo
            userRoleRepository = roleRepo
            auditLogRepository = auditRepo
            principalExtractor = { call ->
                call.request.header("X-Telegram-Id")?.toLongOrNull()?.let { TelegramPrincipal(it, null) }
            }
        }
    }

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
