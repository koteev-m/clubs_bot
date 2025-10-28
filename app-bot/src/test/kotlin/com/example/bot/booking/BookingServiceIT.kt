package com.example.bot.booking

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
import com.example.bot.data.promo.PromoAttributionRepositoryImpl
import com.example.bot.data.promo.PromoLinkRepositoryImpl
import com.example.bot.data.security.ExposedUserRepository
import com.example.bot.data.security.ExposedUserRoleRepository
import com.example.bot.promo.InMemoryPromoAttributionStore
import com.example.bot.promo.PromoAttributionCoordinator
import com.example.bot.promo.PromoAttributionService
import com.example.bot.promo.PromoLinkIssueResult
import com.example.bot.promo.PromoLinkToken
import com.example.bot.promo.PromoLinkTokenCodec
import com.example.bot.promo.PromoStartResult
import com.example.bot.testing.PostgresAppTest
import com.example.bot.workers.OutboxWorker
import com.example.bot.workers.SendOutcome
import com.example.bot.workers.SendPort
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import testing.RequiresDocker
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

@RequiresDocker
@Tag("it")
class BookingServiceIT : PostgresAppTest() {
    private val fixedNow: Instant = Instant.parse("2025-04-01T10:00:00Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private fun newService(
        promoAttribution: PromoAttributionCoordinator = PromoAttributionCoordinator.Noop,
    ): BookingService {
        val bookingRepo = BookingRepository(database, clock)
        val holdRepo = BookingHoldRepository(database, clock)
        val outboxRepo = OutboxRepository(database, clock)
        val auditRepo = AuditLogRepository(database, clock)
        return BookingService(bookingRepo, holdRepo, outboxRepo, auditRepo, promoAttribution)
    }

    @Test
    fun `parallel confirm produces single booking`() =
        runBlocking {
            val service = newService()
            val seed = seedData()
            val holdResult =
                service.hold(
                    HoldRequest(
                        clubId = seed.clubId,
                        tableId = seed.tableId,
                        slotStart = seed.slotStart,
                        slotEnd = seed.slotEnd,
                        guestsCount = 2,
                        ttl = Duration.ofMinutes(15),
                    ),
                    idempotencyKey = "hold-race",
                ) as BookingCmdResult.HoldCreated

            val outcomes =
                listOf("c1", "c2")
                    .map { key -> async { service.confirm(holdResult.holdId, key) } }
                    .awaitAll()

            val bookedCount =
                transaction(database) {
                    BookingsTable
                        .selectAll()
                        .andWhere { BookingsTable.status eq BookingStatus.BOOKED.name }
                        .count()
                }
            assertEquals(1, bookedCount)
            assertTrue(outcomes.any { it is BookingCmdResult.Booked })
        }

    @Test
    fun `confirm is idempotent`() =
        runBlocking {
            val service = newService()
            val seed = seedData()
            val hold =
                service.hold(
                    HoldRequest(
                        clubId = seed.clubId,
                        tableId = seed.tableId,
                        slotStart = seed.slotStart,
                        slotEnd = seed.slotEnd,
                        guestsCount = 3,
                        ttl = Duration.ofMinutes(10),
                    ),
                    idempotencyKey = "hold-idem",
                ) as BookingCmdResult.HoldCreated

            val first = service.confirm(hold.holdId, "confirm-idem") as BookingCmdResult.Booked
            val second = service.confirm(hold.holdId, "confirm-idem") as BookingCmdResult.AlreadyBooked
            assertEquals(first.bookingId, second.bookingId)

            val bookings =
                transaction(database) {
                    BookingsTable
                        .selectAll()
                        .andWhere { BookingsTable.status eq BookingStatus.BOOKED.name }
                        .count()
                }
            assertEquals(1, bookings)
        }

