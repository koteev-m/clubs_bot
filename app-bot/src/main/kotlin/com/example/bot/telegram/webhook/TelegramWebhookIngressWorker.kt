package com.example.bot.telegram.webhook

import com.example.bot.data.security.webhook.TelegramWebhookIngressRepository
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.utility.BotUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

class TelegramWebhookIngressWorker(
    private val repository: TelegramWebhookIngressRepository,
    private val onUpdate: suspend (Update) -> Unit,
    private val metrics: TelegramWebhookIngressMetrics,
    private val batchSize: Int = 16,
    private val idleDelay: Duration = Duration.ofMillis(300),
    private val failureDelay: Duration = Duration.ofSeconds(1),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(TelegramWebhookIngressWorker::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var workerJob: Job? = null

    fun start() {
        if (workerJob?.isActive == true) {
            return
        }
        workerJob =
            scope.launch {
                logger.info("telegram webhook ingress worker started")
                while (isActive) {
                    val claimed =
                        try {
                            repository.claimBatch(batchSize)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            logger.warn("webhook worker: claim failed", t)
                            metrics.recordWorkerFailure()
                            delay(failureDelay.toMillis())
                            continue
                        }

                    if (claimed.isEmpty()) {
                        metrics.refreshQueueStats(repository, clock)
                        delay(idleDelay.toMillis())
                        continue
                    }

                    claimed.forEach { queued ->
                        val processingStartedAt = Instant.now(clock)
                        try {
                            onUpdate(BotUtils.parseUpdate(queued.payloadJson))
                            repository.markDone(queued.id)
                            metrics.recordProcessed(
                                receivedAt = queued.receivedAt,
                                processingStartedAt = processingStartedAt,
                                clock = clock,
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            logger.warn("webhook worker: update_id={} failed", queued.updateId, t)
                            repository.markFailed(
                                id = queued.id,
                                attempts = queued.attempts + 1,
                                error = t.message ?: t.javaClass.simpleName,
                            )
                            metrics.recordProcessingFailure()
                        }
                    }
                    metrics.refreshQueueStats(repository, clock)
                }
            }
    }

    suspend fun shutdown() {
        workerJob?.cancel()
        workerJob?.join()
    }
}
