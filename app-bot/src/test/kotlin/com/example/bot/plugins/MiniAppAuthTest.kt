package com.example.bot.plugins

import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class MiniAppAuthTest {
    @Test
    fun `withMiniAppAuth authorizes valid init data`() =
        testApplication {
            application {
                installMiniAppAuthStatusPage()
                install(ContentNegotiation) { json() }
                routing {
                    route("/t") {
                        withMiniAppAuth { TEST_BOT_TOKEN }
                        get {
                            if (call.attributes.contains(MiniAppUserKey)) {
                                call.respond(HttpStatusCode.OK)
                            } else {
                                call.respond(HttpStatusCode.Unauthorized)
                            }
                        }
                    }
                }
            }

            val initData =
                WebAppInitDataTestHelper.createInitData(
                    TEST_BOT_TOKEN,
                    mapOf(
                        "user" to WebAppInitDataTestHelper.encodeUser(id = 123, username = "tester"),
                        "auth_date" to Instant.now().epochSecond.toString(),
                    ),
                )

            val success =
                client.get("/t") {
                    header("X-Telegram-Init-Data", initData)
                }
            assertEquals(HttpStatusCode.OK, success.status)

            val missing = client.get("/t")
            assertEquals(HttpStatusCode.Unauthorized, missing.status)

            val invalid =
                client.get("/t") {
                    header("X-Telegram-Init-Data", initData + "0")
                }
            assertEquals(HttpStatusCode.Unauthorized, invalid.status)
        }

    @Test
    fun `default policy retains prod query and body compatibility outside support`() =
        testApplication {
            environment { config = MapApplicationConfig("app.env.APP_PROFILE" to "PROD") }
            application {
                install(ContentNegotiation) { json() }
                routing {
                    route("/legacy") {
                        withMiniAppAuth { TEST_BOT_TOKEN }
                        get { call.respond(HttpStatusCode.OK) }
                        post { call.respond(HttpStatusCode.OK) }
                    }
                }
            }
            val initData =
                com.example.bot.testing
                    .createInitData()
            assertEquals(
                HttpStatusCode.OK,
                client.get("/legacy?initData=${initData.encodeURLParameter()}").status,
            )
            assertEquals(
                HttpStatusCode.OK,
                client
                    .post("/legacy") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody("initData=${initData.encodeURLParameter()}")
                    }.status,
            )
        }

    @Test
    fun `withMiniAppAuth prefers init data from header over query`() {
        overrideMiniAppValidatorForTesting { raw, _ ->
            if (raw == "from-header") {
                TelegramMiniUser(id = 1)
            } else {
                null
            }
        }

        try {
            testApplication {
                application {
                    installMiniAppAuthStatusPage()
                    install(ContentNegotiation) { json() }
                    routing {
                        route("/t") {
                            withMiniAppAuth { TEST_BOT_TOKEN }
                            get {
                                if (call.attributes.contains(MiniAppUserKey)) {
                                    call.respond(HttpStatusCode.OK)
                                } else {
                                    call.respond(HttpStatusCode.Unauthorized)
                                }
                            }
                        }
                    }
                }

                val response =
                    client.get("/t?initData=from-query") {
                        header("X-Telegram-Init-Data", "from-header")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }
        } finally {
            resetMiniAppValidator()
        }
    }
}
