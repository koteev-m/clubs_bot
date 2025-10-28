@file:Suppress("SpreadOperator")

package com.example.bot.telegram

import com.example.bot.config.BotLimits
import com.example.bot.notifications.RatePolicy
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.request.InputMediaPhoto
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SendMediaGroup
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.request.SendPhoto
import com.pengrad.telegrambot.response.BaseResponse
import io.ktor.http.HttpStatusCode
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val RETRY_AFTER_POSITIVE_THRESHOLD = 0
private const val JITTER_INCLUSIVE_OFFSET = 1L

sealed interface SendResult {
    data class Ok(val messageId: Long?, val already: Boolean = false) : SendResult

    data class RetryAfter(val retryAfterMs: Long) : SendResult

    data class RetryableError(val message: String) : SendResult

    data class PermanentError(val message: String) : SendResult
}

data class MediaSpec(val fileIdOrUrl: String, val caption: String? = null)

object NotifyMetrics {
    val ok: AtomicLong = AtomicLong()
    val retryAfter: AtomicLong = AtomicLong()
    val retryable: AtomicLong = AtomicLong()
    val permanent: AtomicLong = AtomicLong()
}

class NotifySender(
    private val bot: TelegramBot,
    private val ratePolicy: RatePolicy,
    private val idempotency: NotifyIdempotencyStore = InMemoryNotifyIdempotencyStore(),
    private val registry: MeterRegistry? = null,
    private val baseBackoffMs: Long = BotLimits.notifySendBaseBackoff.toMillis(),
    private val maxBackoffMs: Long = BotLimits.notifySendMaxBackoff.toMillis(),
    private val jitterMs: Long = BotLimits.notifySendJitter.toMillis(),
) {
    private val timer: Timer? =
        registry?.let {
            Timer
                .builder("tg.send.duration.ms")
                .publishPercentiles(*BotLimits.notifyDurationPercentiles)
                .register(it)
        }

    private val baseBackoff: Duration = Duration.ofMillis(baseBackoffMs)
    private val maxBackoff: Duration = Duration.ofMillis(maxBackoffMs)
    private val jitter: Duration = Duration.ofMillis(jitterMs)
    private val maxAttempts: Int = BotLimits.notifySendMaxAttempts

    private sealed interface SendOutcome {
        data object Success : SendOutcome

        data class Retry(val delayMs: Long) : SendOutcome

        data class Fail(val result: SendResult) : SendOutcome
    }

    suspend fun sendMessage(
        chatId: Long,
        text: String,
        threadId: Int? = null,
        dedupKey: String? = null,
    ): SendResult {
        val req = SendMessage(chatId, text)
        threadId?.let { req.messageThreadId(it) }
        return execute(req, chatId, dedupKey)
    }

    suspend fun sendPhoto(
        chatId: Long,
        photoUrlOrFileId: String,
        caption: String? = null,
        threadId: Int? = null,
        dedupKey: String? = null,
    ): SendResult {
        val req = SendPhoto(chatId, photoUrlOrFileId)
        caption?.let { req.caption(it) }
        threadId?.let { req.messageThreadId(it) }
        return execute(req, chatId, dedupKey)
    }

    suspend fun sendMediaGroup(
        chatId: Long,
        media: List<MediaSpec>,
        threadId: Int? = null,
        dedupKey: String? = null,
    ): SendResult {
        val arr =
            media
                .map { m ->
                    val im = InputMediaPhoto(m.fileIdOrUrl)
                    m.caption?.let { im.caption(it) }
                    im
                }.toTypedArray()
        val req = SendMediaGroup(chatId, *arr)
        if (threadId != null) {
            try {
                SendMediaGroup::class.java
                    .getMethod(
                        "messageThreadId",
                        Int::class.javaPrimitiveType,
                    ).invoke(req, threadId)
            } catch (_: Throwable) {
                // ignore
            }
        }
        val initialResult = execute(req, chatId, dedupKey)
        val finalResult =
            when (initialResult) {
                is SendResult.Ok, is SendResult.RetryAfter -> initialResult
                is SendResult.RetryableError, is SendResult.PermanentError -> {
                    val fallbackFailure =
                        media
                            .asFlow()
                            .map { spec ->
                                sendPhoto(chatId, spec.fileIdOrUrl, spec.caption, threadId, null)
                            }
                            .firstOrNull { it !is SendResult.Ok }
                    fallbackFailure ?: SendResult.Ok(messageId = null)
                }
            }
        return finalResult
    }

    suspend fun <R : BaseResponse> execute(
        request: BaseRequest<*, R>,
        chatId: Long,
        dedupKey: String? = null,
    ): SendResult {
        val deduplicatedResult =
            if (dedupKey != null && idempotency.seen(dedupKey)) {
                incOk(already = true)
                SendResult.Ok(messageId = null, already = true)
            } else {
                null
            }

        var result: SendResult? = deduplicatedResult
        var attempt = 0
        while (result == null) {
            when (val outcome = attemptSend(request, chatId, dedupKey, attempt)) {
                SendOutcome.Success -> result = SendResult.Ok(messageId = null)
                is SendOutcome.Retry -> {
                    delay(outcome.delayMs)
                    attempt++
                }
                is SendOutcome.Fail -> result = outcome.result
            }
        }
        return result
    }

    private suspend fun <R : BaseResponse> attemptSend(
        request: BaseRequest<*, R>,
        chatId: Long,
        dedupKey: String?,
        attempt: Int,
    ): SendOutcome {
        fun performRequest(): SendOutcome {
            val startAttempt = System.nanoTime()
            val response: R =
                try {
                    bot.execute(request)
                } catch (t: Throwable) {
                    timer?.record(System.nanoTime() - startAttempt, TimeUnit.NANOSECONDS)
                    return if (attempt >= maxAttempts) {
                        incRetryable()
                        SendOutcome.Fail(SendResult.RetryableError("IO error: ${t.message}"))
                    } else {
                        SendOutcome.Retry(backoffDelay(attempt))
                    }
                }
            timer?.record(System.nanoTime() - startAttempt, TimeUnit.NANOSECONDS)

            return if (response.isOk) {
                dedupKey?.let(idempotency::mark)
                incOk(already = false)
                SendOutcome.Success
            } else {
                val code = response.errorCode()
                val desc = response.description() ?: "unknown"
                val retryAfterSec = response.parameters()?.retryAfter()
                val hasRetryAfter =
                    retryAfterSec != null && retryAfterSec > RETRY_AFTER_POSITIVE_THRESHOLD
                when {
                    code == HttpStatusCode.TooManyRequests.value || hasRetryAfter -> {
                        val retryAfterDuration =
                            when {
                                hasRetryAfter -> Duration.ofSeconds(retryAfterSec!!.toLong())
                                else -> BotLimits.notifyRetryAfterFallback
                            }
                        val retryMs = retryAfterDuration.toMillis()
                        ratePolicy.on429(chatId, retryMs)
                        incRetryAfter(retryMs)
                        SendOutcome.Fail(SendResult.RetryAfter(retryMs))
                    }
                    isServerError(code) -> {
                        if (attempt >= maxAttempts) {
                            incRetryable()
                            SendOutcome.Fail(SendResult.RetryableError("code=$code desc=$desc"))
                        } else {
                            SendOutcome.Retry(backoffDelay(attempt))
                        }
                    }
                    code == HttpStatusCode.BadRequest.value || code == HttpStatusCode.Forbidden.value -> {
                        incPermanent()
                        SendOutcome.Fail(SendResult.PermanentError("code=$code desc=$desc"))
                    }
                    else -> {
                        incPermanent()
                        SendOutcome.Fail(SendResult.PermanentError("code=$code desc=$desc"))
                    }
                }
            }
        }

        val now = System.currentTimeMillis()
        val globalPermit = ratePolicy.acquireGlobal(now = now)
        val rateLimitOutcome: SendOutcome? =
            if (!globalPermit.granted) {
                incRetryAfter(globalPermit.retryAfterMs)
                SendOutcome.Fail(SendResult.RetryAfter(globalPermit.retryAfterMs))
            } else {
                val chatPermit = ratePolicy.acquireChat(chatId, now = now)
                if (!chatPermit.granted) {
                    incRetryAfter(chatPermit.retryAfterMs)
                    SendOutcome.Fail(SendResult.RetryAfter(chatPermit.retryAfterMs))
                } else {
                    null
                }
            }
        return rateLimitOutcome ?: performRequest()
    }

    private fun backoffDelay(attempt: Int): Long {
        val shift = attempt.coerceAtMost(BotLimits.notifyBackoffMaxShift)
        val multiplier = 1L shl shift
        val exponential = baseBackoff.multipliedBy(multiplier)
        val capped = if (exponential > maxBackoff) maxBackoff else exponential
        val jitterBoundExclusive = jitter.toMillis() + JITTER_INCLUSIVE_OFFSET
        val jitterMillis = ThreadLocalRandom.current().nextLong(jitterBoundExclusive)
        return capped.toMillis() + jitterMillis
    }

    private fun incOk(already: Boolean) {
        registry?.counter("tg.send.ok", "already", already.toString())?.increment()
            ?: NotifyMetrics.ok.incrementAndGet()
    }

    private fun incRetryAfter(retryAfterMs: Long) {
        val duration = Duration.ofMillis(retryAfterMs)
        val secondsRoundedUp = duration.seconds + if (duration.nano > 0) 1 else 0
        val sec = secondsRoundedUp.toString()
        registry?.counter("tg.send.retry_after", "retry_after_seconds", sec)?.increment()
            ?: NotifyMetrics.retryAfter.incrementAndGet()
    }

    private fun incRetryable() {
        registry?.counter("tg.send.retryable")?.increment()
            ?: NotifyMetrics.retryable.incrementAndGet()
    }

    private fun incPermanent() {
        registry?.counter("tg.send.permanent")?.increment()
            ?: NotifyMetrics.permanent.incrementAndGet()
    }
}

@Suppress("MagicNumber") // Верхняя граница диапазона HTTP 5xx.
private val SERVER_ERROR_RANGE: IntRange = HttpStatusCode.InternalServerError.value..599

private fun isServerError(code: Int): Boolean = code in SERVER_ERROR_RANGE
