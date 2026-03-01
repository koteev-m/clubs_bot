package com.example.bot.payments

import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.BookingsTable
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.booking.TablesTable
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.booking.core.PaymentsBookingRepository
import com.example.bot.data.db.Clubs
import com.example.bot.data.repo.PaymentsRepositoryImpl
import com.example.bot.data.repo.PaymentsRepositoryImpl.PaymentActionsTable
import com.example.bot.di.DefaultPaymentsService
import com.example.bot.di.PaymentsService
import com.example.bot.payments.finalize.PaymentsFinalizeService
import com.example.bot.testing.PostgresAppTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import testing.RequiresDocker
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@RequiresDocker
class PaymentsPersistenceTest : PostgresAppTest() {
    @Test
    fun `cancel persists action and updates booking`() =
        runBlocking {
            val service = createService()
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 1L, idemKey = "booking-1")

            val result =
                service.service.cancel(
                    clubId = booking.clubId,
                    bookingId = booking.id,
                    reason = "guest_request",
                    idemKey = "cancel-1",
                    actorUserId = 42L,
                )

            assertEquals(false, result.idempotent)
            assertEquals(false, result.alreadyCancelled)

            val saved = service.paymentsRepo.findActionByIdempotencyKey("cancel-1")
            assertNotNull(saved)
            assertEquals(PaymentsRepository.Result.Status.OK, saved!!.result.status)
            assertEquals("guest_request", saved.result.reason)

