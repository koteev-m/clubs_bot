package com.example.bot.di

import com.example.bot.admin.AdminHallsRepository
import com.example.bot.booking.a3.BookingState
import com.example.bot.data.admin.AdminHallsDbRepository
import com.example.bot.layout.AdminTablesRepository
import com.example.bot.data.layout.LayoutDbRepository
import com.example.bot.layout.BookingAwareLayoutRepository
import com.example.bot.layout.LayoutAssetsRepository
import com.example.bot.layout.LayoutRepository
import com.example.bot.promoter.quotas.PromoterQuotaService
import io.micrometer.core.instrument.Metrics
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.time.Clock
import java.time.Duration

val layoutModule =
    module {
        single<LayoutRepository>(named("baseLayout")) {
            val clock = runCatching { get<Clock>() }.getOrElse { Clock.systemUTC() }
            LayoutDbRepository(get(), clock)
        }

        single<LayoutAssetsRepository> { get<LayoutRepository>(named("baseLayout")) as LayoutAssetsRepository }

        single<AdminTablesRepository> { get<LayoutRepository>(named("baseLayout")) as AdminTablesRepository }
        single<AdminHallsRepository> { AdminHallsDbRepository(get()) }

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
