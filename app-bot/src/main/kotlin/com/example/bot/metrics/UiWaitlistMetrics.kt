package com.example.bot.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry

object UiWaitlistMetrics {
    @Volatile private var cAdded: Counter? = null

    @Volatile private var cClaimed: Counter? = null

    @Volatile private var cExpired: Counter? = null

    fun bind(registry: MeterRegistry) {
        cAdded =
            registry.find("ui.waitlist.added").counter()
                ?: Counter
                    .builder("ui.waitlist.added")
                    .description("Waitlist entries added by guests")
                    .register(registry)

        cClaimed =
            registry.find("ui.waitlist.claimed").counter()
                ?: Counter
                    .builder("ui.waitlist.claimed")
                    .description("Waitlist entries called (claimed) by staff")
                    .register(registry)

        cExpired =
            registry.find("ui.waitlist.expired").counter()
                ?: Counter
                    .builder("ui.waitlist.expired")
                    .description("Waitlist entries expired or returned")
                    .register(registry)
    }

    fun incAdded() {
        cAdded?.increment()
    }

    fun incClaimed() {
        cClaimed?.increment()
    }

    fun incExpired() {
        cExpired?.increment()
    }
}
