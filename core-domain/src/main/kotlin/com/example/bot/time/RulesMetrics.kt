package com.example.bot.time

import com.example.bot.telemetry.Telemetry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags

object RulesMetrics {
    private val registry get() = Telemetry.registry

    private fun boolTag(value: Boolean): String = value.toString()

    private fun tags(vararg pairs: Pair<String, String>): Tags =
        Tags.of(pairs.map { (key, value) -> Tag.of(key, value) })

    fun incHolidayInheritedOpen(
        dow: Int,
        overnight: Boolean,
    ) {
        registry.counter(
            "rules.holiday.inherited_open",
            tags(
                "dow" to dow.toString(),
                "overnight" to boolTag(overnight),
            ),
        ).increment()
    }

    fun incHolidayInheritedClose(
        dow: Int,
        overnight: Boolean,
    ) {
        registry.counter(
            "rules.holiday.inherited_close",
            tags(
                "dow" to dow.toString(),
                "overnight" to boolTag(overnight),
            ),
        ).increment()
    }

    fun incExceptionApplied(
        dow: Int,
        overnight: Boolean,
    ) {
        registry.counter(
            "rules.exception.applied",
            tags(
                "dow" to dow.toString(),
                "overnight" to boolTag(overnight),
            ),
        ).increment()
    }

    fun incDayOpen(
        dow: Int,
        exceptionApplied: Boolean,
        holidayApplied: Boolean,
        overnight: Boolean,
    ) {
        registry.counter(
            "rules.day.open",
            tags(
                "dow" to dow.toString(),
                "exception" to boolTag(exceptionApplied),
                "holiday" to boolTag(holidayApplied),
                "overnight" to boolTag(overnight),
            ),
        ).increment()
    }
}
