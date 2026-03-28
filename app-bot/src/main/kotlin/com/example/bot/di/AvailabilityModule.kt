package com.example.bot.di

import com.example.bot.availability.AvailabilityCacheInvalidator
import com.example.bot.availability.AvailabilityService
import com.example.bot.availability.DefaultAvailabilityService
import io.micrometer.core.instrument.MeterRegistry
import org.koin.dsl.module
import java.time.Clock

val availabilityModule =
    module {
        single {
            DefaultAvailabilityService(
                db = get(),
                clock = get<Clock>(),
                meterRegistry = runCatching { get<MeterRegistry>() }.getOrNull(),
            )
        }
        single<AvailabilityService> { get<DefaultAvailabilityService>() }
        single<AvailabilityCacheInvalidator> { get<DefaultAvailabilityService>() }
    }
