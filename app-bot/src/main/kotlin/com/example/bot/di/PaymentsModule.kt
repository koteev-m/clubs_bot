package com.example.bot.di

import com.example.bot.booking.BookingService
import com.example.bot.data.repo.PaymentsRepositoryImpl
import com.example.bot.observability.MetricsProvider
import com.example.bot.payments.PaymentsRepository
import com.example.bot.payments.finalize.DefaultPaymentsFinalizeService
import com.example.bot.payments.finalize.PaymentsFinalizeService
import com.example.bot.data.booking.core.PaymentsBookingRepository
import io.micrometer.tracing.Tracer
import org.koin.dsl.module

val paymentsModule = module {
    single<PaymentsRepository> { PaymentsRepositoryImpl(get()) }
    single<PaymentsFinalizeService> {
        DefaultPaymentsFinalizeService(
            bookingService = get<BookingService>(),
            paymentsRepository = get(),
        )
    }
    single<PaymentsService> {
        val metricsProvider = runCatching { get<MetricsProvider>() }.getOrNull()
        val tracer = runCatching { get<Tracer>() }.getOrNull()
        DefaultPaymentsService(
            finalizeService = get(),
            paymentsRepository = get<PaymentsRepository>(),
            bookingRepository = get<PaymentsBookingRepository>(),
            metricsProvider = metricsProvider,
            tracer = tracer,
        )
    }
}