    @Test
    fun `finalize enqueues and worker marks sent`() =
        runBlocking {
            val service = newService()
            val seed = seedData()
            val bookingId = confirmBooking(service, seed)
            val finalize = service.finalize(bookingId)
            assertTrue(finalize is BookingCmdResult.Booked)

            val sentMessages = mutableListOf<JsonObject>()
            val worker =
                OutboxWorker(
                    repository = OutboxRepository(database, clock),
                    sendPort =
                        object : SendPort {
                            override suspend fun send(
                                topic: String,
                                payload: JsonObject,
                            ): SendOutcome {
                                sentMessages += payload
                                return SendOutcome.Ok
                            }
                        },
                    limit = 5,
                    idleDelay = Duration.ofMillis(50),
                )

            val job = launch { worker.run() }
            delay(200)
            job.cancel()

            val status =
                transaction(database) {
                    BookingsTable
                        .selectAll()
                        .andWhere { BookingsTable.id eq bookingId }
                        .first()[BookingsTable.status]
                }
            assertEquals(BookingStatus.BOOKED.name, status)
            val outboxStatus =
                transaction(database) {
                    BookingOutboxTable
                        .selectAll()
                        .andWhere { BookingOutboxTable.topic eq "booking.confirmed" }
                        .first()[BookingOutboxTable.status]
                }
            assertEquals("SENT", outboxStatus)
            assertTrue(sentMessages.isNotEmpty())
        }

    @Test
    fun `promo deep-link attaches on finalize`() =
        runBlocking {
            val promoLinkRepository = PromoLinkRepositoryImpl(database, clock)
            val promoAttributionRepository = PromoAttributionRepositoryImpl(database, clock)
            val userRepository = ExposedUserRepository(database)
            val userRoleRepository = ExposedUserRoleRepository(database)
            val promoStore = InMemoryPromoAttributionStore(clock = clock)
            val promoService =
                PromoAttributionService(
                    promoLinkRepository = promoLinkRepository,
                    promoAttributionRepository = promoAttributionRepository,
                    userRepository = userRepository,
                    userRoleRepository = userRoleRepository,
                    store = promoStore,
                    clock = clock,
                )
            val service = newService(promoService)
            val seed = seedData()
            val promoterTelegramId = 1_000_001L
            val guestTelegramId = 2_000_002L

            val issued = promoService.issuePromoLink(promoterTelegramId)
            assertTrue(issued is PromoLinkIssueResult.Success)
            issued as PromoLinkIssueResult.Success

            val startResult = promoService.registerStart(guestTelegramId, issued.token)
            assertEquals(PromoStartResult.Stored, startResult)

            val bookingId = confirmBooking(service, seed)

            service.finalize(bookingId, guestTelegramId)
            service.finalize(bookingId, guestTelegramId)

            val stored = promoAttributionRepository.findByBooking(bookingId)
            assertNotNull(stored)
            stored!!
            assertEquals(issued.promoLink.id, stored.promoLinkId)
            assertEquals(issued.promoLink.promoterUserId, stored.promoterUserId)

            val count =
                transaction(database) {
                    exec("SELECT COUNT(*) FROM promo_attribution") { rs ->
                        if (rs.next()) rs.getLong(1) else 0L
                    } ?: 0L
                }
            assertEquals(1L, count)
        }

    @Test
    fun `retryable errors apply exponential backoff`() =
        runBlocking {
            val seed = seedData()
            val bookingRepo = BookingRepository(database, clock)
            val holdRepo = BookingHoldRepository(database, clock)
            val outboxRepo = OutboxRepository(database, clock)
            val auditRepo = AuditLogRepository(database, clock)
            val service = BookingService(bookingRepo, holdRepo, outboxRepo, auditRepo)
            val bookingId = confirmBooking(service, seed)
            service.finalize(bookingId)

            val failingPort =
                object : SendPort {
                    override suspend fun send(
                        topic: String,
                        payload: JsonObject,
                    ): SendOutcome = SendOutcome.RetryableError(RuntimeException("temporary"))
                }
            val workerClock = Clock.fixed(fixedNow, ZoneOffset.UTC)
            val worker =
                OutboxWorker(
                    repository = outboxRepo,
                    sendPort = failingPort,
                    limit = 1,
                    idleDelay = Duration.ofMillis(20),
                    clock = workerClock,
                    random = Random(0),
                )

            val job = launch { worker.run() }
            delay(150)
            job.cancel()

            val stored =
                transaction(database) {
                    BookingOutboxTable
                        .selectAll()
                        .andWhere { BookingOutboxTable.status eq "NEW" }
                        .first()
                }
            assertEquals(1, stored[BookingOutboxTable.attempts])
            val nextAttempt = stored[BookingOutboxTable.nextAttemptAt].toInstant()
            val expectedDelay = computeExpectedDelay(attemptsAfterFailure = 1)
            assertEquals(workerClock.instant().plus(expectedDelay), nextAttempt)
            assertTrue(
                expectedDelay.compareTo(com.example.bot.config.BotLimits.notifySendMaxBackoff) <= 0,
                "expected delay should not exceed max backoff",
            )
        }

