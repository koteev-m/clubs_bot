package com.example.bot.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit

@Suppress("TooManyFunctions")
object UiBookingMetrics {
    @Volatile
    private var countersBound: Boolean = false

    private lateinit var cMenuClicks: Counter
    private lateinit var cNightsRendered: Counter
    private lateinit var cTablesRendered: Counter
    private lateinit var cPagesRendered: Counter
    private lateinit var cTableChosen: Counter
    private lateinit var cGuestsChosen: Counter
    private lateinit var cBookingSuccess: Counter
    private lateinit var cBookingError: Counter

    @PublishedApi
    @Volatile
    internal var listTablesTimer: Timer? = null

    @PublishedApi
    @Volatile
    internal var bookingTotalTimer: Timer? = null
    private const val PERCENTILE_MEDIAN = 0.5
    private const val PERCENTILE_95 = 0.95
    private const val PERCENTILE_99 = 0.99

    fun bind(registry: MeterRegistry) {
        cMenuClicks =
            registry
                .find("ui.menu.clicks")
                .counter()
                ?: Counter.builder("ui.menu.clicks").register(registry)
        cNightsRendered =
            registry
                .find("ui.nights.rendered")
                .counter()
                ?: Counter.builder("ui.nights.rendered").register(registry)
        cTablesRendered =
            registry
                .find("ui.tables.rendered")
                .counter()
                ?: Counter.builder("ui.tables.rendered").register(registry)
        cPagesRendered =
            registry
                .find("ui.tables.pages")
                .counter()
                ?: Counter.builder("ui.tables.pages").register(registry)
        cTableChosen =
            registry
                .find("ui.table.chosen")
                .counter()
                ?: Counter.builder("ui.table.chosen").register(registry)
        cGuestsChosen =
            registry
                .find("ui.guests.chosen")
                .counter()
                ?: Counter.builder("ui.guests.chosen").register(registry)
        cBookingSuccess =
            registry
                .find("ui.booking.success")
                .counter()
                ?: Counter.builder("ui.booking.success").register(registry)
        cBookingError =
            registry
                .find("ui.booking.error")
                .counter()
                ?: Counter.builder("ui.booking.error").register(registry)

        listTablesTimer =
            registry
                .find("ui.tables.fetch.duration.ms")
                .timer()
                ?: Timer.builder("ui.tables.fetch.duration.ms")
                    .publishPercentiles(PERCENTILE_MEDIAN, PERCENTILE_95, PERCENTILE_99)
                    .description("AvailabilityService.listFreeTables duration")
                    .register(registry)

        bookingTotalTimer =
            registry
                .find("ui.booking.total.duration.ms")
                .timer()
                ?: Timer.builder("ui.booking.total.duration.ms")
                    .publishPercentiles(PERCENTILE_MEDIAN, PERCENTILE_95, PERCENTILE_99)
                    .description("End-to-end booking flow from guests selection")
                    .register(registry)

        countersBound = true
    }

    fun incMenuClicks() = incrementIfReady { cMenuClicks }

    fun incNightsRendered() = incrementIfReady { cNightsRendered }

    fun incTablesRendered() = incrementIfReady { cTablesRendered }

    fun incPagesRendered() = incrementIfReady { cPagesRendered }

    fun incTableChosen() = incrementIfReady { cTableChosen }

    fun incGuestsChosen() = incrementIfReady { cGuestsChosen }

    fun incBookingSuccess() = incrementIfReady { cBookingSuccess }

    fun incBookingError() = incrementIfReady { cBookingError }

    inline fun <T> timeListTables(block: () -> T): T {
        val start = System.nanoTime()
        return try {
            block()
        } finally {
            listTablesTimer?.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
        }
    }

    inline fun <T> timeBookingTotal(block: () -> T): T {
        val start = System.nanoTime()
        return try {
            block()
        } finally {
            bookingTotalTimer?.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
        }
    }

    private inline fun incrementIfReady(counterProvider: () -> Counter) {
        if (countersBound) {
            counterProvider().increment()
        }
    }
}
