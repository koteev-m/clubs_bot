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
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals

/** Shared Ktor data contract transport; database fixtures remain disposable. */
open class ClientOnboardingRoutesTestBase {
    protected open fun database(block: (DataSource) -> Unit) {
        val source =
            JdbcDataSource().apply {
                setURL(
                    "jdbc:h2:mem:onboarding_api_${UUID.randomUUID()};" +
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

    protected fun withApp(
        source: DataSource,
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

    protected suspend fun ApplicationTestBuilder.requestAs(
        path: String,
        user: Long,
        body: JsonObject? = null,
        method: HttpMethod = if (body == null) HttpMethod.Get else HttpMethod.Post,
        key: String = UUID.randomUUID().toString(),
    ): HttpResponse =
        client.request(path) {
            this.method = method
            header("X-Telegram-Init-Data", signed(user))
            if (body != null) {
                header("Content-Type", "application/json")
                header("Idempotency-Key", key)
                setBody(body.toString())
            }
        }

    protected suspend fun ApplicationTestBuilder.ok(
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

    protected fun signed(user: Long): String =
        WebAppInitDataTestHelper.createInitData(
            TEST_BOT_TOKEN,
            mapOf(
                "user" to WebAppInitDataTestHelper.encodeUser(user),
                "auth_date" to Instant.now().epochSecond.toString(),
                "start_param" to "ci_" + "s".repeat(43),
            ),
        )

    protected fun obj(vararg pairs: Pair<String, String>) =
        JsonObject(
            pairs.associate {
                it.first to
                    JsonPrimitive(it.second)
            },
        )

    protected fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

    protected fun version(value: JsonObject) = obj("expectedVersion" to value.string("version"))

    protected fun venue() =
        obj(
            "venueName" to "Synthetic Venue",
            "countryCode" to "RU",
            "city" to "Synthetic City",
            "address" to "Synthetic Street 1",
            "timezone" to "Europe/Moscow",
        )

    protected fun DataSource.scalar(sql: String): String =
        connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.next())
                    rows.getString(1)
                }
            }
        }

    protected companion object {
        const val P = "/api/platform/onboarding"
        const val C = "/api/client"
        const val OWNER = 998101L
        const val RECIPIENT = 998102L
        const val FOREIGN = 998103L
    }
}
