package com.example.bot.promo

import com.example.bot.booking.BookingCmdResult
import com.example.bot.booking.BookingService
import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.BookingsTable
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.booking.TablesTable
import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.booking.core.BookingHoldRepository
import com.example.bot.data.booking.core.BookingOutboxTable
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.booking.core.OutboxRepository
import com.example.bot.data.db.Clubs
import com.example.bot.data.notifications.NotificationsOutboxRepository
import com.example.bot.data.promo.BookingTemplateRepositoryImpl
import com.example.bot.data.promo.PromoAttributionRepositoryImpl
import com.example.bot.data.promo.PromoLinkRepositoryImpl
import com.example.bot.data.security.ExposedUserRepository
import com.example.bot.data.security.ExposedUserRoleRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.webhook.SuspiciousIpRepository
import com.example.bot.data.security.webhook.WebhookUpdateDedupRepository
import com.example.bot.telegram.TelegramClient
import com.example.bot.testing.PostgresAppTest
import com.example.bot.webhook.WebhookReply
import com.example.bot.webhook.webhookRoute
import com.example.bot.workers.OutboxWorker
import com.example.bot.workers.SendOutcome
import com.example.bot.workers.SendPort
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import testing.RequiresDocker
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.random.Random

@RequiresDocker
@Tag("it")
class PromoTemplateE2EIT : PostgresAppTest() {
    private val baseInstant: Instant = Instant.parse("2025-01-18T18:00:00Z")
    private val clock: Clock = Clock.fixed(baseInstant, ZoneOffset.UTC)
    private val json: Json = Json { ignoreUnknownKeys = true }

    private lateinit var koin: Koin
    private lateinit var templateService: BookingTemplateService
    private lateinit var promoService: PromoAttributionService
    private lateinit var promoLinkRepository: PromoLinkRepository
    private lateinit var promoAttributionRepository: PromoAttributionRepository
    private lateinit var outboxRepository: OutboxRepository
    private lateinit var outboxWorker: OutboxWorker
    private lateinit var promoStore: PromoAttributionStore
    private lateinit var dedupRepository: WebhookUpdateDedupRepository
    private lateinit var suspiciousIpRepository: SuspiciousIpRepository
    private lateinit var sendPort: RecordingSendPort

    @BeforeEach
    fun setUpDi() {
        stopKoin()
        sendPort = RecordingSendPort()
        koin =
            startKoin {
                modules(
                    module {
                        single { clock }
                        single { database }
                        single { Random(0) }
                        single<PromoAttributionStore> {
                            InMemoryPromoAttributionStore(
                                ttl = Duration.ofHours(1),
                                clock = get(),
                            )
                        }
                        single<PromoLinkRepository> {
                            PromoLinkRepositoryImpl(get(), get())
                        }
                        single<PromoAttributionRepository> {
                            PromoAttributionRepositoryImpl(get(), get())
                        }
                        single<BookingTemplateRepository> {
                            BookingTemplateRepositoryImpl(get(), get())
                        }
                        single { ExposedUserRepository(get()) }
                        single { ExposedUserRoleRepository(get()) }
                        single { NotificationsOutboxRepository(get()) }
                        single { AuditLogRepository(get(), get()) }
                        single { BookingRepository(get(), get()) }
                        single { BookingHoldRepository(get(), get()) }
                        single { OutboxRepository(get(), get()) }
                        single { SuspiciousIpRepository(get(), get()) }
                        single { WebhookUpdateDedupRepository(get(), clock = get()) }
                        single {
                            PromoAttributionService(
                                get(),
                                get(),
                                get(),
                                get(),
                                get(),
                                get(),
                            )
                        }
                        single<PromoAttributionCoordinator> { get<PromoAttributionService>() }
                        single { BookingService(get(), get(), get(), get(), get()) }
                        single {
                            BookingTemplateService(
                                get(),
                                get(),
                                get(),
                                get(),
                                get(),
                            )
                        }
                        single<SendPort> { sendPort }
                        single {
                            OutboxWorker(
                                get(),
                                get(),
                                limit = 5,
                                idleDelay = Duration.ofMillis(10),
                                clock = get(),
                                random = get(),
                            )
                        }
                    },
                )
            }.koin
        templateService = koin.get()
        promoService = koin.get()
        promoLinkRepository = koin.get()
        promoAttributionRepository = koin.get()
        outboxRepository = koin.get()
        outboxWorker = koin.get()
        promoStore = koin.get()
        dedupRepository = koin.get()
        suspiciousIpRepository = koin.get()
    }

    @AfterEach
    fun tearDownDi() {
        stopKoin()
    }

