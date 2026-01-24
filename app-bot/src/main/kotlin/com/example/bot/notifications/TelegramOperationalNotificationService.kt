package com.example.bot.notifications

import com.example.bot.opschat.ClubOpsChatConfig
import com.example.bot.opschat.ClubOpsChatConfigRepository
import com.example.bot.opschat.OpsDomainNotification
import com.example.bot.opschat.OpsNotificationCategory
import com.example.bot.telegram.TelegramClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Duration

data class OpsNotificationServiceConfig(
    val queueCapacity: Int = 500,
    val sendTimeout: Duration = Duration.ofSeconds(3),
    val maxAttempts: Int = 3,
    val retryDelay: Duration = Duration.ofMillis(200),
) {
    init {
        require(queueCapacity > 0) { "queueCapacity must be positive" }
        require(!sendTimeout.isZero && !sendTimeout.isNegative) { "sendTimeout must be positive" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(!retryDelay.isNegative) { "retryDelay must not be negative" }
    }
}

/**
 * Best-effort delivery of operational notifications to Telegram.
 *
 * Отправка выполняется в отдельной корутине (SupervisorJob + Dispatchers.IO).
 */
class TelegramOperationalNotificationService(
    private val telegramClient: TelegramClient,
    private val configRepository: ClubOpsChatConfigRepository,
    private val renderer: OpsNotificationRenderer = OpsNotificationRenderer,
    private val config: OpsNotificationServiceConfig = OpsNotificationServiceConfig(),
) {
    private val logger = LoggerFactory.getLogger(TelegramOperationalNotificationService::class.java)
    private val channel = Channel<OpsDomainNotification>(config.queueCapacity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) {
            logger.debug("Operational notification worker already running")
            return
        }
        job =
            scope.launch(CoroutineName("ops-notification-worker")) {
                while (isActive) {
                    val event = channel.receive()
                    dispatch(event)
                }
            }
    }

    suspend fun stop() {
        val current = job ?: return
        current.cancelAndJoin()
        job = null
    }

    fun enqueue(event: OpsDomainNotification) {
        val result = channel.trySend(event)
        if (result.isFailure) {
            logger.warn(
                "ops notification dropped event={} club_id={} subject_id={}",
                event.event,
                event.clubId,
                event.subjectId,
            )
        }
    }

    private suspend fun dispatch(event: OpsDomainNotification) {
        val config = configRepository.getByClubId(event.clubId)
        if (config == null) {
            logger.warn(
                "ops notification config missing event={} club_id={} subject_id={}",
                event.event,
                event.clubId,
                event.subjectId,
            )
            return
        }
        val threadId = resolveThreadId(event.event.category, config)
        val text = renderer.render(event)
        sendWithRetry(event, config.chatId, threadId, text)
    }

    private fun resolveThreadId(
        category: OpsNotificationCategory,
        config: ClubOpsChatConfig,
    ): Int? =
        when (category) {
            OpsNotificationCategory.BOOKINGS -> config.bookingsThreadId
            OpsNotificationCategory.CHECKIN -> config.checkinThreadId
            OpsNotificationCategory.SUPPORT -> config.supportThreadId
        }

    private suspend fun sendWithRetry(
        event: OpsDomainNotification,
        chatId: Long,
        threadId: Int?,
        text: String,
    ) {
        var attempt = 0
        while (attempt < config.maxAttempts) {
            attempt += 1
            try {
                val response =
                    withTimeout(config.sendTimeout.toMillis()) {
                        telegramClient.sendMessage(chatId, text, threadId)
                    }
                if (response.isOk) {
                    return
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // swallow and retry
            }
            if (attempt < config.maxAttempts && !config.retryDelay.isZero) {
                delay(config.retryDelay.toMillis())
            }
        }
        logger.warn(
            "ops notification send failed event={} club_id={} subject_id={} attempts={}",
            event.event,
            event.clubId,
            event.subjectId,
            config.maxAttempts,
        )
    }
}
