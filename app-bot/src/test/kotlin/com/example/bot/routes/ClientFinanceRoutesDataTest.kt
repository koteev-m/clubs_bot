package com.example.bot.routes

import com.example.bot.data.client.JdbcClientContextRepository
import com.example.bot.data.client.onboarding.JdbcClientOnboardingRepository
import com.example.bot.plugins.installJsonErrorPages
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientFinanceRoutesDataTest {
    @Test
    fun `finance http config persists after reopening and guards identity authority versions and replay`() =
        database { source ->
            lateinit var club: String
            lateinit var saved: JsonObject
            val body =
                JsonObject(
                    obj("expectedVersion" to "1", "entity" to "bracelet", "action" to "create", "name" to "VIP") +
                        ("orderIndex" to JsonPrimitive(0)),
                )
            withApp(source) {
                club = createClub(RECIPIENT, "Finance")
                val path = "$C/clubs/$club/finance/template"
                for (method in listOf(HttpMethod.Get, HttpMethod.Post)) {
                    val denied =
                        client.request(path) {
                            this.method = method
                            header("X-Debug-Roles", "OWNER,GLOBAL_ADMIN")
                        }
                    assertEquals(HttpStatusCode.Unauthorized, denied.status)
                }
                val expired =
                    WebAppInitDataTestHelper.createInitData(
                        TEST_BOT_TOKEN,
                        mapOf(
                            "user" to WebAppInitDataTestHelper.encodeUser(RECIPIENT),
                            "auth_date" to "1",
                        ),
                    )
                assertEquals(HttpStatusCode.Unauthorized, client.request(path) { header(HEADER, expired) }.status)
                assertEquals("1", ok(path, RECIPIENT).string("version"))
                saved = ok(path, RECIPIENT, body, key = "finance-http-create")
                assertEquals(saved, ok(path, RECIPIENT, body, key = "finance-http-create"))
                assertEquals(HttpStatusCode.Conflict, requestAs(path, RECIPIENT, body).status)
                assertEquals(HttpStatusCode.NotFound, requestAs(path, FOREIGN).status)
                assertEquals(HttpStatusCode.NotFound, requestAs(path, OWNER, body).status)
                val spoof =
                    JsonObject(
                        body + ("clubId" to JsonPrimitive(club)) + ("expectedVersion" to saved.getValue("version")),
                    )
                assertEquals(HttpStatusCode.BadRequest, requestAs(path, RECIPIENT, spoof).status)
            }
            withApp(source) {
                val path = "$C/clubs/$club/finance/template"
                assertEquals(saved, ok(path, RECIPIENT))
                source.sql(
                    "UPDATE client_club_grants SET is_active=FALSE WHERE club_id=$club AND permission_code='client.club.finance.write'",
                )
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs(path, RECIPIENT, body, key = "finance-http-create").status,
                )
                assertEquals(JsonPrimitive(false), ok(path, RECIPIENT)["canWrite"])
                source.sql(
                    "UPDATE client_club_grants SET is_active=FALSE WHERE club_id=$club AND permission_code='client.club.finance.read'",
                )
                assertEquals(HttpStatusCode.NotFound, requestAs(path, RECIPIENT).status)
                source.connection.use { connection ->
                    connection.createStatement().use { stmt ->
                        stmt.executeQuery("SELECT COUNT(*) FROM club_bracelet_types WHERE club_id=$club").use { rows ->
                            assertTrue(rows.next())
                            assertEquals(1, rows.getInt(1))
                        }
                    }
                }
            }
        }

    private suspend fun ApplicationTestBuilder.createClub(
        user: Long,
        name: String,
    ): String {
        val invitation = ok("$P/invitations", OWNER, obj("clientName" to name, "recipientLabel" to name))
        ok(
            "$C/invitations/${invitation.string("id")}/claim",
            user,
            obj(
                "token" to invitation.string("token"),
                "expectedVersion" to invitation.string("version"),
            ),
        )
        return ok(
            "$C/clients/${invitation.string("clientId")}/clubs",
            user,
            obj(
                "venueName" to name,
                "countryCode" to "RU",
                "cityId" to "524901",
                "address" to "Synthetic address $name",
            ),
        ).string("id")
    }

    private fun database(block: (JdbcDataSource) -> Unit) {
        val source =
            JdbcDataSource().apply {
                setURL(
                    "jdbc:h2:mem:layout_api_${UUID.randomUUID()};" +
                        "MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
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
        try {
            block(source)
        } finally {
            source.connection.use { it.createStatement().use { statement -> statement.execute("SHUTDOWN") } }
        }
    }

    private fun withApp(
        source: JdbcDataSource,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            installJsonErrorPages()
            clientOnboardingRoutes(
                JdbcClientContextRepository(source),
                JdbcClientOnboardingRepository(source, OWNER),
                "synthetic_bot",
                OWNER,
            ) {
                TEST_BOT_TOKEN
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.requestAs(
        path: String,
        user: Long,
        body: JsonObject? = null,
        method: HttpMethod = if (body == null) HttpMethod.Get else HttpMethod.Post,
        key: String = UUID.randomUUID().toString(),
    ): HttpResponse =
        client.request(path) {
            this.method = method
            header(HEADER, signed(user))
            if (body != null) {
                header("Content-Type", "application/json")
                header("Idempotency-Key", key)
                setBody(body.toString())
            }
        }

    private suspend fun ApplicationTestBuilder.ok(
        path: String,
        user: Long,
        body: JsonObject? = null,
        method: HttpMethod = if (body == null) HttpMethod.Get else HttpMethod.Post,
        key: String = UUID.randomUUID().toString(),
    ): JsonObject {
        val response = requestAs(path, user, body, method, key)
        val text = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, text)
        assertEquals("no-store", response.headers["Cache-Control"])
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun signed(user: Long): String =
        WebAppInitDataTestHelper.createInitData(
            TEST_BOT_TOKEN,
            mapOf(
                "user" to WebAppInitDataTestHelper.encodeUser(user),
                "auth_date" to Instant.now().epochSecond.toString(),
            ),
        )

    private fun obj(vararg pairs: Pair<String, String>) =
        JsonObject(
            pairs.associate {
                it.first to
                    JsonPrimitive(it.second)
            },
        )

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

    private fun JdbcDataSource.sql(sql: String) =
        connection.use { connection ->
            connection.createStatement().use { it.executeUpdate(sql) }
        }

    private companion object {
        const val P = "/api/platform/onboarding"
        const val C = "/api/client"
        const val HEADER = "X-Telegram-Init-Data"
        const val OWNER = 998201L
        const val RECIPIENT = 998202L
        const val FOREIGN = 998203L
    }
}