    @Test
    fun `promo link start applies template end-to-end`() =
        runBlocking {
            val promoterTelegramId = 7_001_001L
            val clubId = insertClub("Hyperion")
            val tableId = insertTable(clubId, tableNumber = 11, capacity = 6, deposit = BigDecimal("180.00"))
            val slotStart = baseInstant.plusSeconds(7_200)
            val slotEnd = slotStart.plusSeconds(14_400)
            insertEvent(clubId, slotStart, slotEnd)
            val promoterUserId = insertUser(promoterTelegramId, "hyperion-promoter")
            assignRole(promoterUserId, Role.PROMOTER, clubId)

            val actor = templateService.resolveActor(promoterTelegramId) ?: error("actor not found")

            val issued = promoService.issuePromoLink(promoterTelegramId)
            assertTrue(issued is PromoLinkIssueResult.Success)
            issued as PromoLinkIssueResult.Success
            val token = issued.token
            assertTrue(token.length <= 64)
            assertNotNull(promoLinkRepository.get(issued.promoLink.id))

            val telegramClient = mockk<TelegramClient>(relaxed = true)
            val updatePayload =
                """
                    {
                      "update_id": 101,
                      "message": {
                        "message_id": 1,
                        "chat": { "id": $promoterTelegramId },
                        "text": "/start $token"
                      }
                    }
                """
                    .trimIndent()
            testApplication {
                application {
                    routing {
                        webhookRoute(
                            security = {
                                requireSecret = false
                                dedupRepository = this@PromoTemplateE2EIT.dedupRepository
                                suspiciousIpRepository = this@PromoTemplateE2EIT.suspiciousIpRepository
                                json = this@PromoTemplateE2EIT.json
                            },
                            handler = { update ->
                                val message = update.message ?: return@webhookRoute null
                                val text = message.text ?: return@webhookRoute null
                                val providedToken =
                                    text
                                        .split(" ", limit = 2)
                                        .getOrNull(1)
                                        ?.trim()
                                        ?: return@webhookRoute null
                                val fromId = message.chat.id
                                when (promoService.registerStart(fromId, providedToken)) {
                                    PromoStartResult.Stored -> WebhookReply.Inline(mapOf("status" to "ok"))
                                    PromoStartResult.Invalid -> WebhookReply.Inline(mapOf("status" to "invalid"))
                                }
                            },
                            client = telegramClient,
                            json = json,
                        )
                    }
                }

                val response =
                    client.post("/webhook") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody(updatePayload)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

            val pending = requireNotNull(promoStore.popFresh(promoterTelegramId, clock.instant()))
            promoStore.put(pending)

            val template =
                templateService.createTemplate(
                    actor,
                    TemplateCreateRequest(
                        promoterUserId = actor.userId,
                        clubId = clubId,
                        tableCapacityMin = 4,
                        notes = "welcome",
                    ),
                )
            val bookingRequest =
                TemplateBookingRequest(
                    clubId = clubId,
                    tableId = tableId,
                    slotStart = slotStart,
                    slotEnd = slotEnd,
                )

            val firstResult = templateService.applyTemplate(actor, template.id, bookingRequest)
            assertTrue(firstResult is BookingCmdResult.Booked)
            val bookingId = (firstResult as BookingCmdResult.Booked).bookingId

            val processed = outboxWorker.runOnce()
            assertTrue(processed)
            val sentMessage = sendPort.sent.single()
            assertEquals("booking.confirmed", sentMessage.first)
            assertEquals(bookingId.toString(), sentMessage.second["bookingId"]?.jsonPrimitive?.content)

            transaction(database) {
                val bookedRows =
                    BookingsTable
                        .selectAll()
                        .andWhere { BookingsTable.status eq BookingStatus.BOOKED.name }
                        .toList()
                assertEquals(1, bookedRows.size)
                assertEquals(bookingId, bookedRows.single()[BookingsTable.id])
            }

            transaction(database) {
                val outboxRows = BookingOutboxTable.selectAll().toList()
                assertEquals(1, outboxRows.size)
                val row = outboxRows.single()
                assertEquals("SENT", row[BookingOutboxTable.status])
                assertEquals(1, row[BookingOutboxTable.attempts])
            }
            assertTrue(outboxRepository.pickBatchForSend(1).isEmpty())

            val storedAttribution = promoAttributionRepository.findByBooking(bookingId)
            assertNotNull(storedAttribution)

            transaction(database) {
                val promoRows =
                    PromoAttributionAssertionTable
                        .selectAll()
                        .andWhere { PromoAttributionAssertionTable.bookingId eq bookingId }
                        .toList()
                assertEquals(1, promoRows.size)
                val total = PromoAttributionAssertionTable.selectAll().count()
                assertEquals(1L, total)
            }

            val duplicateResult = templateService.applyTemplate(actor, template.id, bookingRequest)
            assertTrue(duplicateResult is BookingCmdResult.DuplicateActiveBooking)

            transaction(database) {
                val bookingCount =
                    BookingsTable
                        .selectAll()
                        .andWhere { BookingsTable.status eq BookingStatus.BOOKED.name }
                        .count()
                assertEquals(1L, bookingCount)
                val promoCount = PromoAttributionAssertionTable.selectAll().count()
                assertEquals(1L, promoCount)
                val outboxCount = BookingOutboxTable.selectAll().count()
                assertEquals(1L, outboxCount)
            }
        }

    private fun insertClub(name: String): Long {
        return transaction(database) {
            Clubs.insert { row ->
                row[Clubs.name] = name
                row[Clubs.description] = "$name club"
                row[Clubs.adminChatId] = null
                row[Clubs.timezone] = "UTC"
                row[Clubs.generalTopicId] = null
                row[Clubs.bookingsTopicId] = null
                row[Clubs.listsTopicId] = null
                row[Clubs.qaTopicId] = null
                row[Clubs.mediaTopicId] = null
                row[Clubs.systemTopicId] = null
            } get Clubs.id
        }.value.toLong()
    }

    private fun insertEvent(
        clubId: Long,
        start: Instant,
        end: Instant,
    ) {
        transaction(database) {
            EventsTable.insert { row ->
                row[EventsTable.clubId] = clubId
                row[EventsTable.title] = "Night"
                row[EventsTable.startAt] = OffsetDateTime.ofInstant(start, ZoneOffset.UTC)
                row[EventsTable.endAt] = OffsetDateTime.ofInstant(end, ZoneOffset.UTC)
                row[EventsTable.isSpecial] = false
                row[EventsTable.posterUrl] = null
            }
        }
    }

    private fun insertTable(
        clubId: Long,
        tableNumber: Int,
        capacity: Int,
        deposit: BigDecimal,
    ): Long {
        return transaction(database) {
            TablesTable.insert { row ->
                row[TablesTable.clubId] = clubId
                row[TablesTable.zoneId] = null
                row[TablesTable.tableNumber] = tableNumber
                row[TablesTable.capacity] = capacity
                row[TablesTable.minDeposit] = deposit
                row[TablesTable.active] = true
            } get TablesTable.id
        }
    }

    private fun insertUser(
        telegramId: Long,
        username: String,
    ): Long {
        return transaction(database) {
            TestUsersTable.insert { row ->
                row[TestUsersTable.telegramUserId] = telegramId
                row[TestUsersTable.username] = username
                row[TestUsersTable.displayName] = username
                row[TestUsersTable.phone] = null
            } get TestUsersTable.id
        }
    }

    private fun assignRole(
        userId: Long,
        role: Role,
        clubId: Long?,
    ) {
        transaction(database) {
            TestUserRolesTable.insert { row ->
                row[TestUserRolesTable.userId] = userId
                row[TestUserRolesTable.roleCode] = role.name
                if (clubId != null) {
                    row[TestUserRolesTable.scopeType] = "CLUB"
                    row[TestUserRolesTable.scopeClubId] = clubId
                } else {
                    row[TestUserRolesTable.scopeType] = "GLOBAL"
                    row[TestUserRolesTable.scopeClubId] = null
                }
            }
        }
    }

    private class RecordingSendPort : SendPort {
        val sent: MutableList<Pair<String, JsonObject>> = mutableListOf()

        override suspend fun send(
            topic: String,
            payload: JsonObject,
        ): SendOutcome {
            sent += topic to payload
            return SendOutcome.Ok
        }
    }

    private object TestUsersTable : Table("users") {
        val id = long("id").autoIncrement()
        val telegramUserId = long("telegram_user_id")
        val username = text("username").nullable()
        val displayName = text("display_name").nullable()
        val phone = text("phone_e164").nullable()

        override val primaryKey = PrimaryKey(id)
    }

    private object TestUserRolesTable : Table("user_roles") {
        val id = long("id").autoIncrement()
        val userId = long("user_id")
        val roleCode = text("role_code")
        val scopeType = text("scope_type")
        val scopeClubId = long("scope_club_id").nullable()

        override val primaryKey = PrimaryKey(id)
    }

    private object PromoAttributionAssertionTable : Table("promo_attribution") {
        val id = long("id").autoIncrement()
        val bookingId = uuid("booking_id")

        override val primaryKey = PrimaryKey(id)
    }
}
