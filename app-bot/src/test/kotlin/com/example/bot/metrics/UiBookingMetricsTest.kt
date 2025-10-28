package com.example.bot.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class UiBookingMetricsTest {
    private lateinit var registry: SimpleMeterRegistry

    @BeforeEach
    fun setUp() {
        registry = SimpleMeterRegistry()
        UiBookingMetrics.bind(registry)
    }

    @AfterEach
    fun tearDown() {
        registry.close()
    }

    @Test
    fun `counters are exposed via gauges`() {
        UiBookingMetrics.incMenuClicks()
        UiBookingMetrics.incNightsRendered()
        UiBookingMetrics.incTablesRendered()
        UiBookingMetrics.incPagesRendered()
        UiBookingMetrics.incTableChosen()
        UiBookingMetrics.incGuestsChosen()
        UiBookingMetrics.incBookingSuccess()
        UiBookingMetrics.incBookingError()

        assertEquals(1.0, registry.get("ui.menu.clicks").counter().count())
        assertEquals(1.0, registry.get("ui.nights.rendered").counter().count())
        assertEquals(1.0, registry.get("ui.tables.rendered").counter().count())
        assertEquals(1.0, registry.get("ui.tables.pages").counter().count())
        assertEquals(1.0, registry.get("ui.table.chosen").counter().count())
        assertEquals(1.0, registry.get("ui.guests.chosen").counter().count())
        assertEquals(1.0, registry.get("ui.booking.success").counter().count())
        assertEquals(1.0, registry.get("ui.booking.error").counter().count())
    }

    @Test
    fun `timers record durations`() {
        UiBookingMetrics.timeListTables { Thread.sleep(10) }
        UiBookingMetrics.timeBookingTotal { Thread.sleep(5) }

        val listTimer = registry.get("ui.tables.fetch.duration.ms").timer()
        val totalTimer = registry.get("ui.booking.total.duration.ms").timer()

        assertTrue(listTimer.totalTime(TimeUnit.MILLISECONDS) > 0.0)
        assertTrue(totalTimer.totalTime(TimeUnit.MILLISECONDS) > 0.0)
    }
}