    private fun computeExpectedDelay(attemptsAfterFailure: Int): Duration {
        val base = com.example.bot.config.BotLimits.notifySendBaseBackoff.toMillis()
        val max = com.example.bot.config.BotLimits.notifySendMaxBackoff.toMillis()
        val jitter = com.example.bot.config.BotLimits.notifySendJitter.toMillis()
        val shift =
            (attemptsAfterFailure - 1).coerceAtLeast(
                0,
            ).coerceAtMost(com.example.bot.config.BotLimits.notifyBackoffMaxShift)
        val raw = base shl shift
        val capped = raw.coerceAtMost(max)
        val offset = if (jitter == 0L) 0L else Random(0).nextLong(-jitter, jitter + 1)
        val candidate = (capped + offset).coerceAtLeast(base).coerceAtMost(max)
        return Duration.ofMillis(candidate)
    }

    private suspend fun confirmBooking(
        service: BookingService,
        seed: SeedData,
    ): UUID {
        val hold =
            service.hold(
                HoldRequest(
                    clubId = seed.clubId,
                    tableId = seed.tableId,
                    slotStart = seed.slotStart,
                    slotEnd = seed.slotEnd,
                    guestsCount = 2,
                    ttl = Duration.ofMinutes(30),
                ),
                idempotencyKey = "hold-${seed.tableId}",
            ) as BookingCmdResult.HoldCreated
        val confirm = service.confirm(hold.holdId, "confirm-${seed.tableId}") as BookingCmdResult.Booked
        return confirm.bookingId
    }

    private fun seedData(): SeedData {
        val slotStart = Instant.parse("2025-04-02T18:00:00Z")
        val slotEnd = Instant.parse("2025-04-02T21:00:00Z")
        return transaction(database) {
            val clubId =
                Clubs.insert {
                    it[name] = "Club"
                    it[description] = "Integration"
                    it[timezone] = "UTC"
                } get Clubs.id
            val clubIdValue = clubId.value.toLong()
            val tableId =
                TablesTable.insert {
                    it[TablesTable.clubId] = clubIdValue
                    it[zoneId] = null
                    it[tableNumber] = 5
                    it[capacity] = 6
                    it[minDeposit] = BigDecimal("75.00")
                    it[active] = true
                } get TablesTable.id
            EventsTable.insert {
                it[EventsTable.clubId] = clubIdValue
                it[startAt] = OffsetDateTime.ofInstant(slotStart, ZoneOffset.UTC)
                it[endAt] = OffsetDateTime.ofInstant(slotEnd, ZoneOffset.UTC)
                it[title] = "Event"
                it[isSpecial] = false
                it[posterUrl] = null
            }
            SeedData(
                clubId = clubIdValue,
                tableId = tableId,
                slotStart = slotStart,
                slotEnd = slotEnd,
            )
        }
    }

    private data class SeedData(
        val clubId: Long,
        val tableId: Long,
        val slotStart: Instant,
        val slotEnd: Instant,
    )
}

class PromoLinkTokenCodecTest {
    @Test
    fun `encode decode roundtrip with club`() {
        val original = PromoLinkToken(42, 7)
        val encoded = PromoLinkTokenCodec.encode(original)
        assertEquals(original, PromoLinkTokenCodec.decode(encoded))
        assertTrue(encoded.length <= 64)
    }

    @Test
    fun `encode decode roundtrip without club`() {
        val original = PromoLinkToken(123456789L, null)
        val encoded = PromoLinkTokenCodec.encode(original)
        assertEquals(original, PromoLinkTokenCodec.decode(encoded))
        assertTrue(encoded.length <= 64)
    }

    @Test
    fun `decode returns null for invalid base64`() {
        assertNull(PromoLinkTokenCodec.decode("!invalid!"))
    }

    @Test
    fun `decode returns null for malformed payload`() {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString("abc:def".toByteArray())
        assertNull(PromoLinkTokenCodec.decode(encoded))
    }

    @Test
    fun `max token length is within limit`() {
        val encoded = PromoLinkTokenCodec.encode(PromoLinkToken(Long.MAX_VALUE, Long.MAX_VALUE))
        assertTrue(encoded.length <= 64)
    }

    @Test
    fun `decode returns null when token exceeds limit`() {
        val oversizedPayload = "9".repeat(49)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(oversizedPayload.toByteArray())
        assertTrue(encoded.length > 64)
        assertNull(PromoLinkTokenCodec.decode(encoded))
    }
}
