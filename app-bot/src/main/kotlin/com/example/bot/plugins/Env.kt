package com.example.bot.plugins

import io.ktor.server.application.Application

fun Application.envBool(
    name: String,
    default: Boolean,
): Boolean {
    val envValue = System.getenv(name)?.toBooleanStrictOrNull()
    val configValue = environment.config.propertyOrNull("app.$name")?.getString()?.toBooleanStrictOrNull()
    return envValue ?: configValue ?: default
}

fun Application.envString(
    name: String,
    default: String? = null,
): String? {
    val envValue = System.getenv(name)
    val configValue = environment.config.propertyOrNull("app.$name")?.getString()
    return envValue ?: configValue ?: default
}

private fun String.toBooleanStrictOrNull(): Boolean? =
    when (lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
