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

import com.example.bot.data.privacy.PrivacyConfig
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.installJsonErrorPages
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.webapp.WebAppInitDataTestHelper
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals

internal fun ApplicationTestBuilder.installLegacyAppWithDatabase(
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
        installJsonErrorPages()
        installLegacyBookingWebApp(
            privacyConfig = privacyConfig(),
            legacyHqNotifier = legacyHqNotifier,
            legacyBotTokenProvider = { LEGACY_BOT_TOKEN },
        )
    }
}

internal fun schemaDataSource(): DataSource {
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

internal fun seedLegacyBookingData() {
    val eventStart = Instant.parse("2026-06-04T20:00:00Z")
    val eventEnd = Instant.parse("2026-06-05T03:00:00Z")
    transaction {
        exec(
            """
            INSERT INTO clubs (id, name, description, timezone)
            VALUES (1001, 'Runtime Club', 'Test club', 'Europe/Moscow'),
                (1002, 'Other Club', 'Other test club', 'Europe/Moscow')
            """.trimIndent(),
        )
        exec(
            """
            INSERT INTO events (id, club_id, title, start_at, end_at, is_special)
            VALUES (1001, 1001, 'Runtime Night', '$eventStart', '$eventEnd', FALSE),
                (1002, 1002, 'Other Night', '$eventStart', '$eventEnd', FALSE)
            """.trimIndent(),
        )
        exec(
            """
            INSERT INTO tables (id, club_id, zone_id, table_number, capacity, min_deposit, active)
            VALUES (1007, 1001, NULL, 7, 4, 1000.00, TRUE),
                (1008, 1001, NULL, 8, 4, 1000.00, TRUE),
                (1009, 1001, NULL, 9, 4, 1000.00, TRUE),
                (1010, 1001, NULL, 10, 4, 1000.00, FALSE),
                (1011, 1002, NULL, 11, 4, 1000.00, TRUE)
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

internal fun fallbackInitData(): String =
    WebAppInitDataTestHelper.createInitData(
        FALLBACK_BOT_TOKEN,
        mapOf(
            "auth_date" to Instant.now().epochSecond.toString(),
            "user" to WebAppInitDataTestHelper.encodeUser(id = AUTH_USER_ID),
        ),
    )

internal fun HttpRequestBuilder.validInitData() {
    header("X-Telegram-Init-Data", VALID_INIT_DATA)
}

internal fun privacyConfig(): PrivacyConfig =
    PrivacyConfig.fromEnv(mapOf("PHONE_ENCRYPTION_KEY" to "0123456789abcdef0123456789abcdef"))


internal fun legacyEndpointRequests(): List<LegacyEndpointRequest> =
    listOf(
        LegacyEndpointRequest.Get("GET /api/clubs", "/api/clubs"),
        LegacyEndpointRequest.Get("GET /api/events", "/api/events?clubId=1001"),
        LegacyEndpointRequest.Get(
            "GET /api/tables/free",
            "/api/tables/free?clubId=1001&eventId=1001&guests=2",
        ),
        LegacyEndpointRequest.Post(
            "POST /api/bookings",
            "/api/bookings",
            newBookingRequest(tableId = 1008, guestName = "Auth Coverage"),
        ),
        LegacyEndpointRequest.Get("GET /api/bookings/my", "/api/bookings/my"),
    )

internal sealed class LegacyEndpointRequest(val name: String) {
    abstract suspend fun sendWithoutAuth(builder: ApplicationTestBuilder): io.ktor.client.statement.HttpResponse

    abstract suspend fun sendWithInvalidAuth(builder: ApplicationTestBuilder): io.ktor.client.statement.HttpResponse

    class Get(name: String, private val path: String) : LegacyEndpointRequest(name) {
        override suspend fun sendWithoutAuth(builder: ApplicationTestBuilder): io.ktor.client.statement.HttpResponse =
            builder.client.get(path)

        override suspend fun sendWithInvalidAuth(builder: ApplicationTestBuilder): io.ktor.client.statement.HttpResponse =
            builder.client.get(path) { header("X-Telegram-Init-Data", INVALID_INIT_DATA) }
    }

    class Post(
        name: String,
        private val path: String,
        private val body: String,
    ) : LegacyEndpointRequest(name) {
        override suspend fun sendWithoutAuth(builder: ApplicationTestBuilder): io.ktor.client.statement.HttpResponse =
            builder.client.post(path) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }

        override suspend fun sendWithInvalidAuth(builder: ApplicationTestBuilder): io.ktor.client.statement.HttpResponse =
            builder.client.post(path) {
                header("X-Telegram-Init-Data", INVALID_INIT_DATA)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
    }
}

internal fun newBookingRequest(
    tableId: Long,
    guestName: String,
    eventId: Long = 1001,
    clubId: Long = 1001,
    guestsCount: Int = 2,
    arrivalBy: String? = null,
): String {
    val arrivalByField = arrivalBy?.let { ",\n          \"arrivalBy\": \"$it\"" } ?: ""
    return """
    {
      "clubId": $clubId,
      "eventId": $eventId,
      "tableId": $tableId,
      "guestsCount": $guestsCount,
      "guestName": "$guestName",
      "tgUserId": $SPOOFED_USER_ID$arrivalByField
    }
    """.trimIndent()
}

internal fun assertErrorCode(body: String, expectedCode: String) {
    val json = Json.parseToJsonElement(body).jsonObject
    assertEquals(expectedCode, json["code"]?.jsonPrimitive?.content, body)
}

internal data class LegacyBookingErrorCase(
    val name: String,
    val request: String,
    val expectedStatus: HttpStatusCode,
    val expectedCode: String,
    val expectedMessage: String? = null,
)

internal fun guestTelegramUserIdForTable(tableId: Long): Long? =
    transaction {
        TransactionManager.current()
            .exec(
                """
                SELECT u.telegram_user_id
                FROM bookings b
                JOIN users u ON u.id = b.guest_user_id
                WHERE b.table_id = $tableId
                """.trimIndent(),
            ) { rs ->
                if (rs.next()) rs.getLong(1) else null
            }
    }

internal fun isBookingPersistedForTelegramUser(tableId: Long, telegramUserId: Long): Boolean =
    transaction {
        TransactionManager.current()
            .exec(
                """
                SELECT COUNT(*)
                FROM bookings b
                JOIN users u ON u.id = b.guest_user_id
                WHERE b.table_id = $tableId AND u.telegram_user_id = $telegramUserId
                """.trimIndent(),
            ) { rs ->
                rs.next()
                rs.getLong(1) > 0
            } ?: false
    }

internal fun countBookingsForTable(tableId: Long): Long =
    transaction {
        TransactionManager.current()
            .exec("SELECT COUNT(*) FROM bookings WHERE table_id = $tableId") { rs ->
                rs.next()
                rs.getLong(1)
            } ?: 0L
    }

internal fun restrictBookingGuestNameLength() {
    transaction {
        exec("ALTER TABLE bookings ALTER COLUMN guest_name VARCHAR(32)")
    }
}

internal fun insertCancelledBookingWithIdempotencyKey(idempotencyKey: String) {
    val eventStart = Instant.parse("2026-06-04T20:00:00Z")
    val eventEnd = Instant.parse("2026-06-05T03:00:00Z")
    transaction {
        exec(
            """
            INSERT INTO bookings (
                id, event_id, club_id, table_id, table_number, guest_user_id, guest_name, guests_count,
                min_deposit, total_deposit, slot_start, slot_end, status, qr_secret, idempotency_key,
                created_at, updated_at
            ) VALUES (
                RANDOM_UUID(), 1001, 1001, 1008, 8, 1100, 'Cancelled Duplicate', 2,
                1000.00, 2000.00, '$eventStart', '$eventEnd', 'CANCELLED',
                'cancelled-duplicate-secret', '$idempotencyKey', NOW(), NOW()
            )
            """.trimIndent(),
        )
    }
}

internal class RecordingLegacyHqNotifier : LegacyHqNotifier {
    val messages = mutableListOf<String>()
    val nextMessage = CompletableDeferred<String>()

    override suspend fun notify(textHtml: String) {
        messages += textHtml
        nextMessage.complete(textHtml)
    }
}

internal class SlowLegacyHqNotifier : LegacyHqNotifier {
    val started = CompletableDeferred<Unit>()

    override suspend fun notify(textHtml: String) {
        started.complete(Unit)
        delay(60_000)
    }
}

internal class FailingLegacyHqNotifier : LegacyHqNotifier {
    override suspend fun notify(textHtml: String) {
        error("boom")
    }
}

internal const val AUTH_USER_ID = 1000L
internal const val SPOOFED_USER_ID = 2000L
internal const val LEGACY_BOT_TOKEN = "111111:LEGACY_TOKEN"
internal const val FALLBACK_BOT_TOKEN = "222222:FALLBACK_TOKEN"
internal const val VALID_INIT_DATA = "valid-init-data"
private const val INVALID_INIT_DATA = "invalid-init-data"
