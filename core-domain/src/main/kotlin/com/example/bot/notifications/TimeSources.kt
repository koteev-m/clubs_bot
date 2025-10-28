package com.example.bot.notifications

import java.util.concurrent.atomic.AtomicLong

interface TimeSource {
    fun nowMs(): Long
}

object SystemTimeSource : TimeSource {
    override fun nowMs(): Long = System.currentTimeMillis()
}

class FakeTimeSource(startMs: Long = 0L) : TimeSource {
    private val t = AtomicLong(startMs)

    override fun nowMs(): Long = t.get()

    fun advance(ms: Long) {
        t.addAndGet(ms)
    }
}
