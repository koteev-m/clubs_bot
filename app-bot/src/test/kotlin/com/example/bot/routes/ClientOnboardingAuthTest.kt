package com.example.bot.routes

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.bot.data.client.ClientContextRepository
import com.example.bot.data.client.onboarding.ClientOnboardingRepository
import com.example.bot.data.client.onboarding.OnboardingActor
import com.example.bot.data.client.onboarding.OnboardingFailure
import com.example.bot.data.client.onboarding.OnboardingMutation
import com.example.bot.plugins.configureLoggingAndRequestId
import com.example.bot.plugins.installJsonErrorPages
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respondText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientOnboardingAuthTest {
    @Test
    fun `profile read and update authenticate canonical fresh identity before repository access`() =
        withApp { repository ->
            for (method in listOf(HttpMethod.Get, HttpMethod.Patch)) {
                for (credential in listOf(null, "forged", signed(date = Instant.now().epochSecond - 90_000))) {
                    val response =
                        client.request("$C/clubs/1/profile") {
                            this.method = method
                            if (credential != null) header(HEADER, credential)
                            header("X-Telegram-Id", OWNER.toString())
                            header("X-Debug-Roles", "OWNER,GLOBAL_ADMIN")
                            header(KEY, "synthetic-profile-key")
                            if (method == HttpMethod.Patch) setBody("""{"expectedVersion":"1","name":"Forged"}""")
                        }
                    response.assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
                    assertEquals("no-store", response.headers["Cache-Control"])
                }
            }
            coVerify(exactly = 0) { repository.read(any(), any(), any(), any()) }
            coVerify(exactly = 0) { repository.mutate(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `layout hall table and plan namespaces require fresh auth before any repository or body access`() =
        withApp { repository ->
            val paths =
                listOf(
                    HttpMethod.Get to "$C/clubs/1/layout",
                    HttpMethod.Post to "$C/clubs/1/halls",
                    HttpMethod.Patch to "$C/clubs/1/halls/2",
                    HttpMethod.Post to "$C/clubs/1/halls/2/tables",
                    HttpMethod.Patch to "$C/clubs/1/halls/2/tables/3",
                    HttpMethod.Get to "$C/clubs/1/halls/2/plan",
                    HttpMethod.Post to "$C/clubs/1/halls/2/plan",
                )
            for ((method, path) in paths) {
                for (credential in listOf(
                    null,
                    "forged",
                    signed(date = Instant.now().epochSecond - 90_000),
                    signed(token = "222222:OTHER_SYNTHETIC_BOT"),
                )) {
                    client
                        .request(path) {
                            this.method = method
                            if (credential != null) header(HEADER, credential)
                            header("X-Telegram-Id", OWNER.toString())
                            header("X-Debug-Roles", "OWNER,GLOBAL_ADMIN")
                            setBody("malformed input")
                        }.assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
                }
                client
                    .request("$path?clientId=999") {
                        this.method = method
                        header(HEADER, signed(user = CLIENT))
                    }.assertClientError(HttpStatusCode.BadRequest, "validation_error")
            }
            coVerify(exactly = 0) { repository.read(any(), any(), any(), any()) }
            coVerify(exactly = 0) { repository.mutate(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `layout read image and mutation cancellation reaches application boundary`() {
        for ((method, path) in listOf(
            HttpMethod.Get to "$C/clubs/1/layout",
            HttpMethod.Get to "$C/clubs/1/halls/2/plan",
            HttpMethod.Post to "$C/clubs/1/halls",
            HttpMethod.Post to "$C/clubs/1/halls/2/plan",
        )) {
            testApplication {
                val context = mockk<ClientContextRepository>()
                val repository = mockk<ClientOnboardingRepository>()
                coEvery { repository.read(any(), any(), any(), any()) } throws
                    CancellationException("synthetic cancellation")
                coEvery { repository.mutate(any(), any(), any(), any(), any()) } throws
                    CancellationException("synthetic cancellation")
                var cancellations = 0
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) {
                        exception<CancellationException> { call, _ ->
                            cancellations++
                            call.respondText("cancelled", status = HttpStatusCode.ServiceUnavailable)
                        }
                    }
                    clientOnboardingRoutes(context, repository, "synthetic_bot", OWNER) { TEST_BOT_TOKEN }
                }
                val response =
                    client.request(path) {
                        this.method = method
                        header(HEADER, signed(user = CLIENT))
                        header(KEY, "synthetic-cancel-key")
                        if (method == HttpMethod.Post) {
                            if (path.endsWith("/plan")) {
                                header("Content-Type", "image/png")
                                header("X-Layout-Version", "1")
                                setBody(byteArrayOf(1, 2, 3))
                            } else {
                                setBody("{\"name\":\"cancelled\"}")
                            }
                        }
                    }
                assertEquals(1, cancellations)
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                assertEquals("cancelled", response.bodyAsText())
            }
        }
    }

    @Test
    fun `layout persistence failures use detail free common errors`() =
        withApp { repository ->
            coEvery { repository.read(any(), any(), any(), any()) } throws IllegalStateException(TOKEN)
            val response = client.get("$C/clubs/1/layout") { header(HEADER, signed(user = CLIENT)) }
            response.assertClientError(HttpStatusCode.InternalServerError, "internal_error")
            assertFalse(response.bodyAsText().contains(TOKEN))
            assertEquals("no-store", response.headers["Cache-Control"])
        }

    @Test
    fun `all onboarding namespaces require fresh canonical signed transport`() =
        withApp { repository ->
            for (path in listOf(
                "$P/invitations",
                "$C/invitations/resolve",
                "$P/applications/search",
                "$C/applications/1/club",
            )) {
                for (credential in listOf(null, "forged", signed(date = Instant.now().epochSecond - 90_000))) {
                    client
                        .post(path) {
                            if (credential != null) header(HEADER, credential)
                            header("X-Telegram-Id", OWNER.toString())
                            setBody("{}")
                        }.assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
                }
                client
                    .post(path) {
                        header("X-Telegram-InitData", signed())
                    }.assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
                client
                    .post(path) {
                        header(HEADER, signed())
                        header(HEADER, signed())
                    }.assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
                client
                    .post("$path?clientId=123") {
                        header(HEADER, signed())
                    }.assertClientError(HttpStatusCode.BadRequest, "validation_error")
                client
                    .post("$path?initData=synthetic-secret") {
                        header(HEADER, signed())
                    }.assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
            }
            coVerify(exactly = 0) { repository.read(any(), any(), any(), any()) }
            coVerify(exactly = 0) { repository.mutate(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `legacy owner headers and another signed user cannot enter platform onboarding`() =
        withApp { repository ->
            for (path in listOf("$P/invitations", "$P/applications/1/approve", "$P/clients/1/members/2/restore")) {
                client
                    .post(path) {
                        header(HEADER, signed(user = CLIENT))
                        header("X-Telegram-Id", OWNER.toString())
                        header("X-Debug-Roles", "OWNER")
                        setBody("{}")
                    }.assertClientError(HttpStatusCode.Forbidden, "forbidden")
            }
            coVerify(exactly = 0) { repository.mutate(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `missing or nonpositive platform owner fails closed`() {
        for (configured in listOf(null, 0L, -1L)) {
            withApp(ownerId = configured) { repository ->
                client
                    .get("$P/clients/1") { header(HEADER, signed()) }
                    .assertClientError(HttpStatusCode.Forbidden, "forbidden")
                coVerify(exactly = 0) { repository.read(any(), any(), any(), any()) }
            }
        }
    }

    @Test
    fun `owner issue links use configured bot and replays never reconstruct secrets`() =
        withApp { repository ->
            coEvery { repository.mutate(any(), OnboardingMutation.INVITATION_ISSUE, null, any(), any()) } returns
                JsonObject(mapOf("id" to JsonPrimitive("1"), "token" to JsonPrimitive(TOKEN)))
            val response =
                client.post("$P/invitations") {
                    header(HEADER, signed())
                    header(KEY, "synthetic-issue-key")
                    setBody("""{"clientName":"Synthetic client","recipientLabel":"Recipient"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers["Cache-Control"])
            assertTrue(response.bodyAsText().contains("https://t.me/synthetic_bot?startapp=ci_$TOKEN"))
            coVerify {
                repository.mutate(
                    OnboardingActor(OWNER, "platform"),
                    OnboardingMutation.INVITATION_ISSUE,
                    null,
                    any(),
                    "synthetic-issue-key",
                )
            }
            coEvery { repository.mutate(any(), any(), any(), any(), any()) } returns
                JsonObject(mapOf("id" to JsonPrimitive("1")))
            val replay =
                client.post("$P/invitations") {
                    header(HEADER, signed())
                    header(KEY, "synthetic-issue-key")
                    setBody("""{"clientName":"Synthetic client","recipientLabel":"Recipient"}""")
                }
            assertFalse(replay.bodyAsText().contains("invitationLink"))
        }

    @Test
    fun `reported stage username produces the exact main mini app link without contacting Telegram`() {
        for (username in listOf("NightConcierge_Bot", "@NightConcierge_Bot")) {
            withApp(botUsername = username) { repository ->
                coEvery { repository.mutate(any(), OnboardingMutation.INVITATION_ISSUE, null, any(), any()) } returns
                    JsonObject(mapOf("id" to JsonPrimitive("1"), "token" to JsonPrimitive(TOKEN)))
                val response =
                    client.post("$P/invitations") {
                        header(HEADER, signed())
                        header(KEY, "synthetic-stage-name-key")
                        setBody("""{"clientName":"Synthetic client","recipientLabel":"Recipient"}""")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("https://t.me/NightConcierge_Bot?startapp=ci_$TOKEN"))
                assertFalse(response.bodyAsText().contains("https://t.me/@"))
            }
        }
    }

    @Test
    fun `missing or malformed bot username is a known nonmutating failure for issue and reissue`() {
        for (username in listOf(null, "", "bad", "bot name", "https://t.me/synthetic_bot", "bot?startapp=evil")) {
            withApp(botUsername = username) { repository ->
                for (path in listOf("$P/invitations", "$P/invitations/1/reissue")) {
                    val response =
                        client.post(path) {
                            header(HEADER, signed())
                            header(KEY, "synthetic-config-key")
                            setBody("{}")
                        }
                    response.assertClientError(HttpStatusCode.ServiceUnavailable, "invitation_link_unavailable")
                    assertEquals("no-store", response.headers["Cache-Control"])
                }
                coVerify(exactly = 0) { repository.mutate(any(), any(), any(), any(), any()) }
            }
        }
    }

    @Test
    fun `configuration failure never reveals itself before owner authorization`() =
        withApp(botUsername = null) { repository ->
            client
                .post("$P/invitations") {
                    header(HEADER, signed(user = CLIENT))
                    header(KEY, "synthetic-config-key")
                    setBody("{}")
                }.assertClientError(HttpStatusCode.Forbidden, "forbidden")
            coVerify(exactly = 0) { repository.mutate(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `body id key and size validation happens after authority and before mutation`() =
        withApp { repository ->
            for (body in listOf("", "[]", "{", """{"padding":"${"x".repeat(17_000)}"}""")) {
                client
                    .post("$P/invitations") {
                        header(HEADER, signed())
                        header(KEY, "synthetic-validation")
                        setBody(body)
                    }.assertClientError(HttpStatusCode.BadRequest, "validation_error")
            }
            for (key in listOf(null, "", "short", "bad key!!")) {
                client
                    .post("$P/invitations") {
                        header(HEADER, signed())
                        if (key != null) header(KEY, key)
                        setBody("{}")
                    }.assertClientError(HttpStatusCode.BadRequest, "validation_error")
            }
            for (id in listOf("0", "01", "-1", "9223372036854775808")) {
                client
                    .get("$P/invitations/$id") { header(HEADER, signed()) }
                    .assertClientError(HttpStatusCode.BadRequest, "validation_error")
            }
            client
                .post("$P/clients/1/members/2/restore") {
                    header(HEADER, signed())
                    header(KEY, "synthetic-validation")
                    setBody("""{"userId":"3","expectedVersion":"1","permissions":[]}""")
                }.assertClientError(HttpStatusCode.BadRequest, "validation_error")
            coVerify(exactly = 0) { repository.mutate(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `domain conflicts and persistence failures use the shared safe ApiError`() =
        withApp { repository ->
            for (code in listOf(
                "version_conflict",
                "invalid_transition",
                "idempotency_conflict",
                "duplicate_application",
            )) {
                coEvery { repository.mutate(any(), any(), any(), any(), any()) } throws OnboardingFailure(code)
                client
                    .post("$C/applications/1/submit") {
                        header(HEADER, signed(user = CLIENT))
                        header(KEY, "synthetic-error-test")
                        setBody("""{"expectedVersion":"1"}""")
                    }.assertClientError(HttpStatusCode.Conflict, code)
            }
            coEvery { repository.read(any(), any(), any(), any()) } throws IllegalStateException(TOKEN)
            client
                .get("$C/applications/1") { header(HEADER, signed(user = CLIENT)) }
                .assertClientError(HttpStatusCode.InternalServerError, "internal_error")
        }

    @Test
    fun `launch query and API credentials are absent from HTTP logging`() {
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            withApp { _ ->
                client.get("/app?tgWebAppStartParam=ci_$TOKEN")
                client.post("$C/invitations/resolve") {
                    header(HEADER, signed(user = CLIENT))
                    header(KEY, "synthetic-logging-key")
                    setBody("""{"token":"$TOKEN"}""")
                }
            }
            val logs = appender.list.joinToString("\n") { it.formattedMessage }
            assertTrue(logs.contains("GET /app"))
            assertFalse(logs.contains(TOKEN))
            assertFalse(logs.contains("synthetic-logging-key"))
            assertFalse(logs.contains("auth_date="))
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun withApp(
        ownerId: Long? = OWNER,
        botUsername: String? = "synthetic_bot",
        block: suspend ApplicationTestBuilder.(ClientOnboardingRepository) -> Unit,
    ) = testApplication {
        val context = mockk<ClientContextRepository>()
        val repository = mockk<ClientOnboardingRepository>()
        coEvery { context.context(any(), any()) } returns emptyList()
        coEvery { repository.read(any(), any(), any(), any()) } returns JsonObject(emptyMap())
        coEvery { repository.mutate(any(), any(), any(), any(), any()) } returns JsonObject(emptyMap())
        application {
            configureLoggingAndRequestId()
            install(ContentNegotiation) { json() }
            installJsonErrorPages()
            clientOnboardingRoutes(context, repository, botUsername, ownerId) { TEST_BOT_TOKEN }
            webAppRoutes()
        }
        block(repository)
    }

    private fun signed(
        user: Long = OWNER,
        date: Long = Instant.now().epochSecond,
        token: String = TEST_BOT_TOKEN,
    ): String =
        WebAppInitDataTestHelper.createInitData(
            token,
            mapOf("user" to WebAppInitDataTestHelper.encodeUser(user), "auth_date" to date.toString()),
        )

    private companion object {
        const val P = "/api/platform/onboarding"
        const val C = "/api/client"
        const val HEADER = "X-Telegram-Init-Data"
        const val KEY = "Idempotency-Key"
        const val OWNER = 998001L
        const val CLIENT = 998002L
        const val TOKEN = "synthetic_token_256_bit_shape_012345678901234"
    }
}
