package com.example.bot.config

import java.util.Locale

private val envOverrides = ThreadLocal<Map<String, String?>>()

internal fun env(name: String): String? {
    val overrides = envOverrides.get()
    if (overrides != null && overrides.containsKey(name)) {
        return overrides[name]
    }
    return System.getenv(name)
}

internal inline fun <T> withEnv(
    vararg overrides: Pair<String, String?>,
    block: () -> T,
): T {
    val map = linkedMapOf<String, String?>()
    overrides.forEach { (key, value) -> map[key] = value }
    return withEnv(map, block)
}

internal inline fun <T> withEnv(
    overrides: Map<String, String?>,
    block: () -> T,
): T {
    val previous = envOverrides.get()
    val merged =
        if (previous != null) {
            previous.toMutableMap().apply { putAll(overrides) }
        } else {
            overrides.toMutableMap()
        }
    envOverrides.set(merged)
    return try {
        block()
    } finally {
        if (previous == null) {
            envOverrides.remove()
        } else {
            envOverrides.set(previous)
        }
    }
}

internal fun envRequired(name: String): String = env(name) ?: error("ENV $name is required")

internal fun envInt(
    name: String,
    default: Int? = null,
): Int = env(name)?.toIntOrNull() ?: default ?: error("ENV $name is required (int)")

internal fun envLong(
    name: String,
    default: Long? = null,
): Long = env(name)?.toLongOrNull() ?: default ?: error("ENV $name is required (long)")

internal fun envBool(
    name: String,
    default: Boolean? = null,
): Boolean =
    env(name)?.let { it.toBooleanStrictOrNull() ?: error("ENV $name must be boolean") }
        ?: default ?: error("ENV $name is required (boolean)")

internal fun envList(
    name: String,
    delimiter: Char = ',',
    trim: Boolean = true,
): List<String>? = env(name)?.split(delimiter)?.map { if (trim) it.trim() else it }?.filter { it.isNotEmpty() }

private fun String.toBooleanStrictOrNull(): Boolean? =
    when (this.lowercase(Locale.ROOT)) {
        "true" -> true
        "false" -> false
        else -> null
    }

/** Безопасная маскировка секретов при логировании */
internal fun maskSecret(
    value: String?,
    head: Int = 3,
    tail: Int = 4,
): String =
    when {
        value == null -> "null"
        value.length <= head + tail -> "*".repeat(value.length)
        else -> value.take(head) + "*".repeat(value.length - head - tail) + value.takeLast(tail)
    }
