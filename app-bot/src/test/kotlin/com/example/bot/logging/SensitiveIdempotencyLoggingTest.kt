package com.example.bot.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.OutputStreamAppender
import com.example.bot.audit.AuditLogEvent
import com.example.bot.audit.AuditLogRepository
import com.example.bot.booking.BookingCmdResult
import com.example.bot.booking.BookingService
import com.example.bot.data.db.withRetriedTx
import com.example.bot.data.notifications.NotificationsOutboxRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.data.security.webhook.DedupResult
import com.example.bot.data.security.webhook.SuspiciousIpRepository
import com.example.bot.data.security.webhook.WebhookUpdateDedupRepository
import com.example.bot.payments.PaymentsRepository
import com.example.bot.payments.finalize.DefaultPaymentsFinalizeService
import com.example.bot.promo.BookingTemplate
import com.example.bot.promo.BookingTemplateRepository
import com.example.bot.promo.BookingTemplateService
import com.example.bot.promo.TemplateActor
import com.example.bot.promo.TemplateBookingRequest
import com.example.bot.promo.templateIdempotencyKey
import com.example.bot.routes.bookingFinalizeRoutes
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.security.rbac.authorize
import com.example.bot.security.webhook.TELEGRAM_SECRET_HEADER
import com.example.bot.security.webhook.WebhookSecurity
import com.example.bot.security.webhook.webhookIdempotencyKey
import com.example.bot.testing.createInitData
import com.example.bot.testing.withInitData
import com.example.bot.webapp.TEST_BOT_TOKEN
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val SECRET_KEY = "secret-idem-scope-expanded-DO-NOT-LOG-58241"
private const val SHORT_SECRET_KEY = "K9ZQ7XWV"
private const val PROBE_LOGGER = "SensitiveIdempotencyLoggingProbe"

class SensitiveIdempotencyLoggingTest {
    @BeforeEach
    fun clearMdcBeforeTest() {
        MDC.clear()
    }

    @AfterEach
    fun assertMdcCleanAfterTest() {
        sensitiveMdcAliases.forEach { alias ->
            assertEquals(null, MDC.get(alias), "Sensitive MDC alias was not cleaned: $alias")
        }
        MDC.clear()
    }

