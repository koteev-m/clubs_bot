package com.example.bot.di

import com.example.bot.booking.a3.BookingState
import com.example.bot.layout.AdminTablesRepository
import com.example.bot.layout.BookingAwareLayoutRepository
import com.example.bot.layout.InMemoryLayoutRepository
import com.example.bot.layout.LayoutRepository
import com.example.bot.layout.Table
import com.example.bot.layout.TableStatus
import com.example.bot.layout.Zone
import com.example.bot.promoter.quotas.PromoterQuotaService
import io.micrometer.core.instrument.Metrics
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.time.Clock
import java.time.Duration
import java.time.Instant

val layoutModule =
    module {
        single<LayoutRepository>(named("baseLayout")) {
            val clock = runCatching { get<Clock>() }.getOrElse { Clock.systemUTC() }
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
                baseUpdatedAt = Instant.parse("2024-05-01T00:00:00Z"),
                eventUpdatedAt = eventWatermarks,
                clock = clock,
            )
        }

        single<AdminTablesRepository> { get<LayoutRepository>(named("baseLayout")) as AdminTablesRepository }

        single {
            val holdTtl = envDuration("BOOKING_HOLD_TTL", Duration.ofMinutes(10))
            val idemTtl = envDuration("BOOKING_IDEMPOTENCY_TTL", Duration.ofMinutes(15))
            val bookingRetention = envDuration("BOOKING_RETENTION_TTL", Duration.ofHours(48))
            val watermarkRetention = envDuration("BOOKING_WATERMARK_TTL", Duration.ofDays(7))
            val latePlusOne = envDuration("BOOKING_LATE_PLUS_ONE_OFFSET", Duration.ofMinutes(30))
            val arrivalBefore = envDuration("BOOKING_ARRIVAL_BEFORE", Duration.ofMinutes(15))
            val arrivalAfter = envDuration("BOOKING_ARRIVAL_AFTER", Duration.ofMinutes(45))
            BookingState(
                get(named("baseLayout")),
                get(),
                get<PromoterQuotaService>(),
                holdTtl = holdTtl,
                latePlusOneOffset = latePlusOne,
                arrivalWindowBefore = arrivalBefore,
                arrivalWindowAfter = arrivalAfter,
                idempotencyTtl = idemTtl,
                bookingRetention = bookingRetention,
                watermarkRetention = watermarkRetention,
                meterRegistry = Metrics.globalRegistry,
            )
        }

        single<LayoutRepository> {
            BookingAwareLayoutRepository(get(named("baseLayout")), get())
        }
    }

private fun envDuration(name: String, default: Duration): Duration =
    System.getenv(name)?.let { runCatching { Duration.parse(it) }.getOrNull() } ?: default
