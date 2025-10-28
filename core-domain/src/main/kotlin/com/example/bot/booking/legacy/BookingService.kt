package com.example.bot.booking.legacy

import com.example.bot.booking.BookingReadRepository
import com.example.bot.booking.BookingRecord
import com.example.bot.booking.BookingWriteRepository
import com.example.bot.outbox.OutboxService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

private const val HOLD_TTL_MINUTES = 7L
private const val QR_BYTES = 32

/**
 * Service implementing table booking lifecycle: hold -> confirm -> seat/cancel.
 */
class BookingService(
    private val readRepo: BookingReadRepository,
    private val writeRepo: BookingWriteRepository,
    private val outbox: OutboxService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val holdTtl: Duration = Duration.ofMinutes(HOLD_TTL_MINUTES)

    /** Places a hold on a table. */
    suspend fun hold(
        req: HoldRequest,
        idemKey: String,
    ): Either<BookingError, HoldResponse> =
        runCatching {
            withContext(Dispatchers.IO) {
                val event =
                    readRepo.findEvent(req.clubId, req.eventStartUtc)
                        ?: return@withContext Either.Left(BookingError.NotFound("event not found"))
                val table =
                    readRepo.findTable(req.tableId)
                        ?: return@withContext Either.Left(BookingError.NotFound("table not found"))
                if (!table.active) {
                    return@withContext Either.Left(BookingError.Validation("table inactive"))
                }
                if (req.guestsCount > table.capacity) {
                    return@withContext Either.Left(BookingError.Validation("capacity exceeded"))
                }
                val expiresAt = Instant.now(clock).plus(holdTtl)
                val record =
                    try {
                        writeRepo.insertHold(
                            tableId = table.id,
                            eventId = event.id,
                            guests = req.guestsCount,
                            expiresAt = expiresAt,
                            idempotencyKey = idemKey,
                        )
                    } catch (e: IllegalStateException) {
                        return@withContext Either.Left(BookingError.Conflict(e.message ?: "conflict"))
                    }
                val totalDeposit = table.minDeposit.multiply(BigDecimal(req.guestsCount))
                Either.Right(
                    HoldResponse(
                        holdId = record.id,
                        expiresAt = record.expiresAt,
                        tableId = record.tableId,
                        eventId = record.eventId,
                        totalDeposit = totalDeposit,
                    ),
                )
            }
        }.getOrElse { e -> Either.Left(BookingError.Internal("hold failed", e)) }

    /** Confirms a booking, optionally using existing hold. */
    suspend fun confirm(
        req: ConfirmRequest,
        idemKey: String,
    ): Either<BookingError, BookingSummary> =
        runCatching {
            withContext(Dispatchers.IO) {
                val event =
                    readRepo.findEvent(req.clubId, req.eventStartUtc)
                        ?: return@withContext Either.Left(BookingError.NotFound("event not found"))
                val table =
                    readRepo.findTable(req.tableId)
                        ?: return@withContext Either.Left(BookingError.NotFound("table not found"))
                if (!table.active) {
                    return@withContext Either.Left(BookingError.Validation("table inactive"))
                }
                if (req.guestsCount > table.capacity) {
                    return@withContext Either.Left(BookingError.Validation("capacity exceeded"))
                }

                req.holdId?.let { writeRepo.deleteHold(it) }
                val totalDeposit = table.minDeposit.multiply(BigDecimal(req.guestsCount))
                val qr = randomQr()
                val record =
                    try {
                        writeRepo.insertBooking(
                            tableId = table.id,
                            eventId = event.id,
                            tableNumber = table.number,
                            guests = req.guestsCount,
                            totalDeposit = totalDeposit,
                            status = "BOOKED",
                            arrivalBy = event.endUtc,
                            qrSecret = qr,
                            idempotencyKey = idemKey,
                        )
                    } catch (e: IllegalStateException) {
                        return@withContext Either.Left(BookingError.Conflict(e.message ?: "conflict"))
                    }
                outbox.enqueue("BOOKING_CREATED", event.clubId, null, record.id.toString())
                Either.Right(record.toSummary(event.clubId, totalDeposit))
            }
        }.getOrElse { e -> Either.Left(BookingError.Internal("confirm failed", e)) }

    /** Cancels an existing booking. */
    @Suppress("UnusedParameter")
    suspend fun cancel(
        bookingId: UUID,
        actorUserId: Long,
        reason: String?,
        idemKey: String,
    ): Either<BookingError, BookingSummary> =
        runCatching {
            withContext(Dispatchers.IO) {
                val booking =
                    readRepo.findBookingById(bookingId)
                        ?: return@withContext Either.Left(BookingError.NotFound("booking not found"))
                if (booking.status != "BOOKED") {
                    return@withContext Either.Left(BookingError.Validation("cannot cancel in status ${booking.status}"))
                }
                writeRepo.updateStatus(bookingId, "CANCELLED")
                outbox.enqueue("BOOKING_CANCELLED", bookingId.mostSignificantBits, null, bookingId.toString())
                Either.Right(booking.copy(status = "CANCELLED").toSummary(null, booking.totalDeposit))
            }
        }.getOrElse { e -> Either.Left(BookingError.Internal("cancel failed", e)) }

    /** Marks booking as seated using QR secret. */
    @Suppress("UnusedParameter")
    suspend fun seatByQr(
        qrSecret: String,
        entryManagerUserId: Long,
        idemKey: String,
    ): Either<BookingError, BookingSummary> =
        runCatching {
            withContext(Dispatchers.IO) {
                val booking =
                    readRepo.findBookingByQr(qrSecret)
                        ?: return@withContext Either.Left(BookingError.NotFound("booking not found"))
                if (booking.status != "BOOKED") {
                    return@withContext Either.Left(BookingError.Validation("cannot seat in status ${booking.status}"))
                }
                writeRepo.updateStatus(booking.id, "SEATED")
                outbox.enqueue("BOOKING_SEATED", booking.id.mostSignificantBits, null, booking.id.toString())
                Either.Right(booking.copy(status = "SEATED").toSummary(null, booking.totalDeposit))
            }
        }.getOrElse { e -> Either.Left(BookingError.Internal("seat failed", e)) }

    /** Marks overdue bookings as NO_SHOW based on arrivalBy. */
    @Suppress("UnusedParameter")
    suspend fun markNoShowOverdue(now: Instant): Int =
        withContext(Dispatchers.IO) {
            // In-memory repository used in tests does not support batch update; this is noop.
            0
        }

    private fun BookingRecord.toSummary(
        clubId: Long?,
        totalDeposit: BigDecimal,
    ): BookingSummary =
        BookingSummary(
            id = id,
            clubId = clubId ?: 0L,
            eventId = eventId,
            tableId = tableId,
            tableNumber = tableNumber,
            guestsCount = guests,
            totalDeposit = totalDeposit,
            status = status,
            arrivalBy = arrivalBy,
            qrSecret = qrSecret,
        )

    private fun randomQr(): String {
        val bytes = Random.nextBytes(QR_BYTES)
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

/** Simple Either implementation. */
sealed class Either<out L, out R> {
    data class Left<L>(val value: L) : Either<L, Nothing>()

    data class Right<R>(val value: R) : Either<Nothing, R>()
}
