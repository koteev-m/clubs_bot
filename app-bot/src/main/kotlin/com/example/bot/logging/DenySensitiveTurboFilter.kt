package com.example.bot.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.Marker
import org.slf4j.helpers.MessageFormatter

class DenySensitiveTurboFilter : TurboFilter() {
    override fun decide(
        marker: Marker?,
        logger: Logger?,
        level: Level?,
        format: String?,
        params: Array<out Any>?,
        t: Throwable?,
    ): FilterReply {
        val message =
            when {
                format.isNullOrEmpty() -> format.orEmpty()
                params.isNullOrEmpty() -> format
                else -> MessageFormatter.arrayFormat(format, params).message
            }

        return if (message != null && SENSITIVE_PATTERN.containsMatchIn(message)) {
            FilterReply.DENY
        } else {
            FilterReply.NEUTRAL
        }
    }

    companion object {
        private val SENSITIVE_PATTERN = Regex("""(?i)\b(qr|start_param|idempotencyKey)\b""")
    }
}
