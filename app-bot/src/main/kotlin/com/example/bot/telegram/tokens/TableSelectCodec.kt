package com.example.bot.telegram.tokens

import java.time.Instant

object TableSelectCodec {
    private const val RADIX = 36
    private const val SEPARATOR = '.'
    private const val PREFIX = "tbl:"
    private const val EXPECTED_PARTS = 4

    fun encode(
        clubId: Long,
        startUtc: Instant,
        endUtc: Instant,
        tableId: Long,
    ): String {
        val clubPart = clubId.toString(RADIX)
        val startPart = startUtc.epochSecond.toString(RADIX)
        val endPart = endUtc.epochSecond.toString(RADIX)
        val tablePart = tableId.toString(RADIX)
        val payload = listOf(clubPart, startPart, endPart, tablePart).joinToString(SEPARATOR.toString())
        return PREFIX + payload
    }

    fun decode(token: String): DecodedTable? =
        runCatching {
            require(token.startsWith(PREFIX))
            val payload = token.substring(PREFIX.length)
            val parts = payload.split(SEPARATOR)
            require(parts.size == EXPECTED_PARTS)
            require(parts.none { it.isEmpty() })

            val values =
                parts.map { part ->
                    part.toLongOrNull(RADIX) ?: throw IllegalArgumentException("Not a number")
                }
            require(values.none { it < 0 })

            DecodedTable(
                clubId = values[0],
                startUtc = Instant.ofEpochSecond(values[1]),
                endUtc = Instant.ofEpochSecond(values[2]),
                tableId = values[3],
            )
        }.getOrNull()
}

data class DecodedTable(val clubId: Long, val startUtc: Instant, val endUtc: Instant, val tableId: Long)
