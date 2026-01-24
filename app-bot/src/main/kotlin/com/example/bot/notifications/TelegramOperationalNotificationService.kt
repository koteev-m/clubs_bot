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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Duration

data class OpsNotificationServiceConfig(
    val enabled: Boolean = true,
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

    companion object {
        private val configLogger = LoggerFactory.getLogger("OpsNotificationConfig")
        private const val RAW_PREVIEW_LIMIT = 32
        private const val QUEUE_CAPACITY_MIN = 1
        private const val QUEUE_CAPACITY_MAX = 5000
        private const val MAX_ATTEMPTS_MIN = 1
        private const val MAX_ATTEMPTS_MAX = 10
        private const val SEND_TIMEOUT_MIN_MS = 1L
        private const val SEND_TIMEOUT_MAX_MS = 30000L
        private const val RETRY_DELAY_MIN_MS = 0L
        private const val RETRY_DELAY_MAX_MS = 60000L

        fun fromEnv(env: (String) -> String? = System::getenv): OpsNotificationServiceConfig {
            val defaults = OpsNotificationServiceConfig()
            val enabled = readBoolean(env, "OPS_NOTIFY_ENABLED", defaults.enabled)
            val queueCapacity =
                readInt(
                    env,
                    "OPS_NOTIFY_QUEUE_CAPACITY",
                    defaults.queueCapacity,
                    min = QUEUE_CAPACITY_MIN,
                    max = QUEUE_CAPACITY_MAX,
                )
            val sendTimeoutMs =
                readLong(
                    env,
                    "OPS_NOTIFY_SEND_TIMEOUT_MS",
                    defaults.sendTimeout.toMillis(),
                    min = SEND_TIMEOUT_MIN_MS,
                    max = SEND_TIMEOUT_MAX_MS,
                )
            val maxAttempts =
                readInt(
                    env,
                    "OPS_NOTIFY_MAX_ATTEMPTS",
                    defaults.maxAttempts,
                    min = MAX_ATTEMPTS_MIN,
                    max = MAX_ATTEMPTS_MAX,
                )
            val retryDelayMs =
                readLong(
                    env,
                    "OPS_NOTIFY_RETRY_DELAY_MS",
                    defaults.retryDelay.toMillis(),
                    min = RETRY_DELAY_MIN_MS,
                    max = RETRY_DELAY_MAX_MS,
                )

            return OpsNotificationServiceConfig(
                enabled = enabled,
                queueCapacity = queueCapacity,
                sendTimeout = Duration.ofMillis(sendTimeoutMs),
                maxAttempts = maxAttempts,
                retryDelay = Duration.ofMillis(retryDelayMs),
            )
        }

        private fun readInt(
            env: (String) -> String?,
            name: String,
            default: Int,
            min: Int,
            max: Int,
        ): Int {
            val raw = env(name) ?: return default
            val value = raw.toIntOrNull() ?: return logInvalid(name, raw, default, "non-numeric")
            if (value < min || value > max) {
                return logInvalid(name, raw, default, "out-of-range[$min..$max]")
            }
            return value
        }

        private fun readLong(
            env: (String) -> String?,
            name: String,
            default: Long,
            min: Long,
            max: Long,
        ): Long {
            val raw = env(name) ?: return default
            val value = raw.toLongOrNull() ?: return logInvalid(name, raw, default, "non-numeric")
            if (value < min || value > max) {
                return logInvalid(name, raw, default, "out-of-range[$min..$max]")
            }
            return value
        }

        private fun readBoolean(
            env: (String) -> String?,
            name: String,
            default: Boolean,
        ): Boolean {
            val raw = env(name) ?: return default
            val value = raw.toBooleanStrictOrNull()
            return value ?: logInvalid(name, raw, default, "non-boolean")
        }

        private fun <T> logInvalid(name: String, raw: String, default: T, reason: String): T {
            val preview = rawPreview(raw)
            configLogger.warn(
                "$name is invalid ($reason, raw=$preview, length=${raw.length}), using default=$default",
            )
            return default
        }

        private fun rawPreview(raw: String): String {
            val sanitized = sanitizePreview(raw)
            return if (sanitized.length <= RAW_PREVIEW_LIMIT) {
                sanitized
            } else {
                "${sanitized.take(RAW_PREVIEW_LIMIT)}…"
            }
        }

        private fun sanitizePreview(raw: String): String =
            buildString(raw.length) {
                raw.forEach { ch ->
                    append(if (ch.isISOControl()) ' ' else ch)
                }
            }
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
                    try {
                        dispatch(event)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (exception: Exception) {
                        logger.warn(
                            "ops notification processing failed event={} club_id={} subject_id_hash={}",
                            event.event,
                            event.clubId,
                            subjectFingerprint(event.subjectId),
                            exception,
                        )
                    }
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
                "ops notification dropped event={} club_id={} subject_id_hash={}",
                event.event,
                event.clubId,
                subjectFingerprint(event.subjectId),
            )
        }
    }

    private suspend fun dispatch(event: OpsDomainNotification) {
        val config = configRepository.getByClubId(event.clubId)
        if (config == null) {
            logger.warn(
                "ops notification config missing event={} club_id={} subject_id_hash={}",
                event.event,
                event.clubId,
                subjectFingerprint(event.subjectId),
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
            } catch (_: TimeoutCancellationException) {
                // treat timeout as a failed attempt and retry
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // swallow and retry
            }
            if (attempt < config.maxAttempts && !config.retryDelay.isZero) {
                delay(config.retryDelay.toMillis())
            }
        }
        logger.warn(
            "ops notification send failed event={} club_id={} subject_id_hash={} attempts={}",
            event.event,
            event.clubId,
            subjectFingerprint(event.subjectId),
            config.maxAttempts,
        )
    }

    private fun subjectFingerprint(subjectId: String?): String =
        subjectId?.let { hashSubjectId(it) } ?: "absent"

    private fun hashSubjectId(subjectId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(subjectId.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it) }.take(12)
    }
}
