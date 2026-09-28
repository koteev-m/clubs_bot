package com.example.bot.routes

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.bot.data.security.PermissionCodes
import com.example.bot.data.security.Role
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.configureLoggingAndRequestId
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.testing.createInitData
import com.example.bot.webapp.TEST_BOT_TOKEN
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.TestApplicationRequest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportAuthTransportRoutesTest : SupportAdminRoutesFixture() {
    @ParameterizedTest
    @CsvSource(
        "APP_PROFILE, PROD",
        "APP_PROFILE, PRODUCTION",
        "APP_PROFILE, STAGE",
        "APP_PROFILE, STAGING",
        "APP_ENV, PROD",
        "APP_ENV, PRODUCTION",
        "APP_ENV, STAGE",
        "APP_ENV, STAGING",
    )
    fun `prod-like guest and staff accept headers and reject every query presence`(
        key: String,
        profile: String,
    ) = withSupportAdminApp { context ->
        environment { config = MapApplicationConfig("app.env.$key" to profile) }
        val targets = authenticatedTargets(context)
        targets.forEach { target ->
            listOf("X-Telegram-Init-Data", "X-Telegram-InitData").forEach { headerName ->
                val response = client.get(target.path) { header(headerName, target.initData) }
                assertEquals(HttpStatusCode.OK, response.status)
                response.assertNoStoreHeaders()
                val ids =
                    json
                        .parseToJsonElement(response.bodyAsText())
                        .jsonArray
                        .map {
                            it.jsonObject
                                .getValue("id")
                                .jsonPrimitive.long
                        }
                assertEquals(listOf(target.expectedId), ids)
            }

            val queries =
                listOf(
                    "initData=${target.initData.encodeURLParameter()}",
                    "initData",
                    "initData=",
                    "initData=%20",
                    "initData=SEC002_QUERY_SENTINEL_bad_signature",
                    "initData=%25ZZ",
                    "initData=&initData=${target.initData.encodeURLParameter()}",
                    "%69nitData=SEC002_QUERY_SENTINEL_encoded_key",
                )
            queries.forEach { query ->
                listOf<String?>(null, "X-Telegram-Init-Data", "X-Telegram-InitData").forEach { headerName ->
                    val response =
                        client.get("${target.path}?$query") {
                            if (headerName != null) header(headerName, target.initData)
                        }
                    response.assertTransportDenied()
                    assertFalse(response.bodyAsText().contains(target.initData))
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource("APP_PROFILE, DEV", "APP_ENV, TEST")
    fun `non-prod support retains query fallback and header precedence`(
        key: String,
        profile: String,
    ) = withSupportAdminApp { context ->
        environment { config = MapApplicationConfig("app.env.$key" to profile) }
        authenticatedTargets(context).forEach { target ->
            val queryOnly = client.get("${target.path}?initData=${target.initData.encodeURLParameter()}")
            assertEquals(HttpStatusCode.OK, queryOnly.status)
            queryOnly.assertNoStoreHeaders()
            val headerAndQuery =
                client.get("${target.path}?initData=bad") {
                    header("X-Telegram-Init-Data", target.initData)
                }
            assertEquals(HttpStatusCode.OK, headerAndQuery.status)
            headerAndQuery.assertNoStoreHeaders()
        }
    }

    @Test
    fun `prod support policy leaves neighboring route query auth unchanged`() =
        withSupportAdminApp {
            environment { config = MapApplicationConfig("app.env.APP_PROFILE" to "PROD") }
            application {
                routing {
                    route("/api/support-other") {
                        withMiniAppAuth { TEST_BOT_TOKEN }
                        get { call.respond(HttpStatusCode.OK) }
                    }
                }
            }
            val response = client.get("/api/support-other?initData=${createInitData().encodeURLParameter()}")
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `forbidden support query never reaches validator or public and application log detail`() {
        var validations = 0
        overrideMiniAppValidatorForTesting { _, _ ->
            validations++
            TelegramMiniUser(id = 901L)
        }
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        rootLogger.addAppender(appender)
        try {
            withSupportAdminApp { context ->
                environment { config = MapApplicationConfig("app.env.APP_PROFILE" to "PROD") }
                application {
                    configureLoggingAndRequestId()
                    installRawQueryIngress()
                }
                val targets = authenticatedTargets(context)
                targets.forEach { target ->
                    assertRawQueriesDenied(target)
                    listOf<String?>(null, target.initData).forEach { headerValue ->
                        val response =
                            client.get("${target.path}?initData=SEC002_QUERY_SENTINEL_validator_bypass") {
                                if (headerValue != null) header("X-Telegram-Init-Data", headerValue)
                            }
                        response.assertTransportDenied()
                    }
                }
                assertEquals(0, validations)
                val control =
                    client.get(targets.first().path) {
                        header("X-Telegram-Init-Data", targets.first().initData)
                    }
                assertEquals(HttpStatusCode.OK, control.status)
                assertEquals(1, validations)
            }
            val events = appender.list.toList()
            assertTrue(events.any { it.formattedMessage.contains("initData query transport forbidden") })
            events.forEach { event ->
                val loggedDetail =
                    listOf(
                        event.message,
                        event.formattedMessage,
                        event.argumentArray?.contentToString(),
                        event.throwableProxy?.message,
                        event.mdcPropertyMap.toString(),
                    ).joinToString("\n")
                assertFalse(loggedDetail.contains("SEC002_QUERY_SENTINEL"))
            }
        } finally {
            rootLogger.detachAppender(appender)
            appender.stop()
            resetMiniAppValidator()
        }
    }

    private fun Application.installRawQueryIngress() {
        intercept(ApplicationCallPipeline.Setup) {
            val rawQuery =
                when (call.request.headers["X-Test-Raw-Query"]) {
                    "value", "encoded-path" -> "%69nitData=SEC002_QUERY_SENTINEL_%ZZ"
                    "key" -> "%ZZ=SEC002_QUERY_SENTINEL_undecodable_key"
                    "trimmed-key" -> " initData =SEC002_QUERY_SENTINEL_trimmed"
                    "late" -> "padding&".repeat(1_100) + "initData=SEC002_QUERY_SENTINEL_late"
                    else -> null
                }
            if (rawQuery != null) {
                // Inject at test-host ingress: HttpClient validates/normalizes raw query input.
                val request = call.request as TestApplicationRequest
                if (request.headers["X-Test-Raw-Query"] == "encoded-path") {
                    request.uri = request.uri.replace("/support/", "/%73upport/")
                }
                request.uri += "?$rawQuery"
            }
        }
    }

    private suspend fun ApplicationTestBuilder.assertRawQueriesDenied(target: AuthTarget) {
        listOf("value", "key", "trimmed-key", "late", "encoded-path").forEach { rawCase ->
            listOf<String?>(null, target.initData).forEach { headerValue ->
                val response =
                    client.get(target.path) {
                        header("X-Test-Raw-Query", rawCase)
                        if (headerValue != null) header("X-Telegram-Init-Data", headerValue)
                    }
                response.assertTransportDenied()
            }
        }
    }

    private suspend fun authenticatedTargets(context: TestContext): List<AuthTarget> {
        val guestId = insertUser(context.database, context.userRepository, 901L, "transport-guest")
        val staffId = insertUser(context.database, context.userRepository, 902L, "transport-staff")
        val clubId = insertClub(context.database, "Transport Club")
        val assignment = insertRoleAssignment(context.database, staffId, Role.MANAGER, clubId)
        grantPermission(context.database, assignment, PermissionCodes.SUPPORT_VIEW)
        val ticketId = createTicket(context, clubId, guestId)
        return listOf(
            AuthTarget("/api/support/tickets/my", createInitData(userId = 901L), ticketId),
            AuthTarget("/api/support/staff/clubs", createInitData(userId = 902L), clubId),
        )
    }

    private suspend fun HttpResponse.assertTransportDenied() {
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertNoStoreHeaders()
        val body = bodyAsText()
        assertEquals("unauthorized", errorCode(body))
        val payload = json.parseToJsonElement(body).jsonObject
        assertEquals(setOf("error", "message", "code"), payload.keys)
        assertEquals("initData query transport forbidden", payload.getValue("error").jsonPrimitive.content)
        assertEquals(payload["error"], payload["message"])
        assertFalse(body.contains("SEC002_QUERY_SENTINEL"))
    }

    private data class AuthTarget(
        val path: String,
        val initData: String,
        val expectedId: Long,
    )
}
