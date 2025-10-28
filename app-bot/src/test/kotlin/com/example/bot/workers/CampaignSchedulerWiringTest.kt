package com.example.bot.workers

import com.example.bot.notifications.SchedulerApi
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class CampaignSchedulerWiringTest :
    StringSpec({
        "scheduler starts on application start" {
            val fakeApi = CountingSchedulerApi()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            testApplication {
                application {
                    install(Koin) {
                        allowOverride(true)
                        modules(
                            schedulerModule,
                            module {
                                single { SchedulerConfig(tickInterval = Duration.ofMillis(50), batchSize = 10) }
                                single<CoroutineScope>(named("campaignSchedulerScope")) { scope }
                                single<SchedulerApi> { fakeApi }
                            },
                        )
                    }

                    launchCampaignSchedulerOnStart()

                    runBlocking {
                        this@application.monitor.raise(ApplicationStarted, this@application)

                        try {
                            withTimeout(5_000) {
                                while (fakeApi.listActiveCalls.get() == 0) {
                                    delay(10)
                                }
                            }
                        } finally {
                            this@application.monitor.raise(ApplicationStopping, this@application)
                            scope.cancel()
                        }
                    }
                }
            }

            fakeApi.listActiveCalls.get().shouldBeGreaterThanOrEqual(1)
        }
    })

private class CountingSchedulerApi : SchedulerApi {
    val listActiveCalls = AtomicInteger(0)
    val enqueueBatchCalls = AtomicInteger(0)

    override suspend fun listActive(): List<SchedulerApi.Campaign> {
        listActiveCalls.incrementAndGet()
        return listOf(SchedulerApi.Campaign(1, SchedulerApi.Status.SCHEDULED, null, null))
    }

    override suspend fun markSending(id: Long) {
        // no-op
    }

    override suspend fun enqueueBatch(
        campaignId: Long,
        limit: Int,
    ): Int {
        enqueueBatchCalls.incrementAndGet()
        return 1
}

    override suspend fun progress(campaignId: Long): SchedulerApi.Progress =
        SchedulerApi.Progress(enqueued = 0, total = 0)

    override suspend fun markDone(id: Long) {
        // no-op
    }
}
