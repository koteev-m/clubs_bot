package com.example.testing.support

import java.util.concurrent.atomic.AtomicLong

/** Simple pluggable time source for tests. */
interface TimeSource {
    fun nowMs(): Long
}

class FakeTimeSource(startMs: Long = 0L) : TimeSource {
    private val t = AtomicLong(startMs)

    override fun nowMs(): Long = t.get()

    fun advance(ms: Long) {
        t.addAndGet(ms)
    }

    fun set(ms: Long) {
        t.set(ms)
    }
}
