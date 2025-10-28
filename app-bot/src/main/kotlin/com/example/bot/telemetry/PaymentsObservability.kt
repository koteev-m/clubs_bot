package com.example.bot.telemetry

import java.util.concurrent.atomic.AtomicReference

internal object PaymentsObservability {
    private val enabledOverride = AtomicReference<Boolean?>(null)
    private val debugOverride = AtomicReference<Boolean?>(null)

    fun isEnabled(): Boolean = enabledOverride.get() ?: readFlag("PAYMENTS_OBS_ENABLED", default = true)

    fun debugGaugesEnabled(): Boolean = debugOverride.get() ?: readFlag("PAYMENTS_DEBUG_GAUGES", default = false)

    fun overrideEnabled(value: Boolean?) {
        enabledOverride.set(value)
    }

    fun overrideDebug(value: Boolean?) {
        debugOverride.set(value)
    }

    fun resetOverrides() {
        enabledOverride.set(null)
        debugOverride.set(null)
    }

    private fun readFlag(name: String, default: Boolean): Boolean {
        val env = System.getenv(name)?.toBooleanStrictOrNull()
        return env ?: default
    }
}
