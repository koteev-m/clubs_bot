package com.example.bot.checkin

import com.example.bot.data.db.envLong
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

data class BookingQrConfig(
    val secret: String = System.getenv(ENV_QR_SECRET) ?: "",
    val oldSecret: String? = System.getenv(ENV_QR_OLD_SECRET),
    val ttl: Duration = Duration.ofSeconds(
        envLong(
            ENV_BOOKING_QR_TTL_SECONDS,
            DEFAULT_TTL_SECONDS,
            min = 1,
            envProvider = System::getenv,
            log = log,
        ),
    ),
) {
    init {
        if (secret.isBlank() && (oldSecret == null || oldSecret.isBlank())) {
            if (warnedOnce.compareAndSet(false, true)) {
                log.warn("QR secrets are missing or empty: {} and {} are not set", ENV_QR_SECRET, ENV_QR_OLD_SECRET)
            }
        }
    }

    companion object {
        const val ENV_QR_SECRET: String = "QR_SECRET"
        const val ENV_QR_OLD_SECRET: String = "QR_OLD_SECRET"
        const val ENV_BOOKING_QR_TTL_SECONDS: String = "BOOKING_QR_TTL_SECONDS"
        private const val DEFAULT_TTL_SECONDS: Long = 12 * 60 * 60
        private val warnedOnce = AtomicBoolean(false)
        private val log = LoggerFactory.getLogger(BookingQrConfig::class.java)

        fun fromEnv(): BookingQrConfig = BookingQrConfig()
    }
}
