package com.example.bot.di

import com.example.bot.availability.AvailabilityCacheInvalidator
import com.example.bot.availability.AvailabilityService
import com.example.bot.availability.DefaultAvailabilityService
import com.example.bot.data.privacy.JdbcLegacyClubAccess
import com.example.bot.plugins.DataSourceHolder
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
                legacyClubAccess = JdbcLegacyClubAccess(checkNotNull(DataSourceHolder.dataSource)),
            )
        }
        single<AvailabilityService> { get<DefaultAvailabilityService>() }
        single<AvailabilityCacheInvalidator> { get<DefaultAvailabilityService>() }
    }
