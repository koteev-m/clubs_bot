package com.example.bot.di

import com.example.bot.coredata.ClubSeed
import com.example.bot.coredata.CoreDataSeed
import com.example.bot.coredata.EventSeed
import com.example.bot.coredata.HallSeed
import com.example.bot.coredata.HallTableSeed
import com.example.bot.coredata.HallZoneSeed
import com.example.bot.layout.ArrivalWindow
import com.example.bot.layout.InMemoryLayoutRepository
import java.time.Instant
import java.time.LocalTime

fun defaultCoreDataSeed(): CoreDataSeed {
    val club =
        ClubSeed(
            id = 1,
            city = "Moscow",
            name = "Club 1",
            genres = listOf("house"),
            tags = listOf("popular"),
            logoUrl = null,
            isActive = true,
        )

    val events =
        listOf(
            EventSeed(
                id = 100,
                clubId = 1,
                startUtc = Instant.parse("2024-05-02T21:00:00Z"),
                endUtc = Instant.parse("2024-05-03T05:00:00Z"),
                title = "Night 1",
                isSpecial = false,
            ),
            EventSeed(
                id = 200,
                clubId = 1,
                startUtc = Instant.parse("2024-05-03T21:00:00Z"),
                endUtc = Instant.parse("2024-05-04T05:00:00Z"),
                title = "Night 2",
                isSpecial = true,
            ),
        )

    val zones =
        listOf(
            HallZoneSeed(id = "vip", name = "VIP", tags = listOf("vip"), order = 1),
            HallZoneSeed(id = "dancefloor", name = "Dancefloor", tags = listOf("near_stage"), order = 2),
            HallZoneSeed(id = "balcony", name = "Balcony", tags = emptyList(), order = 3),
        )

    val tables =
        listOf(
            HallTableSeed(
                id = 1,
                tableNumber = 1,
                zoneId = "vip",
                label = "VIP-1",
                capacity = 4,
                minimumTier = "vip",
                minDeposit = 0,
                zone = "vip",
                arrivalWindow = null,
                mysteryEligible = false,
                x = 1.0,
                y = 1.0,
            ),
            HallTableSeed(
                id = 2,
                tableNumber = 2,
                zoneId = "vip",
                label = "VIP-2",
                capacity = 4,
                minimumTier = "vip",
                minDeposit = 0,
                zone = "vip",
                arrivalWindow = null,
                mysteryEligible = false,
                x = 3.0,
                y = 1.0,
            ),
            HallTableSeed(
                id = 3,
                tableNumber = 3,
                zoneId = "dancefloor",
                label = "D-1",
                capacity = 6,
                minimumTier = "premium",
                minDeposit = 0,
                zone = "dancefloor",
                arrivalWindow = null,
                mysteryEligible = false,
                x = 6.0,
                y = 1.0,
            ),
            HallTableSeed(
                id = 4,
                tableNumber = 4,
                zoneId = "dancefloor",
                label = "D-2",
                capacity = 6,
                minimumTier = "premium",
                minDeposit = 0,
                zone = "dancefloor",
                arrivalWindow = null,
                mysteryEligible = false,
                x = 8.0,
                y = 2.5,
            ),
            HallTableSeed(
                id = 5,
                tableNumber = 5,
                zoneId = "dancefloor",
                label = "D-3",
                capacity = 8,
                minimumTier = "premium",
                minDeposit = 0,
                zone = "dancefloor",
                arrivalWindow = null,
                mysteryEligible = false,
                x = 10.5,
                y = 4.5,
            ),
            HallTableSeed(
                id = 6,
                tableNumber = 6,
                zoneId = "balcony",
                label = "B-1",
                capacity = 3,
                minimumTier = "standard",
                minDeposit = 0,
                zone = "balcony",
                arrivalWindow = ArrivalWindow(LocalTime.of(22, 0), LocalTime.of(23, 0)),
                mysteryEligible = false,
                x = 1.0,
                y = 6.0,
            ),
            HallTableSeed(
                id = 7,
                tableNumber = 7,
                zoneId = "balcony",
                label = "B-2",
                capacity = 5,
                minimumTier = "standard",
                minDeposit = 0,
                zone = "balcony",
                arrivalWindow = null,
                mysteryEligible = false,
                x = 3.0,
                y = 7.5,
            ),
        )

    val hall =
        HallSeed(
            id = 1,
            clubId = 1,
            name = "Main Hall",
            isActive = true,
            layoutRevision = 1,
            geometryJson = InMemoryLayoutRepository.DEFAULT_GEOMETRY_JSON,
            zones = zones,
            tables = tables,
        )

    return CoreDataSeed(
        clubs = listOf(club),
        events = events,
        halls = listOf(hall),
    )
}
