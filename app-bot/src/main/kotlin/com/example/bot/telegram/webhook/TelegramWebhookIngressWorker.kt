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
                        try {
                            onUpdate(BotUtils.parseUpdate(queued.payloadJson))
                            try {
                                val done =
                                    repository.markDone(
                                        id = queued.id,
                                        claimAttempt = queued.claimAttempt,
                                    )
                                if (done) {
                                    metrics.recordProcessed(receivedAt = queued.receivedAt, clock = clock)
                                } else {
                                    logger.debug(
                                        "webhook worker: stale completion ignored update_id={} claim_attempt={}",
                                        queued.updateId,
                                        queued.claimAttempt,
                                    )
                                }
                            } catch (markDoneError: Throwable) {
                                if (markDoneError is CancellationException) {
                                    throw markDoneError
                                }
                                logger.warn("webhook worker: markDone failed update_id={}", queued.updateId, markDoneError)
                                metrics.recordWorkerFailure()
                                delay(failureDelay.toMillis())
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            logger.warn("webhook worker: update_id={} failed", queued.updateId, t)
                            metrics.recordProcessingFailure()
                            try {
                                val failed =
                                    repository.markFailed(
                                        id = queued.id,
                                        claimAttempt = queued.claimAttempt,
                                        error = t.message ?: t.javaClass.simpleName,
                                    )
                                if (!failed) {
                                    logger.debug(
                                        "webhook worker: stale failure mark ignored update_id={} claim_attempt={}",
                                        queued.updateId,
                                        queued.claimAttempt,
                                    )
                                }
                            } catch (markError: Throwable) {
                                if (markError is CancellationException) {
                                    throw markError
                                }
                                logger.warn("webhook worker: markFailed failed update_id={}", queued.updateId, markError)
                                metrics.recordWorkerFailure()
                                delay(failureDelay.toMillis())
                            }
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
