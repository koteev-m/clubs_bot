package com.example.bot.data.checkin

import java.util.UUID

internal data class CanonicalBookingSubject(
    val bookingId: UUID,
    val subjectId: String,
)

internal fun canonicalizeBookingSubject(input: String): CanonicalBookingSubject? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    val numeric = trimmed.toLongOrNull()
    if (numeric != null) {
        if (numeric <= 0) return null
        val bookingId = runCatching { UUID(0L, numeric) }.getOrNull() ?: return null
        return CanonicalBookingSubject(bookingId, numeric.toString())
    }

    val uuid = runCatching { UUID.fromString(trimmed) }.getOrNull() ?: return null
    if (uuid.mostSignificantBits != 0L || uuid.leastSignificantBits <= 0) return null

    val numericFromUuid = uuid.leastSignificantBits
    return CanonicalBookingSubject(UUID(0L, numericFromUuid), numericFromUuid.toString())
}
