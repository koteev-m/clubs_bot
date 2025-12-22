package com.example.bot.logging

fun maskQrToken(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val trimmed = raw.trim()
    if (trimmed.startsWith("GL:")) {
        return "***"
    }
    if (trimmed.startsWith("INV:")) {
        return "***"
    }
    return when {
        trimmed.length <= 6 -> "***"
        else -> trimmed.take(6) + "...[masked]"
    }
}
