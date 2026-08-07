package com.example.bot.di

import com.example.bot.data.booking.core.BookingCancellationResult
import com.example.bot.data.booking.core.PaymentsBookingRepository
import com.example.bot.observability.MetricsProvider
import com.example.bot.payments.PaymentsRepository
import com.example.bot.payments.PaymentsRepository.CancelExecution
import com.example.bot.payments.PaymentsRepository.RefundExecution
import com.example.bot.payments.PaymentsRepository.RefundFingerprint
import com.example.bot.payments.PaymentsRepository.RefundRequestMode
import com.example.bot.payments.finalize.PaymentsFinalizeService
import com.example.bot.telemetry.PaymentsMetrics
import com.example.bot.telemetry.PaymentsSpanScope
import com.example.bot.telemetry.PaymentsTraceMetadata
import com.example.bot.telemetry.maskBookingId
import com.example.bot.telemetry.setRefundAmount
import com.example.bot.telemetry.setResult
import com.example.bot.telemetry.spanSuspending
import com.example.bot.availability.AvailabilityCacheInvalidator
import com.example.bot.opschat.NoopOpsNotificationPublisher
import com.example.bot.opschat.OpsDomainNotification
import com.example.bot.opschat.OpsNotificationEvent
import com.example.bot.opschat.OpsNotificationPublisher
import io.micrometer.tracing.Tracer
import org.slf4j.MDC
import java.time.Clock
import java.time.Instant
import java.util.UUID

