package com.example.notifications.support

import com.example.bot.notifications.TimeSource
import java.util.concurrent.atomic.AtomicLong

/** Deterministic [TimeSource] for tests. */
class FakeTimeSource(startMs: Long = 0L) : TimeSource {
    private val now = AtomicLong(startMs)

    override fun nowMs(): Long = now.get()

    fun advance(ms: Long): Long = now.addAndGet(ms)

    fun set(ms: Long) = now.set(ms)
}
