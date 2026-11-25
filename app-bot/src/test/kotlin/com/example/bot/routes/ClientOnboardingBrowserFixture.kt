package com.example.bot.routes

import com.example.bot.data.client.JdbcClientContextRepository
import com.example.bot.data.client.onboarding.JdbcClientOnboardingRepository
import com.example.bot.data.client.onboarding.OnboardingActor
import com.example.bot.data.client.onboarding.OnboardingMutation
import com.example.bot.plugins.installJsonErrorPages
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import io.ktor.http.HttpStatusCode
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
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

        fun migrations() =
            Flyway
                .configure()
                .dataSource(source)
                .locations("classpath:db/migration/common", "classpath:db/migration/h2")

        migrations().target("79").load().migrate()
        // Seed genuine historical clients, memberships and clubs before applying the owner-proof migration.
        val legacy = runBlocking { historicalClubs(JdbcClientOnboardingRepository(source, OWNER)) }
        migrations().load().migrate()
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
                get("/fixture/historical-clubs/{key}") {
                    val clubs = legacy[call.parameters["key"]]
                    if (clubs == null) call.respond(HttpStatusCode.NotFound) else call.respond(clubs)
                }
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

    private suspend fun historicalClubs(repository: JdbcClientOnboardingRepository): Map<String, JsonObject> {
        val names =
            mapOf(
                "preparation" to listOf("Север", "Полярная ночь"),
                "receipt" to listOf("Проверка результата"),
                "team" to listOf("Team A1", "Team A2"),
                "layout" to listOf("Layout acceptance club"),
            )
        val member = OnboardingActor(RECIPIENT, "platform")
        return buildMap {
            for ((scenario, venues) in names) {
                for (browser in listOf("chromium", "webkit")) {
                    val key = "$scenario-$browser"
                    val invitation =
                        repository.mutate(
                            OnboardingActor(OWNER, "platform"),
                            OnboardingMutation.INVITATION_ISSUE,
                            null,
                            buildJsonObject {
                                put("clientName", "До подтверждения владельцев · $key")
                                put("recipientLabel", "Участник")
                                put("allowedClubs", venues.size + 1)
                            },
                            "historical-invite-$key",
                        )
                    val clientId = invitation.getValue("clientId").jsonPrimitive.long
                    repository.mutate(
                        member,
                        OnboardingMutation.INVITATION_CLAIM,
                        invitation.getValue("id").jsonPrimitive.long,
                        buildJsonObject {
                            put("token", invitation.getValue("token"))
                            put("expectedVersion", invitation.getValue("version"))
                        },
                        "historical-claim-$key",
                    )
                    val clubs =
                        venues.mapIndexed { index, name ->
                            repository.mutate(
                                member,
                                OnboardingMutation.CLUB_CREATE,
                                clientId,
                                OnboardingLocations.creationBody(
                                    buildJsonObject {
                                        put("venueName", name)
                                        put("countryCode", "RU")
                                        put("cityId", "524901")
                                        put("address", "Тестовая улица, $index")
                                    },
                                ),
                                "historical-club-$key-$index",
                            )
                        }
                    put(
                        key,
                        buildJsonObject {
                            put("clientId", clientId.toString())
                            put("clientName", invitation.getValue("clientName"))
                            put("clubs", JsonArray(clubs))
                        },
                    )
                }
            }
        }
    }

    private fun signed(
        persona: String?,
        startParam: String?,
    ): String {
        val user =
            when (persona) {
                "owner" -> OWNER
                "recipient" -> RECIPIENT
                "foreign" -> 998103L
                "staff" -> 998104L
                "second-manager" -> 998105L
                else -> error("Unknown synthetic persona")
            }
        val fields =
            mutableMapOf(
                "user" to WebAppInitDataTestHelper.encodeUser(user),
                "auth_date" to Instant.now().epochSecond.toString(),
            )
        if (startParam != null) {
            require(Regex("c[im]_[A-Za-z0-9_-]{43}").matches(startParam))
            fields["start_param"] = startParam
        }
        return WebAppInitDataTestHelper.createInitData(TEST_BOT_TOKEN, fields)
    }

    private const val OWNER = 998101L
    private const val RECIPIENT = 998102L
}