    @Test
    fun `booking finalize route never serializes raw idempotency key`() {
        val bookingId = UUID.randomUUID()
        val bookingService = mockk<BookingService>()
        coEvery { bookingService.finalize(bookingId, 808L) } coAnswers {
            LoggerFactory.getLogger(PROBE_LOGGER).info("booking finalize probe")
            BookingCmdResult.Booked(bookingId)
        }
        JsonLogCapture(PROBE_LOGGER).use { capture ->
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    bookingFinalizeRoutes(bookingService) { TEST_BOT_TOKEN }
                }
                val response =
                    client.post("/api/clubs/7/bookings/finalize") {
                        contentType(ContentType.Application.Json)
                        header("Idempotency-Key", SECRET_KEY)
                        withInitData(createInitData(userId = 808L))
                        setBody("""{"bookingId":"$bookingId"}""")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }
            capture.assertClean(SECRET_KEY)
        }
    }

    @Test
    fun `booking template service never serializes generated idempotency key`() =
        runBlocking {
            val template =
                BookingTemplate(
                    id = 91L,
                    promoterUserId = 22L,
                    clubId = 7L,
                    tableCapacityMin = 2,
                    notes = null,
                    isActive = true,
                    createdAt = Instant.parse("2026-08-05T12:00:00Z"),
                )
            val request =
                TemplateBookingRequest(
                    clubId = template.clubId,
                    tableId = 44L,
                    slotStart = Instant.parse("2026-08-05T21:00:00Z"),
                    slotEnd = Instant.parse("2026-08-06T03:00:00Z"),
                )
            val holdId = UUID.randomUUID()
            val bookingId = UUID.randomUUID()
            val repository = mockk<BookingTemplateRepository>()
            val bookingService = mockk<BookingService>()
            coEvery { repository.get(template.id) } returns template
            coEvery { bookingService.hold(any(), any()) } returns BookingCmdResult.HoldCreated(holdId)
            coEvery { bookingService.confirm(holdId, any(), any(), any()) } returns BookingCmdResult.Booked(bookingId)
            coEvery { bookingService.finalize(bookingId, 2200L) } returns BookingCmdResult.Booked(bookingId)
            val service =
                BookingTemplateService(
                    repository = repository,
                    bookingService = bookingService,
                    userRepository = mockk(relaxed = true),
                    userRoleRepository = mockk(relaxed = true),
                    notificationsOutbox = mockk<NotificationsOutboxRepository>(relaxed = true),
                )
            val generatedKey =
                templateIdempotencyKey(
                    template.id,
                    template.promoterUserId,
                    request.slotStart,
                    request.tableId,
                )

            JsonLogCapture(BookingTemplateService::class.java.name).use { capture ->
                val result =
                    service.applyTemplate(
                        TemplateActor(
                            userId = template.promoterUserId,
                            telegramUserId = 2200L,
                            roles = setOf(Role.PROMOTER),
                            clubIds = setOf(template.clubId),
                        ),
                        template.id,
                        request,
                    )
                assertEquals(BookingCmdResult.Booked(bookingId), result)
                capture.assertClean(generatedKey)
            }
        }

    @Test
    fun `rbac audit fingerprint does not expose raw key to json logs`() {
        val auditEvent = slot<AuditLogEvent>()
        val auditRepository = mockk<AuditLogRepository>()
        coEvery { auditRepository.append(capture(auditEvent)) } coAnswers {
            LoggerFactory.getLogger(PROBE_LOGGER).info("rbac audit probe")
            1L
        }
        val user = User(id = 1L, telegramId = 101L, username = "rbac-user")
        val userRepository =
            object : UserRepository {
                override suspend fun getByTelegramId(id: Long): User? = if (id == user.telegramId) user else null

                override suspend fun getById(id: Long): User? = if (id == user.id) user else null
            }
        val roleRepository =
            object : UserRoleRepository {
                override suspend fun listRoles(userId: Long): Set<Role> = setOf(Role.MANAGER)

                override suspend fun listClubIdsFor(userId: Long): Set<Long> = setOf(7L)
            }

        JsonLogCapture(PROBE_LOGGER).use { capture ->
            testApplication {
                application {
                    install(RbacPlugin) {
                        this.userRepository = userRepository
                        userRoleRepository = roleRepository
                        auditLogRepository = auditRepository
                        principalExtractor = { call ->
                            call.request.headers["X-Telegram-Id"]
                                ?.toLongOrNull()
                                ?.let { TelegramPrincipal(it, null) }
                        }
                    }
                    routing {
                        authorize(Role.MANAGER) {
                            get("/secure") { call.respondText("ok") }
                        }
                    }
                }
                val response =
                    client.get("/secure") {
                        header("X-Telegram-Id", user.telegramId.toString())
                        header("Idempotency-Key", SECRET_KEY)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }
            val expectedPayload = "rbac|$SECRET_KEY|GET|/secure|access_granted"
            val expectedFingerprint =
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(expectedPayload.toByteArray(StandardCharsets.UTF_8)),
                )
            assertEquals(expectedFingerprint, auditEvent.captured.fingerprint)
            capture.assertClean(SECRET_KEY)
        }
    }

    @Test
    fun `webhook keeps business key but never serializes it through mdc`() {
        val dedupRepository = mockk<WebhookUpdateDedupRepository>()
        val suspiciousRepository = mockk<SuspiciousIpRepository>(relaxed = true)
        coEvery { dedupRepository.mark(77L) } returns DedupResult.FirstSeen(77L)
        coEvery { suspiciousRepository.record(any(), any(), any(), any()) } returns 1L

        JsonLogCapture(PROBE_LOGGER).use { capture ->
            testApplication {
                application {
                    routing {
                        route("/webhook") {
                            install(WebhookSecurity) {
                                secretToken = "webhook-token"
                                this.dedupRepository = dedupRepository
                                suspiciousIpRepository = suspiciousRepository
                            }
                            post {
                                assertEquals(SECRET_KEY, call.webhookIdempotencyKey())
                                LoggerFactory.getLogger(PROBE_LOGGER).info("webhook probe")
                                call.respondText("OK")
                            }
                        }
                    }
                }
                val response =
                    client.post("/webhook") {
                        header(TELEGRAM_SECRET_HEADER, "webhook-token")
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        header("Idempotency-Key", SECRET_KEY)
                        setBody("""{"update_id":77}""")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }
            capture.assertClean(SECRET_KEY)
        }
    }

    @Test
    fun `payments finalize logs presence only for long and short keys`() =
        runBlocking {
            val bookingId = UUID.randomUUID()
            val longRecord = paymentRecord(bookingId, SECRET_KEY, "CAPTURED")
            val shortRecord = paymentRecord(bookingId, SHORT_SECRET_KEY, "CAPTURED")
            val repository = mockk<PaymentsRepository>(relaxed = true)
            val bookingService = mockk<BookingService>()
            coEvery { repository.findByIdempotencyKey(SECRET_KEY) } returnsMany listOf(null, longRecord)
            coEvery { repository.findByIdempotencyKey(SHORT_SECRET_KEY) } returns shortRecord
            coEvery { repository.createInitiated(any(), any(), any(), any(), any(), SECRET_KEY) } returns longRecord
            coEvery { bookingService.finalize(bookingId, 42L) } returns BookingCmdResult.Booked(bookingId)
            val service = DefaultPaymentsFinalizeService(bookingService, repository)

            JsonLogCapture("PaymentsFinalizeService").use { capture ->
                assertEquals(
                    "CAPTURED",
                    service.finalize(7L, bookingId, null, SECRET_KEY, 42L).paymentStatus,
                )
                assertEquals(
                    "CAPTURED",
                    service.finalize(7L, bookingId, null, SHORT_SECRET_KEY, 42L).paymentStatus,
                )
                capture.assertClean(SECRET_KEY, SHORT_SECRET_KEY)
            }
            coVerify(exactly = 1) { bookingService.finalize(bookingId, 42L) }
        }

    @Test
    fun `db transaction logs never serialize sql exception detail`() =
        runBlocking {
            val failure =
                SQLException(
                    "duplicate Key (idempotency_key)=($SECRET_KEY)",
                    "23505",
                    IllegalStateException("nested $SECRET_KEY"),
                )
            JsonLogCapture("DbTx").use { capture ->
                val thrown =
                    try {
                        withRetriedTx(name = "sensitive-logging", manageTransaction = false) {
                            throw failure
                        }
                    } catch (error: SQLException) {
                        error
                    }
                assertSame(failure, thrown)
                capture.assertClean(SECRET_KEY)
            }
        }

    private fun paymentRecord(
        bookingId: UUID,
        key: String,
        status: String,
    ): PaymentsRepository.PaymentRecord {
        val now = Instant.parse("2026-08-05T12:00:00Z")
        return PaymentsRepository.PaymentRecord(
            id = UUID.randomUUID(),
            bookingId = bookingId,
            provider = "test",
            currency = "RUB",
            amountMinor = 0L,
            status = status,
            payload = "logging-test",
            externalId = null,
            telegramPaymentChargeId = null,
            providerPaymentChargeId = null,
            idempotencyKey = key,
            createdAt = now,
            updatedAt = now,
        )
    }
}

