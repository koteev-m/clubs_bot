package com.example.bot.data.client.assignments

import com.example.bot.data.client.onboarding.OnboardingFailure
import kotlinx.coroutines.runBlocking

/** The same persisted scenarios run against H2 and real PostgreSQL 16. */
internal abstract class AssignmentContractBase {
    protected abstract fun openDatabase(): AssignmentTestDatabase

    protected fun scenario(block: suspend AssignmentLifecycleFixture.() -> Unit) =
        runBlocking {
            AssignmentLifecycleFixture(openDatabase()).use { fixture ->
                fixture.prepare()
                fixture.block()
            }
        }

    protected suspend fun rejected(block: suspend () -> Unit): OnboardingFailure {
        try {
            block()
        } catch (failure: OnboardingFailure) {
            return failure
        }
        throw AssertionError("Expected an explicit foundation rejection")
    }
}
