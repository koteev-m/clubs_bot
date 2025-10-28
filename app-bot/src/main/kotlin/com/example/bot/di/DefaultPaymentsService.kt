package com.example.bot.di

import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.core.BookingCancellationResult
import com.example.bot.data.booking.core.PaymentsBookingRepository
import com.example.bot.observability.MetricsProvider
import com.example.bot.payments.PaymentsRepository
import com.example.bot.payments.PaymentsRepository.Action
import com.example.bot.payments.PaymentsRepository.Result
import com.example.bot.payments.PaymentsRepository.Result.Status as ActionStatus
import com.example.bot.payments.PaymentsRepository.SavedAction
import com.example.bot.payments.finalize.PaymentsFinalizeService
import com.example.bot.telemetry.PaymentsMetrics
import com.example.bot.telemetry.PaymentsSpanScope
import com.example.bot.telemetry.PaymentsTraceMetadata
import com.example.bot.telemetry.maskBookingId
import com.example.bot.telemetry.setRefundAmount
import com.example.bot.telemetry.setResult
import com.example.bot.telemetry.spanSuspending
import io.micrometer.tracing.Tracer
import org.slf4j.MDC
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DefaultPaymentsService(
    private val finalizeService: PaymentsFinalizeService,
    private val paymentsRepository: PaymentsRepository,
    private val bookingRepository: PaymentsBookingRepository,
    private val metricsProvider: MetricsProvider?,
    private val tracer: Tracer?,
) : PaymentsService {

    private data class BookingLedger(
        var status: BookingStatus = BookingStatus.BOOKED,
        var capturedMinor: Long = 0,
        var refundedMinor: Long = 0,
    )

    private val ledgers = ConcurrentHashMap<Pair<Long, UUID>, BookingLedger>()

    private fun currentRequestId(): String? = MDC.get("requestId") ?: MDC.get("callId")

    private sealed interface RefundOutcome {
        data class Success(val amount: Long, val remainderAfter: Long) : RefundOutcome

        data class Conflict(val reason: String) : RefundOutcome

        data class Unprocessable(val reason: String) : RefundOutcome

        data class Validation(val reason: String) : RefundOutcome
    }

    override suspend fun finalize(
        clubId: Long,
        bookingId: UUID,
        paymentToken: String?,
        idemKey: String,
        actorUserId: Long,
    ): PaymentsService.FinalizeResult {
        val traceMetadata =
            PaymentsTraceMetadata(
                httpRoute = "/api/clubs/{clubId}/bookings/finalize",
                paymentsPath = PaymentsMetrics.Path.Finalize.tag,
                idempotencyKeyPresent = idemKey.isNotBlank(),
                bookingIdMasked = maskBookingId(bookingId),
                requestId = currentRequestId(),
            )
        return tracer.spanSuspending("payments.finalize", traceMetadata) {
            try {
                val result = finalizeService.finalize(clubId, bookingId, paymentToken, idemKey, actorUserId)
                val ledgerKey = clubId to bookingId
                val ledger = ledgers.computeIfAbsent(ledgerKey) { BookingLedger() }
                ledger.status = BookingStatus.BOOKED
                setResult(PaymentsMetrics.Result.Ok)
                PaymentsService.FinalizeResult(result.paymentStatus)
            } catch (conflict: PaymentsFinalizeService.ConflictException) {
                PaymentsMetrics.incrementErrors(
                    metricsProvider,
                    PaymentsMetrics.Path.Finalize,
                    PaymentsMetrics.ErrorKind.State,
                )
                setResult(PaymentsMetrics.Result.Conflict)
                throw conflict
            } catch (validation: PaymentsFinalizeService.ValidationException) {
                PaymentsMetrics.incrementErrors(
                    metricsProvider,
                    PaymentsMetrics.Path.Finalize,
                    PaymentsMetrics.ErrorKind.Validation,
                )
                setResult(PaymentsMetrics.Result.Validation)
                throw validation
            } catch (unexpected: Throwable) {
                PaymentsMetrics.incrementErrors(
                    metricsProvider,
                    PaymentsMetrics.Path.Finalize,
                    PaymentsMetrics.ErrorKind.Unexpected,
                )
                setResult(PaymentsMetrics.Result.Unexpected)
                throw unexpected
            }
        }
    }

    override suspend fun cancel(
        clubId: Long,
        bookingId: UUID,
        reason: String?,
        idemKey: String,
        actorUserId: Long,
    ): PaymentsService.CancelResult {
        val traceMetadata =
            PaymentsTraceMetadata(
                httpRoute = "/api/clubs/{clubId}/bookings/{bookingId}/cancel",
                paymentsPath = PaymentsMetrics.Path.Cancel.tag,
                idempotencyKeyPresent = idemKey.isNotBlank(),
                bookingIdMasked = maskBookingId(bookingId),
                requestId = currentRequestId(),
            )
        return tracer.spanSuspending("payments.cancel", traceMetadata) {
            try {
                if (reason != null && reason.length > MAX_REASON_LENGTH) {
                    throw PaymentsService.ValidationException("reason too long")
                }

                val existing = paymentsRepository.findActionByIdempotencyKey(idemKey)
                if (existing != null) {
                    return@spanSuspending handleExistingCancel(existing, clubId, bookingId)
                }

                val cancelResult = bookingRepository.cancel(bookingId, clubId)
                return@spanSuspending when (cancelResult) {
                    is BookingCancellationResult.Cancelled -> {
                        updateLedgerStatus(clubId, bookingId, BookingStatus.CANCELLED)
                        paymentsRepository.recordAction(
                            bookingId = bookingId,
                            key = idemKey,
                            action = Action.CANCEL,
                            result = Result(ActionStatus.OK, reason),
                        )
                        setResult(PaymentsMetrics.Result.Ok)
                        PaymentsService.CancelResult(
                            bookingId = bookingId,
                            idempotent = false,
                            alreadyCancelled = false,
                        )
                    }

                    is BookingCancellationResult.AlreadyCancelled -> {
                        updateLedgerStatus(clubId, bookingId, BookingStatus.CANCELLED)
                        paymentsRepository.recordAction(
                            bookingId = bookingId,
                            key = idemKey,
                            action = Action.CANCEL,
                            result = Result(ActionStatus.ALREADY, reason ?: "already_cancelled"),
                        )
                        setResult(PaymentsMetrics.Result.Ok)
                        PaymentsService.CancelResult(
                            bookingId = bookingId,
                            idempotent = false,
                            alreadyCancelled = true,
                        )
                    }

                    is BookingCancellationResult.ConflictingStatus -> {
                        val message = "cannot cancel booking in status ${cancelResult.record.status}"
                        paymentsRepository.recordAction(
                            bookingId = bookingId,
                            key = idemKey,
                            action = Action.CANCEL,
                            result = Result(ActionStatus.CONFLICT, message),
                        )
                        throw PaymentsService.ConflictException(message)
                    }

                    BookingCancellationResult.NotFound -> {
                        throw PaymentsService.ValidationException("booking not found")
                    }
                }
            } catch (validation: PaymentsService.ValidationException) {
                PaymentsMetrics.incrementErrors(
                    metricsProvider,
                    PaymentsMetrics.Path.Cancel,
                    PaymentsMetrics.ErrorKind.Validation,
                )
                setResult(PaymentsMetrics.Result.Validation)
                throw validation
            } catch (conflict: PaymentsService.ConflictException) {
                PaymentsMetrics.incrementErrors(
                    metricsProvider,
                    PaymentsMetrics.Path.Cancel,
                    PaymentsMetrics.ErrorKind.State,
                )
                setResult(PaymentsMetrics.Result.Conflict)
                throw conflict
            } catch (unexpected: Throwable) {
                if (unexpected !is PaymentsService.UnprocessableException) {
                    PaymentsMetrics.incrementErrors(
                        metricsProvider,
                        PaymentsMetrics.Path.Cancel,
                        PaymentsMetrics.ErrorKind.Unexpected,
                    )
                    setResult(PaymentsMetrics.Result.Unexpected)
                }
                throw unexpected
            }
        }
    }

    override suspend fun refund(
        clubId: Long,
        bookingId: UUID,
        amountMinor: Long?,
        idemKey: String,
        actorUserId: Long,
    ): PaymentsService.RefundResult {
        val traceMetadata =
            PaymentsTraceMetadata(
                httpRoute = "/api/clubs/{clubId}/bookings/{bookingId}/refund",
                paymentsPath = PaymentsMetrics.Path.Refund.tag,
                idempotencyKeyPresent = idemKey.isNotBlank(),
                bookingIdMasked = maskBookingId(bookingId),
                requestId = currentRequestId(),
            )
        return tracer.spanSuspending("payments.refund", traceMetadata) {
            try {
                if (amountMinor != null && amountMinor < 0) {
                    throw PaymentsService.ValidationException("amount must be non-negative")
                }

                val existing = paymentsRepository.findActionByIdempotencyKey(idemKey)
                if (existing != null) {
                    return@spanSuspending handleExistingRefund(existing, clubId, bookingId, amountMinor)
                }

                val ledgerKey = clubId to bookingId
                val ledger = ledgers.computeIfAbsent(ledgerKey) { BookingLedger() }
                val outcome: RefundOutcome
                synchronized(ledger) {
                    val remainder = ledger.capturedMinor - ledger.refundedMinor
                    outcome =
                        when {
                            amountMinor == null && remainder <= 0 -> RefundOutcome.Conflict("nothing to refund")
                            else -> {
                                val target = amountMinor ?: remainder
                                when {
                                    target < 0 -> RefundOutcome.Validation("invalid refund amount")
                                    remainder <= 0 && target > 0 -> RefundOutcome.Conflict("nothing to refund")
                                    target > remainder -> RefundOutcome.Unprocessable("exceeds remainder")
                                    else -> {
                                        ledger.refundedMinor += target
                                        val remainderAfter = ledger.capturedMinor - ledger.refundedMinor
                                        RefundOutcome.Success(target, remainderAfter)
                                    }
                                }
                            }
                        }
                }

                when (outcome) {
                    is RefundOutcome.Success -> {
                        PaymentsMetrics.updateRefundRemainder(
                            metricsProvider,
                            clubId,
                            maskBookingId(bookingId),
                            outcome.remainderAfter,
                        )
                        paymentsRepository.recordAction(
                            bookingId = bookingId,
                            key = idemKey,
                            action = Action.REFUND,
                            result = Result(ActionStatus.OK, outcome.amount.toString()),
                        )
                        setResult(PaymentsMetrics.Result.Ok)
                        setRefundAmount(outcome.amount)
                        PaymentsService.RefundResult(outcome.amount, idempotent = false)
                    }

                    is RefundOutcome.Conflict -> {
                        paymentsRepository.recordAction(
                            bookingId = bookingId,
                            key = idemKey,
                            action = Action.REFUND,
                            result = Result(ActionStatus.CONFLICT, outcome.reason),
                        )
                        throw PaymentsService.ConflictException(outcome.reason)
                    }

                    is RefundOutcome.Unprocessable -> {
                        paymentsRepository.recordAction(
                            bookingId = bookingId,
                            key = idemKey,
                            action = Action.REFUND,
                            result = Result(ActionStatus.ERROR, outcome.reason),
                        )
                        throw PaymentsService.UnprocessableException(outcome.reason)
                    }

                    is RefundOutcome.Validation -> throw PaymentsService.ValidationException(outcome.reason)
                }
            } catch (validation: PaymentsService.ValidationException) {
                PaymentsMetrics.incrementErrors(
                    metricsProvider,
                    PaymentsMetrics.Path.Refund,
                    PaymentsMetrics.ErrorKind.Validation,
                )
                setResult(PaymentsMetrics.Result.Validation)
                throw validation
            } catch (conflict: PaymentsService.ConflictException) {
                PaymentsMetrics.incrementErrors(
                    metricsProvider,
                    PaymentsMetrics.Path.Refund,
                    PaymentsMetrics.ErrorKind.State,
                )
                setResult(PaymentsMetrics.Result.Conflict)
                throw conflict
            } catch (unprocessable: PaymentsService.UnprocessableException) {
                PaymentsMetrics.incrementErrors(
                    metricsProvider,
                    PaymentsMetrics.Path.Refund,
                    PaymentsMetrics.ErrorKind.Unprocessable,
                )
                setResult(PaymentsMetrics.Result.Unprocessable)
                throw unprocessable
            } catch (unexpected: Throwable) {
                PaymentsMetrics.incrementErrors(
                    metricsProvider,
                    PaymentsMetrics.Path.Refund,
                    PaymentsMetrics.ErrorKind.Unexpected,
                )
                setResult(PaymentsMetrics.Result.Unexpected)
                throw unexpected
            }
        }
    }

    internal fun seedLedger(
        clubId: Long,
        bookingId: UUID,
        status: String,
        capturedMinor: Long,
        refundedMinor: Long,
    ) {
        val bookingStatus = when (status.uppercase()) {
            "BOOKED" -> BookingStatus.BOOKED
            "CANCELLED" -> BookingStatus.CANCELLED
            else -> BookingStatus.BOOKED
        }
        val ledger = ledgers.computeIfAbsent(clubId to bookingId) { BookingLedger() }
        ledger.status = bookingStatus
        ledger.capturedMinor = capturedMinor
        ledger.refundedMinor = refundedMinor
        PaymentsMetrics.updateRefundRemainder(
            metricsProvider,
            clubId,
            maskBookingId(bookingId),
            capturedMinor - refundedMinor,
        )
    }

    private fun updateLedgerStatus(
        clubId: Long,
        bookingId: UUID,
        status: BookingStatus,
    ) {
        val ledger = ledgers.computeIfAbsent(clubId to bookingId) { BookingLedger() }
        synchronized(ledger) {
            ledger.status = status
        }
    }

    private fun PaymentsSpanScope.handleExistingCancel(
        existing: SavedAction,
        clubId: Long,
        bookingId: UUID,
    ): PaymentsService.CancelResult {
        if (existing.action != Action.CANCEL) {
            throw PaymentsService.ConflictException("idempotency key already used for ${existing.action}")
        }
        if (existing.bookingId != bookingId) {
            throw PaymentsService.ConflictException("idempotency key mismatch")
        }
        return when (existing.result.status) {
            ActionStatus.OK -> {
                PaymentsMetrics.incrementIdempotentHit(
                    metricsProvider,
                    PaymentsMetrics.Path.Cancel,
                )
                updateLedgerStatus(clubId, bookingId, BookingStatus.CANCELLED)
                setResult(PaymentsMetrics.Result.Ok)
                PaymentsService.CancelResult(bookingId, idempotent = true, alreadyCancelled = false)
            }

            ActionStatus.ALREADY -> {
                PaymentsMetrics.incrementIdempotentHit(
                    metricsProvider,
                    PaymentsMetrics.Path.Cancel,
                )
                updateLedgerStatus(clubId, bookingId, BookingStatus.CANCELLED)
                setResult(PaymentsMetrics.Result.Ok)
                PaymentsService.CancelResult(bookingId, idempotent = true, alreadyCancelled = true)
            }

            ActionStatus.CONFLICT -> {
                PaymentsMetrics.incrementErrors(
                    metricsProvider,
                    PaymentsMetrics.Path.Cancel,
                    PaymentsMetrics.ErrorKind.State,
                )
                setResult(PaymentsMetrics.Result.Conflict)
                throw PaymentsService.ConflictException(existing.result.reason ?: "cannot cancel")
            }

            ActionStatus.ERROR -> {
                PaymentsMetrics.incrementErrors(
                    metricsProvider,
                    PaymentsMetrics.Path.Cancel,
                    PaymentsMetrics.ErrorKind.Validation,
                )
                setResult(PaymentsMetrics.Result.Validation)
                throw PaymentsService.ValidationException(existing.result.reason ?: "cannot cancel")
            }
        }
    }

    private fun PaymentsSpanScope.handleExistingRefund(
        existing: SavedAction,
        clubId: Long,
        bookingId: UUID,
        requestedAmount: Long?,
    ): PaymentsService.RefundResult {
        if (existing.action != Action.REFUND) {
            throw PaymentsService.ConflictException("idempotency key already used for ${existing.action}")
        }
        if (existing.bookingId != bookingId) {
            throw PaymentsService.ConflictException("idempotency key mismatch")
        }
        return when (existing.result.status) {
            ActionStatus.OK -> {
                val amount = existing.result.reason?.toLongOrNull()
                    ?: throw PaymentsService.ValidationException("stored refund amount missing")
                if (requestedAmount != null && requestedAmount != amount) {
                    throw PaymentsService.ConflictException("idempotency payload mismatch")
                }
                PaymentsMetrics.incrementIdempotentHit(
                    metricsProvider,
                    PaymentsMetrics.Path.Refund,
                )
                setResult(PaymentsMetrics.Result.Ok)
                PaymentsService.RefundResult(amount, idempotent = true)
            }

            ActionStatus.ALREADY -> {
                val amount = existing.result.reason?.toLongOrNull() ?: 0L
                PaymentsMetrics.incrementIdempotentHit(
                    metricsProvider,
                    PaymentsMetrics.Path.Refund,
                )
                setResult(PaymentsMetrics.Result.Ok)
                PaymentsService.RefundResult(amount, idempotent = true)
            }

            ActionStatus.CONFLICT -> {
                PaymentsMetrics.incrementErrors(
                    metricsProvider,
                    PaymentsMetrics.Path.Refund,
                    PaymentsMetrics.ErrorKind.State,
                )
                setResult(PaymentsMetrics.Result.Conflict)
                throw PaymentsService.ConflictException(existing.result.reason ?: "refund conflict")
            }

            ActionStatus.ERROR -> {
                PaymentsMetrics.incrementErrors(
                    metricsProvider,
                    PaymentsMetrics.Path.Refund,
                    PaymentsMetrics.ErrorKind.Validation,
                )
                setResult(PaymentsMetrics.Result.Validation)
                throw PaymentsService.ValidationException(existing.result.reason ?: "refund error")
            }
        }
    }

    companion object {
        private const val MAX_REASON_LENGTH = 1024
    }
}
