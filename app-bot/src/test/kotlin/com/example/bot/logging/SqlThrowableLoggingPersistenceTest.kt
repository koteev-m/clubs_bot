package com.example.bot.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.OutputStreamAppender
import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.BookingsTable
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.booking.TablesTable
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.db.Clubs
import com.example.bot.data.db.withRetriedTx
import com.example.bot.data.repo.PaymentsRepositoryImpl
import com.example.bot.data.repo.PaymentsRepositoryImpl.PaymentsTable
import com.example.bot.di.DefaultPaymentsService
import com.example.bot.di.PaymentsService
import com.example.bot.payments.finalize.PaymentsFinalizeService
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.installJsonErrorPages
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.routes.paymentsCancelRefundRoutes
import com.example.bot.testing.PostgresAppTest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SQL_THROWABLE_SENTINEL = "secret-idem-sql-throwable-DO-NOT-LOG-73194"
private const val SQL_THROWABLE_TEST_TOKEN = "sql-throwable-test-token"
private const val PAYMENT_FAILURE_FUNCTION = "test_payment_action_secret_failure"
private const val PAYMENT_FAILURE_TRIGGER = "test_payment_action_secret_failure_trigger"

class SqlThrowableLoggingPersistenceTest : PostgresAppTest() {
    private val user = TelegramMiniUser(id = 73194L, username = "sql-logging-review")

    @BeforeEach
    fun prepareSqlFailureProbe() {
        overrideMiniAppValidatorForTesting { _, _ -> user }
        transaction(database) {
            exec("DROP TABLE IF EXISTS sql_throwable_logging_probe")
            exec(
                """
                CREATE TABLE sql_throwable_logging_probe (
                    idempotency_key varchar(255) NOT NULL,
                    CONSTRAINT sql_throwable_logging_probe_reject
                        CHECK (idempotency_key = 'allowed')
                )
                """.trimIndent(),
            )
        }
    }

    @AfterEach
    fun cleanupSqlFailureProbe() {
        resetMiniAppValidator()
        transaction(database) {
            exec("DROP TRIGGER IF EXISTS $PAYMENT_FAILURE_TRIGGER ON payment_actions")
            exec("DROP FUNCTION IF EXISTS $PAYMENT_FAILURE_FUNCTION() CASCADE")
            exec("DROP TABLE IF EXISTS sql_throwable_logging_probe")
        }
    }

    @Test
    fun `postgres sql throwable never reaches payment route status pages or json logs`() {
        val booking = seedBooking()
        seedPayment(booking)
        installPaymentActionFailureTrigger()
        val service = createService()
        val managedProbe = ManagedSqlFailureProbe(database)
        SqlBoundaryLogCapture(
            "Exposed",
            "DbTx",
            "PaymentsCancelRefundRoutes",
            "JsonErrorPages",
        ).use { capture ->
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(CallId) { generate { UUID.randomUUID().toString() } }
                    install(Koin) {
                        modules(
                            module {
                                single<PaymentsService> { service }
                            },
                        )
                    }
                    installJsonErrorPages()
                    paymentsCancelRefundRoutes { SQL_THROWABLE_TEST_TOKEN }
                    routing {
                        post("/api/sql-throwable-probe") {
                            managedProbe.failWithSql(call.request.header("Idempotency-Key").orEmpty())
                        }
                    }
                }

                val paymentResponse =
                    client.post("/api/clubs/${booking.clubId}/bookings/${booking.id}/refund") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        header("Idempotency-Key", SQL_THROWABLE_SENTINEL)
                        header("X-Telegram-Init-Data", "stub")
                        setBody("""{"amountMinor":1}""")
                    }
                assertEquals(HttpStatusCode.InternalServerError, paymentResponse.status)
                assertFalse(paymentResponse.bodyAsText().contains(SQL_THROWABLE_SENTINEL))

