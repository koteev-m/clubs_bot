package com.example.bot.logging

fun maskQrToken(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val trimmed = raw.trim()
    if (trimmed.startsWith("GL:")) {
        return "GL:***"
    }
    if (trimmed.startsWith("INV:")) {
        return "INV:***"
    }
    return when {
        trimmed.length <= 6 -> "***"
        else -> trimmed.take(6) + "...[masked]"
    }
}
