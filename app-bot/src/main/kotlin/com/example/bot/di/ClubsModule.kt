package com.example.bot.di

import com.example.bot.clubs.ClubsRepository
import com.example.bot.clubs.EventsRepository
import com.example.bot.data.clubs.ClubsDbRepository
import com.example.bot.data.clubs.EventsDbRepository
import org.koin.dsl.module

val clubsModule =
    module {
        single<ClubsRepository> {
            ClubsDbRepository(get())
        }
        single<EventsRepository> {
            EventsDbRepository(get())
        }
    }
