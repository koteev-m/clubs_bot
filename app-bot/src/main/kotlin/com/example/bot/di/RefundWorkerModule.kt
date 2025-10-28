package com.example.bot.di

import com.example.bot.data.booking.core.OutboxRepository
import com.example.bot.observability.MetricsProvider
import com.example.bot.payments.provider.HttpProviderRefundClient
import com.example.bot.payments.provider.ProviderRefundClient
import com.example.bot.payments.provider.ProviderRefundClientConfig
import com.example.bot.workers.RefundOutboxWorker
import com.example.bot.workers.RefundWorkerConfig
import io.micrometer.tracing.Tracer
import org.koin.dsl.module
import java.time.Clock

val refundWorkerModule =
    module {
        single { RefundWorkerConfig.fromEnv() }
        single<ProviderRefundClient> { HttpProviderRefundClient(ProviderRefundClientConfig.fromEnv()) }
        single {
            val metrics = runCatching { get<MetricsProvider>() }.getOrNull()
            val tracer = runCatching { get<Tracer>() }.getOrNull()
            val clock = runCatching { get<Clock>() }.getOrElse { Clock.systemUTC() }
            RefundOutboxWorker(
                outbox = get<OutboxRepository>(),
                client = get(),
                metrics = metrics,
                tracer = tracer,
                clock = clock,
                config = get(),
            )
        }
    }