                val statusPagesResponse =
                    client.post("/api/sql-throwable-probe") {
                        header("Idempotency-Key", SQL_THROWABLE_SENTINEL)
                    }
                assertEquals(HttpStatusCode.InternalServerError, statusPagesResponse.status)
                assertFalse(statusPagesResponse.bodyAsText().contains(SQL_THROWABLE_SENTINEL))
            }

            capture.assertLoggerObserved("DbTx")
            capture.assertLoggerObserved("PaymentsCancelRefundRoutes")
            capture.assertLoggerObserved("JsonErrorPages")
            capture.assertClean(SQL_THROWABLE_SENTINEL)
        }
    }

    private fun createService(): PaymentsService =
        DefaultPaymentsService(
            finalizeService = NoopFinalizeService,
            paymentsRepository = PaymentsRepositoryImpl(database),
            bookingRepository = BookingRepository(database),
            metricsProvider = null,
            tracer = null,
        )

    private fun seedBooking(): BookingSeed {
        val bookingId = UUID.randomUUID()
        val slotStart = OffsetDateTime.ofInstant(Instant.parse("2026-08-05T20:00:00Z"), ZoneOffset.UTC)
        val slotEnd = slotStart.plusHours(4)
        var clubId = 0L
        transaction(database) {
            val clubPk =
                Clubs.insert {
                    it[name] = "SQL logging test club"
                    it[description] = "Test club"
                    it[timezone] = "UTC"
                } get Clubs.id
            clubId = clubPk.value.toLong()
            val tableId =
                TablesTable.insert {
                    it[TablesTable.clubId] = clubId
                    it[zoneId] = null
                    it[tableNumber] = 73194
                    it[capacity] = 4
                    it[minDeposit] = BigDecimal("100.00")
                    it[active] = true
                } get TablesTable.id
            val eventId =
                EventsTable.insert {
                    it[EventsTable.clubId] = clubId
                    it[startAt] = slotStart
                    it[endAt] = slotEnd
                    it[title] = "SQL logging test event"
                    it[isSpecial] = false
                    it[posterUrl] = null
                } get EventsTable.id
            BookingsTable.insert {
                it[id] = bookingId
                it[BookingsTable.eventId] = eventId
                it[BookingsTable.clubId] = clubId
                it[BookingsTable.tableId] = tableId
                it[tableNumber] = 73194
                it[guestUserId] = null
                it[guestName] = "SQL logging test guest"
                it[phoneE164] = null
                it[promoterUserId] = null
                it[guestsCount] = 2
                it[minDeposit] = BigDecimal("100.00")
                it[totalDeposit] = BigDecimal("200.00")
                it[BookingsTable.slotStart] = slotStart
                it[BookingsTable.slotEnd] = slotEnd
                it[arrivalBy] = slotStart
                it[status] = BookingStatus.BOOKED.name
                it[qrSecret] = "sql-logging-qr-$bookingId"
                it[idempotencyKey] = "sql-logging-booking-$bookingId"
                it[createdAt] = slotStart
                it[updatedAt] = slotStart
            }
        }
        return BookingSeed(bookingId, clubId)
    }

    private fun seedPayment(booking: BookingSeed) {
        val paymentId = UUID.randomUUID()
        transaction(database) {
            PaymentsTable.insert {
                it[id] = paymentId
                it[bookingId] = booking.id
                it[provider] = "TEST"
                it[currency] = "RUB"
                it[amountMinor] = 500L
                it[status] = "CAPTURED"
                it[payload] = "sql-logging-payment"
                it[idempotencyKey] = "sql-logging-payment-$paymentId"
            }
        }
    }

    private fun installPaymentActionFailureTrigger() {
        transaction(database) {
            exec(
                """
                CREATE FUNCTION $PAYMENT_FAILURE_FUNCTION() RETURNS trigger
                LANGUAGE plpgsql
                AS ${'$'}${'$'}
                BEGIN
                    RAISE EXCEPTION USING
                        ERRCODE = '23514',
                        MESSAGE = 'payment action rejected',
                        DETAIL = 'Key (idempotency_key)=(' || NEW.idempotency_key || ')';
                END;
                ${'$'}${'$'}
                """.trimIndent(),
            )
            exec(
                """
                CREATE TRIGGER $PAYMENT_FAILURE_TRIGGER
                BEFORE INSERT ON payment_actions
                FOR EACH ROW EXECUTE FUNCTION $PAYMENT_FAILURE_FUNCTION()
                """.trimIndent(),
            )
        }
    }

    private data class BookingSeed(
        val id: UUID,
        val clubId: Long,
    )
}

private object SqlThrowableLoggingProbeTable : Table("sql_throwable_logging_probe") {
    val idempotencyKey = varchar("idempotency_key", 255)
}

private class ManagedSqlFailureProbe(
    private val database: Database,
) {
    suspend fun failWithSql(idempotencyKey: String): Nothing {
        try {
            withRetriedTx(name = "payment-sql-logging-probe", database = database) {
                SqlThrowableLoggingProbeTable.insert {
                    it[SqlThrowableLoggingProbeTable.idempotencyKey] = idempotencyKey
                }
            }
            error("SQL failure probe unexpectedly succeeded")
        } catch (sql: SQLException) {
            val wrapper = IllegalStateException("database operation failed")
            wrapper.addSuppressed(sql)
            throw wrapper
        }
    }
}

