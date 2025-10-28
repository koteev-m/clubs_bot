package com.example.bot.plugins

import com.example.bot.config.BotLimits
import com.example.bot.ratelimit.SubjectBucketStore
import com.example.bot.ratelimit.TokenBucket
import com.example.bot.webapp.InitDataPrincipalKey
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.header
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.request.port
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Конфиг для rate-limiting.
 */
class RateLimitConfig {
    // IP
    var ipEnabled: Boolean = (System.getenv("RL_IP_ENABLED")?.toBooleanStrictOrNull() ?: true)
    var ipRps: Double = (System.getenv("RL_IP_RPS")?.toDoubleOrNull() ?: 100.0)
    var ipBurst: Double = (System.getenv("RL_IP_BURST")?.toDoubleOrNull() ?: 20.0)

    // Subject (например, chatId)
    var subjectEnabled: Boolean = (System.getenv("RL_SUBJECT_ENABLED")?.toBooleanStrictOrNull() ?: true)
    var subjectRps: Double = (System.getenv("RL_SUBJECT_RPS")?.toDoubleOrNull() ?: 60.0)
    var subjectBurst: Double = (System.getenv("RL_SUBJECT_BURST")?.toDoubleOrNull() ?: 20.0)
    var subjectTtl: Duration =
        System.getenv("RL_SUBJECT_TTL_SECONDS")?.toLongOrNull()?.let(Duration::ofSeconds)
            ?: Duration.ofSeconds(600)
    var subjectPathPrefixes: List<String> =
        listOf(
            "/webhook",
            "/api/bookings/confirm",
            "/api/guest-lists/import",
            "/api/clubs/",
            "/api/admin/outbox",
        )

    /**
     * Извлекает ключ для subject-лимитера (например, chatId).
     * По умолчанию пробуем X-Chat-Id, X-Telegram-Chat-Id, query chatId, иначе null.
     */
    var subjectKeyExtractor: suspend (io.ktor.server.application.ApplicationCall) -> String? = { call ->
        call.request.header("X-Chat-Id")
            ?: call.request.header("X-Telegram-Chat-Id")
            ?: call.request.queryParameters["chatId"]
    }

    // Ответ при ограничении
    var retryAfter: Duration =
        System.getenv("RL_RETRY_AFTER_SECONDS")?.toLongOrNull()?.let(Duration::ofSeconds)
            ?: BotLimits.RateLimit.HOT_PATH_DEFAULT_RETRY_AFTER
}

object RateLimitMetrics {
    val ipBlocked = AtomicLong(0)
    val subjectBlocked = AtomicLong(0)
    val subjectStoreSize = AtomicLong(0)
    val lastBlockedRequestId = AtomicReference<String?>(null)
}

private fun String?.toBooleanStrictOrNull(): Boolean? =
    when (this?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

val RateLimitPlugin =
    createApplicationPlugin(name = "RateLimitPlugin", createConfiguration = ::RateLimitConfig) {
        val cfg = pluginConfig
        val logger = LoggerFactory.getLogger("RateLimit")

        val ipBuckets = ConcurrentHashMap<String, TokenBucket>()

        val subjectStore =
            SubjectBucketStore(
                capacity = cfg.subjectBurst,
                refillPerSec = cfg.subjectRps,
                ttl = cfg.subjectTtl,
            )

        onCall { call ->
            val path = call.request.path()
            val requestId =
                call.request.header(HttpHeaders.XRequestId)
                    ?: call.callId

            // 1) IP limiting
            if (cfg.ipEnabled) {
                val ip = clientIp(call)
                val bucket =
                    ipBuckets.computeIfAbsent(ip) {
                        TokenBucket(capacity = cfg.ipBurst, refillPerSec = cfg.ipRps)
                    }
                if (!bucket.tryAcquire()) {
                    RateLimitMetrics.ipBlocked.incrementAndGet()
                    RateLimitMetrics.lastBlockedRequestId.set(requestId)
                    call.response.header(HttpHeaders.RetryAfter, cfg.retryAfter.seconds.toString())
                    logger.warn(
                        "ratelimit.blocked type=ip ip={} path={} requestId={} host={}:{}",
                        ip,
                        path,
                        requestId,
                        call.request.host(),
                        call.request.port(),
                    )
                    call.respondText("Too Many Requests (IP limit)", status = HttpStatusCode.TooManyRequests)
                    return@onCall
                }
            }

            // 2) Subject limiting
            if (cfg.subjectEnabled && cfg.subjectPathPrefixes.any { path.startsWith(it) }) {
                val key = cfg.subjectKeyExtractor(call)
                if (key != null) {
                    val ok = subjectStore.tryAcquire(key)
                    RateLimitMetrics.subjectStoreSize.set(subjectStore.size())
                    if (!ok) {
                        RateLimitMetrics.subjectBlocked.incrementAndGet()
                        RateLimitMetrics.lastBlockedRequestId.set(requestId)
                        call.response.header(HttpHeaders.RetryAfter, cfg.retryAfter.seconds.toString())
                        logger.warn(
                            "ratelimit.blocked type=subject subject={} path={} requestId={} host={}:{}",
                            key,
                            path,
                            requestId,
                            call.request.host(),
                            call.request.port(),
                        )
                        call.respondText("Too Many Requests (subject limit)", status = HttpStatusCode.TooManyRequests)
                        return@onCall
                    }
                }
            }
        }
    }

private fun clientIp(call: io.ktor.server.application.ApplicationCall): String {
    val forwarded =
        call.request.header("X-Forwarded-For")
            ?.split(',')
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val real = call.request.header("X-Real-IP")?.takeIf { it.isNotBlank() }
    val hostWithPort = "${call.request.host()}:${call.request.port()}"
    return forwarded ?: real ?: hostWithPort
}

fun Application.installRateLimitPluginDefaults() {
    if (pluginOrNull(RateLimitPlugin) != null) return

    val log = LoggerFactory.getLogger("RateLimitPlugin")
    val app = this
    install(RateLimitPlugin) {
        app.resolveDouble("RL_IP_RPS")?.let { ipRps = it }
        app.resolveDouble("RL_IP_BURST")?.let { ipBurst = it }
        app.resolveDouble("RL_SUBJECT_RPS")?.let { subjectRps = it }
        app.resolveDouble("RL_SUBJECT_BURST")?.let { subjectBurst = it }
        app.resolveLong("RL_SUBJECT_TTL_SECONDS")?.let { subjectTtl = Duration.ofSeconds(it) }
        app.resolveLong("RL_RETRY_AFTER_SECONDS")?.let { retryAfter = Duration.ofSeconds(it) }
        app.resolveEnv("RL_SUBJECT_PATH_PREFIXES")?.split(',')?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.let { subjectPathPrefixes = it }
        ipEnabled = app.resolveFlag("RL_IP_ENABLED", ipEnabled)
        subjectEnabled = app.resolveFlag("RL_SUBJECT_ENABLED", subjectEnabled)

        subjectKeyExtractor = { call ->
            val initDataPrincipal =
                if (call.attributes.contains(InitDataPrincipalKey)) {
                    call.attributes[InitDataPrincipalKey]
                } else {
                    null
                }
            initDataPrincipal?.userId?.toString()
                ?: call.request.header("X-Chat-Id")
                ?: call.request.header("X-Telegram-Chat-Id")
                ?: call.request.queryParameters["chatId"]
        }

        log.info("[plugin] RateLimit enabled (maxConcurrent={}, retryAfter={})", subjectBurst, retryAfter)
    }
}
