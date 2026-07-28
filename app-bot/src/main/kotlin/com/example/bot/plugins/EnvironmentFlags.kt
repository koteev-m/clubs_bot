package com.example.bot.plugins

import io.ktor.server.application.Application

internal fun Application.resolveEnv(name: String): String? =
    resolveEnvValue(
        configValue = environment.config.propertyOrNull("app.env.$name")?.getString(),
        processValue = System.getenv(name),
    )

internal enum class BlankConfigSemantics {
    ABSENT_FALLBACK_TO_PROCESS,
    EXPLICIT_ABSENT_NO_FALLBACK,
}

internal fun resolveEnvValue(
    configValue: String?,
    processValue: String?,
): String? =
    resolveEnvValue(
        configValue = configValue,
        hasConfigValue = configValue != null,
        processValue = processValue,
        blankConfigSemantics = BlankConfigSemantics.ABSENT_FALLBACK_TO_PROCESS,
    )

internal fun resolveEnvValue(
    configValue: String?,
    hasConfigValue: Boolean,
    processValue: String?,
    blankConfigSemantics: BlankConfigSemantics,
): String? {
    val configured = configValue.orEmpty().trim()
    return when {
        !hasConfigValue -> processValue
        configured.isNotBlank() -> configured
        blankConfigSemantics == BlankConfigSemantics.ABSENT_FALLBACK_TO_PROCESS -> processValue
        else -> null
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