class DefaultPaymentsService(
    private val finalizeService: PaymentsFinalizeService,
    private val paymentsRepository: PaymentsRepository,
    private val bookingRepository: PaymentsBookingRepository,
    private val metricsProvider: MetricsProvider?,
    private val tracer: Tracer?,
    private val opsPublisher: OpsNotificationPublisher = NoopOpsNotificationPublisher,
    private val clock: Clock = Clock.systemUTC(),
    private val availabilityCacheInvalidator: AvailabilityCacheInvalidator = AvailabilityCacheInvalidator.Noop,
) : PaymentsService {
    private fun currentRequestId(): String? = MDC.get("requestId") ?: MDC.get("callId")

    @Suppress("ExceptionRaisedInUnexpectedLocation")
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
                return@spanSuspending if (idemKey.isBlank()) {
                    cancelWithoutIdempotency(clubId, bookingId)
                } else {
                    cancelIdempotently(clubId, bookingId, reason, idemKey)
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

                val fingerprint =
                    RefundFingerprint(
                        mode =
                            if (amountMinor == null) {
                                RefundRequestMode.ALL_REMAINING
                            } else {
                                RefundRequestMode.EXPLICIT
                            },
                        requestAmountMinor = amountMinor,
                    )
                when (
                    val outcome =
                        paymentsRepository.executeRefundIdempotently(
                            clubId = clubId,
                            bookingId = bookingId,
                            idempotencyKey = idemKey,
                            fingerprint = fingerprint,
                        )
                ) {
                    is RefundExecution.Success -> {
                        outcome.remainingMinor?.let { remainder ->
                            PaymentsMetrics.updateRefundRemainder(
                                metricsProvider,
                                clubId,
                                maskBookingId(bookingId),
                                remainder,
                            )
                        }
                        if (outcome.idempotent) {
                            PaymentsMetrics.incrementIdempotentHit(metricsProvider, PaymentsMetrics.Path.Refund)
                        }
                        setResult(PaymentsMetrics.Result.Ok)
                        setRefundAmount(outcome.amountMinor)
                        PaymentsService.RefundResult(outcome.amountMinor, idempotent = outcome.idempotent)
                    }
                    is RefundExecution.Conflict -> throw PaymentsService.ConflictException(outcome.reason)
                    is RefundExecution.Unprocessable -> throw PaymentsService.UnprocessableException(outcome.reason)
                    RefundExecution.IdempotencyBindingMismatch ->
                        throw PaymentsService.ValidationException(
                            "idempotency key already used for different operation",
                        )
                    RefundExecution.IdempotencyPayloadMismatch ->
                        throw PaymentsService.ConflictException("idempotency payload mismatch")
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

    private fun notifyBestEffort(notification: OpsDomainNotification) {
        runCatching { opsPublisher.enqueue(notification) }
    }

    private suspend fun PaymentsSpanScope.cancelIdempotently(
        clubId: Long,
        bookingId: UUID,
        reason: String?,
        idempotencyKey: String,
    ): PaymentsService.CancelResult =
        when (
            val outcome =
                paymentsRepository.executeCancelIdempotently(
                    clubId = clubId,
                    bookingId = bookingId,
                    idempotencyKey = idempotencyKey,
                    reason = reason,
                )
        ) {
            is CancelExecution.Success -> {
                if (outcome.idempotent) {
                    PaymentsMetrics.incrementIdempotentHit(metricsProvider, PaymentsMetrics.Path.Cancel)
                } else {
                    availabilityCacheInvalidator.invalidateTables(outcome.clubId, outcome.slotStart)
                    if (!outcome.alreadyCancelled) {
                        notifyCancellation(outcome.clubId, outcome.bookingId)
                    }
                }
                setResult(PaymentsMetrics.Result.Ok)
                PaymentsService.CancelResult(
                    bookingId = bookingId,
                    idempotent = outcome.idempotent,
                    alreadyCancelled = outcome.alreadyCancelled,
                )
            }
            else -> throw outcome.toServiceException()
        }

    private fun CancelExecution.toServiceException(): RuntimeException =
        when (this) {
            is CancelExecution.Conflict -> PaymentsService.ConflictException(reason)
            is CancelExecution.StoredError -> PaymentsService.ValidationException(reason)
            CancelExecution.NotFound -> PaymentsService.ValidationException("booking not found")
            CancelExecution.IdempotencyBindingMismatch ->
                PaymentsService.ValidationException(
                    "idempotency key already used for different operation",
                )
            is CancelExecution.Success -> IllegalStateException("cancel success is not an error")
        }

    private suspend fun PaymentsSpanScope.cancelWithoutIdempotency(
        clubId: Long,
        bookingId: UUID,
    ): PaymentsService.CancelResult =
        when (val cancelResult = bookingRepository.cancel(bookingId, clubId)) {
            is BookingCancellationResult.Cancelled -> {
                availabilityCacheInvalidator.invalidateTables(
                    cancelResult.record.clubId,
                    cancelResult.record.slotStart,
                )
                notifyCancellation(clubId, bookingId)
                setResult(PaymentsMetrics.Result.Ok)
                PaymentsService.CancelResult(
                    bookingId = bookingId,
                    idempotent = false,
                    alreadyCancelled = false,
                )
            }
            is BookingCancellationResult.AlreadyCancelled -> {
                availabilityCacheInvalidator.invalidateTables(
                    cancelResult.record.clubId,
                    cancelResult.record.slotStart,
                )
                setResult(PaymentsMetrics.Result.Ok)
                PaymentsService.CancelResult(
                    bookingId = bookingId,
                    idempotent = false,
                    alreadyCancelled = true,
                )
            }
            is BookingCancellationResult.ConflictingStatus ->
                throw PaymentsService.ConflictException(
                    "cannot cancel booking in status ${cancelResult.record.status}",
                )
            BookingCancellationResult.NotFound -> throw PaymentsService.ValidationException("booking not found")
        }

    private fun notifyCancellation(
        clubId: Long,
        bookingId: UUID,
    ) {
        notifyBestEffort(
            OpsDomainNotification(
                clubId = clubId,
                event = OpsNotificationEvent.BOOKING_CANCELLED,
                subjectId = bookingId.toString(),
                occurredAt = Instant.now(clock),
            ),
        )
    }

    companion object {
        private const val MAX_REASON_LENGTH = 1024
    }
}
