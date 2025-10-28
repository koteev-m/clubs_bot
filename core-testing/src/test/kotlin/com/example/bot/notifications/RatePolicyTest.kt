package com.example.bot.notifications

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan

class RatePolicyTest :
    StringSpec({
        "global and chat buckets" {
            val ts = FakeTimeSource(0)
            val policy = DefaultRatePolicy(globalRps = 5, chatRps = 2.0, timeSource = ts)

            repeat(5) { policy.acquireGlobal(now = ts.nowMs()).granted.shouldBeTrue() }
            val sixth = policy.acquireGlobal(now = ts.nowMs())
            sixth.granted.shouldBeFalse()
            sixth.retryAfterMs.shouldBeGreaterThanOrEqual(150)
            sixth.retryAfterMs.shouldBeLessThan(250)

            policy.acquireChat(42).granted.shouldBeTrue()
            policy.acquireChat(42).granted.shouldBeTrue()
            val third = policy.acquireChat(42)
            third.granted.shouldBeFalse()
            third.retryAfterMs.shouldBeGreaterThanOrEqual(450)
            third.retryAfterMs.shouldBeLessThan(550)

            policy.on429(42, 1500)
            ts.advance(1000)
            policy.acquireChat(42).granted.shouldBeFalse()
            policy.acquireGlobal().granted.shouldBeFalse()
        }
    })
