package com.example.bot.text

private val phoneRegex = Regex("""(?<!\d)(\+?\d[\d\s()\-]{7,}\d)""")
private val initDataRegex = Regex("""(?i)(initData\s*[=:]\s*)([^\s,;]+)""")
private val qrSecretRegex = Regex("""(?i)(qrSecret\s*[=:]\s*)([^\s,;]+)""")
private val idemRegex = Regex("""(?i)(idempotency(?:-key|_key)?\s*[=:]\s*)([^\s,;]+)""")
private val tokenLikeRegex = Regex("""(?i)(token\s*[=:]\s*)([^\s,;]+)""")

fun maskSensitiveOutgoingText(text: String): String {
    var masked = text
    masked = masked.replace(phoneRegex, "***PHONE***")
    masked = masked.replace(initDataRegex) { "${it.groupValues[1]}***" }
    masked = masked.replace(qrSecretRegex) { "${it.groupValues[1]}***" }
    masked = masked.replace(idemRegex) { "${it.groupValues[1]}***" }
    masked = masked.replace(tokenLikeRegex) { "${it.groupValues[1]}***" }
    return masked
}
