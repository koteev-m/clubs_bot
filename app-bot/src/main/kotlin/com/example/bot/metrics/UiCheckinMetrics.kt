package com.example.bot.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit

private const val CHECKIN_PERCENTILE_P50 = 0.5
private const val CHECKIN_PERCENTILE_P95 = 0.95
private const val CHECKIN_PERCENTILE_P99 = 0.99

object UiCheckinMetrics {
    @PublishedApi
    @Volatile
    internal var checkinScanTimer: Timer? = null

    @Volatile private var cScanTotal: Counter? = null

    @Volatile private var cScanError: Counter? = null

    @Volatile private var cOldSecretFallback: Counter? = null

    @Volatile private var qrInvalidCounter: Counter? = null

    @Volatile private var qrExpiredCounter: Counter? = null

    @Volatile private var qrScopeMismatchCounter: Counter? = null

    @Volatile private var rotationActiveGauge: Gauge? = null

    @Volatile private var rotationDeadlineGauge: Gauge? = null

    // by-name (ручной чек-ин)
    @Volatile private var cByNameTotal: Counter? = null

    @Volatile private var cByNameError: Counter? = null

    @PublishedApi @Volatile
    internal var byNameTimer: Timer? = null

    // late override (вне окна прибытия по статусу CALLED и т.п.)
    @Volatile private var cLateOverride: Counter? = null

    fun bind(
        registry: MeterRegistry,
        rotationConfig: QrRotationConfig = QrRotationConfig.fromEnv(),
    ) {
        val rotationActive = rotationConfig.rotationActive
        rotationActiveGauge =
            registry.find("ui_checkin_rotation_active").gauge()
                ?: Gauge
                    .builder("ui_checkin_rotation_active") { if (rotationActive) 1.0 else 0.0 }
                    .description("QR rotation window is active (1) when old secret is configured")
                    .strongReference(true)
                    .register(registry)

        val rotationDeadlineSeconds = rotationConfig.rotationDeadlineEpochSeconds?.toDouble()
        rotationDeadlineGauge =
            rotationDeadlineSeconds?.let { seconds ->
                registry.find("ui_checkin_rotation_deadline_seconds").gauge()
                    ?: Gauge
                        .builder("ui_checkin_rotation_deadline_seconds") { seconds }
                        .description("Deadline for QR rotation window as epoch seconds")
                        .strongReference(true)
                        .register(registry)
            }

        cScanTotal =
            registry.find("ui.checkin.scan.total").counter()
                ?: Counter
                    .builder("ui.checkin.scan.total")
                    .description("Total check-in scan attempts")
                    .register(registry)

        cScanError =
            registry.find("ui.checkin.scan.error").counter()
                ?: Counter
                    .builder("ui.checkin.scan.error")
                    .description("Failed check-in scans (any error)")
                    .register(registry)

        cOldSecretFallback =
            registry.find("ui_checkin_old_secret_fallback_total").counter()
                ?: Counter
                    .builder("ui_checkin_old_secret_fallback_total")
                    .description("Check-in scans validated with QR_OLD_SECRET")
                    .register(registry)

        checkinScanTimer =
            registry.find("ui.checkin.scan.duration.ms").timer()
                ?: Timer
                    .builder("ui.checkin.scan.duration.ms")
                    .publishPercentiles(
                        CHECKIN_PERCENTILE_P50,
                        CHECKIN_PERCENTILE_P95,
                        CHECKIN_PERCENTILE_P99,
                    ).description("Check-in scan processing duration")
                    .register(registry)

        // by-name
        cByNameTotal =
            registry.find("ui.arrival.by_name_total").counter()
                ?: Counter
                    .builder("ui.arrival.by_name_total")
                    .description("Total manual check-in attempts (by name)")
                    .register(registry)

        cByNameError =
            registry.find("ui.checkin.by_name.error").counter()
                ?: Counter
                    .builder("ui.checkin.by_name.error")
                    .description("Failed manual check-ins (by name)")
                    .register(registry)

        byNameTimer =
            registry.find("ui.checkin.by_name.duration.ms").timer()
                ?: Timer
                    .builder("ui.checkin.by_name.duration.ms")
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .description("Manual check-in processing duration (by name)")
                    .register(registry)

        cLateOverride =
            registry.find("ui.arrival.late_override_total").counter()
                ?: Counter
                    .builder("ui.arrival.late_override_total")
                    .description("Arrivals outside arrival_window with override (e.g., CALLED)")
                    .register(registry)

        qrInvalidCounter =
            registry.find("ui_checkin_qr_invalid_total").counter()
                ?: Counter
                    .builder("ui_checkin_qr_invalid_total")
                    .description("Total invalid UI check-in QR attempts")
                    .register(registry)

        qrExpiredCounter =
            registry.find("ui_checkin_qr_expired_total").counter()
                ?: Counter
                    .builder("ui_checkin_qr_expired_total")
                    .description("Total expired UI check-in QR attempts")
                    .register(registry)

        qrScopeMismatchCounter =
            registry.find("ui_checkin_qr_scope_mismatch_total").counter()
                ?: Counter
                    .builder("ui_checkin_qr_scope_mismatch_total")
                    .description("Total UI check-in QR attempts with scope mismatch")
                    .register(registry)
    }

    fun incTotal() {
        cScanTotal?.increment()
    }

    fun incError() {
        cScanError?.increment()
    }

    fun incOldSecretFallback() {
        cOldSecretFallback?.increment()
    }

    fun incQrInvalid() {
        qrInvalidCounter?.increment()
    }

    fun incQrExpired() {
        qrExpiredCounter?.increment()
    }

    fun incQrScopeMismatch() {
        qrScopeMismatchCounter?.increment()
    }

    /** Несуспендящая версия (для не‑Ktor кода). */
    inline fun <T> timeScan(block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            checkinScanTimer?.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
        }
    }

    /** SUSPEND‑версия: используйте в Ktor‑хендлерах. */
    suspend inline fun <T> timeScanSuspend(crossinline block: suspend () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            checkinScanTimer?.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
        }
    }

    fun incByNameTotal() {
        cByNameTotal?.increment()
    }

    fun incByNameError() {
        cByNameError?.increment()
    }

    /** SUSPEND‑версия: используйте в Ktor‑хендлерах для ручного чек‑ина. */
    suspend inline fun <T> timeByNameSuspend(crossinline block: suspend () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            byNameTimer?.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
        }
    }

    fun incLateOverride() {
        cLateOverride?.increment()
    }
}
