package com.example.bot.plugins

import io.ktor.server.application.Application

internal fun Application.resolveEnv(name: String): String? {
    val fromConfig = environment.config.propertyOrNull("app.env.$name")?.getString()
    return when {
        !fromConfig.isNullOrBlank() -> fromConfig
        else -> System.getenv(name)
    }
}

internal fun Application.resolveFlag(
    name: String,
    default: Boolean,
): Boolean {
    val fromConfig = environment.config.propertyOrNull("app.flags.$name")?.getString()
    val configValue = fromConfig?.toBooleanStrictOrNull()
    val envValue = System.getenv(name)?.toBooleanStrictOrNull()
    return configValue ?: envValue ?: default
}

internal fun Application.resolveInt(name: String): Int? = resolveEnv(name)?.toIntOrNull()

internal fun Application.resolveLong(name: String): Long? = resolveEnv(name)?.toLongOrNull()

internal fun Application.resolveDouble(name: String): Double? = resolveEnv(name)?.toDoubleOrNull()

private fun String.toBooleanStrictOrNull(): Boolean? =
    when (lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
