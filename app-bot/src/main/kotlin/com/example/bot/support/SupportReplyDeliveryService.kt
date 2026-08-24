package com.example.bot.support

import com.example.bot.clubs.ClubsRepository
import com.example.bot.data.support.ClaimedSupportReplyDelivery
import com.example.bot.data.support.SupportReplyDeliveryClaimResult
import com.example.bot.data.support.SupportReplyDeliveryFinalizationResult
import com.example.bot.data.support.SupportRepository
import com.example.bot.data.support.claimReplyDelivery
import com.example.bot.data.support.finalizeReplyDelivery
import com.example.bot.telegram.SupportCallbacks
import com.example.bot.telegram.TelegramClient
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration

private val DEFAULT_SEND_TIMEOUT: Duration = Duration.ofSeconds(5)
private val DEFAULT_CANCELLATION_CLEANUP_TIMEOUT: Duration = Duration.ofSeconds(1)

interface SupportReplyTelegramGateway {
    val isConfigured: Boolean

    suspend fun send(request: BaseRequest<*, *>): BaseResponse
}

class TelegramSupportReplyGateway(
    private val telegramClient: TelegramClient,
    override val isConfigured: Boolean,
) : SupportReplyTelegramGateway {
    override suspend fun send(request: BaseRequest<*, *>): BaseResponse = telegramClient.send(request)
}

