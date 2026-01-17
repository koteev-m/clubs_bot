package com.example.bot.di

import com.example.bot.coredata.CoreDataSeed
import com.example.bot.data.coredata.CoreDataSeeder
import org.koin.dsl.module
import java.time.Clock

val coreDataModule =
    module {
        single<CoreDataSeed> { defaultCoreDataSeed() }
        single {
            val clock = runCatching { get<Clock>() }.getOrElse { Clock.systemUTC() }
            CoreDataSeeder(get(), clock)
        }
    }
