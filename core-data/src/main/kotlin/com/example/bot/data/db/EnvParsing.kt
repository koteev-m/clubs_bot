package com.example.bot.data.db

import org.slf4j.Logger

internal fun envInt(
    name: String,
    default: Int,
    min: Int? = null,
    max: Int? = null,
    envProvider: (String) -> String?,
    log: Logger,
): Int {
    val raw = envProvider(name) ?: return default
    val parsed = raw.toIntOrNull()
    if (parsed == null) {
        log.warn("Invalid int value for {}='{}', using default {}", name, raw, default)
        return default
    }
    if (min != null && parsed < min || max != null && parsed > max) {
        log.warn(
            "Value for {}={} is outside allowed range [{}; {}], using default {}",
            name,
            parsed,
            min ?: "-",
            max ?: "-",
            default,
        )
        return default
    }
    return parsed
}

internal fun envLong(
    name: String,
    default: Long,
    min: Long? = null,
    max: Long? = null,
    envProvider: (String) -> String?,
    log: Logger,
): Long {
    val raw = envProvider(name) ?: return default
    val parsed = raw.toLongOrNull()
    if (parsed == null) {
        log.warn("Invalid long value for {}='{}', using default {}", name, raw, default)
        return default
    }
    if (min != null && parsed < min || max != null && parsed > max) {
        log.warn(
            "Value for {}={} is outside allowed range [{}; {}], using default {}",
            name,
            parsed,
            min ?: "-",
            max ?: "-",
            default,
        )
        return default
    }
    return parsed
}
