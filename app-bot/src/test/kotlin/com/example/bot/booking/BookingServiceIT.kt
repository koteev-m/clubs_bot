package com.example.bot.booking

import com.example.bot.data.booking.BookingHoldsTable
import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.BookingsTable
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.booking.TablesTable
import com.example.bot.audit.AuditLogger
import com.example.bot.data.audit.AuditLogRepositoryImpl
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertFalse
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
        val auditRepo = AuditLogRepositoryImpl(database, clock)
        val auditLogger = AuditLogger(auditRepo)
        return BookingService(
            bookingRepository = bookingRepo,
            holdRepository = holdRepo,
            outboxRepository = outboxRepo,
            auditLogger = auditLogger,
            promoAttribution = promoAttribution,
        )
    }

    @Test
    fun `parallel hold on same slot allows only one success`() =
        runBlocking {
            val service = newService()
            val seed = seedData()

            val outcomes =
                (1..20)
                    .map { idx -> async { service.hold(seed.holdRequest(guests = 2), "hold-race-$idx") } }
                    .awaitAll()

            val holdCreated = outcomes.count { it is BookingCmdResult.HoldCreated }
            val conflicts = outcomes.count { it is BookingCmdResult.DuplicateActiveBooking }

            assertEquals(1, holdCreated)
            assertEquals(19, conflicts)

            val activeHolds =
                transaction(database) {
                    BookingHoldsTable
                        .selectAll()
                        .andWhere { BookingHoldsTable.tableId eq seed.tableId }
                        .andWhere {
                            BookingHoldsTable.slotStart eq OffsetDateTime.ofInstant(seed.slotStart, ZoneOffset.UTC)
                        }
                        .andWhere {
                            BookingHoldsTable.slotEnd eq OffsetDateTime.ofInstant(seed.slotEnd, ZoneOffset.UTC)
                        }
                        .count()
                }
            assertEquals(1, activeHolds)
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
    fun `parallel confirm with same idempotency key returns booked and already booked`() =
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
                    idempotencyKey = "hold-idem-concurrent",
                ) as BookingCmdResult.HoldCreated

            val sameConfirmKey = "confirm-idem-concurrent"
            val outcomes =
                listOf(1, 2)
                    .map { async { service.confirm(hold.holdId, sameConfirmKey) } }
                    .awaitAll()

            val booked = outcomes.filterIsInstance<BookingCmdResult.Booked>()
            val alreadyBooked = outcomes.filterIsInstance<BookingCmdResult.AlreadyBooked>()

            assertEquals(1, booked.size)
            assertEquals(1, alreadyBooked.size)
            assertEquals(booked.single().bookingId, alreadyBooked.single().bookingId)

            val activeBookingsForSlot =
                transaction(database) {
                    BookingsTable
                        .selectAll()
                        .andWhere { BookingsTable.tableId eq seed.tableId }
                        .andWhere {
                            BookingsTable.slotStart eq OffsetDateTime.ofInstant(seed.slotStart, ZoneOffset.UTC)
                        }
                        .andWhere {
                            BookingsTable.slotEnd eq OffsetDateTime.ofInstant(seed.slotEnd, ZoneOffset.UTC)
                        }
                        .andWhere { BookingsTable.status eq BookingStatus.BOOKED.name }
                        .count()
                }
            assertEquals(1, activeBookingsForSlot)
            assertFalse(outcomes.any { it is BookingCmdResult.NotFound })
            assertFalse(outcomes.any { it is BookingCmdResult.HoldExpired })
            assertFalse(outcomes.any { it is BookingCmdResult.DuplicateActiveBooking })
        }

    @Test
    fun `confirm and concurrent hold keep slot invariants`() =
        runBlocking {
            val service = newService()
            val seed = seedData()
            val initialHold =
                service.hold(
                    HoldRequest(
                        clubId = seed.clubId,
                        tableId = seed.tableId,
                        slotStart = seed.slotStart,
                        slotEnd = seed.slotEnd,
                        guestsCount = 2,
                        ttl = Duration.ofMinutes(15),
                    ),
                    idempotencyKey = "confirm-vs-hold-initial",
                ) as BookingCmdResult.HoldCreated

            val startGate = CompletableDeferred<Unit>()
            val confirmDeferred =
                async {
                    startGate.await()
                    service.confirm(initialHold.holdId, "confirm-vs-hold-confirm")
                }
            val holdDeferred =
                async {
                    startGate.await()
                    service.hold(seed.holdRequest(guests = 3), "confirm-vs-hold-new")
                }
            startGate.complete(Unit)

            val confirmOutcome = confirmDeferred.await()
            val holdOutcome = holdDeferred.await()

            val successfulConfirm = confirmOutcome is BookingCmdResult.Booked
            val successfulNewHold = holdOutcome is BookingCmdResult.HoldCreated
            assertTrue(successfulConfirm.xor(successfulNewHold))

            assertTrue(
                confirmOutcome is BookingCmdResult.Booked ||
                    confirmOutcome is BookingCmdResult.DuplicateActiveBooking ||
                    confirmOutcome is BookingCmdResult.NotFound ||
                    confirmOutcome is BookingCmdResult.HoldExpired ||
                    confirmOutcome is BookingCmdResult.IdempotencyConflict,
            )
            assertTrue(
                holdOutcome is BookingCmdResult.HoldCreated ||
                    holdOutcome is BookingCmdResult.DuplicateActiveBooking ||
                    holdOutcome is BookingCmdResult.IdempotencyConflict,
            )

            val bookedCount =
                transaction(database) {
                    BookingsTable
                        .selectAll()
                        .andWhere { BookingsTable.tableId eq seed.tableId }
                        .andWhere {
                            BookingsTable.slotStart eq OffsetDateTime.ofInstant(seed.slotStart, ZoneOffset.UTC)
                        }
                        .andWhere {
                            BookingsTable.slotEnd eq OffsetDateTime.ofInstant(seed.slotEnd, ZoneOffset.UTC)
                        }
                        .andWhere { BookingsTable.status eq BookingStatus.BOOKED.name }
                        .count()
                }
            val activeHoldCount =
                transaction(database) {
                    BookingHoldsTable
                        .selectAll()
                        .andWhere { BookingHoldsTable.tableId eq seed.tableId }
                        .andWhere {
                            BookingHoldsTable.slotStart eq OffsetDateTime.ofInstant(seed.slotStart, ZoneOffset.UTC)
                        }
                        .andWhere {
                            BookingHoldsTable.slotEnd eq OffsetDateTime.ofInstant(seed.slotEnd, ZoneOffset.UTC)
                        }
                        .andWhere { BookingHoldsTable.expiresAt greater fixedNow.atOffset(ZoneOffset.UTC) }
                        .count()
                }
            assertTrue(bookedCount == 1L || activeHoldCount == 1L)
            assertFalse(bookedCount == 1L && activeHoldCount == 1L)
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
            val auditRepo = AuditLogRepositoryImpl(database, clock)
            val auditLogger = AuditLogger(auditRepo)
            val service =
                BookingService(
                    bookingRepository = bookingRepo,
                    holdRepository = holdRepo,
                    outboxRepository = outboxRepo,
                    auditLogger = auditLogger,
                )
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
        val base =
            com.example.bot.config.BotLimits.notifySendBaseBackoff
                .toMillis()
        val max =
            com.example.bot.config.BotLimits.notifySendMaxBackoff
                .toMillis()
        val jitter =
            com.example.bot.config.BotLimits.notifySendJitter
                .toMillis()
        val shift =
            (attemptsAfterFailure - 1)
                .coerceAtLeast(
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
    ) {

        fun holdRequest(guests: Int): HoldRequest =
            HoldRequest(
                clubId = clubId,
                tableId = tableId,
                slotStart = slotStart,
                slotEnd = slotEnd,
                guestsCount = guests,
                ttl = Duration.ofMinutes(15),
            )
    }
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
