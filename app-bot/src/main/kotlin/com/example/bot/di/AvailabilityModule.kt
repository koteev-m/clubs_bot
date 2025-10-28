package com.example.bot.di

import com.example.bot.availability.AvailabilityService
import com.example.bot.availability.DefaultAvailabilityService
import org.koin.dsl.module
import java.time.Clock

val availabilityModule =
    module {
        single<AvailabilityService> {
            DefaultAvailabilityService(
                get(),
                get<Clock>(),
            )
        }
    }
