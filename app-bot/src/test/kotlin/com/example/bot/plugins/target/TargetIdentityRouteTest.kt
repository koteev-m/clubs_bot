package com.example.bot.plugins.target

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.bot.http.ErrorCodes
import com.example.bot.http.respondError
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.TelegramMiniUser
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.queryString
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Only a test-host probe, no production routing installation. */
class TargetIdentityRouteTest {
    @Test
    fun `signed TARGET header ignores forged legacy identity body and tenant hints`() =
        testApplication {
            val fixture = TargetIdentityFixture()
            application {
                install(ContentNegotiation) { json() }
                routing {
                    post(PATH) {
                        call.attributes.put(MiniAppUserKey, TelegramMiniUser(99, username = "forged"))
                        val identity =
                            fixture.verifier.verify(
                                call.request.headers,
                                call.request.queryString(),
                                fixture.context,
                            )
                        if (identity == null) {
                            call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.unauthorized)
                        } else {
                            call.respondText(
                                "${identity.telegramUserId}/${identity.context.clientId}/${identity.context.clubId}",
                                ContentType.Text.Plain,
                            )
                        }
                    }
                }
            }
            val response =
                client.post(PATH) {
                    header(HEADER, fixture.signed())
                    header("X-Telegram-Id", "99")
                    header("X-Debug-Roles", "OWNER,GLOBAL_ADMIN")
                    header("X-Bot-Id", "other")
                    header("X-Client-Id", "102")
                    header("X-Club-Id", "2001")
                    setBody("{\"userId\":99,\"clientId\":102,\"clubId\":2001,\"role\":\"OWNER\"}")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("7/101/1001", response.bodyAsText())
            val denied =
                client.post(PATH) {
                    header("X-Telegram-Id", "99")
                    header("X-Debug-Roles", "OWNER,GLOBAL_ADMIN")
                    setBody("{\"initData\":\"${fixture.signed()}\"}")
                }
            assertEquals(HttpStatusCode.Unauthorized, denied.status)
        }

    @Test
    fun `ambiguous credentials wrong bot stale signature and query cannot authenticate through Ktor`() =
        testApplication {
            val fixture = TargetIdentityFixture()
            application {
                install(ContentNegotiation) { json() }
                routing {
                    post(PATH) {
                        val identity =
                            fixture.verifier.verify(
                                call.request.headers,
                                call.request.queryString(),
                                fixture.context,
                            )
                        if (identity == null) {
                            call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.unauthorized)
                        } else {
                            call.respondText("authenticated")
                        }
                    }
                }
            }
            for (raw in listOf(
                "forged",
                fixture.signed(key = "222222:OTHER_SYNTHETIC_BOT"),
                fixture.signed(
                    date =
                        fixture.now
                            .minusSeconds(300)
                            .epochSecond
                            .toString(),
                ),
                "state=first&" + fixture.signed(extras = mapOf("state" to "last")),
            )) {
                val response = client.post(PATH) { header(HEADER, raw) }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
                assertFalse(response.bodyAsText().contains(raw))
            }
            for (mode in listOf("duplicate", "alias", "combined", "authorization", "query")) {
                val response =
                    client.post(if (mode == "query") "$PATH?clientId=102" else PATH) {
                        header(
                            HEADER,
                            if (mode ==
                                "combined"
                            ) {
                                "${fixture.signed()}, ${fixture.signed()}"
                            } else {
                                fixture.signed()
                            },
                        )
                        when (mode) {
                            "duplicate" -> header(HEADER, fixture.signed())
                            "alias" -> header("X-Telegram-InitData", fixture.signed())
                            "authorization" -> header("Authorization", "synthetic")
                        }
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
        }

    @Test
    fun `invalid credentials and provider exceptions leak no credential in logs or response`() {
        val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val captured = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(captured)
        try {
            testApplication {
                val fixture = TargetIdentityFixture()
                val raw = fixture.signed(extras = mapOf("start_param" to CANARY))
                fixture.onToken = { error(CANARY) }
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        post(PATH) {
                            val identity =
                                fixture.verifier.verify(
                                    call.request.headers,
                                    call.request.queryString(),
                                    fixture.context,
                                )
                            if (identity == null) {
                                call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.unauthorized)
                            } else {
                                call.respondText("authenticated")
                            }
                        }
                    }
                }
                val response = client.post(PATH) { header(HEADER, raw) }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
                assertFalse(response.bodyAsText().contains(CANARY))
                assertFalse(response.bodyAsText().contains(raw))
            }
            assertFalse(
                captured.list.any { event ->
                    event.formattedMessage.contains(CANARY) || event.throwableProxy?.message?.contains(CANARY) == true
                },
            )
        } finally {
            logger.detachAppender(captured)
            captured.stop()
        }
    }

    companion object {
        private const val PATH = "/test-only-target-identity-probe"
        private const val HEADER = "X-Telegram-Init-Data"
        private const val CANARY = "synthetic_target_credential_canary"
    }
}
