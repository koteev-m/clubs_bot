package com.example.bot.telegram.tokens

import java.time.Instant

object NightTokenCodec {
    private const val RADIX = 36
    private const val SEPARATOR = '.'

    // format: <clubBase36>.<epochSecBase36>
    fun encode(
        clubId: Long,
        startUtc: Instant,
    ): String = "${clubId.toString(RADIX)}$SEPARATOR${startUtc.epochSecond.toString(RADIX)}"

    fun decode(token: String): Pair<Long, Instant>? {
        val separatorIndex = token.indexOf(SEPARATOR)
        if (separatorIndex <= 0 || separatorIndex >= token.lastIndex) return null

        val clubPart = token.substring(0, separatorIndex)
        val secondsPart = token.substring(separatorIndex + 1)
        val club = clubPart.toLongOrNull(RADIX)
        val seconds = secondsPart.toLongOrNull(RADIX)

        return when {
            club == null || seconds == null -> null
            club < 0 || seconds < 0 -> null
            else -> club to Instant.ofEpochSecond(seconds)
        }
    }
}
