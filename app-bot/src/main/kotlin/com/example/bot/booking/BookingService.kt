package com.example.bot.booking

import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.booking.core.BookingCoreError
import com.example.bot.data.booking.core.BookingCoreResult
import com.example.bot.data.booking.core.BookingHoldRepository
import com.example.bot.data.booking.core.BookingRecord
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.booking.core.OutboxRepository
import com.example.bot.data.db.withTxRetry
import com.example.bot.promo.PromoAttributionCoordinator
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.util.UUID

sealed interface BookingCmdResult {
    data class HoldCreated(val holdId: UUID) : BookingCmdResult

    data class Booked(val bookingId: UUID) : BookingCmdResult

    data class AlreadyBooked(val bookingId: UUID) : BookingCmdResult

    data object HoldExpired : BookingCmdResult

    data object DuplicateActiveBooking : BookingCmdResult

    data object IdempotencyConflict : BookingCmdResult

    data object NotFound : BookingCmdResult
}

sealed interface BookingStatusUpdateResult {
    data class Success(val record: BookingRecord) : BookingStatusUpdateResult

    data class Conflict(val record: BookingRecord) : BookingStatusUpdateResult

    data object NotFound : BookingStatusUpdateResult
}

data class HoldRequest(
    val clubId: Long,
    val tableId: Long,
    val slotStart: Instant,
    val slotEnd: Instant,
    val guestsCount: Int,
    val ttl: Duration,
)

