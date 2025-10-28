package com.example.testing.support

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Helper around [runTest] to avoid using blocking calls in coroutine tests.
 */
fun runBlockingTest(block: suspend TestScope.() -> Unit) = runTest { block() }

/**
 * JUnit extension that fails a test if [forbiddenSleep] was invoked.
 */
class NoThreadSleepExtension :
    BeforeEachCallback,
    AfterEachCallback {
    override fun beforeEach(context: ExtensionContext) {
        slept.set(false)
    }

    override fun afterEach(context: ExtensionContext) {
        if (slept.get()) {
            throw AssertionError("Thread.sleep is not allowed in tests")
        }
    }

    companion object {
        internal val slept = AtomicBoolean(false)
    }
}

/**
 * Wrapper that marks usage of [Thread.sleep]; use only in rare cases.
 */
fun forbiddenSleep(millis: Long) {
    NoThreadSleepExtension.slept.set(true)
    Thread.sleep(millis)
}
