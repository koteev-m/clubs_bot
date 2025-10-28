package com.example.bot.plugins

import com.example.bot.config.BotLimits
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.request.path
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/**
 * Конфигурация плагина лимитирования «горячих» путей.
 */
class HotPathLimiterConfig {
    /**
     * Список префиксов путей, к которым применяем лимит (например, ["/webhook", "/api/clubs/"]).
     */
    var pathPrefixes: List<String> = emptyList()

    /**
     * Максимум параллельных обработок для каждого совпадающего пути.
     */
    var maxConcurrent: Int =
        Runtime.getRuntime().availableProcessors()
            .coerceAtLeast(BotLimits.RateLimit.HOT_PATH_MIN_CONFIG_PARALLELISM)

    /**
     * Заголовок с информацией о лимитах (например, Retry-After).
     */
    var throttlingHeader: String = "Retry-After"

    /**
     * Значение Retry-After (секунды) при отказе.
     */
    var retryAfter: Duration = BotLimits.RateLimit.HOT_PATH_DEFAULT_RETRY_AFTER
}

/**
 * Простые метрики плагина (без привязки к конкретному реестру; можно подключить к Micrometer извне).
 */
object HotPathMetrics {
    val active = AtomicInteger(0)
    val throttled = AtomicLong(0)
    val availablePermits = AtomicInteger(0)
}

private enum class HotPathDecision { SKIP, ACQUIRE, THROTTLE }

val HotPathLimiter =
    createApplicationPlugin(name = "HotPathLimiter", createConfiguration = ::HotPathLimiterConfig) {
        val cfg = pluginConfig
        val semaphore = Semaphore(cfg.maxConcurrent, true)

        onCall { call ->
            val path = call.request.path()
            val decision =
                when {
                    cfg.pathPrefixes.none { prefix -> path.startsWith(prefix) } -> HotPathDecision.SKIP
                    semaphore.tryAcquire() -> HotPathDecision.ACQUIRE
                    else -> HotPathDecision.THROTTLE
                }

            when (decision) {
                HotPathDecision.SKIP -> Unit
                HotPathDecision.THROTTLE -> {
                    HotPathMetrics.throttled.incrementAndGet()
                    call.response.header(cfg.throttlingHeader, cfg.retryAfter.seconds.toString())
                    call.respondText("Too Many Requests", status = HttpStatusCode.TooManyRequests)
                }
                HotPathDecision.ACQUIRE -> {
                    HotPathMetrics.active.incrementAndGet()
                    HotPathMetrics.availablePermits.set(semaphore.availablePermits())

                    call.response.pipeline.intercept(ApplicationSendPipeline.Engine) {
                        try {
                            proceed()
                        } finally {
                            semaphore.release()
                            HotPathMetrics.active.decrementAndGet()
                            HotPathMetrics.availablePermits.set(semaphore.availablePermits())
                        }
                    }
                }
            }
        }
    }

/**
 * Утилита для регистрации плагина с ENV/дефолтами.
 */
fun Application.installHotPathLimiterDefaults() {
    if (pluginOrNull(HotPathLimiter) != null) return

    val defaults =
        listOf(
            "/webhook",
            "/api/clubs/",
            "/api/guest-lists/import",
        )
    val app = this
    val log = LoggerFactory.getLogger("HotPathLimiter")
    install(HotPathLimiter) {
        pathPrefixes = defaults
        maxConcurrent =
            app.resolveInt("HOT_PATH_MAX_CONCURRENT")
                ?.coerceAtLeast(1)
                ?: maxOf(2, Runtime.getRuntime().availableProcessors())
        retryAfter =
            app.resolveLong("HOT_PATH_RETRY_AFTER_SEC")?.let(Duration::ofSeconds)
                ?: BotLimits.RateLimit.HOT_PATH_DEFAULT_RETRY_AFTER

        log.info("[plugin] HotPathLimiter enabled (paths={})", pathPrefixes)
    }
}
