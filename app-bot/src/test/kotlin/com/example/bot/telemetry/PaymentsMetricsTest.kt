package com.example.bot.telemetry

import com.example.bot.observability.MetricsProvider
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class PaymentsMetricsTest : StringSpec({
    lateinit var metricsProvider: MetricsProvider

    beforeTest {
        PaymentsMetrics.resetForTest()
        metricsProvider = MetricsProvider(MetricsProvider.simpleRegistry())
    }

    afterTest {
        PaymentsMetrics.resetForTest()
    }

    "increments counters for idempotent outbox and errors" {
        PaymentsMetrics.incrementIdempotentHit(metricsProvider, PaymentsMetrics.Path.Cancel)
        PaymentsMetrics.incrementOutboxEnqueued(metricsProvider, "payment.finalized")
        PaymentsMetrics.incrementErrors(
            metricsProvider,
            PaymentsMetrics.Path.Refund,
            PaymentsMetrics.ErrorKind.Unprocessable,
        )

        metricsProvider.registry
            .find("payments.idempotent.hit")
            .tags("path", "cancel")
            .counter()!!.count() shouldBeExactly 1.0

        metricsProvider.registry
            .find("payments.outbox.enqueued")
            .tags("event", "payment.finalized")
            .counter()!!.count() shouldBeExactly 1.0

        metricsProvider.registry
            .find("payments.errors")
            .tags("path", "refund", "kind", "unprocessable")
            .counter()!!.count() shouldBeExactly 1.0
    }

    "records timer samples with result tags" {
        val sample = PaymentsMetrics.timer(metricsProvider, PaymentsMetrics.Path.Cancel, PaymentsMetrics.Source.MiniApp)
        sample.record(PaymentsMetrics.Result.Ok)

        metricsProvider.registry
            .find("payments.cancel.duration")
            .tags("path", "cancel", "result", "ok", "source", "miniapp")
            .timer()!!.count() shouldBe 1
    }

    "no-op when observability disabled" {
        PaymentsMetrics.overrideObservabilityEnabledForTest(false)

        PaymentsMetrics.timer(metricsProvider, PaymentsMetrics.Path.Cancel).record(PaymentsMetrics.Result.Ok)
        PaymentsMetrics.incrementErrors(
            metricsProvider,
            PaymentsMetrics.Path.Cancel,
            PaymentsMetrics.ErrorKind.Unexpected,
        )

        metricsProvider.registry
            .find("payments.cancel.duration")
            .timer()
            .shouldBeNull()

        metricsProvider.registry
            .find("payments.errors")
            .counter()
            .shouldBeNull()
    }
})