class BookingService(
    private val bookingRepository: BookingRepository,
    private val holdRepository: BookingHoldRepository,
    private val outboxRepository: OutboxRepository,
    private val auditLogRepository: AuditLogRepository,
    private val promoAttribution: PromoAttributionCoordinator = PromoAttributionCoordinator.Noop,
    private val meterRegistry: MeterRegistry? = null,
) {
    suspend fun hold(
        req: HoldRequest,
        idempotencyKey: String,
    ): BookingCmdResult =
        withTxRetry {
            val existingHold = holdRepository.findHoldByIdempotencyKey(idempotencyKey)
            if (existingHold != null) {
                val matchesRequest =
                    existingHold.clubId == req.clubId &&
                        existingHold.tableId == req.tableId &&
                        existingHold.slotStart == req.slotStart &&
                        existingHold.slotEnd == req.slotEnd &&
                        existingHold.guests == req.guestsCount
                return@withTxRetry if (matchesRequest) {
                    log(
                        "booking.hold",
                        req.clubId,
                        "idempotent",
                        buildJsonObject {
                            put("holdId", existingHold.id.toString())
                        },
                    )
                    BookingCmdResult.HoldCreated(existingHold.id)
                } else {
                    log(
                        "booking.hold",
                        req.clubId,
                        "idem_conflict",
                        buildJsonObject {
                            put("existingHoldId", existingHold.id.toString())
                        },
                    )
                    BookingCmdResult.IdempotencyConflict
                }
            }

            val hasActiveBooking =
                bookingRepository.existsActiveFor(req.tableId, req.slotStart, req.slotEnd)
            if (hasActiveBooking) {
                log("booking.hold", req.clubId, "duplicate_active", null)
                return@withTxRetry BookingCmdResult.DuplicateActiveBooking
            }

            val result =
                holdRepository.createHold(
                    tableId = req.tableId,
                    slotStart = req.slotStart,
                    slotEnd = req.slotEnd,
                    guestsCount = req.guestsCount,
                    ttl = req.ttl,
                    idempotencyKey = idempotencyKey,
                )
            when (result) {
                is BookingCoreResult.Success -> {
                    log(
                        action = "booking.hold",
                        clubId = req.clubId,
                        outcome = "created",
                        meta = buildJsonObject { put("holdId", result.value.id.toString()) },
                    )
                    BookingCmdResult.HoldCreated(result.value.id)
                }

                is BookingCoreResult.Failure -> {
                    when (result.error) {
                        BookingCoreError.ActiveHoldExists -> {
                            log("booking.hold", req.clubId, "duplicate_active", null)
                            BookingCmdResult.DuplicateActiveBooking
                        }

                        BookingCoreError.OptimisticRetryExceeded -> {
                            log("booking.hold", req.clubId, "retry_exceeded", null)
                            BookingCmdResult.IdempotencyConflict
                        }

                        else -> {
                            log("booking.hold", req.clubId, "unexpected", null)
                            throw IllegalStateException("unexpected hold error: ${result.error}")
                        }
                    }
                }
            }
        }

    suspend fun confirm(
        holdId: UUID,
        idempotencyKey: String,
    ): BookingCmdResult =
        withTxRetry {
            val existingBooking = bookingRepository.findByIdempotencyKey(idempotencyKey)
            if (existingBooking != null) {
                log(
                    action = "booking.confirm",
                    clubId = existingBooking.clubId,
                    outcome = "idempotent",
                    meta = buildJsonObject { put("bookingId", existingBooking.id.toString()) },
                )
                return@withTxRetry BookingCmdResult.AlreadyBooked(existingBooking.id)
            }

            val holdResult = holdRepository.consumeHold(holdId)
            val hold =
                when (holdResult) {
                    is BookingCoreResult.Success -> holdResult.value
                    is BookingCoreResult.Failure -> {
                        return@withTxRetry when (holdResult.error) {
                            BookingCoreError.HoldExpired -> {
                                log("booking.confirm", null, "hold_expired", null)
                                BookingCmdResult.HoldExpired
                            }

                            BookingCoreError.HoldNotFound -> {
                                log("booking.confirm", null, "hold_not_found", null)
                                BookingCmdResult.NotFound
                            }

                            BookingCoreError.OptimisticRetryExceeded -> {
                                log("booking.confirm", null, "retry_exceeded", null)
                                BookingCmdResult.IdempotencyConflict
                            }

                            else -> {
                                log("booking.confirm", null, "unexpected", null)
                                throw IllegalStateException("unexpected hold consume error: ${holdResult.error}")
                            }
                        }
                    }
                }

            val activeExists =
                bookingRepository.existsActiveFor(hold.tableId, hold.slotStart, hold.slotEnd)
            if (activeExists) {
                log(
                    "booking.confirm",
                    hold.clubId,
                    "duplicate_active",
                    buildJsonObject {
                        put("holdId", hold.id.toString())
                    },
                )
                return@withTxRetry BookingCmdResult.DuplicateActiveBooking
            }

            val booked =
                bookingRepository.createBooked(
                    clubId = hold.clubId,
                    tableId = hold.tableId,
                    slotStart = hold.slotStart,
                    slotEnd = hold.slotEnd,
                    guests = hold.guests,
                    minRate = hold.minDeposit,
                    idempotencyKey = idempotencyKey,
                )
            when (booked) {
                is BookingCoreResult.Success -> {
                    val record = booked.value
                    log(
                        action = "booking.confirm",
                        clubId = record.clubId,
                        outcome = "booked",
                        meta = buildJsonObject { put("bookingId", record.id.toString()) },
                    )
                    BookingCmdResult.Booked(record.id)
                }

                is BookingCoreResult.Failure -> {
                    when (booked.error) {
                        BookingCoreError.DuplicateActiveBooking -> {
                            log("booking.confirm", hold.clubId, "duplicate_active", null)
                            BookingCmdResult.DuplicateActiveBooking
                        }

                        BookingCoreError.IdempotencyConflict -> {
                            log("booking.confirm", hold.clubId, "idem_conflict", null)
                            BookingCmdResult.IdempotencyConflict
                        }

                        BookingCoreError.BookingNotFound -> {
                            log("booking.confirm", hold.clubId, "not_found", null)
                            BookingCmdResult.NotFound
                        }

                        BookingCoreError.OptimisticRetryExceeded -> {
                            log("booking.confirm", hold.clubId, "retry_exceeded", null)
                            BookingCmdResult.IdempotencyConflict
                        }

                        else -> {
                            log("booking.confirm", hold.clubId, "unexpected", null)
                            throw IllegalStateException("unexpected booking error: ${booked.error}")
                        }
                    }
                }
            }
        }

    suspend fun finalize(
        bookingId: UUID,
        telegramUserId: Long? = null,
    ): BookingCmdResult =
        withTxRetry {
            val booking = bookingRepository.findById(bookingId)
            val record =
                booking ?: run {
                    log("booking.finalize", null, "not_found", null)
                    return@withTxRetry BookingCmdResult.NotFound
                }

            promoAttribution.attachPending(record.id, telegramUserId)

            val payload =
                buildJsonObject {
                    put("bookingId", record.id.toString())
                    put("tableId", record.tableId)
                    put("clubId", record.clubId)
                    put("slotStart", record.slotStart.toString())
                    put("slotEnd", record.slotEnd.toString())
                }
            outboxRepository.enqueue("booking.confirmed", payload)
            log(
                action = "booking.finalize",
                clubId = record.clubId,
                outcome = "sent",
                meta = payload,
            )
            BookingCmdResult.Booked(record.id)
        }

    suspend fun seat(
        clubId: Long,
        bookingId: UUID,
    ): BookingStatusUpdateResult =
        updateStatus(
            clubId = clubId,
            bookingId = bookingId,
            targetStatus = BookingStatus.SEATED,
            action = "booking.seat",
            metricName = "booking.seated.total",
            outboxTopic = "booking.seated",
        )

    suspend fun markNoShow(
        clubId: Long,
        bookingId: UUID,
    ): BookingStatusUpdateResult =
        updateStatus(
            clubId = clubId,
            bookingId = bookingId,
            targetStatus = BookingStatus.NO_SHOW,
            action = "booking.no_show",
            metricName = "booking.noshow.total",
            outboxTopic = "booking.no_show",
        )

    private suspend fun log(
        action: String,
        clubId: Long?,
        outcome: String,
        meta: JsonObject?,
    ) {
        auditLogRepository.log(
            userId = null,
            action = action,
            resource = "booking",
            clubId = clubId,
            result = outcome,
            ip = null,
            meta = meta,
        )
    }

    private suspend fun updateStatus(
        clubId: Long,
        bookingId: UUID,
        targetStatus: BookingStatus,
        action: String,
        metricName: String,
        outboxTopic: String,
    ): BookingStatusUpdateResult {
        val current = bookingRepository.findById(bookingId)
            ?: run {
                log(action, clubId, "not_found", metaFor(bookingId, null))
                return BookingStatusUpdateResult.NotFound
            }
        if (current.clubId != clubId) {
            log(action, clubId, "not_found", metaFor(bookingId, null))
            return BookingStatusUpdateResult.NotFound
        }
        if (current.status != BookingStatus.BOOKED) {
            log(action, clubId, "conflict", metaFor(bookingId, current.status))
            return BookingStatusUpdateResult.Conflict(current)
        }

        return when (val updated = bookingRepository.setStatus(bookingId, targetStatus)) {
            is BookingCoreResult.Success -> {
                val record = updated.value
                val payload = payloadFor(record)
                meterRegistry?.counter(metricName, "club_id", clubId.toString())?.increment()
                outboxRepository.enqueue(outboxTopic, payload)
                log(action, clubId, "ok", payload)
                BookingStatusUpdateResult.Success(record)
            }

            is BookingCoreResult.Failure -> {
                when (updated.error) {
                    BookingCoreError.BookingNotFound -> {
                        log(action, clubId, "not_found", metaFor(bookingId, null))
                        BookingStatusUpdateResult.NotFound
                    }

                    BookingCoreError.OptimisticRetryExceeded -> {
                        val refreshed = bookingRepository.findById(bookingId) ?: current
                        log(action, clubId, "retry_exceeded", metaFor(bookingId, refreshed.status))
                        BookingStatusUpdateResult.Conflict(refreshed)
                    }

                    else -> {
                        log(action, clubId, "unexpected", metaFor(bookingId, current.status))
                        throw IllegalStateException("unexpected booking status update error: ${updated.error}")
                    }
                }
            }
        }
    }

    private fun payloadFor(record: BookingRecord): JsonObject =
        buildJsonObject {
            put("bookingId", record.id.toString())
            put("clubId", record.clubId)
            put("tableId", record.tableId)
            put("status", record.status.name)
            put("slotStart", record.slotStart.toString())
            put("slotEnd", record.slotEnd.toString())
            put("guests", record.guests)
        }

    private fun metaFor(
        bookingId: UUID,
        status: BookingStatus?,
    ): JsonObject =
        buildJsonObject {
            put("bookingId", bookingId.toString())
            status?.let { put("status", it.name) }
        }
}