private class JsonLogCapture(
    loggerName: String,
) : AutoCloseable {
    private val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    private val logger = loggerContext.getLogger(loggerName)
    private val previousLevel = logger.level
    private val previousAdditive = logger.isAdditive
    private val records = mutableListOf<String>()
    private val appender =
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
                synchronized(records) { records += rendered }
            }
        }

    init {
        check(productionJsonEncoders(loggerContext).isNotEmpty()) {
            "Production JSON logging encoder is not active in the test runtime"
        }
        appender.context = loggerContext
        appender.start()
        logger.level = Level.TRACE
        logger.isAdditive = false
        logger.addAppender(appender)
    }

    fun assertClean(vararg sensitiveValues: String) {
        val snapshot = synchronized(records) { records.toList() }
        assertTrue(snapshot.isNotEmpty(), "Expected a production-path log event")
        val rendered = snapshot.joinToString("\n")
        sensitiveValues.forEach { sensitive ->
            val fragments = setOf(sensitive, sensitive.take(4), sensitive.takeLast(4))
            fragments.filter { it.length >= 4 }.forEach { fragment ->
                assertFalse(rendered.contains(fragment), "Sensitive idempotency material reached logging output")
            }
        }
        sensitiveMdcAliases.forEach { alias ->
            assertFalse(rendered.contains("\"$alias\""), "Sensitive MDC alias reached JSON logging output")
        }
    }

    override fun close() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = previousLevel
        logger.isAdditive = previousAdditive
    }
}

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

private val sensitiveMdcAliases =
    setOf(
        "Idempotency-Key",
        "idempotency_key",
        "idempotencyKey",
        "idemKey",
    )
