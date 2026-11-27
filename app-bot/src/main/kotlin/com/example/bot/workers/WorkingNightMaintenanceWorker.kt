package com.example.bot.workers

import com.example.bot.data.client.onboarding.ClientWorkingNightMaintenance
import com.example.bot.data.finance.WorkingNightShiftMaintenance
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Clock
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

internal fun Application.launchWorkingNightMaintenance(
    dataSource: DataSource,
    clock: Clock,
) {
    val nights = ClientWorkingNightMaintenance(dataSource, clock)
    val shifts = WorkingNightShiftMaintenance(dataSource, clock)
    var job: Job? = null
    monitor.subscribe(ApplicationStarted) {
        job =
            launch {
                while (isActive) {
                    refreshWorkingNights { nights.refresh() }
                    refreshWorkingNights(shifts::refresh)
                    delay(15.seconds)
                }
            }
    }
    monitor.subscribe(ApplicationStopping) {
        runBlocking { job?.cancelAndJoin() }
    }
}

private suspend fun refreshWorkingNights(refresh: suspend () -> Unit) {
    try {
        refresh()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        workingNightLogger.warn("Working night maintenance will retry after {}", failure.javaClass.simpleName)
    }
}

private val workingNightLogger = LoggerFactory.getLogger("WorkingNightMaintenanceWorker")
