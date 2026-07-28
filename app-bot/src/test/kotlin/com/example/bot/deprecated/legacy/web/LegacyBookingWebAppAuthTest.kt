@file:Suppress(
    "ktlint:standard:argument-list-wrapping",
    "ktlint:standard:function-signature",
    "ktlint:standard:import-ordering",
    "ktlint:standard:indent",
    "ktlint:standard:max-line-length",
    "ktlint:standard:multiline-expression-wrapping",
    "ktlint:standard:string-template-indent",
)

package com.example.bot.deprecated.legacy.web

import com.example.bot.bootstrapLegacyBookingWebApp
import com.example.bot.isLegacyBookingEnabled
import com.example.bot.plugins.installJsonErrorPages
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyBookingWebAppAuthTest {
    @AfterTest
    fun cleanup() {
        resetMiniAppValidator()
    }

    @Test
    fun `valid mini app auth allows legacy api access`() = testApplication {
        installLegacyAppWithDatabase(authenticatedUserId = AUTH_USER_ID)

        val response = client.get("/api/bookings/my") { validInitData() }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"tableNumber\":7"))
    }

    @Test
    fun `query and header spoofed identity do not override valid mini app auth`() = testApplication {
        installLegacyAppWithDatabase(authenticatedUserId = AUTH_USER_ID)

        val response = client.get("/api/bookings/my?tgUserId=$SPOOFED_USER_ID") {
            validInitData()
            header("X-TG-User-Id", SPOOFED_USER_ID.toString())
            header("X-TG-Username", "spoofed")
            header("X-TG-Display", "Spoofed User")
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(body.contains("\"tableNumber\":7"), body)
        assertFalse(body.contains("\"tableNumber\":9"), body)
    }

    @Test
    fun `my bookings uses only auth context not client provided identity`() = testApplication {
        installLegacyAppWithDatabase(authenticatedUserId = AUTH_USER_ID)

        val response = client.get("/api/bookings/my?tgUserId=$SPOOFED_USER_ID") { validInitData() }
        val bookings = Json.parseToJsonElement(response.bodyAsText()).jsonArray

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, bookings.size)
        assertEquals("7", bookings.single().jsonObject["tableNumber"]?.jsonPrimitive?.content)
    }

    @Test
    fun `post booking persists authenticated user when client identity is spoofed`() = testApplication {
        installLegacyAppWithDatabase(authenticatedUserId = AUTH_USER_ID)

        val response = client.post("/api/bookings?tgUserId=$SPOOFED_USER_ID") {
            validInitData()
            header("X-TG-User-Id", SPOOFED_USER_ID.toString())
            header("X-TG-Username", "spoofed")
            header("X-TG-Display", "Spoofed User")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "clubId": 1001,
                  "eventId": 1001,
                  "tableId": 1008,
                  "guestsCount": 2,
                  "guestName": "Spoof Attempt",
                  "tgUserId": $SPOOFED_USER_ID
                }
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(AUTH_USER_ID, guestTelegramUserIdForTable(tableId = 1008))
        assertFalse(isBookingPersistedForTelegramUser(tableId = 1008, telegramUserId = SPOOFED_USER_ID))
    }

    @Test
    fun `notifier is called after successful legacy booking`() = testApplication {
        val notifier = RecordingLegacyHqNotifier()
        installLegacyAppWithDatabase(authenticatedUserId = AUTH_USER_ID, legacyHqNotifier = notifier)

        val response = client.post("/api/bookings?tgUserId=$SPOOFED_USER_ID") {
            validInitData()
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "clubId": 1001,
                  "eventId": 1001,
                  "tableId": 1008,
                  "guestsCount": 2,
                  "guestName": "Runtime Contract",
                  "tgUserId": $SPOOFED_USER_ID
                }
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val message = withTimeout(1_000) { notifier.nextMessage.await() }
        assertEquals(1, notifier.messages.size)
        assertTrue(message.contains("Новая бронь"))
    }

    @Test
    fun `slow notifier does not block successful legacy booking response after commit`() = testApplication {
        val notifier = SlowLegacyHqNotifier()
        installLegacyAppWithDatabase(authenticatedUserId = AUTH_USER_ID, legacyHqNotifier = notifier)

        val response = client.post("/api/bookings") {
            validInitData()
            contentType(ContentType.Application.Json)
            setBody(newBookingRequest(tableId = 1008, guestName = "Slow Notifier"))
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(1, countBookingsForTable(tableId = 1008))
        withTimeout(1_000) { notifier.started.await() }
    }

    @Test
    fun `failing notifier does not break successful legacy booking response after commit`() = testApplication {
        installLegacyAppWithDatabase(authenticatedUserId = AUTH_USER_ID, legacyHqNotifier = FailingLegacyHqNotifier())

        val response = client.post("/api/bookings") {
            validInitData()
            contentType(ContentType.Application.Json)
            setBody(newBookingRequest(tableId = 1008, guestName = "Failing Notifier"))
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(1, countBookingsForTable(tableId = 1008))
    }

    @Test
    fun `post booking rejects malformed arrivalBy as validation error`() = testApplication {
        installLegacyAppWithDatabase(authenticatedUserId = AUTH_USER_ID)

        val response = client.post("/api/bookings") {
            validInitData()
            contentType(ContentType.Application.Json)
            setBody(newBookingRequest(tableId = 1008, guestName = "Bad Arrival", arrivalBy = "not-an-instant"))
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.BadRequest, response.status, body)
        assertTrue(body.contains("\"code\":\"validation_error\""), body)
        assertTrue(body.contains("arrivalBy"), body)
        assertEquals(0, countBookingsForTable(tableId = 1008))
    }

    @Test
    fun `post booking rejects invalid json with shared error envelope`() = testApplication {
        installLegacyAppWithDatabase(authenticatedUserId = AUTH_USER_ID)

        val response = client.post("/api/bookings") {
            validInitData()
            contentType(ContentType.Application.Json)
            setBody("{not-json")
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.BadRequest, response.status, body)
        assertErrorCode(body, "invalid_json")
        assertEquals(0, countBookingsForTable(tableId = 1008))
    }

    @Test
    fun `post booking maps legacy booking errors to explicit error envelope statuses`() = testApplication {
        installLegacyAppWithDatabase(authenticatedUserId = AUTH_USER_ID)

        val cases = listOf(
            LegacyBookingErrorCase(
                name = "event not found",
                request = newBookingRequest(
                    eventId = 9999,
                    tableId = 1008,
                    guestName = "Missing Event",
                ),
                expectedStatus = HttpStatusCode.NotFound,
                expectedCode = "EVENT_NOT_FOUND",
            ),
            LegacyBookingErrorCase(
                name = "table not found",
                request = newBookingRequest(tableId = 9999, guestName = "Missing Table"),
                expectedStatus = HttpStatusCode.NotFound,
                expectedCode = "TABLE_NOT_FOUND",
            ),
            LegacyBookingErrorCase(
                name = "event club mismatch",
                request = newBookingRequest(eventId = 1002, tableId = 1008, guestName = "Wrong Event Club"),
                expectedStatus = HttpStatusCode.BadRequest,
                expectedCode = "EVENT_CLUB_MISMATCH",
                expectedMessage = "Event does not belong to the requested club",
            ),
            LegacyBookingErrorCase(
                name = "table club mismatch",
                request = newBookingRequest(tableId = 1011, guestName = "Wrong Table Club"),
                expectedStatus = HttpStatusCode.BadRequest,
                expectedCode = "TABLE_CLUB_MISMATCH",
                expectedMessage = "Table does not belong to the requested club",
            ),
            LegacyBookingErrorCase(
                name = "table inactive",
                request = newBookingRequest(tableId = 1010, guestName = "Inactive Table"),
                expectedStatus = HttpStatusCode.BadRequest,
                expectedCode = "TABLE_INACTIVE",
            ),
            LegacyBookingErrorCase(
                name = "capacity exceeded",
                request = newBookingRequest(
                    tableId = 1008,
                    guestName = "Too Many Guests",
                    guestsCount = 5,
                ),
                expectedStatus = HttpStatusCode.BadRequest,
                expectedCode = "CAPACITY_EXCEEDED",
            ),
            LegacyBookingErrorCase(
                name = "already booked",
                request = newBookingRequest(tableId = 1007, guestName = "Already Booked"),
                expectedStatus = HttpStatusCode.Conflict,
                expectedCode = "ALREADY_BOOKED",
            ),
        )

        cases.forEach { case ->
            val response = client.post("/api/bookings") {
                validInitData()
                contentType(ContentType.Application.Json)
                setBody(case.request)
            }
            val body = response.bodyAsText()

            assertEquals(case.expectedStatus, response.status, "${case.name}: $body")
            assertErrorCode(body, case.expectedCode)
            assertTrue(body.contains("\"status\":${case.expectedStatus.value}"), body)
            case.expectedMessage?.let { expectedMessage ->
                assertTrue(body.contains("\"message\":\"$expectedMessage\""), body)
            }
        }
    }

    @Test
    fun `post booking unexpected non constraint failure is internal error not conflict`() = testApplication {
        installLegacyAppWithDatabase(authenticatedUserId = AUTH_USER_ID)
        restrictBookingGuestNameLength()

        val response = client.post("/api/bookings") {
            validInitData()
            contentType(ContentType.Application.Json)
            setBody(newBookingRequest(tableId = 1008, guestName = "Runtime failure name exceeds varchar limit"))
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.InternalServerError, response.status, body)
        assertErrorCode(body, "internal_error")
        assertTrue(body.contains("\"message\":\"Internal booking error\""), body)
        assertTrue(body.contains("\"status\":500"), body)
        assertFalse(body.contains("CONFLICT"), body)
        assertFalse(body.contains("\"status\":409"), body)
        assertEquals(0, countBookingsForTable(tableId = 1008))
    }

    @Test
    fun `post booking insert constraint conflict remains conflict path`() = testApplication {
        installLegacyAppWithDatabase(authenticatedUserId = AUTH_USER_ID)
        insertCancelledBookingWithIdempotencyKey(idempotencyKey = "tg-$AUTH_USER_ID-1001-1008-2")

        val response = client.post("/api/bookings") {
            validInitData()
            contentType(ContentType.Application.Json)
            setBody(newBookingRequest(tableId = 1008, guestName = "Constraint Conflict"))
        }

        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.Conflict, response.status, body)
        assertErrorCode(body, "CONFLICT")
        assertTrue(body.contains("\"status\":409"), body)
        assertEquals(1, countBookingsForTable(tableId = 1008))
    }

    @Test
    fun `legacy api endpoints are fail-closed when auth missing`() = testApplication {
        installLegacyAppWithDatabase(authenticatedUserId = AUTH_USER_ID)

        legacyEndpointRequests().forEach { request ->
            val response = request.sendWithoutAuth(this)
            assertEquals(HttpStatusCode.Unauthorized, response.status, request.name)
        }
    }

    @Test
    fun `legacy api endpoints reject invalid init data`() = testApplication {
        installLegacyAppWithDatabase(authenticatedUserId = AUTH_USER_ID)

        legacyEndpointRequests().forEach { request ->
            val response = request.sendWithInvalidAuth(this)
            assertEquals(HttpStatusCode.Unauthorized, response.status, request.name)
        }
    }

    @Test
    fun `valid mini app auth allows all legacy api endpoint contracts`() = testApplication {
        installLegacyAppWithDatabase(authenticatedUserId = AUTH_USER_ID)

        assertEquals(HttpStatusCode.OK, client.get("/api/clubs") { validInitData() }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/events?clubId=1001") { validInitData() }.status)
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/tables/free?clubId=1001&eventId=1001&guests=2") { validInitData() }.status,
        )
        assertEquals(HttpStatusCode.OK, client.get("/api/bookings/my") { validInitData() }.status)
        assertEquals(
            HttpStatusCode.OK,
            client.post("/api/bookings") {
                validInitData()
                contentType(ContentType.Application.Json)
                setBody(newBookingRequest(tableId = 1008, guestName = "Valid Coverage"))
            }.status,
        )
    }

    @Test
    fun `legacy bootstrap fails fast when enabled with incomplete config`() = testApplication {
        environment {
            config = MapApplicationConfig().apply {
                put("app.flags.LEGACY_BOOKING_WEBAPP_ENABLED", "true")
            }
        }

        application {
            bootstrapLegacyBookingWebApp(privacyConfig())
        }

        assertFailsWith<IllegalArgumentException> {
            client.get("/api/clubs")
        }
    }

    @Test
    fun `legacy bootstrap uses bot token fallback when telegram bot token is absent`() = testApplication {
        val dataSource = schemaDataSource()
        Database.connect(dataSource)
        seedLegacyBookingData()
        val initData = fallbackInitData()
        environment {
            config = MapApplicationConfig().apply {
                put("app.flags.LEGACY_BOOKING_WEBAPP_ENABLED", "true")
                put("app.env.TELEGRAM_BOT_TOKEN", " ")
                put("app.env.BOT_TOKEN", FALLBACK_BOT_TOKEN)
                put("app.env.LEGACY_HQ_CHAT_ID", "1000")
            }
        }

        application {
            install(ContentNegotiation) { json() }
            bootstrapLegacyBookingWebApp(privacyConfig())
        }

        val response = client.get("/api/bookings/my") {
            header("X-Telegram-Init-Data", initData)
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("\"tableNumber\":7"), response.bodyAsText())
    }

    @Test
    fun `legacy api is fail-closed when auth missing`() = testApplication {
        installLegacyAppWithDatabase(authenticatedUserId = AUTH_USER_ID)

        val response = client.get("/api/bookings/my")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `legacy api rejects invalid init data`() = testApplication {
        overrideMiniAppValidatorForTesting { _, _ -> null }
        application {
            install(ContentNegotiation) { json() }
            installJsonErrorPages()
            installLegacyBookingWebApp(
                privacyConfig = privacyConfig(),
                legacyBotTokenProvider = { LEGACY_BOT_TOKEN },
            )
        }

        val response = client.get("/api/bookings/my?tgUserId=$SPOOFED_USER_ID") {
            header("X-Telegram-Init-Data", VALID_INIT_DATA)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `legacy feature flag defaults to disabled`() = testApplication {
        application {
            routing {
                if (isLegacyBookingEnabled()) {
                    get("/legacy-enabled") {}
                }
            }
        }

        val response = client.get("/legacy-enabled")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

}
