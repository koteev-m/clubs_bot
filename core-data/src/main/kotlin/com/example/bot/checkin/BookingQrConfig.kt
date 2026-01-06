package com.example.bot.checkin

import com.example.bot.data.db.envLong
import org.slf4j.LoggerFactory
import java.time.Duration

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
    companion object {
        const val ENV_QR_SECRET: String = "QR_SECRET"
        const val ENV_QR_OLD_SECRET: String = "QR_OLD_SECRET"
        const val ENV_BOOKING_QR_TTL_SECONDS: String = "BOOKING_QR_TTL_SECONDS"
        private const val DEFAULT_TTL_SECONDS: Long = 12 * 60 * 60
        private val log = LoggerFactory.getLogger(BookingQrConfig::class.java)

        fun fromEnv(): BookingQrConfig = BookingQrConfig()
    }
}