private object NoopFinalizeService : PaymentsFinalizeService {
    override suspend fun finalize(
        clubId: Long,
        bookingId: UUID,
        paymentToken: String?,
        idemKey: String,
        actorUserId: Long,
    ): PaymentsFinalizeService.FinalizeResult = PaymentsFinalizeService.FinalizeResult("NOOP")
}

private class SqlBoundaryLogCapture(
    vararg loggerNames: String,
) : AutoCloseable {
    private val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    private val records = mutableListOf<CapturedSqlLog>()
    private val loggerStates =
        loggerNames.map { loggerName ->
            val logger = loggerContext.getLogger(loggerName)
            val appender =
                object : AppenderBase<ILoggingEvent>() {
                    override fun append(eventObject: ILoggingEvent) {
                        eventObject.prepareForDeferredProcessing()
                        val rendered =
                            buildString {
                                append(eventObject.formattedMessage)
                                eventObject.argumentArray?.forEach { argument -> append('|').append(argument) }
                                append('|').append(eventObject.mdcPropertyMap)
                                eventObject.throwableProxy?.let { throwable ->
                                    append('|').append(ThrowableProxyUtil.asString(throwable))
                                }
                                productionJsonEncoders(loggerContext).forEach { encoder ->
                                    append('|').append(encoder.encode(eventObject).toString(StandardCharsets.UTF_8))
                                }
                            }
                        synchronized(records) {
                            records +=
                                CapturedSqlLog(
                                    loggerName = eventObject.loggerName,
                                    rendered = rendered,
                                    safeShape =
                                        "level=${eventObject.level} " +
                                            "transactionAttempt=${
                                                eventObject.message.contains("Transaction attempt")
                                            } " +
                                            "failed=${eventObject.message.contains("failed")} " +
                                            "statements=${eventObject.message.contains("Statement(s)")} " +
                                            "throwable=${eventObject.throwableProxy?.className ?: "none"}",
                                )
                        }
                    }
                }
            appender.context = loggerContext
            appender.start()
            val state = LoggerState(logger, logger.level, logger.isAdditive, appender)
            logger.level = if (loggerName == "Exposed") Level.INFO else Level.TRACE
            logger.isAdditive = false
            logger.addAppender(appender)
            state
        }

    init {
        check(productionJsonEncoders(loggerContext).isNotEmpty()) {
            "Production JSON logging encoder is not active in the test runtime"
        }
    }

    fun assertLoggerObserved(loggerName: String) {
        assertTrue(snapshot().any { it.loggerName == loggerName }, "Expected a $loggerName log event")
    }

    fun assertClean(sensitiveValue: String) {
        setOf(sensitiveValue, sensitiveValue.take(8), sensitiveValue.takeLast(8)).forEach { fragment ->
            val unsafeLoggers =
                snapshot()
                    .filter { record -> record.rendered.contains(fragment) }
                    .map { record -> "${record.loggerName}(${record.safeShape})" }
                    .toSortedSet()
            assertTrue(
                unsafeLoggers.isEmpty(),
                "Sensitive SQL material reached logging output via $unsafeLoggers",
            )
        }
    }

    private fun snapshot(): List<CapturedSqlLog> = synchronized(records) { records.toList() }

    override fun close() {
        loggerStates.forEach { state ->
            state.logger.detachAppender(state.appender)
            state.appender.stop()
            state.logger.level = state.previousLevel
            state.logger.isAdditive = state.previousAdditive
        }
    }
}

private data class CapturedSqlLog(
    val loggerName: String,
    val rendered: String,
    val safeShape: String,
)

private data class LoggerState(
    val logger: Logger,
    val previousLevel: Level?,
    val previousAdditive: Boolean,
    val appender: AppenderBase<ILoggingEvent>,
)

@Suppress("UNCHECKED_CAST")
private fun productionJsonEncoders(context: LoggerContext): List<LoggingEventCompositeJsonEncoder> {
    val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
    return root
        .iteratorForAppenders()
        .asSequence()
        .mapNotNull { appender ->
            val output = appender as? OutputStreamAppender<ILoggingEvent>
            output?.encoder as? LoggingEventCompositeJsonEncoder
        }.toList()
}
