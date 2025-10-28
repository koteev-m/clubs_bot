package com.example.bot.notifications

/** Escape text for safe HTML display. */
fun escapeHtml(text: String): String =
    buildString {
        text.forEach { ch ->
            when (ch) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }

private val mdv2SpecialChars =
    setOf('_', '*', '[', ']', '(', ')', '~', '\\', '>', '#', '+', '-', '=', '|', '{', '}', '.', '!', '`')

/** Escape text for safe MarkdownV2 display. */
fun escapeMdV2(text: String): String =
    buildString {
        text.forEach { ch ->
            if (mdv2SpecialChars.contains(ch)) append('\\')
            append(ch)
        }
    }

fun tmplBookingCreatedRU(name: String): String = "\u2705 Бронь создана: ${escapeMdV2(name)}"

fun tmplBookingCreatedEN(name: String): String = "\u2705 Booking created: ${escapeMdV2(name)}"

fun tmplGuestArrivedRU(name: String): String = "\uD83C\uDF89 Гость ${escapeMdV2(name)} прибыл"

fun tmplGuestArrivedEN(name: String): String = "\uD83C\uDF89 Guest ${escapeMdV2(name)} arrived"

fun tmplAfishaRU(title: String): String = "<b>${escapeHtml(title)}</b>"

fun tmplAfishaEN(title: String): String = "<b>${escapeHtml(title)}</b>"

fun tmplReminderRU(text: String): String = "Напоминание: ${escapeMdV2(text)}"

fun tmplReminderEN(text: String): String = "Reminder: ${escapeMdV2(text)}"
