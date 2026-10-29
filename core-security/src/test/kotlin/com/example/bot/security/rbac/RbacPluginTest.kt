package com.example.bot.security.rbac

import com.example.bot.audit.AuditLogRepository
import com.example.bot.audit.StandardAuditAction
import com.example.bot.audit.StandardAuditEntityType
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.security.auth.TelegramPrincipal
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
                auditRepo.append(
                    match {
                        it.actorUserId == null &&
                            it.clubId == null &&
                            it.entityType == StandardAuditEntityType.HTTP_ACCESS &&
                            it.action == StandardAuditAction.ACCESS_DENIED
                    },
                )
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
                auditRepo.append(
                    match {
                        it.actorUserId == 1L &&
                            it.clubId == null &&
                            it.entityType == StandardAuditEntityType.HTTP_ACCESS &&
                            it.action == StandardAuditAction.ACCESS_DENIED
                    },
                )
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
                auditRepo.append(
                    match {
                        it.actorUserId == 1L &&
                            it.clubId == 42L &&
                            it.entityType == StandardAuditEntityType.HTTP_ACCESS &&
                            it.action == StandardAuditAction.ACCESS_DENIED
                    },
                )
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
                auditRepo.append(
                    match {
                        it.actorUserId == 1L &&
                            it.clubId == 42L &&
                            it.entityType == StandardAuditEntityType.HTTP_ACCESS &&
                            it.action == StandardAuditAction.ACCESS_GRANTED
                    },
                )
            }
        }

    @Test
    fun `multipart cancellation stops the original live request stream`() =
        testApplication {
            val releaseProducer = CompletableDeferred<Unit>()
            val auditRepo = relaxedAuditRepo()
            val userRepo = InMemoryUserRepository(mapOf(1L to User(id = 1L, telegramId = 1L, username = "one")))
            val roleRepo = InMemoryUserRoleRepository(mapOf(1L to setOf(Role.MANAGER)), mapOf(1L to setOf(42L)))
            application {
                configureTestSecurity(auditRepo, userRepo, roleRepo)
                routing {
                    authorize(Role.MANAGER) {
                        post("/upload") {
                            try {
                                val source = call.request.receiveChannel()
                                val received = call.receiveChannel()
                                assertFalse(source.isClosedForRead, "producer must still be live")
                                received.cancel(CancellationException("synthetic upload rejection"))
                                assertTrue(source.isClosedForRead, "rejecting the upload must cancel its live producer")
                                call.respondText("rejected")
                            } finally {
                                releaseProducer.complete(Unit)
                            }
                        }
                    }
                }
            }
            val response =
                client.post("/upload") {
                    header("X-Telegram-Id", "1")
                    setBody(
                        object : OutgoingContent.WriteChannelContent() {
                            override val contentType =
                                ContentType.MultiPart.FormData.withParameter("boundary", "live-test")

                            override suspend fun writeTo(channel: ByteWriteChannel) {
                                channel.writeFully("--live-test\r\n".encodeToByteArray())
                                channel.flush()
                                releaseProducer.await()
                            }
                        },
                    )
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("rejected", response.bodyAsText())
        }

    @Test
    fun `JSON and form club scopes retain replay for the endpoint`() =
        testApplication {
            val auditRepo = relaxedAuditRepo()
            val userRepo = InMemoryUserRepository(mapOf(1L to User(id = 1L, telegramId = 1L, username = "one")))
            val roleRepo = InMemoryUserRoleRepository(mapOf(1L to setOf(Role.MANAGER)), mapOf(1L to setOf(42L)))
            application {
                configureTestSecurity(auditRepo, userRepo, roleRepo)
                routing {
                    authorize(Role.MANAGER) {
                        clubScoped(ClubScope.Own) {
                            post("/body-scope") { call.respondText(call.receiveText()) }
                        }
                    }
                }
            }
            for (
            (type, body) in
            listOf(
                ContentType.Application.Json to "{\"clubId\":42,\"value\":\"retained\"}",
                ContentType.Application.FormUrlEncoded to "clubId=42&value=retained",
            )
            ) {
                val response =
                    client.post("/body-scope") {
                        header("X-Telegram-Id", "1")
                        contentType(type)
                        setBody(body)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(body, response.bodyAsText())
            }
        }

    private fun relaxedAuditRepo(): AuditLogRepository {
        val repo = mockk<AuditLogRepository>(relaxed = true)
        coEvery { repo.append(any()) } returns 1L
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
                call.request
                    .header("X-Telegram-Id")
                    ?.toLongOrNull()
                    ?.let { TelegramPrincipal(it, null) }
            }
        }
    }

    private class InMemoryUserRepository(
        private val users: Map<Long, User>,
    ) : UserRepository {
        override suspend fun getByTelegramId(id: Long): User? = users[id]

        override suspend fun getById(id: Long): User? = users[id]
    }

    private class InMemoryUserRoleRepository(
        private val roles: Map<Long, Set<Role>>,
        private val clubs: Map<Long, Set<Long>>,
    ) : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = roles[userId] ?: emptySet()

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = clubs[userId] ?: emptySet()
    }
}
