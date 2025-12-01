package com.example.bot.di

import com.example.bot.layout.InMemoryLayoutRepository
import com.example.bot.layout.LayoutRepository
import com.example.bot.layout.Table
import com.example.bot.layout.TableStatus
import com.example.bot.layout.Zone
import org.koin.dsl.module
import java.time.Instant

val layoutModule =
    module {
        single<LayoutRepository> {
            val zones =
                listOf(
                    Zone(id = "vip", name = "VIP", tags = listOf("vip"), order = 1),
                    Zone(id = "dancefloor", name = "Dancefloor", tags = listOf("near_stage"), order = 2),
                    Zone(id = "balcony", name = "Balcony", tags = emptyList(), order = 3),
                )

            val tables =
                listOf(
                    Table(id = 1, zoneId = "vip", label = "VIP-1", capacity = 4, minimumTier = "vip", status = TableStatus.FREE),
                    Table(id = 2, zoneId = "vip", label = "VIP-2", capacity = 4, minimumTier = "vip", status = TableStatus.FREE),
                    Table(id = 3, zoneId = "dancefloor", label = "D-1", capacity = 6, minimumTier = "premium", status = TableStatus.FREE),
                    Table(id = 4, zoneId = "dancefloor", label = "D-2", capacity = 6, minimumTier = "premium", status = TableStatus.FREE),
                    Table(id = 5, zoneId = "dancefloor", label = "D-3", capacity = 8, minimumTier = "premium", status = TableStatus.FREE),
                    Table(id = 6, zoneId = "balcony", label = "B-1", capacity = 3, minimumTier = "standard", status = TableStatus.FREE),
                    Table(id = 7, zoneId = "balcony", label = "B-2", capacity = 5, minimumTier = "standard", status = TableStatus.FREE),
                )

            val statusOverrides: Map<Long?, Map<Long, TableStatus>> =
                mapOf(
                    100L to mapOf(3L to TableStatus.HOLD, 5L to TableStatus.BOOKED),
                    200L to mapOf(2L to TableStatus.HOLD, 4L to TableStatus.BOOKED),
                )

            val eventWatermarks: Map<Long?, Instant> =
                mapOf(
                    100L to Instant.parse("2024-05-02T00:00:00Z"),
                    200L to Instant.parse("2024-05-03T00:00:00Z"),
                )

            InMemoryLayoutRepository(
                layouts =
                    listOf(
                        InMemoryLayoutRepository.LayoutSeed(
                            clubId = 1,
                            zones = zones,
                            tables = tables,
                            geometryJson = InMemoryLayoutRepository.DEFAULT_GEOMETRY_JSON,
                            statusOverrides = statusOverrides,
                        ),
                    ),
                updatedAt = Instant.parse("2024-05-01T00:00:00Z"),
                eventUpdatedAt = eventWatermarks,
            )
        }
    }
