package com.example.bot.telegram

import com.example.bot.workers.SendOutcome
import com.example.bot.workers.SendPort
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory

/**
 * Adapter that bridges the generic [SendPort] contract with the [NotifySender].
 *
 * The outbox worker owns retry/backoff policies, therefore the adapter always
 * reports [SendOutcome.Ok] regardless of the downstream result while capturing
 * diagnostics via optional [NotifyAdapterMetrics].
 */
class NotifySenderSendPort(
    private val sender: NotifySender,
    private val metrics: NotifyAdapterMetrics? = null,
) : SendPort {

    override suspend fun send(topic: String, payload: JsonObject): SendOutcome {
        metrics?.incAttempts()

        val chatId = payload["chatId"]?.jsonPrimitive?.longOrNull
        if (chatId == null) {
            metrics?.incPermanent()
            logger.debug("Skipping notify send: missing chatId for topic {}", topic)
            return SendOutcome.Ok
        }

        val text = payload["text"]?.jsonPrimitive?.contentOrNull
        val dedupKey = payload["dedup"]?.jsonPrimitive?.contentOrNull

        if (text == null) {
            metrics?.incPermanent()
            logger.debug("Skipping notify send: unsupported payload without text for topic {}", topic)
            return SendOutcome.Ok
        }

        val result =
            try {
                sender.sendMessage(chatId, text, dedupKey = dedupKey)
            } catch (t: Throwable) {
                metrics?.incRetryable()
                logger.debug("Notify sender threw for topic {}", topic, t)
                return SendOutcome.Ok
            }

        when (result) {
            is SendResult.Ok -> metrics?.incOk()
            is SendResult.RetryAfter -> metrics?.incRetryAfter(result.retryAfterMs)
            is SendResult.RetryableError -> metrics?.incRetryable()
            is SendResult.PermanentError -> metrics?.incPermanent()
        }

        return SendOutcome.Ok
    }

    companion object {
        private val logger = LoggerFactory.getLogger(NotifySenderSendPort::class.java)
    }
}

/**
 * Lightweight metrics facade decoupled from Micrometer, to keep wiring flexible
 * in tests and environments without a registry.
 */
class NotifyAdapterMetrics(
    private val onAttempt: (() -> Unit)? = null,
    private val onOk: (() -> Unit)? = null,
    private val onRetryAfter: ((Long) -> Unit)? = null,
    private val onRetryable: (() -> Unit)? = null,
    private val onPermanent: (() -> Unit)? = null,
) {
    fun incAttempts() {
        onAttempt?.invoke()
    }

    fun incOk() {
        onOk?.invoke()
    }

    fun incRetryAfter(delayMs: Long) {
        onRetryAfter?.invoke(delayMs)
    }

    fun incRetryable() {
        onRetryable?.invoke()
    }

    fun incPermanent() {
        onPermanent?.invoke()
    }
}
