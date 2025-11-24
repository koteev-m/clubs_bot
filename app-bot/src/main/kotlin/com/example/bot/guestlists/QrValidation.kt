package com.example.bot.guestlists

internal const val MIN_QR_LEN: Int = 12
internal const val MAX_QR_LEN: Int = 512
private val GL_QR_RE = Regex("""^GL:\d+:\d+:\d+:[0-9a-fA-F]{16,}$""")

/**
 * Возвращает null, если OK; иначе строковый код ошибки: "empty_qr", "invalid_qr_length", "invalid_qr_format".
 */
internal fun quickValidateQr(qrRaw: String?): String? {
    val qr = qrRaw?.trim().orEmpty()
    if (qr.isEmpty()) return "empty_qr"
    if (qr.length !in MIN_QR_LEN..MAX_QR_LEN) return "invalid_qr_length"
    if (!GL_QR_RE.matches(qr)) return "invalid_qr_format"
    return null
}
