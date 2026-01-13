package com.example.bot.telegram

object SupportCallbacks {
    const val RATE_PREFIX = "support_rate:"
    const val MAX_BYTES = 64

    private const val RATE_UP = "up"
    private const val RATE_DOWN = "down"

    data class ParsedRate(
        val ticketId: Long,
        val rating: Int,
    )

    fun buildRate(ticketId: Long, up: Boolean): String =
        "$RATE_PREFIX$ticketId:${if (up) RATE_UP else RATE_DOWN}"

    fun fits(data: String): Boolean = data.toByteArray(Charsets.UTF_8).size < MAX_BYTES

    fun isRateCallback(data: String): Boolean = data.startsWith(RATE_PREFIX)

    fun parseRate(data: String): ParsedRate? {
        if (!data.startsWith(RATE_PREFIX)) return null
        val parts = data.removePrefix(RATE_PREFIX).split(":", limit = 3)
        if (parts.size != 2) return null
        val ticketId = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val rating =
            when (parts[1]) {
                RATE_UP -> 1
                RATE_DOWN -> -1
                else -> return null
            }
        return ParsedRate(ticketId = ticketId, rating = rating)
    }
}
