package com.example.bot.di

import com.example.bot.clubs.ClubsRepository
import com.example.bot.clubs.EventsRepository
import com.example.bot.clubs.InMemoryClubsRepository
import com.example.bot.clubs.InMemoryEventsRepository
import org.koin.dsl.module

val clubsModule =
    module {
        single<ClubsRepository> { InMemoryClubsRepository(emptyList()) }
        single<EventsRepository> { InMemoryEventsRepository(emptyList()) }
    }
