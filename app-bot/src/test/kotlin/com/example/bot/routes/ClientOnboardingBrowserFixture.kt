package com.example.bot.routes

import com.example.bot.data.client.JdbcClientContextRepository
import com.example.bot.data.client.onboarding.JdbcClientOnboardingRepository
import com.example.bot.plugins.installJsonErrorPages
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.time.Instant

/** Disposable browser acceptance harness. Test classpath only; no application config or Telegram client. */
object ClientOnboardingBrowserFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        check(System.getenv("CLB_ONBOARDING_BROWSER_FIXTURE") == "synthetic")
        val source =
            JdbcDataSource().apply {
                setURL("jdbc:h2:mem:onboarding_browser;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1")
                user = "sa"
                password = ""
            }
        Flyway
            .configure()
            .dataSource(source)
            .locations("classpath:db/migration/common", "classpath:db/migration/h2")
            .load()
            .migrate()
        embeddedServer(Netty, host = "127.0.0.1", port = 54863) {
            install(ContentNegotiation) { json() }
            installJsonErrorPages()
            platformOwnerRoutes(OWNER) { TEST_BOT_TOKEN }
            clientOnboardingRoutes(
                JdbcClientContextRepository(source),
                JdbcClientOnboardingRepository(source, OWNER),
                "synthetic_bot",
                OWNER,
            ) { TEST_BOT_TOKEN }
            webAppRoutes()
            routing {
                get("/fixture/identity/{persona}") {
                    call.respond(mapOf("initData" to signed(call.parameters["persona"], null)))
                }
                post("/fixture/identity/{persona}") {
                    val body = call.receive<Map<String, String>>()
                    call.respond(mapOf("initData" to signed(call.parameters["persona"], body["startParam"])))
                }
            }
        }.start(wait = true)
    }

    private fun signed(
        persona: String?,
        startParam: String?,
    ): String {
        val user =
            when (persona) {
                "owner" -> OWNER
                "recipient" -> 998102L
                "foreign" -> 998103L
                else -> error("Unknown synthetic persona")
            }
        val fields =
            mutableMapOf(
                "user" to WebAppInitDataTestHelper.encodeUser(user),
                "auth_date" to Instant.now().epochSecond.toString(),
            )
        if (startParam != null) {
            require(Regex("ci_[A-Za-z0-9_-]{43}").matches(startParam))
            fields["start_param"] = startParam
        }
        return WebAppInitDataTestHelper.createInitData(TEST_BOT_TOKEN, fields)
    }

    private const val OWNER = 998101L
}
