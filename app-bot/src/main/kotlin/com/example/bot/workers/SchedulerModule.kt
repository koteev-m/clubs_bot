package com.example.bot.workers

import com.example.bot.telemetry.Telemetry
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.koin.core.error.ClosedScopeException
import org.koin.core.error.NoBeanDefFoundException // старое имя (deprecated, но всё ещё есть)
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin
import org.slf4j.LoggerFactory
import java.time.Duration

// Если этих констант у тебя нет выше по проекту — раскомментируй:
// private val DEFAULT_TICK_INTERVAL: Duration = Duration.ofSeconds(15)
// private const val DEFAULT_BATCH_SIZE: Int = 100

/** Optional scheduler configuration sourced from environment variables. */
data class SchedulerConfig(
    val tickInterval: Duration =
        System.getenv("SCHEDULER_TICK_MS")?.toLongOrNull()?.let(Duration::ofMillis) ?: DEFAULT_TICK_INTERVAL,
    val batchSize: Int = System.getenv("SCHEDULER_BATCH")?.toIntOrNull() ?: DEFAULT_BATCH_SIZE,
)

/** Lightweight metrics adapter around Telemetry/Micrometer. */
class WorkerMetrics(
    private val onStarted: (() -> Unit)? = null,
    private val onStopped: (() -> Unit)? = null,
    private val onEnqueued: ((Int) -> Unit)? = null,
    private val onError: (() -> Unit)? = null,
) {
    fun markStarted() = onStarted?.invoke() ?: Unit
    fun markStopped() = onStopped?.invoke() ?: Unit
    fun markEnqueued(n: Int) = onEnqueued?.invoke(n) ?: Unit
    fun markError() = onError?.invoke() ?: Unit

    companion object {
        fun fromTelemetry(): WorkerMetrics {
            return runCatching {
                val registry = Telemetry.registry
                val started = registry.counter("campaign_scheduler_started_total")
                val stopped = registry.counter("campaign_scheduler_stopped_total")
                val enqueued = registry.counter("campaign_scheduler_enqueued_total")
                val errors = registry.counter("campaign_scheduler_errors_total")
                WorkerMetrics(
                    onStarted = { started.increment() },
                    onStopped = { stopped.increment() },
                    onEnqueued = { n -> if (n > 0) enqueued.increment(n.toDouble()) },
                    onError = { errors.increment() },
                )
            }.getOrElse { WorkerMetrics() }
        }
    }
}

val schedulerModule =
    module {
        single(named("campaignSchedulerScope")) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }
        single { SchedulerConfig() }
        single { WorkerMetrics.fromTelemetry() }
        single {
            // Lazy — создастся только по запросу из Koin
            CampaignScheduler(
                scope = get(named("campaignSchedulerScope")),
                api = get(),      // SchedulerApi должен быть провайжен, когда включишь флаг
                config = get(),
                metrics = get(),
            )
        }
    }

private val schedLog = LoggerFactory.getLogger("CampaignScheduler")

fun Application.launchCampaignSchedulerOnStart() {
    // Включать в проде, когда связана реальная реализация SchedulerApi
    val enabled = System.getenv("CAMPAIGN_SCHEDULER_ENABLED")
        ?.equals("true", ignoreCase = true) ?: false

    if (!enabled) {
        schedLog.info("CampaignScheduler disabled via CAMPAIGN_SCHEDULER_ENABLED")
        return
    }

    var scheduler: CampaignScheduler? = null

    monitor.subscribe(ApplicationStarted) {
        runCatching {
            val worker = getKoin().get<CampaignScheduler>()
            scheduler = worker
            schedLog.info("Starting CampaignScheduler …")
            worker.start()
        }.onFailure { t ->
            // Поддерживаем и старый, и новый тип Koin-исключения
            val isMissingDefinition =
                t is NoBeanDefFoundException || t.javaClass.simpleName == "NoDefinitionFoundException"

            when {
                isMissingDefinition -> {
                    schedLog.warn("CampaignScheduler NOT started: missing Koin definition: ${t.message}")
                }
                t is ClosedScopeException -> {
                    schedLog.warn("CampaignScheduler NOT started: Koin scope is closed")
                }
                else -> throw t
            }
        }
    }

    monitor.subscribe(ApplicationStopping) {
        scheduler?.let {
            runBlocking {
                runCatching { it.stop() }
                    .onSuccess { schedLog.info("CampaignScheduler stopped") }
                    .onFailure { e -> schedLog.warn("CampaignScheduler stop failed: ${e.message}") }
            }
        }
        scheduler = null
    }
}
