package com.example.bot.plugins

import io.ktor.server.application.Application

fun Application.envBool(
    name: String,
    default: Boolean,
): Boolean {
    val envValue = System.getenv(name)?.toBooleanStrictOrNull()
    val configValue =
        environment.config
            .propertyOrNull("app.$name")
            ?.getString()
            ?.toBooleanStrictOrNull()
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

fun Application.miniAppBotToken(): String? {
    val primary = envString("BOT_TOKEN") ?: System.getProperty("BOT_TOKEN")
    if (!primary.isNullOrBlank()) {
        return primary
    }
    return envString("TELEGRAM_BOT_TOKEN") ?: System.getProperty("TELEGRAM_BOT_TOKEN")
}

fun Application.miniAppBotTokenRequired(): String =
    miniAppBotToken() ?: error("BOT_TOKEN or TELEGRAM_BOT_TOKEN is missing")

fun Application.miniAppBotTokenProvider(default: String = ""): () -> String =
    { miniAppBotToken() ?: default }

private fun String.toBooleanStrictOrNull(): Boolean? =
    when (lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