            val status = currentBookingStatus(booking.id)
            assertEquals(BookingStatus.CANCELLED, status)
        }

    @Test
    fun `cancel idempotency returns stored result`() =
        runBlocking {
            val service = createService()
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 2L, idemKey = "booking-2")

            service.service.cancel(booking.clubId, booking.id, null, "cancel-repeat", 7L)
            val second = service.service.cancel(booking.clubId, booking.id, null, "cancel-repeat", 7L)

            assertTrue(second.idempotent)
            assertEquals(false, second.alreadyCancelled)

            val saved = service.paymentsRepo.findActionByIdempotencyKey("cancel-repeat")
            assertNotNull(saved)
            assertEquals(PaymentsRepository.Result.Status.OK, saved!!.result.status)

            val actionsCount = transaction(database) { PaymentActionsTable.selectAll().count() }
            assertEquals(1, actionsCount)
        }


    @Test
    fun `cancel concurrent duplicate idempotency key does not fail with 500`() =
        runBlocking {
            val service = createService()
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 5L, idemKey = "booking-5")

            val results =
                listOf(1, 2)
                    .map {
                        async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                            service.service.cancel(
                                clubId = booking.clubId,
                                bookingId = booking.id,
                                reason = "race",
                                idemKey = "cancel-race",
                                actorUserId = 99L,
                            )
                        }
                    }
                    .also { deferred -> deferred.forEach { it.start() } }
                    .awaitAll()

            assertEquals(2, results.size)
            assertTrue(results.all { it.bookingId == booking.id })

            val actionsCount =
                transaction(database) {
                    PaymentActionsTable
                        .selectAll()
                        .where { PaymentActionsTable.idempotencyKey eq "cancel-race" }
                        .count()
                }
            assertEquals(1, actionsCount)
        }

    @Test
    fun `refund concurrent duplicate idempotency key does not fail with 500`() =
        runBlocking {
            val service = createService()
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 6L, idemKey = "booking-6")
            service.service.seedLedger(
                clubId = booking.clubId,
                bookingId = booking.id,
                status = BookingStatus.BOOKED.name,
                capturedMinor = 500,
                refundedMinor = 0,
            )

            val results =
                listOf(1, 2)
                    .map {
                        async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                            service.service.refund(
                                clubId = booking.clubId,
                                bookingId = booking.id,
                                amountMinor = 300,
                                idemKey = "refund-race",
                                actorUserId = 100L,
                            )
                        }
                    }
                    .also { deferred -> deferred.forEach { it.start() } }
                    .awaitAll()

            assertEquals(2, results.size)
            assertTrue(results.all { it.refundAmountMinor == 300L })

            val actionsCount =
                transaction(database) {
                    PaymentActionsTable
                        .selectAll()
                        .where { PaymentActionsTable.idempotencyKey eq "refund-race" }
                        .count()
                }
            assertEquals(1, actionsCount)
        }

    @Test
    fun `cancel conflict persists conflict status`() =
        runBlocking {
            val service = createService()
            val booking = seedBooking(status = BookingStatus.SEATED, clubId = 3L, idemKey = "booking-3")

            try {
                service.service.cancel(booking.clubId, booking.id, null, "cancel-conflict", 9L)
                fail("expected conflict")
            } catch (conflict: PaymentsService.ConflictException) {
                assertTrue(conflict.message?.contains("status") == true)
            }

            val saved = service.paymentsRepo.findActionByIdempotencyKey("cancel-conflict")
            assertNotNull(saved)
            assertEquals(PaymentsRepository.Result.Status.CONFLICT, saved!!.result.status)
            assertTrue(saved.result.reason!!.contains("SEATED"))
        }

    @Test
    fun `cancel idempotency survives new service instance`() =
        runBlocking {
            val first = createService()
            val booking = seedBooking(status = BookingStatus.BOOKED, clubId = 4L, idemKey = "booking-4")

            first.service.cancel(booking.clubId, booking.id, null, "cancel-restart", 11L)

            val second = createService()
            val result = second.service.cancel(booking.clubId, booking.id, null, "cancel-restart", 11L)

            assertTrue(result.idempotent)
            assertEquals(BookingStatus.CANCELLED, currentBookingStatus(booking.id))
        }

    private fun currentBookingStatus(id: UUID): BookingStatus =
        transaction(database) {
            val row =
                BookingsTable
                    .selectAll()
                    .where { BookingsTable.id eq id }
                    .firstOrNull() ?: fail("booking not found")
            BookingStatus.valueOf(row[BookingsTable.status])
        }

    private fun createService(): ServiceContext {
        val paymentsRepo = PaymentsRepositoryImpl(database)
        val bookingRepo: PaymentsBookingRepository = BookingRepository(database)
        val service =
            DefaultPaymentsService(
                finalizeService = NoopFinalizeService,
                paymentsRepository = paymentsRepo,
                bookingRepository = bookingRepo,
                metricsProvider = null,
                tracer = null,
            )
        return ServiceContext(service, paymentsRepo)
    }

    private fun seedBooking(
        status: BookingStatus,
        clubId: Long,
        idemKey: String,
    ): BookingSeed {
        val id = UUID.randomUUID()
        val slotStart = OffsetDateTime.ofInstant(Instant.parse("2025-01-01T20:00:00Z"), ZoneOffset.UTC)
        val slotEnd = slotStart.plusHours(4)
        var persistedClubId = clubId
        transaction(database) {
            val clubPk =
                Clubs.insert {
                    it[name] = "Club $clubId"
                    it[description] = "Test club"
                    it[timezone] = "UTC"
                } get Clubs.id
            persistedClubId = clubPk.value.toLong()
            val persistedTableId =
                TablesTable.insert {
                    it[TablesTable.clubId] = persistedClubId
                    it[zoneId] = null
                    it[tableNumber] = 10
                    it[capacity] = 4
                    it[minDeposit] = BigDecimal("100.00")
                    it[active] = true
                } get TablesTable.id
            val persistedEventId =
                EventsTable.insert {
                    it[EventsTable.clubId] = persistedClubId
                    it[startAt] = slotStart
                    it[endAt] = slotEnd
                    it[title] = "Party"
                    it[isSpecial] = false
                    it[posterUrl] = null
                } get EventsTable.id
            BookingsTable.insert {
                it[BookingsTable.id] = id
                it[BookingsTable.eventId] = persistedEventId
                it[BookingsTable.clubId] = persistedClubId
                it[BookingsTable.tableId] = persistedTableId
                it[BookingsTable.tableNumber] = 10
                it[BookingsTable.guestUserId] = null
                it[BookingsTable.guestName] = "Guest"
                it[BookingsTable.phoneE164] = null
                it[BookingsTable.promoterUserId] = null
                it[BookingsTable.guestsCount] = 2
                it[BookingsTable.minDeposit] = BigDecimal("100.00")
                it[BookingsTable.totalDeposit] = BigDecimal("200.00")
                it[BookingsTable.slotStart] = slotStart
                it[BookingsTable.slotEnd] = slotEnd
                it[BookingsTable.arrivalBy] = slotStart
                it[BookingsTable.status] = status.name
                it[BookingsTable.qrSecret] = "qr-$id"
                it[BookingsTable.idempotencyKey] = idemKey
                it[BookingsTable.createdAt] = slotStart
                it[BookingsTable.updatedAt] = slotStart
            }
        }
        return BookingSeed(id = id, clubId = persistedClubId)
    }

    private data class ServiceContext(
        val service: DefaultPaymentsService,
        val paymentsRepo: PaymentsRepository,
    )

    private data class BookingSeed(
        val id: UUID,
        val clubId: Long,
    )

    private object NoopFinalizeService : PaymentsFinalizeService {
        override suspend fun finalize(
            clubId: Long,
            bookingId: UUID,
            paymentToken: String?,
            idemKey: String,
            actorUserId: Long,
        ): PaymentsFinalizeService.FinalizeResult = PaymentsFinalizeService.FinalizeResult("NOOP")
    }
}
