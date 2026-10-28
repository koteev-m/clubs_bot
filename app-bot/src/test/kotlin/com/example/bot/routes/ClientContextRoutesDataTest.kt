package com.example.bot.routes

import com.example.bot.data.admin.AdminClubsDbRepository
import com.example.bot.data.client.ClientAudience
import com.example.bot.data.client.JdbcClientContextRepository
import com.example.bot.data.clubs.ClubsDbRepository
import com.example.bot.data.security.ExposedUserRepository
import com.example.bot.data.security.ExposedUserRolePermissionRepository
import com.example.bot.data.support.SupportRepository
import com.example.bot.data.support.SupportServiceImpl
import com.example.bot.plugins.DataSourceHolder
import com.example.bot.plugins.TrustedBotConnection
import com.example.bot.plugins.configureSecurity
import com.example.bot.plugins.installJsonErrorPages
import com.example.bot.support.SupportReplyDeliveryService
import com.example.bot.testing.createInitData
import com.example.bot.webapp.TEST_BOT_TOKEN
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class ClientContextRoutesDataTest {
    @Test
    fun `signed A B staff and membership-only identities see exact persisted grants`() =
        withDatabase { source ->
            withApp(source) {
                assertContext(
                    99001,
                    """{"clients":[{"id":"101","name":"Client A","clubs":[{"id":"1001","name":"A1","permissions":["client.club.read"]},{"id":"1002","name":"A2","permissions":["client.club.read"]}]}]}""",
                )
                assertContext(
                    99002,
                    """{"clients":[{"id":"102","name":"FOREIGN Client B","clubs":[{"id":"2001","name":"FOREIGN B1","permissions":["client.club.read"]}]}]}""",
                )
                assertContext(
                    99003,
                    """{"clients":[{"id":"101","name":"Client A","clubs":[{"id":"1001","name":"A1","permissions":["client.club.read"]}]}]}""",
                )
                assertContext(99004, """{"clients":[{"id":"101","name":"Client A","clubs":[]}]}""")
                assertContext(99005, """{"clients":[]}""")
                assertContext(99999, """{"clients":[]}""")
                val detail = getAs("/api/client/clubs/1001", 99003)
                assertEquals(HttpStatusCode.OK, detail.status)
                assertEquals(
                    Json.parseToJsonElement(
                        """{"clientId":"101","id":"1001","name":"A1","permissions":["client.club.read"]}""",
                    ),
                    Json.parseToJsonElement(detail.bodyAsText()),
                )
                for (pair in listOf(
                    99001L to 2001L,
                    99002L to 1001L,
                    99003L to 1002L,
                    99004L to 1001L,
                    99005L to 1001L,
                )) {
                    getAs(
                        "/api/client/clubs/${pair.second}",
                        pair.first,
                    ).assertClientError(HttpStatusCode.NotFound, "not_found")
                }
            }
        }

    @Test
    fun `committed deactivation is visible on the next request without a cached authorization`() =
        withDatabase { source ->
            withApp(source) {
                assertEquals(HttpStatusCode.OK, getAs("/api/client/clubs/1001", 99001).status)
                source.sql("UPDATE client_club_grants SET is_active = FALSE WHERE user_id = 99101 AND club_id = 1001")
                getAs("/api/client/clubs/1001", 99001).assertClientError(HttpStatusCode.NotFound, "not_found")
                assertEquals(HttpStatusCode.OK, getAs("/api/client/clubs/1002", 99001).status)
                source.sql("UPDATE client_memberships SET is_active = FALSE WHERE user_id = 99101")
                assertContext(99001, """{"clients":[]}""")
                getAs("/api/client/clubs/1002", 99001).assertClientError(HttpStatusCode.NotFound, "not_found")
                source.sql("UPDATE clients SET is_active = FALSE WHERE id = 101")
                assertContext(99003, """{"clients":[]}""")
                assertEquals(HttpStatusCode.OK, getAs("/api/client/clubs/2001", 99002).status)
                source.sql("UPDATE clients SET is_active = TRUE WHERE id = 101")
                assertContext(99001, """{"clients":[]}""")
                source.sql("UPDATE client_memberships SET is_active = TRUE WHERE user_id = 99101")
                getAs("/api/client/clubs/1001", 99001).assertClientError(HttpStatusCode.NotFound, "not_found")
            }
        }

    @Test
    fun `missing foreign unmapped and revoked details have the same non-disclosing response`() =
        withDatabase { source ->
            source.sql("UPDATE client_club_grants SET is_active = FALSE WHERE user_id = 99101 AND club_id = 1002")
            withApp(source) {
                val bodies = mutableSetOf<String>()
                for (id in listOf(2001L, 777777L, 3001L, 1002L)) {
                    val response = getAs("/api/client/clubs/$id", 99001)
                    response.assertClientError(HttpStatusCode.NotFound, "not_found")
                    bodies += response.bodyAsText()
                }
                assertEquals(1, bodies.size)
            }
        }

    @Test
    fun `server restricted audience filters memberships and allowlist without supplying authority`() =
        withDatabase { source ->
            source.sql("INSERT INTO client_memberships(client_id,user_id,is_active) VALUES (102,99101,TRUE)")
            source.sql(
                "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES (102,99101,2001,'client.club.read',TRUE)",
            )
            withApp(source, ClientAudience.Client(101, setOf(1001))) {
                assertContext(
                    99001,
                    """{"clients":[{"id":"101","name":"Client A","clubs":[{"id":"1001","name":"A1","permissions":["client.club.read"]}]}]}""",
                )
                for (id in listOf(
                    1002,
                    2001,
                )) {
                    getAs("/api/client/clubs/$id", 99001).assertClientError(HttpStatusCode.NotFound, "not_found")
                }
                assertContext(99002, """{"clients":[]}""")
                assertContext(99004, """{"clients":[{"id":"101","name":"Client A","clubs":[]}]}""")
            }
            withApp(source, ClientAudience.Client(101, emptySet())) {
                assertContext(99001, """{"clients":[{"id":"101","name":"Client A","clubs":[]}]}""")
                getAs("/api/client/clubs/1001", 99001).assertClientError(HttpStatusCode.NotFound, "not_found")
            }
        }

    @Test
    fun `same persisted context survives a second application instance and GET never provisions`() =
        withDatabase { source ->
            val before = source.domainCounts()
            var first = ""
            withApp(source) {
                first = getAs("/api/client/context", 99001).bodyAsText()
                getAs("/api/client/context", 99999)
                getAs("/api/client/clubs/1001", 99999)
            }
            withApp(source) {
                assertEquals(first, getAs("/api/client/context", 99001).bodyAsText())
                source.sql("UPDATE client_club_grants SET is_active = FALSE WHERE user_id = 99101")
                assertContext(99001, """{"clients":[{"id":"101","name":"Client A","clubs":[]}]}""")
            }
            assertEquals(before, source.domainCounts())
        }

    @Test
    fun `production RBAC wiring and legacy GLOBAL roles cannot authorize or contaminate client reads`() =
        withDatabase { source ->
            withApp(source) {
                val signedAWithLegacyOwnerB =
                    client.get("/api/client/context") {
                        header("X-Telegram-Init-Data", createInitData(userId = 99001))
                        header("X-Telegram-Id", "99005")
                    }
                assertEquals(getAs("/api/client/context", 99001).bodyAsText(), signedAWithLegacyOwnerB.bodyAsText())
                client
                    .get("/api/client/context") { header("X-Telegram-Id", "99005") }
                    .assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
                assertContext(99005, """{"clients":[]}""")
                assertEquals(HttpStatusCode.OK, getAs("/api/platform/access", 99005).status)
                assertEquals(HttpStatusCode.OK, getAs("/api/admin/clubs", 99005).status)
                assertEquals(HttpStatusCode.Forbidden, getAs("/api/platform/access", 99001).status)
                assertEquals(HttpStatusCode.Forbidden, getAs("/api/admin/clubs", 99001).status)
                assertEquals(HttpStatusCode.Forbidden, getAs("/api/support/tickets?clubId=1001", 99001).status)
                val staffClubs = getAs("/api/support/staff/clubs", 99001)
                assertEquals(HttpStatusCode.OK, staffClubs.status)
                assertEquals("[]", staffClubs.bodyAsText())
                val guestTickets = getAs("/api/support/tickets/my", 99001)
                assertEquals(HttpStatusCode.OK, guestTickets.status)
                assertEquals("[]", guestTickets.bodyAsText())
            }
        }

    private fun withDatabase(block: (JdbcDataSource) -> Unit) {
        val source =
            JdbcDataSource().apply {
                setURL(
                    "jdbc:h2:mem:client_routes_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                )
                user = "sa"
                password = ""
            }
        Flyway
            .configure()
            .dataSource(
                source,
            ).locations("classpath:db/migration/common", "classpath:db/migration/h2")
            .load()
            .migrate()
        source.sql(
            "INSERT INTO users(id,telegram_user_id) VALUES (99101,99001),(99102,99002),(99103,99003),(99104,99004),(99105,99005)",
        )
        source.sql(
            "INSERT INTO clubs(id,name,timezone,is_active) VALUES (1001,'A1','UTC',FALSE),(1002,'A2','UTC',TRUE),(2001,'FOREIGN B1','UTC',TRUE),(3001,'Unmapped','UTC',TRUE)",
        )
        source.sql(
            "INSERT INTO clients(id,display_name,is_active) VALUES (101,'Client A',TRUE),(102,'FOREIGN Client B',TRUE)",
        )
        source.sql(
            "INSERT INTO client_memberships(client_id,user_id,is_active) VALUES (101,99101,TRUE),(102,99102,TRUE),(101,99103,TRUE),(101,99104,TRUE)",
        )
        source.sql("INSERT INTO client_clubs(client_id,club_id) VALUES (101,1001),(101,1002),(102,2001)")
        source.sql(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES (101,99101,1001,'client.club.read',TRUE),(101,99101,1002,'client.club.read',TRUE),(102,99102,2001,'client.club.read',TRUE),(101,99103,1001,'client.club.read',TRUE)",
        )
        source.sql("INSERT INTO user_roles(user_id,role_code,scope_type) VALUES (99105,'OWNER','GLOBAL')")
        try {
            block(source)
        } finally {
            DataSourceHolder.dataSource = null
            source.sql("SHUTDOWN")
        }
    }

    private fun withApp(
        source: JdbcDataSource,
        audience: ClientAudience = ClientAudience.Platform,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            installJsonErrorPages()
            DataSourceHolder.dataSource = source
            configureSecurity()
            val database = Database.connect(source)
            if (audience == ClientAudience.Platform) {
                clientContextRoutes(JdbcClientContextRepository(source)) { TEST_BOT_TOKEN }
            } else {
                clientContextRoutes(
                    JdbcClientContextRepository(
                        source,
                    ),
                    TrustedBotConnection("synthetic-client", audience) {
                        TEST_BOT_TOKEN
                    },
                )
            }
            platformOwnerRoutes(99005) { TEST_BOT_TOKEN }
            adminClubsRoutes(AdminClubsDbRepository(database)) { TEST_BOT_TOKEN }
            val support = SupportServiceImpl(SupportRepository(database))
            supportRoutes(
                supportService = support,
                staffSupportReadService = support,
                userRepository = ExposedUserRepository(database),
                userRolePermissionRepository = ExposedUserRolePermissionRepository(database),
                clubsRepository = ClubsDbRepository(database),
                supportReplyDeliveryService = mockk<SupportReplyDeliveryService>(),
                botTokenProvider = { TEST_BOT_TOKEN },
            )
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.getAs(
        path: String,
        telegramId: Long,
    ): HttpResponse = client.get(path) { header("X-Telegram-Init-Data", createInitData(userId = telegramId)) }

    private suspend fun ApplicationTestBuilder.assertContext(
        telegramId: Long,
        expected: String,
    ) {
        val response = getAs("/api/client/context", telegramId)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(response.bodyAsText()))
    }

    private fun JdbcDataSource.sql(sql: String) =
        connection.use { connection ->
            connection.createStatement().use { it.execute(sql) }
            Unit
        }

    private fun JdbcDataSource.domainCounts(): List<Long> =
        connection.use { connection ->
            listOf(
                "users",
                "clubs",
                "roles",
                "user_roles",
                "user_role_permissions",
                "clients",
                "client_memberships",
                "client_clubs",
                "client_club_grants",
            ).map { table ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM $table").use {
                        it.next()
                        it.getLong(1)
                    }
                }
            }
        }
}
