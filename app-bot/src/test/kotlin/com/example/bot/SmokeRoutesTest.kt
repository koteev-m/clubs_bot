package com.example.bot

import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.repo.ClubRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.guestlists.GuestListRepository
import com.example.bot.observability.DefaultHealthService
import com.example.bot.plugins.DataSourceHolder
import com.example.bot.plugins.MigrationState
import com.example.bot.routes.checkinCompatRoutes
import com.example.bot.routes.clubsPublicRoutes
import com.example.bot.routes.healthRoutes
import com.example.bot.routes.pingRoute
import com.example.bot.security.rbac.RbacPlugin
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.PreparedStatement
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke:
 * 1) /health, /ready, /ping -> 200
 * 2) /api/clubs -> 200 (JSON-массив) или 404
 * 3) POST /api/checkin/qr без тела -> один из {400,415,401,404}
 */
class SmokeRoutesTest {
    @Test
    fun smoke_endpoints_behave_as_expected() =
        testApplication {
            val statement =
                mockk<PreparedStatement>(relaxed = true) {
                    every { execute() } returns true
                }
            val connection =
                mockk<Connection>(relaxed = true) {
                    every { prepareStatement(any()) } returns statement
                }
            val dataSource =
                mockk<DataSource> {
                    every { getConnection() } returns connection
                }

            val previousDataSource = DataSourceHolder.dataSource
            val previousMigrations = MigrationState.migrationsApplied

            application {
                DataSourceHolder.dataSource = dataSource
                MigrationState.migrationsApplied = true

                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository =
                        object : UserRepository {
                            override suspend fun getByTelegramId(id: Long) = null
                        }
                    userRoleRepository =
                        object : UserRoleRepository {
                            override suspend fun listRoles(userId: Long): Set<Role> = emptySet()

                            override suspend fun listClubIdsFor(userId: Long): Set<Long> = emptySet()
                        }
                    auditLogRepository = mockk<AuditLogRepository>(relaxed = true)
                    principalExtractor = { null }
                }

                healthRoutes(DefaultHealthService())
                pingRoute()

                clubsPublicRoutes(
                    repository =
                        object : ClubRepository {
                            override suspend fun listClubs(limit: Int) = emptyList<com.example.bot.data.repo.ClubDto>()
                        },
                )

                checkinCompatRoutes(
                    repository = mockk<GuestListRepository>(relaxed = true),
                    initDataBotTokenProvider = { "test-bot-token" },
                    qrSecretProvider = { "test-qr-secret" },
                )
            }

            try {
                val health = client.get("/health")
                val healthBody = runCatching { health.bodyAsText() }.getOrDefault("")
                assertEquals(
                    HttpStatusCode.OK,
                    health.status,
                    "GET /health should return 200, body=$healthBody",
                )

                val ready = client.get("/ready")
                assertEquals(HttpStatusCode.OK, ready.status, "GET /ready should return 200")

                val ping = client.get("/ping")
                assertEquals(HttpStatusCode.OK, ping.status, "GET /ping should return 200")

                val clubs: HttpResponse = client.get("/api/clubs")
                when (clubs.status) {
                    HttpStatusCode.OK -> {
                        val body = clubs.bodyAsText().trim()
                        assertTrue(
                            body.startsWith("["),
                            "Expected JSON array from /api/clubs",
                        )
                    }
                    HttpStatusCode.NotFound -> assertTrue(true)
                    else -> error("Unexpected status from /api/clubs: ${clubs.status}")
                }

                val checkin = client.post("/api/checkin/qr")
                val acceptable =
                    setOf(
                        HttpStatusCode.BadRequest,
                        HttpStatusCode.UnsupportedMediaType,
                        HttpStatusCode.Unauthorized,
                        HttpStatusCode.NotFound,
                    )
                assertTrue(
                    checkin.status in acceptable,
                    "Unexpected status from /api/checkin/qr: ${checkin.status}",
                )
            } finally {
                DataSourceHolder.dataSource = previousDataSource
                MigrationState.migrationsApplied = previousMigrations
            }
        }
}
