package com.example.bot.telegram.tokens

import java.time.Duration
import java.time.Instant

object GuestsSelectCodec {
    private const val RADIX = 36
    private const val SEPARATOR = '.'
    private const val PREFIX = "g:"
    private const val EXPECTED_PARTS = 5
    private const val CLUB_INDEX = 0
    private const val START_INDEX = 1
    private const val END_INDEX = 2
    private const val TABLE_INDEX = 3
    private const val GUESTS_INDEX = 4
    private const val MIN_ID = 0L
    private const val MIN_GUESTS = 1
    private const val MAX_GUESTS = Int.MAX_VALUE

    fun encode(
        clubId: Long,
        startUtc: Instant,
        endUtc: Instant,
        tableId: Long,
        guests: Int,
    ): String {
        require(clubId >= MIN_ID) { "clubId must be non-negative" }
        require(tableId >= MIN_ID) { "tableId must be non-negative" }
        require(guests >= MIN_GUESTS) { "guests must be positive" }
        val normalizedEnd = normalizeEnd(startUtc, endUtc)
        val payload =
            listOf(
                clubId.toString(RADIX),
                startUtc.epochSecond.toString(RADIX),
                normalizedEnd.epochSecond.toString(RADIX),
                tableId.toString(RADIX),
                guests.toString(RADIX),
            ).joinToString(SEPARATOR.toString())
        return PREFIX + payload
    }

    fun decode(token: String): DecodedGuests? =
        runCatching {
            require(token.startsWith(PREFIX))
            val payload = token.substring(PREFIX.length)
            val parts = payload.split(SEPARATOR)
            require(parts.size == EXPECTED_PARTS)
            require(parts.none { it.isEmpty() })

            val clubId = parts[CLUB_INDEX].toLongOrNull(RADIX) ?: throw IllegalArgumentException("Not a number")
            val startSec = parts[START_INDEX].toLongOrNull(RADIX) ?: throw IllegalArgumentException("Not a number")
            val endSec = parts[END_INDEX].toLongOrNull(RADIX) ?: throw IllegalArgumentException("Not a number")
            val tableId = parts[TABLE_INDEX].toLongOrNull(RADIX) ?: throw IllegalArgumentException("Not a number")
            val guestsLong = parts[GUESTS_INDEX].toLongOrNull(RADIX) ?: throw IllegalArgumentException("Not a number")

            require(clubId >= MIN_ID)
            require(tableId >= MIN_ID)
            require(startSec >= MIN_ID)
            require(endSec >= MIN_ID)
            require(guestsLong >= MIN_GUESTS)
            require(guestsLong <= MAX_GUESTS)

            DecodedGuests(
                clubId = clubId,
                startUtc = Instant.ofEpochSecond(startSec),
                endUtc = Instant.ofEpochSecond(endSec),
                tableId = tableId,
                guests = guestsLong.toInt(),
            )
        }.getOrNull()

    private fun normalizeEnd(
        startUtc: Instant,
        endUtc: Instant,
    ): Instant {
        return if (endUtc.isAfter(startUtc)) {
            endUtc
        } else {
            startUtc.plus(DEFAULT_DURATION)
        }
    }

    private val DEFAULT_DURATION: Duration = Duration.ofHours(8)
}

data class DecodedGuests(
    val clubId: Long,
    val startUtc: Instant,
    val endUtc: Instant,
    val tableId: Long,
    val guests: Int,
)