class SupportReplyDeliveryServiceImpl(
    private val repository: SupportRepository,
    private val clubsRepository: ClubsRepository,
    private val telegramGateway: SupportReplyTelegramGateway,
    private val sendTimeout: Duration = DEFAULT_SEND_TIMEOUT,
    private val cancellationCleanupTimeout: Duration = DEFAULT_CANCELLATION_CLEANUP_TIMEOUT,
) : SupportReplyDeliveryService {
    init {
        require(sendTimeout.toMillis() > 0L)
        require(cancellationCleanupTimeout.toMillis() > 0L)
    }

    override suspend fun deliver(deliveryId: Long): SupportReplyDeliveryOutcome {
        var claimed = false
        return try {
            when (val claim = claim(deliveryId)) {
                is SupportReplyDeliveryClaimResult.Claimed -> {
                    claimed = true
                    deliverClaimed(claim.delivery)
                }
                SupportReplyDeliveryClaimResult.NotClaimed -> SupportReplyDeliveryOutcome.PersistenceFailure
            }
        } catch (cancellation: CancellationException) {
            if (claimed) {
                bestEffortCancellationFinalization(deliveryId)
            }
            throw cancellation
        } catch (_: Exception) {
            return SupportReplyDeliveryOutcome.PersistenceFailure
        }
    }

    private suspend fun deliverClaimed(delivery: ClaimedSupportReplyDelivery): SupportReplyDeliveryOutcome {
        val recipientTelegramUserId = delivery.recipientTelegramUserId?.takeIf { it > 0L }
        return when {
            recipientTelegramUserId == null ->
                finalizeResult(
                    deliveryId = delivery.id,
                    status = SupportReplyDeliveryStatus.FAILED,
                    failureCode = SupportReplyDeliveryFailureCode.RECIPIENT_UNAVAILABLE,
                    outcome = SupportReplyDeliveryOutcome.Failed,
                )
            !telegramGateway.isConfigured ->
                finalizeResult(
                    deliveryId = delivery.id,
                    status = SupportReplyDeliveryStatus.FAILED,
                    failureCode = SupportReplyDeliveryFailureCode.CLIENT_UNAVAILABLE,
                    outcome = SupportReplyDeliveryOutcome.Failed,
                )
            else -> deliverTelegramAttempt(delivery, recipientTelegramUserId)
        }
    }

    private suspend fun deliverTelegramAttempt(
        delivery: ClaimedSupportReplyDelivery,
        recipientTelegramUserId: Long,
    ): SupportReplyDeliveryOutcome {
        val request = buildTelegramRequest(delivery, recipientTelegramUserId)
        return when (val attempt = attemptTelegramSend(request)) {
            is TelegramAttemptResult.Response ->
                if (attempt.isOk) {
                    finalizeResult(
                        deliveryId = delivery.id,
                        status = SupportReplyDeliveryStatus.DELIVERED,
                        failureCode = null,
                        outcome = SupportReplyDeliveryOutcome.Delivered,
                    )
                } else {
                    finalizeResult(
                        deliveryId = delivery.id,
                        status = SupportReplyDeliveryStatus.FAILED,
                        failureCode = SupportReplyDeliveryFailureCode.TELEGRAM_REJECTED,
                        outcome = SupportReplyDeliveryOutcome.Failed,
                    )
                }
            TelegramAttemptResult.TimedOut ->
                finalizeResult(
                    deliveryId = delivery.id,
                    status = SupportReplyDeliveryStatus.UNCONFIRMED,
                    failureCode = SupportReplyDeliveryFailureCode.TIMEOUT,
                    outcome = SupportReplyDeliveryOutcome.Unconfirmed,
                )
            TelegramAttemptResult.TransportError ->
                finalizeResult(
                    deliveryId = delivery.id,
                    status = SupportReplyDeliveryStatus.UNCONFIRMED,
                    failureCode = SupportReplyDeliveryFailureCode.TRANSPORT_ERROR,
                    outcome = SupportReplyDeliveryOutcome.Unconfirmed,
                )
        }
    }

    private suspend fun attemptTelegramSend(request: SendMessage): TelegramAttemptResult {
        var attemptCancellation: CancellationException? = null
        return try {
            val response =
                withTimeoutOrNull(sendTimeout.toMillis()) {
                    sendCapturingCancellation(request) { cancellation ->
                        attemptCancellation = cancellation
                    }
                }
            response?.let { TelegramAttemptResult.Response(it.isOk) }
                ?: TelegramAttemptResult.TimedOut
        } catch (cancellation: CancellationException) {
            throw attemptCancellation ?: cancellation
        } catch (_: Exception) {
            TelegramAttemptResult.TransportError
        }
    }

    private suspend fun sendCapturingCancellation(
        request: SendMessage,
        capture: (CancellationException) -> Unit,
    ): BaseResponse =
        try {
            telegramGateway.send(request)
        } catch (timeout: TimeoutCancellationException) {
            throw timeout
        } catch (cancellation: CancellationException) {
            capture(cancellation)
            throw cancellation
        }

    private suspend fun claim(deliveryId: Long): SupportReplyDeliveryClaimResult =
        repository.claimReplyDelivery(deliveryId)

    private suspend fun buildTelegramRequest(
        delivery: ClaimedSupportReplyDelivery,
        recipientTelegramUserId: Long,
    ): SendMessage {
        val clubName =
            try {
                sanitizeClubName(clubsRepository.getById(delivery.clubId)?.name)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        val request =
            SendMessage(
                recipientTelegramUserId,
                buildSupportReplyMessage(clubName, delivery.persistedReplyText),
            )
        buildSupportRatingKeyboard(delivery.ticketId)?.let(request::replyMarkup)
        return request
    }

    private suspend fun finalizeResult(
        deliveryId: Long,
        status: SupportReplyDeliveryStatus,
        failureCode: SupportReplyDeliveryFailureCode?,
        outcome: SupportReplyDeliveryOutcome,
    ): SupportReplyDeliveryOutcome =
        when (
            repository.finalizeReplyDelivery(
                deliveryId = deliveryId,
                resultStatus = status,
                failureCode = failureCode,
            )
        ) {
            SupportReplyDeliveryFinalizationResult.Finalized -> outcome
            SupportReplyDeliveryFinalizationResult.NotFinalized ->
                SupportReplyDeliveryOutcome.PersistenceFailure
        }

    private suspend fun bestEffortCancellationFinalization(deliveryId: Long) {
        try {
            withContext(NonCancellable) {
                withTimeout(cancellationCleanupTimeout.toMillis()) {
                    repository.finalizeReplyDelivery(
                        deliveryId = deliveryId,
                        resultStatus = SupportReplyDeliveryStatus.UNCONFIRMED,
                        failureCode = SupportReplyDeliveryFailureCode.CANCELED,
                    )
                }
            }
        } catch (_: TimeoutCancellationException) {
            // The original delivery cancellation is rethrown by the caller.
        } catch (_: Exception) {
            // Best effort only; SENDING remains an observable non-success state.
        }
    }
}

private sealed interface TelegramAttemptResult {
    data class Response(
        val isOk: Boolean,
    ) : TelegramAttemptResult

    data object TimedOut : TelegramAttemptResult

    data object TransportError : TelegramAttemptResult
}

private fun buildSupportRatingKeyboard(ticketId: Long): InlineKeyboardMarkup? {
    val up = SupportCallbacks.buildRate(ticketId, up = true)
    val down = SupportCallbacks.buildRate(ticketId, up = false)
    if (!SupportCallbacks.fits(up) || !SupportCallbacks.fits(down)) {
        return null
    }
    return InlineKeyboardMarkup(
        arrayOf(
            InlineKeyboardButton("👍").callbackData(up),
            InlineKeyboardButton("👎").callbackData(down),
        ),
    )
}
