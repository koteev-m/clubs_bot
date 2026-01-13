package com.example.bot.support

internal const val MAX_CLUB_NAME_LEN = 80
private val WS_REGEX = Regex("\\s+")

internal fun sanitizeClubName(raw: String?): String? =
    raw
        ?.trim()
        ?.replace(WS_REGEX, " ")
        ?.take(MAX_CLUB_NAME_LEN)
        ?.takeIf { it.isNotBlank() }

internal fun buildSupportReplyMessage(
    clubName: String?,
    replyText: String,
): String =
    buildString {
        val safeName = sanitizeClubName(clubName)
        if (safeName != null) {
            appendLine("Ответ от клуба «$safeName»")
        } else {
            appendLine("Ответ от клуба")
        }
        appendLine()
        append(replyText)
    }
