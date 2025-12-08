package com.example.bot.di

import com.example.bot.clubs.ClubsRepository
import com.example.bot.clubs.EventsRepository
import com.example.bot.clubs.InMemoryClubsRepository
import com.example.bot.clubs.InMemoryEventsRepository
import java.time.Instant
import org.koin.dsl.module

val clubsModule =
    module {
        single<ClubsRepository> {
            InMemoryClubsRepository(
                listOf(
                    com.example.bot.clubs.Club(
                        id = 1,
                        city = "Moscow",
                        name = "Club 1",
                        genres = listOf("house"),
                        tags = listOf("popular"),
                        logoUrl = null,
                    ),
                ),
                updatedAt = Instant.parse("2024-05-01T00:00:00Z"),
            )
        }
        single<EventsRepository> {
            InMemoryEventsRepository(
                listOf(
                    com.example.bot.clubs.Event(
                        id = 100,
                        clubId = 1,
                        startUtc = Instant.parse("2024-05-02T21:00:00Z"),
                        endUtc = Instant.parse("2024-05-03T05:00:00Z"),
                        title = "Night 1",
                        isSpecial = false,
                    ),
                    com.example.bot.clubs.Event(
                        id = 200,
                        clubId = 1,
                        startUtc = Instant.parse("2024-05-03T21:00:00Z"),
                        endUtc = Instant.parse("2024-05-04T05:00:00Z"),
                        title = "Night 2",
                        isSpecial = true,
                    ),
                ),
                updatedAt = Instant.parse("2024-05-01T00:00:00Z"),
            )
        }
    }
