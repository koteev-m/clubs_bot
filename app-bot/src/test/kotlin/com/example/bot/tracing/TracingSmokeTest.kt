package com.example.bot.tracing

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.bot.booking.a3.Booking
import com.example.bot.booking.a3.BookingResponseSnapshot
import com.example.bot.booking.a3.BookingState
import com.example.bot.booking.a3.BookingStatus
import com.example.bot.booking.a3.BookingView
import com.example.bot.booking.a3.HoldResult
import com.example.bot.club.GuestList
import com.example.bot.club.GuestListEntry
import com.example.bot.club.GuestListEntryPage
import com.example.bot.club.GuestListEntrySearch
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListRepository
import com.example.bot.club.GuestListStatus
import com.example.bot.club.ParsedGuest
import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.logging.MdcKeys
import com.example.bot.observability.TracingProvider
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.installMiniAppAuthStatusPage
import com.example.bot.plugins.installTracing
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.routes.bookingA3Routes
import com.example.bot.routes.checkinRoutes
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.security.auth.TelegramPrincipal
import io.ktor.server.application.install
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class TracingSmokeTest :
    StringSpec({
        "booking logging emits trace and business ids" {
            GlobalOpenTelemetry.resetForTest()
            val exporter = InMemorySpanExporter.create()
            val tracing = TracingProvider.create(exporter)
            val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
            val listAppender = ListAppender<ILoggingEvent>().apply { start() }
            (loggerContext.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).addAppender(listAppender)

            overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = 99) }
            val bookingState = mockk<BookingState>()
            val bookingId = 42L
            val clubId = 1L
            val snapshot =
                BookingResponseSnapshot(
                    booking =
                        BookingView(
                            id = bookingId,
                            clubId = clubId,
                            tableId = 7,
                            eventId = 3,
                            status = BookingStatus.HOLD.name,
                            guestCount = 2,
                            arrivalWindow = listOf("2025-01-01T00:00:00Z", "2025-01-01T01:00:00Z"),
                            latePlusOneAllowedUntil = null,
                            plusOneUsed = false,
                            capacityAtHold = 4,
                            createdAt = Instant.EPOCH.toString(),
                            updatedAt = Instant.EPOCH.toString(),
                        ),
                    latePlusOneAllowedUntil = null,
                    arrivalWindow = listOf("2025-01-01T00:00:00Z", "2025-01-01T01:00:00Z"),
                )
            val booking =
                Booking(
                    id = bookingId,
                    userId = 99,
                    clubId = clubId,
                    tableId = 7,
                    eventId = 3,
                    status = BookingStatus.HOLD,
                    guestCount = 2,
                    arrivalWindow = Instant.EPOCH to Instant.EPOCH,
                    latePlusOneAllowedUntil = null,
                    plusOneUsed = false,
                    capacityAtHold = 4,
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                    holdExpiresAt = null,
                )
            coEvery {
                bookingState.hold(
                    userId = any(),
                    clubId = clubId,
                    tableId = any(),
                    eventId = any(),
                    guestCount = any(),
                    idempotencyKey = any(),
                    requestHash = any(),
                    promoterId = any(),
                )
            } returns HoldResult.Success(
                booking = booking,
                snapshot = snapshot,
                bodyJson = Json.encodeToString(snapshot),
                cached = false,
            )

            try {
                testApplication {
                    application {
                        install(ContentNegotiation) {
                            json()
                        }
                        installTracing(tracing.tracer)
                        installMiniAppAuthStatusPage()
                        bookingA3Routes(
                            bookingState = bookingState,
                            meterRegistry = null,
                            botTokenProvider = { "test-token" },
                        )
                    }

                    val response =
                        client.post("/api/clubs/1/bookings/hold") {
                            headers {
                                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                                append("X-Telegram-InitData", "test-init-data")
                                append("Idempotency-Key", "idem-1")
                            }
                            setBody("""{"tableId":7,"eventId":3,"guestCount":2}""")
                        }
                    response.status.value.shouldBeGreaterThan(0)
                }

                val loggedEvent =
                    listAppender
                        .list
                        .firstOrNull { event ->
                            event.formattedMessage.contains("booking.audit") ||
                                event.formattedMessage.contains("booking.hold")
                        }
                        .shouldNotBeNull()
                val mdc = loggedEvent.mdcPropertyMap
                mdc[MdcKeys.TRACE_ID].shouldNotBeNull().shouldNotBeBlank()
                mdc[MdcKeys.SPAN_ID].shouldNotBeNull().shouldNotBeBlank()
                listOf(
                    MdcKeys.CLUB_ID,
                    MdcKeys.LIST_ID,
                    MdcKeys.ENTRY_ID,
                    MdcKeys.BOOKING_ID,
                ).count { key -> !mdc[key].isNullOrBlank() }.shouldBeGreaterThan(0)
                loggedEvent.loggerName.shouldContain("BookingA3Routes")
            } finally {
                (loggerContext.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).detachAppender(listAppender)
                listAppender.stop()
                tracing.sdk.close()
                exporter.reset()
                resetMiniAppValidator()
            }
        }

        "checkin logging emits trace, clubId and masked QR" {
            GlobalOpenTelemetry.resetForTest()
            val exporter = InMemorySpanExporter.create()
            val tracing = TracingProvider.create(exporter)
            val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
            val listAppender = ListAppender<ILoggingEvent>().apply { start() }
            loggerContext.turboFilterList.clear()
            val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            rootLogger.addAppender(listAppender)
            (loggerContext.getLogger("CheckinRoutes") as Logger).addAppender(listAppender)

            overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = 101) }
            val guestListRepository = NoopGuestListRepository()

            try {
                testApplication {
                    application {
                        install(ContentNegotiation) {
                            json()
                        }
                        installTracing(tracing.tracer)
                        installMiniAppAuthStatusPage()
                        install(RbacPlugin) {
                            userRepository =
                                object : UserRepository {
                                    override suspend fun getByTelegramId(id: Long): User =
                                        User(id = id, telegramId = id, username = "tester")

                                    override suspend fun getById(id: Long): User =
                                        User(id = id, telegramId = id, username = "tester")
                                }
                            userRoleRepository =
                                object : UserRoleRepository {
                                    override suspend fun listRoles(userId: Long): Set<Role> = setOf(Role.MANAGER)

                                    override suspend fun listClubIdsFor(userId: Long): Set<Long> = setOf(1L)
                                }
                            auditLogRepository = mockk<AuditLogRepository>(relaxed = true)
                            principalExtractor = { TelegramPrincipal(101, "tester") }
                        }

                        checkinRoutes(
                            repository = guestListRepository,
                            promoterInviteService = null,
                            botTokenProvider = { "test-token" },
                            qrSecretProvider = { "secret" },
                            oldQrSecretProvider = { null },
                        )
                    }

                    val response =
                        client.post("/api/clubs/1/checkin/scan") {
                            headers {
                                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                                append("X-Telegram-InitData", "test-init-data")
                            }
                            setBody("""{"qr":"GL:bad"}""")
                        }
                    response.status shouldBe HttpStatusCode.BadRequest
                }

                val loggedEvent =
                    listAppender
                        .list
                        .firstOrNull { event ->
                            event.formattedMessage.contains("checkin.scan")
                        }
                        ?: error("No checkin.scan log found, events=${listAppender.list.map { it.formattedMessage }}")

                val mdc = loggedEvent.mdcPropertyMap
                mdc[MdcKeys.TRACE_ID].shouldNotBeNull().shouldNotBeBlank()
                mdc[MdcKeys.SPAN_ID].shouldNotBeNull().shouldNotBeBlank()
                mdc[MdcKeys.CLUB_ID] shouldBe "1"
                loggedEvent.formattedMessage.shouldNotContain("GL:")
                loggedEvent.formattedMessage.shouldNotContain("INV:")
            } finally {
                (loggerContext.getLogger("CheckinRoutes") as Logger).detachAppender(listAppender)
                rootLogger.detachAppender(listAppender)
                listAppender.stop()
                tracing.sdk.close()
                exporter.reset()
                resetMiniAppValidator()
            }
        }
    })

