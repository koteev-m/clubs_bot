package com.example.notifications.util

import com.example.notifications.support.FakeTimeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Verifies behaviour of [FakeTimeSource]. */
class FakeTimeSourceTest {
    @Test
    fun `advance and set adjust current time`() {
        val ts = FakeTimeSource(100L)
        assertEquals(100L, ts.nowMs())
        ts.advance(50L)
        assertEquals(150L, ts.nowMs())
        ts.set(20L)
        assertEquals(20L, ts.nowMs())
    }
}
