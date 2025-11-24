package com.example.bot.plugins

internal val FINGERPRINT_RE = Regex(""".*[._-][0-9a-fA-F]{8,}\.[A-Za-z0-9]+$""")

internal fun extractFingerprintComponent(file: String): String? =
    Regex("""[0-9a-fA-F]{8,}""").find(file)?.value