private class NoopGuestListRepository : GuestListRepository {
    override suspend fun createList(
        clubId: Long,
        eventId: Long,
        ownerType: GuestListOwnerType,
        ownerUserId: Long,
        title: String,
        capacity: Int,
        arrivalWindowStart: Instant?,
        arrivalWindowEnd: Instant?,
        status: GuestListStatus,
    ): GuestList = throw UnsupportedOperationException("createList not used")

    override suspend fun getList(id: Long): GuestList? = null

    override suspend fun findEntry(id: Long): GuestListEntry? = null

    override suspend fun listListsByClub(
        clubId: Long,
        page: Int,
        size: Int,
    ): List<GuestList> = emptyList()

    override suspend fun addEntry(
        listId: Long,
        fullName: String,
        phone: String?,
        guestsCount: Int,
        notes: String?,
        status: GuestListEntryStatus,
    ): GuestListEntry = throw UnsupportedOperationException("addEntry not used")

    override suspend fun setEntryStatus(
        entryId: Long,
        status: GuestListEntryStatus,
        checkedInBy: Long?,
        at: Instant?,
    ): GuestListEntry? = null

    override suspend fun listEntries(
        listId: Long,
        page: Int,
        size: Int,
        statusFilter: GuestListEntryStatus?,
    ): List<GuestListEntry> = emptyList()

    override suspend fun markArrived(
        entryId: Long,
        at: Instant,
    ): Boolean = false

    override suspend fun bulkImport(
        listId: Long,
        rows: List<ParsedGuest>,
        dryRun: Boolean,
    ): GuestListEntryPage = GuestListEntryPage(emptyList(), 0)

    override suspend fun searchEntries(
        filter: GuestListEntrySearch,
        page: Int,
        size: Int,
    ): GuestListEntryPage = GuestListEntryPage(emptyList(), 0)
}
