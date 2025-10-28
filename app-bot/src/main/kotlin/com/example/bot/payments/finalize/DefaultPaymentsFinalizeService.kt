package com.example.bot.payments.finalize

import com.example.bot.booking.BookingCmdResult
import com.example.bot.booking.BookingService
import com.example.bot.payments.PaymentsRepository
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

private val logger = LoggerFactory.getLogger("PaymentsFinalizeService")

class DefaultPaymentsFinalizeService(
    private val bookingService: BookingService,
    private val paymentsRepository: PaymentsRepository,
) : PaymentsFinalizeService {

    override suspend fun finalize(
        clubId: Long,
        bookingId: UUID,
        paymentToken: String?,
        idemKey: String,
        actorUserId: Long,
    ): PaymentsFinalizeService.FinalizeResult {
        val sanitizedToken = paymentToken?.trim()?.takeIf { it.isNotEmpty() }
        if (sanitizedToken != null && sanitizedToken.length !in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH) {
            throw PaymentsFinalizeService.ValidationException("invalid payment token")
        }

        val tokenDigest = sanitizedToken?.let(::hashToken)
        val existing = paymentsRepository.findByIdempotencyKey(idemKey)
        if (existing != null) {
            validateExistingRecord(existing.bookingId, bookingId, existing.externalId, tokenDigest)
            if (logger.isDebugEnabled) {
                logger.debug(
                    "payments.finalize.idempotent clubId={} bookingId={} idemKey={}",
                    clubId,
                    bookingId,
                    maskIdemKey(idemKey),
                )
            }
            return PaymentsFinalizeService.FinalizeResult(existing.status)
        }

        val bookingResult = bookingService.finalize(bookingId, actorUserId)
        val paymentStatus = when (bookingResult) {
            is BookingCmdResult.Booked -> statusFor(tokenDigest)
            is BookingCmdResult.AlreadyBooked -> statusFor(tokenDigest)
            BookingCmdResult.NotFound -> throw PaymentsFinalizeService.ValidationException("booking not found")
            BookingCmdResult.IdempotencyConflict -> throw PaymentsFinalizeService.ConflictException("booking idempotency conflict")
            BookingCmdResult.DuplicateActiveBooking -> throw PaymentsFinalizeService.ConflictException("booking duplicate active")
            BookingCmdResult.HoldExpired -> throw PaymentsFinalizeService.ConflictException("booking hold expired")
            else -> throw PaymentsFinalizeService.ConflictException("booking finalize failed")
        }

        val stored = tryPersistResult(bookingId, idemKey, tokenDigest, paymentStatus)
        logger.info(
            "payments.finalize.stored clubId={} bookingId={} status={} idemKey={}",
            clubId,
            bookingId,
            stored.status,
            maskIdemKey(idemKey),
        )
        return PaymentsFinalizeService.FinalizeResult(stored.status)
    }

    private suspend fun tryPersistResult(
        bookingId: UUID,
        idemKey: String,
        tokenDigest: String?,
        paymentStatus: String,
    ) = try {
        val created = paymentsRepository.createInitiated(
            bookingId = bookingId,
            provider = PROVIDER_MINIAPP,
            currency = DEFAULT_CURRENCY,
            amountMinor = 0,
            payload = "miniapp-finalize:${UUID.randomUUID()}",
            idempotencyKey = idemKey,
        )
        paymentsRepository.updateStatus(created.id, paymentStatus, tokenDigest)
        paymentsRepository.findByIdempotencyKey(idemKey) ?: created.copy(status = paymentStatus, externalId = tokenDigest)
    } catch (error: Throwable) {
        val existing = paymentsRepository.findByIdempotencyKey(idemKey)
        if (existing != null) {
            validateExistingRecord(existing.bookingId, bookingId, existing.externalId, tokenDigest)
            existing
        } else {
            logger.error("payments.finalize.persist_failed bookingId={}", bookingId, error)
            throw error
        }
    }

    private fun validateExistingRecord(
        storedBookingId: UUID?,
        expectedBookingId: UUID,
        storedToken: String?,
        requestedToken: String?,
    ) {
        if (storedBookingId != expectedBookingId) {
            throw PaymentsFinalizeService.ConflictException("idempotency key mismatch")
        }
        val matches = when {
            storedToken == null && requestedToken == null -> true
            storedToken != null && requestedToken != null -> storedToken == requestedToken
            else -> false
        }
        if (!matches) {
            throw PaymentsFinalizeService.ConflictException("idempotency token mismatch")
        }
    }

    private fun statusFor(tokenDigest: String?): String = tokenDigest?.let { STATUS_CAPTURED } ?: STATUS_NO_PAYMENT

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashed = digest.digest(token.toByteArray(StandardCharsets.UTF_8))
        return hashed.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun maskIdemKey(value: String): String {
        if (value.length <= 8) {
            return value
        }
        val prefix = value.take(4)
        val suffix = value.takeLast(4)
        return "$prefix…$suffix"
    }

    companion object {
        private const val MIN_TOKEN_LENGTH = 8
        private const val MAX_TOKEN_LENGTH = 256
        private const val STATUS_CAPTURED = "CAPTURED"
        private const val STATUS_NO_PAYMENT = "NO_PAYMENT"
        private const val PROVIDER_MINIAPP = "MINIAPP"
        private const val DEFAULT_CURRENCY = "N/A"
    }
}
