package com.example.bot.routes

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.bot.data.client.ClientAudience
import com.example.bot.data.client.ClientContextRepository
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.TrustedBotConnection
import com.example.bot.plugins.installJsonErrorPages
import com.example.bot.plugins.withClientContextAuth
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.TestApplicationRequest
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientContextAuthTest {
    @Test
    fun `canonical signed header ignores legacy user and untrusted bot selection`() =
        withApp { repository ->
            val response =
                client.get(PATH) {
                    header(HEADER, signed())
                    header("X-Telegram-Id", "99002")
                    header("X-Telegram-Username", "forged")
                    header("X-Bot-Id", "other")
                    header("X-Club-Id", "2001")
                    header("X-Forwarded-Host", "other-bot.invalid")
                    header("If-None-Match", "*")
                    header("If-Modified-Since", "Wed, 31 Dec 2099 23:59:59 GMT")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("{\"clients\":[]}", response.bodyAsText())
            assertEquals("no-store", response.headers["Cache-Control"])
            assertEquals("X-Telegram-Init-Data", response.headers["Vary"])
            assertEquals(null, response.headers["ETag"])
            assertEquals(null, response.headers["Last-Modified"])
            coVerify(exactly = 1) { repository.context(USER, ClientAudience.Platform) }
        }

    @Test
    fun `missing alias duplicate body and invalid signatures never read the repository`() =
        withApp { repository ->
            val variants =
                listOf<String?>(
                    null,
                    "",
                    "forged",
                    signed(token = "222222:OTHER_SYNTHETIC_BOT"),
                    signed(user = 0),
                    signed(user = -1),
                    signed(date = Instant.now().epochSecond - 90_000),
                    signed(date = Instant.now().epochSecond + 3_600),
                    signed().replace("synthetic_signature", "changed_signature"),
                )
            for (raw in variants) {
                val response =
                    client.get(PATH) {
                        if (raw != null) header(HEADER, raw)
                        header("X-Telegram-Id", USER.toString())
                    }
                response.assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
            }
            for (mode in listOf("alias", "both", "duplicate", "combined", "body")) {
                val response =
                    client.get(PATH) {
                        when (mode) {
                            "alias" -> header("X-Telegram-InitData", signed())
                            "both" -> {
                                header(HEADER, signed())
                                header("X-Telegram-InitData", "")
                            }
                            "duplicate" -> {
                                header(HEADER, signed())
                                header(HEADER, signed())
                            }
                            "combined" -> header(HEADER, "${signed()}, ${signed()}")
                            "body" -> setBody("{\"initData\":\"${signed()}\"}")
                        }
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status, mode)
                response.assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
            }
            client.get("/duplicate-client-fixture").assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
            coVerify(exactly = 0) { repository.context(any(), any()) }
        }

    @Test
    fun `raw query key is checked before signature and values only after signature`() =
        withApp { repository ->
            for (query in listOf(
                "initData=%GG",
                "%69nitData=canary",
                "%GG=x",
                " initData =x",
                "pad&".repeat(1100) + "initData=x",
            )) {
                for (raw in listOf(null, signed())) {
                    val response =
                        client.get(PATH) {
                            header("X-Test-Raw-Query", query)
                            if (raw != null) header(HEADER, raw)
                        }
                    response.assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
                }
            }
            for (query in listOf("x=%GG", "clientId=102", "botId=other", "clubId=2001", "role=OWNER", "&")) {
                for (raw in listOf(null, "forged", signed())) {
                    val response =
                        client.get(PATH) {
                            header("X-Test-Raw-Query", query)
                            if (raw != null) header(HEADER, raw)
                        }
                    if (raw != null && raw != "forged") {
                        response.assertClientError(HttpStatusCode.BadRequest, "validation_error")
                    } else {
                        response.assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
                    }
                }
            }
            coVerify(exactly = 0) { repository.context(any(), any()) }
        }

    @Test
    fun `namespace guard covers encoded paths and leaves neighbors unchanged`() =
        withApp { _ ->
            for (path in listOf(
                "/api/%63lient/context",
                "/api/client/missing",
                "/api/client",
                "/api/client/clubs/1001",
            )) {
                client.get(path).assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
            }
            assertEquals(HttpStatusCode.OK, client.get("/api/client-other").status)
            val trailingQuestion =
                client.get(PATH) {
                    header(HEADER, signed())
                    header("X-Test-Raw-Query", "")
                }
            assertEquals(HttpStatusCode.OK, trailingQuestion.status)
        }

    @Test
    fun `club id is canonical positive Long and validation follows auth`() =
        withApp { repository ->
            for (id in listOf("0", "-1", "+1", "01", "1.0", "nope", "9223372036854775808")) {
                client.get("/api/client/clubs/$id").assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
                client
                    .get("/api/client/clubs/$id") { header(HEADER, signed()) }
                    .assertClientError(HttpStatusCode.BadRequest, "validation_error")
            }
            client
                .get("/api/client/clubs/9223372036854775807") { header(HEADER, signed()) }
                .assertClientError(HttpStatusCode.NotFound, "not_found")
            coVerify(exactly = 1) { repository.club(USER, ClientAudience.Platform, Long.MAX_VALUE) }
        }

    @Test
    fun `provider fails closed at startup and on request without leaking its exception`() {
        for (token in listOf("", " ", "invalid", "111:has space")) {
            assertFailsWith<IllegalArgumentException> {
                TrustedBotConnection(
                    "platform",
                    ClientAudience.Platform,
                ) { token }
            }
        }
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            var reads = 0
            withApp(tokenProvider = {
                reads++
                if (reads > 1) error(SECRET_CANARY)
                TEST_BOT_TOKEN
            }) { repository ->
                client
                    .get(PATH) { header(HEADER, signed()) }
                    .assertClientError(HttpStatusCode.InternalServerError, "internal_error")
                coVerify(exactly = 0) { repository.context(any(), any()) }
            }
            assertEquals(2, reads)
            assertFalse(
                appender.list.any {
                    it.formattedMessage.contains(SECRET_CANARY) ||
                        it.throwableProxy?.message?.contains(SECRET_CANARY) == true
                },
            )
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `repository failure returns only envelope and never replays previous data`() =
        withApp { repository ->
            assertEquals(HttpStatusCode.OK, client.get(PATH) { header(HEADER, signed()) }.status)
            coEvery { repository.context(any(), any()) } throws IllegalStateException(SECRET_CANARY)
            client
                .get(PATH) { header(HEADER, signed()) }
                .assertClientError(HttpStatusCode.InternalServerError, "internal_error")
        }

    @Test
    fun `route guard requires early identity even with a forged legacy attribute`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installJsonErrorPages()
                intercept(
                    ApplicationCallPipeline.Monitoring,
                ) { call.attributes.put(MiniAppUserKey, TelegramMiniUser(USER)) }
                routing {
                    route("/api/client/context") {
                        withClientContextAuth(
                            TrustedBotConnection("platform", ClientAudience.Platform) { TEST_BOT_TOKEN },
                        )
                        get { call.respondText("must not be reached") }
                    }
                }
            }
            client.get(PATH) { header(HEADER, signed()) }.assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
        }

    @Test
    fun `credential provider cancellation is propagated at startup`() {
        assertFailsWith<CancellationException> {
            TrustedBotConnection("platform", ClientAudience.Platform) { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `request cancellation escapes credential and repository error mapping`() {
        for (providerFailure in listOf(true, false)) {
            testApplication {
                val repository = mockk<ClientContextRepository>()
                coEvery { repository.context(any(), any()) } throws CancellationException("synthetic cancellation")
                var reads = 0
                var cancellations = 0
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) {
                        exception<CancellationException> { call, _ ->
                            cancellations++
                            call.respondText("cancelled", status = HttpStatusCode.ServiceUnavailable)
                        }
                    }
                    clientContextRoutes(repository) {
                        reads++
                        if (providerFailure && reads > 1) throw CancellationException("synthetic cancellation")
                        TEST_BOT_TOKEN
                    }
                }
                val response = client.get(PATH) { header(HEADER, signed()) }
                assertEquals(1, cancellations)
                assertTrue(response.status != HttpStatusCode.InternalServerError)
                if (!providerFailure) assertEquals("cancelled", response.bodyAsText())
            }
        }
    }

    private fun withApp(
        tokenProvider: () -> String = { TEST_BOT_TOKEN },
        block: suspend ApplicationTestBuilder.(ClientContextRepository) -> Unit,
    ) = testApplication {
        val repository = mockk<ClientContextRepository>()
        coEvery { repository.context(any(), any()) } returns emptyList()
        coEvery { repository.club(any(), any(), any()) } returns null
        application {
            install(ContentNegotiation) { json() }
            install(ConditionalHeaders)
            installJsonErrorPages()
            intercept(ApplicationCallPipeline.Setup) {
                val request = call.request as TestApplicationRequest
                if (request.uri == "/duplicate-client-fixture") {
                    request.uri = PATH
                    request.addHeader(HEADER, signed())
                    request.addHeader(HEADER, signed())
                    assertEquals(2, request.headers.getAll(HEADER)?.size)
                }
                call.attributes.put(MiniAppUserKey, TelegramMiniUser(99002))
                call.request.headers["X-Test-Raw-Query"]?.let { rawQuery ->
                    (call.request as TestApplicationRequest).uri += "?$rawQuery"
                }
            }
            clientContextRoutes(repository, tokenProvider)
            routing { get("/api/client-other") { call.respondText("neighbor") } }
        }
        block(repository)
    }

    private fun signed(
        user: Long = USER,
        token: String = TEST_BOT_TOKEN,
        date: Long = Instant.now().epochSecond,
    ): String =
        WebAppInitDataTestHelper.createInitData(
            token,
            mapOf(
                "user" to WebAppInitDataTestHelper.encodeUser(user),
                "auth_date" to date.toString(),
                "signature" to "synthetic_signature",
            ),
        )

    private companion object {
        const val PATH = "/api/client/context"
        const val HEADER = "X-Telegram-Init-Data"
        const val USER = 99001L
        const val SECRET_CANARY = "client-secret-canary"
    }
}

internal suspend fun io.ktor.client.statement.HttpResponse.assertClientError(
    status: HttpStatusCode,
    code: String,
) {
    assertEquals(status, this.status)
    assertEquals("no-store", headers["Cache-Control"])
    assertEquals("X-Telegram-Init-Data", headers["Vary"])
    assertEquals(null, headers["ETag"])
    val body = bodyAsText()
    val payload = Json.parseToJsonElement(body).jsonObject
    assertEquals(code, payload.getValue("code").jsonPrimitive.content)
    assertEquals(status.value.toString(), payload.getValue("status").jsonPrimitive.content)
    assertFalse(payload.keys.any { it !in setOf("code", "message", "requestId", "status", "details") })
    for (canary in listOf(
        "client-secret-canary",
        "synthetic_signature",
        "TEST_BOT_TOKEN",
        "FOREIGN",
    )) {
        assertFalse(body.contains(canary))
    }
}
