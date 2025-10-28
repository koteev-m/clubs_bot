package com.example.bot.di

import com.example.bot.booking.BookingService
import com.example.bot.club.GuestListRepository
import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.booking.core.BookingHoldRepository
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.booking.core.OutboxRepository
import com.example.bot.data.booking.core.PaymentsBookingRepository
import com.example.bot.data.club.GuestListRepositoryImpl
import com.example.bot.data.notifications.NotificationsOutboxRepository
import com.example.bot.data.promo.BookingTemplateRepositoryImpl
import com.example.bot.data.promo.PromoAttributionRepositoryImpl
import com.example.bot.data.promo.PromoLinkRepositoryImpl
import com.example.bot.data.security.ExposedUserRepository
import com.example.bot.data.security.ExposedUserRoleRepository
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.plugins.DataSourceHolder
import com.example.bot.promo.BookingTemplateRepository
import com.example.bot.promo.BookingTemplateService
import com.example.bot.promo.InMemoryPromoAttributionStore
import com.example.bot.promo.PromoAttributionCoordinator
import com.example.bot.promo.PromoAttributionRepository
import com.example.bot.promo.PromoAttributionService
import com.example.bot.promo.PromoAttributionStore
import com.example.bot.promo.PromoLinkRepository
import com.example.bot.telegram.NotifyAdapterMetrics
import com.example.bot.telegram.NotificationsDispatchConfig
import com.example.bot.telegram.NotifySenderSendPort
import com.example.bot.telegram.bookings.MyBookingsMetrics
import com.example.bot.telegram.bookings.MyBookingsService
import com.example.bot.workers.OutboxWorker
import com.example.bot.workers.SendOutcome
import com.example.bot.workers.SendPort
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.tracing.Tracer
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Database
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import kotlin.math.ceil

val bookingModule =
    module {
        // DB
        single {
            val ds = DataSourceHolder.dataSource ?: error("DataSource is not initialized")
            Database.connect(ds)
        }

        // Core repos
        single { BookingRepository(get()) }
        single<PaymentsBookingRepository> { get<BookingRepository>() }
        single { BookingHoldRepository(get()) }
        single { OutboxRepository(get()) }
        single { NotificationsOutboxRepository(get()) }
        single { AuditLogRepository(get()) }

        // Guests
        single<GuestListRepository> { GuestListRepositoryImpl(get()) }

        // Promo repos (interfaces -> impl)
        single<PromoLinkRepository> { PromoLinkRepositoryImpl(get()) }
        single<PromoAttributionRepository> { PromoAttributionRepositoryImpl(get()) }
        single<BookingTemplateRepository> { BookingTemplateRepositoryImpl(get()) }

        // Security repos (interfaces -> impl)
        single<UserRepository> { ExposedUserRepository(get()) }
        single<UserRoleRepository> { ExposedUserRoleRepository(get()) }

        single { MyBookingsMetrics(runCatching { get<MeterRegistry>() }.getOrNull()) }
        single { MyBookingsService(get(), get(), get(), get()) }

        // Promo store/service
        single<PromoAttributionStore> { InMemoryPromoAttributionStore() }

        // ВНИМАНИЕ: порядок параметров соответствует реальной сигнатуре
        single {
            PromoAttributionService(
                get<PromoLinkRepository>(),
                get<PromoAttributionRepository>(),
                get<PromoAttributionStore>(),
                get<UserRepository>(),
                get<UserRoleRepository>(),
            )
        }
        single<PromoAttributionCoordinator> { get<PromoAttributionService>() }

        // ВНИМАНИЕ: порядок параметров соответствует реальной сигнатуре
        single {
            BookingTemplateService(
                get<BookingTemplateRepository>(),
                get<BookingService>(),
                get<UserRepository>(),
                get<UserRoleRepository>(),
                get<NotificationsOutboxRepository>(),
            )
        }

        single { NotificationsDispatchConfig.fromEnvironment() }

        // Outbound port wiring (NotifySender via environment toggle)
        single<SendPort> {
            val dispatchConfig = get<NotificationsDispatchConfig>()
            if (dispatchConfig.isDispatchActive) {
                notifySendPortLogger.info("Using NotifySenderSendPort (notifications enabled)")
                val metrics =
                    runCatching { get<MeterRegistry>() }
                        .map { registry ->
                            val attempts = registry.counter("notify.adapter.attempts")
                            val ok = registry.counter("notify.adapter.ok")
                            val retryable = registry.counter("notify.adapter.retryable")
                            val permanent = registry.counter("notify.adapter.permanent")
                            NotifyAdapterMetrics(
                                onAttempt = { attempts.increment() },
                                onOk = { ok.increment() },
                                onRetryAfter = { delayMs ->
                                    val seconds = ceil(delayMs / 1000.0).toLong().coerceAtLeast(0)
                                    registry
                                        .counter(
                                            "notify.adapter.retry_after",
                                            "seconds",
                                            seconds.toString(),
                                        ).increment()
                                },
                                onRetryable = { retryable.increment() },
                                onPermanent = { permanent.increment() },
                            )
                        }.getOrNull()
                NotifySenderSendPort(get(), metrics)
            } else {
                notifySendPortLogger.info("Using DummySendPort (notifications disabled)")
                DummySendPort
            }
        }

        single {
            val config = get<NotificationsDispatchConfig>()
            val port = get<SendPort>()
            val implementation = port::class.simpleName ?: port::class.java.simpleName
            config.toHealth(implementation)
        }

        // High-level services/workers
        single {
            BookingService(
                get(),
                get(),
                get(),
                get(),
                get(),
                runCatching { get<MeterRegistry>() }.getOrNull(),
            )
        }
        single {
            val tracer = runCatching { get<Tracer>() }.getOrNull()
            OutboxWorker(get(), get(), tracer = tracer)
        }
    }

private object DummySendPort : SendPort {
    override suspend fun send(
        topic: String,
        payload: JsonObject,
    ): SendOutcome {
        return SendOutcome.Ok
    }
}

private val notifySendPortLogger = LoggerFactory.getLogger("NotifySenderWiring")
