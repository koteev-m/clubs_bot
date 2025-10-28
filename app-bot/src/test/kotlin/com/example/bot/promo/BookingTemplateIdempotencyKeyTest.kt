package com.example.bot.promo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class BookingTemplateIdempotencyKeyTest {
    private val slot = Instant.parse("2025-05-01T18:00:00Z")

    @Test
    fun `same input yields deterministic key`() {
        val first = templateIdempotencyKey(12L, 34L, slot, 56L)
        val second = templateIdempotencyKey(12L, 34L, slot, 56L)
        assertEquals(first, second)
    }

    @Test
    fun `changing any attribute alters key`() {
        val base = templateIdempotencyKey(12L, 34L, slot, 56L)
        val differentTemplate = templateIdempotencyKey(99L, 34L, slot, 56L)
        val differentPromoter = templateIdempotencyKey(12L, 77L, slot, 56L)
        val differentSlot = templateIdempotencyKey(12L, 34L, slot.plusSeconds(3_600), 56L)
        val differentTable = templateIdempotencyKey(12L, 34L, slot, 57L)

        assertNotEquals(base, differentTemplate)
        assertNotEquals(base, differentPromoter)
        assertNotEquals(base, differentSlot)
        assertNotEquals(base, differentTable)
    }
}
