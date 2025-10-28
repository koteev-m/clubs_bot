package com.example.bot.di

import org.koin.dsl.module
import java.time.Clock

val webAppModule =
    module {
        single<Clock> { Clock.systemUTC() }
        // GuestListRepository already registered in data module; do not duplicate bindings here.
    }
