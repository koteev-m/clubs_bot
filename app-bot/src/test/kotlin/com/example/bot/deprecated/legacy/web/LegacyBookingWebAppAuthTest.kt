package com.example.bot.deprecated.legacy.web

import com.example.bot.data.privacy.PrivacyConfig
import com.example.bot.isLegacyBookingEnabled
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.HttpRequestBuilder
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals(1, notifier.messages.size)
        assertTrue(notifier.messages.single().contains("Новая бронь"))
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

    private fun ApplicationTestBuilder.installLegacyAppWithDatabase(
        authenticatedUserId: Long,
        legacyHqNotifier: LegacyHqNotifier = NoopLegacyHqNotifier,
    ) {
        val dataSource = schemaDataSource()
        Database.connect(dataSource)
        seedLegacyBookingData()
        overrideMiniAppValidatorForTesting { raw, token ->
            if (raw == VALID_INIT_DATA && token == LEGACY_BOT_TOKEN) TelegramMiniUser(id = authenticatedUserId) else null
        }
        application {
            install(ContentNegotiation) { json() }
            installLegacyBookingWebApp(
                privacyConfig = privacyConfig(),
                legacyHqNotifier = legacyHqNotifier,
                legacyBotTokenProvider = { LEGACY_BOT_TOKEN },
            )
        }
    }

    private fun schemaDataSource(): DataSource {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:legacy_booking_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE clubs (
                        id BIGINT PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NULL,
                        timezone TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE events (
                        id BIGINT PRIMARY KEY,
                        club_id BIGINT NOT NULL REFERENCES clubs(id),
                        title TEXT NULL,
                        start_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        end_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        is_special BOOLEAN NOT NULL DEFAULT FALSE
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE tables (
                        id BIGINT PRIMARY KEY,
                        club_id BIGINT NOT NULL REFERENCES clubs(id),
                        zone_id BIGINT NULL,
                        table_number INT NOT NULL,
                        capacity INT NOT NULL,
                        min_deposit NUMERIC(12,2) NOT NULL,
                        active BOOLEAN NOT NULL DEFAULT TRUE
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE users (
                        id BIGINT PRIMARY KEY,
                        telegram_user_id BIGINT UNIQUE,
                        username TEXT NULL,
                        display_name TEXT NULL,
                        phone_e164 TEXT NULL,
                        encrypted_phone TEXT NULL,
                        phone_hash VARCHAR(64) NULL,
                        anonymized_at TIMESTAMP WITH TIME ZONE NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE bookings (
                        id UUID PRIMARY KEY,
                        event_id BIGINT NOT NULL REFERENCES events(id),
                        club_id BIGINT NOT NULL REFERENCES clubs(id),
                        table_id BIGINT NOT NULL REFERENCES tables(id),
                        table_number INT NOT NULL,
                        guest_user_id BIGINT NULL REFERENCES users(id),
                        guest_name TEXT NULL,
                        phone_e164 TEXT NULL,
                        encrypted_phone TEXT NULL,
                        phone_hash VARCHAR(64) NULL,
                        anonymized_at TIMESTAMP WITH TIME ZONE NULL,
                        promoter_user_id BIGINT NULL REFERENCES users(id),
                        guests_count INT NOT NULL,
                        min_deposit NUMERIC(12,2) NOT NULL,
                        total_deposit NUMERIC(12,2) NOT NULL,
                        slot_start TIMESTAMP WITH TIME ZONE NOT NULL,
                        slot_end TIMESTAMP WITH TIME ZONE NOT NULL,
                        arrival_by TIMESTAMP WITH TIME ZONE NULL,
                        status TEXT NOT NULL,
                        qr_secret VARCHAR(64) NOT NULL UNIQUE,
                        idempotency_key TEXT NOT NULL UNIQUE,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
        return dataSource
    }

    private fun seedLegacyBookingData() {
        val eventStart = Instant.parse("2026-06-04T20:00:00Z")
        val eventEnd = Instant.parse("2026-06-05T03:00:00Z")
        transaction {
            exec("INSERT INTO clubs (id, name, description, timezone) VALUES (1001, 'Runtime Club', 'Test club', 'Europe/Moscow')")
            exec(
                """
                INSERT INTO events (id, club_id, title, start_at, end_at, is_special)
                VALUES (1001, 1001, 'Runtime Night', '$eventStart', '$eventEnd', FALSE)
                """.trimIndent(),
            )
            exec(
                """
                INSERT INTO tables (id, club_id, zone_id, table_number, capacity, min_deposit, active)
                VALUES (1007, 1001, NULL, 7, 4, 1000.00, TRUE), (1008, 1001, NULL, 8, 4, 1000.00, TRUE), (1009, 1001, NULL, 9, 4, 1000.00, TRUE)
                """.trimIndent(),
            )
            exec(
                """
                INSERT INTO users (id, telegram_user_id, username, display_name)
                VALUES (1100, $AUTH_USER_ID, 'real', 'Real User'), (1101, $SPOOFED_USER_ID, 'spoofed', 'Spoofed User')
                """.trimIndent(),
            )
            exec(
                """
                INSERT INTO bookings (
                    id, event_id, club_id, table_id, table_number, guest_user_id, guest_name, guests_count,
                    min_deposit, total_deposit, slot_start, slot_end, status, qr_secret, idempotency_key,
                    created_at, updated_at
                ) VALUES
                (RANDOM_UUID(), 1001, 1001, 1007, 7, 1100, 'Real User', 2, 1000.00, 2000.00, '$eventStart', '$eventEnd', 'SEATED', 'real-secret', 'real-idem', NOW(), NOW()),
                (RANDOM_UUID(), 1001, 1001, 1009, 9, 1101, 'Spoofed User', 2, 1000.00, 2000.00, '$eventStart', '$eventEnd', 'SEATED', 'spoof-secret', 'spoof-idem', NOW(), NOW())
                """.trimIndent(),
            )
        }
    }

    private fun HttpRequestBuilder.validInitData() {
        header("X-Telegram-Init-Data", VALID_INIT_DATA)
    }

    private fun privacyConfig(): PrivacyConfig =
        PrivacyConfig.fromEnv(mapOf("PHONE_ENCRYPTION_KEY" to "0123456789abcdef0123456789abcdef"))

    private class RecordingLegacyHqNotifier : LegacyHqNotifier {
        val messages = mutableListOf<String>()

        override suspend fun notify(textHtml: String) {
            messages += textHtml
        }
    }

    private companion object {
        private const val AUTH_USER_ID = 1000L
        private const val SPOOFED_USER_ID = 2000L
        private const val LEGACY_BOT_TOKEN = "111111:LEGACY_TOKEN"
        private const val VALID_INIT_DATA = "valid-init-data"
    }
}
