package com.example.bot.telemetry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

class TelemetryTest :
    StringSpec({
        "registry is simple" {
            Telemetry.registry.shouldBeInstanceOf<SimpleMeterRegistry>()
        }
    })
