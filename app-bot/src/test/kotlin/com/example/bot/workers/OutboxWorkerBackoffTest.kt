package com.example.bot.workers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.random.Random

class OutboxWorkerBackoffTest {
    @Test
    fun `exponential backoff doubles with each attempt until shift cap`() {
        val base = Duration.ofMillis(200)
        val max = Duration.ofMillis(10_000)
        val jitter = Duration.ZERO
        val maxShift = 5
        val random = Random(0)

        val first = computeBackoffDelay(1, base, max, jitter, maxShift, random)
        val second = computeBackoffDelay(2, base, max, jitter, maxShift, random)
        val third = computeBackoffDelay(3, base, max, jitter, maxShift, random)
        val capped = computeBackoffDelay(10, base, max, jitter, maxShift, random)

        assertEquals(base, first)
        assertEquals(base.multipliedBy(2), second)
        assertEquals(base.multipliedBy(4), third)
        assertEquals(base.multipliedBy(32), capped)
    }

    @Test
    fun `jitter keeps value within allowed window`() {
        val base = Duration.ofMillis(500)
        val max = Duration.ofMillis(10_000)
        val jitter = Duration.ofMillis(100)
        val maxShift = 3
        val random = Random(42)

        val delay = computeBackoffDelay(1, base, max, jitter, maxShift, random)
        val lowerBound = base.minus(jitter)
        val upperBound = base.plus(jitter)
        assertTrue(!delay.isNegative)
        assertTrue(delay >= lowerBound)
        assertTrue(delay <= upperBound)
    }

    @Test
    fun `backoff is capped by maximum`() {
        val base = Duration.ofMillis(500)
        val max = Duration.ofMillis(1500)
        val jitter = Duration.ZERO
        val maxShift = 10
        val random = Random(0)

        val delay = computeBackoffDelay(10, base, max, jitter, maxShift, random)
        assertEquals(max, delay)
    }
}
