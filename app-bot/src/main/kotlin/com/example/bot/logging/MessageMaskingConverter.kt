package com.example.bot.logging

import ch.qos.logback.classic.pattern.MessageConverter
import ch.qos.logback.classic.spi.ILoggingEvent

class MessageMaskingConverter : MessageConverter() {
    override fun convert(event: ILoggingEvent): String {
        val original = event.formattedMessage ?: ""
        if (original.isEmpty()) {
            return original
        }

        return maskPersonName(maskPhone(maskTokens(original)))
    }

    companion object {
        private val PHONE_REGEX = Regex("""(?<!\w)(\+?\d[\d\s().-]{7,}\d)""")
        private val NAME_PAIR_REGEX = Regex("""(?i)\b(fullName|fio|name|guest|ФИО)(\s*=\s*)([^,;|]+)""")
        private val TOKEN_REGEX = Regex("""\b\d{6,12}:[A-Za-z0-9_-]{30,}\b""")

        fun maskTokens(text: String?): String {
            if (text.isNullOrEmpty()) return text.orEmpty()
            return TOKEN_REGEX.replace(text, "***REDACTED***")
        }

        fun maskPhone(text: String?): String {
            if (text.isNullOrEmpty()) return text.orEmpty()
            return PHONE_REGEX.replace(text) { matchResult ->
                maskPhoneNumber(matchResult.value)
            }
        }

        fun maskPersonName(text: String?): String {
            if (text.isNullOrEmpty()) return text.orEmpty()
            return NAME_PAIR_REGEX.replace(text) { matchResult ->
                val key = matchResult.groupValues[1]
                val delimiter = matchResult.groupValues[2]
                val value = matchResult.groupValues[3]
                val maskedValue = maskNameValue(value)
                "$key$delimiter$maskedValue"
            }
        }

        private fun maskPhoneNumber(rawPhone: String): String {
            val digitsCount = rawPhone.count { it.isDigit() }
            if (digitsCount == 0 || digitsCount < 9) {
                return rawPhone
            }

            val digitsToShow =
                when {
                    digitsCount <= 2 -> digitsCount
                    digitsCount <= 4 -> 2
                    else -> 3
                }

            val digitsToMask = (digitsCount - digitsToShow).coerceAtLeast(0)
            var maskedDigits = 0
            val result = StringBuilder(rawPhone.length)
            for (ch in rawPhone) {
                if (ch.isDigit()) {
                    if (maskedDigits < digitsToMask) {
                        result.append('*')
                        maskedDigits++
                    } else {
                        result.append(ch)
                    }
                } else {
                    result.append(ch)
                }
            }
            return result.toString()
        }

        private fun maskNameValue(value: String): String {
            if (value.isBlank()) return value

            val leadingWhitespace = value.takeWhile { it.isWhitespace() }
            val trailingWhitespace = value.takeLastWhile { it.isWhitespace() }
            val core = value.trim()
            if (core.isEmpty()) return value

            val maskedCore =
                core
                    .split(Regex("\\s+"))
                    .joinToString(" ") { part ->
                        if (part.isEmpty()) {
                            part
                        } else {
                            buildString {
                                append(part.first())
                                repeat(part.length - 1) { append('*') }
                            }
                        }
                    }

            return leadingWhitespace + maskedCore + trailingWhitespace
        }
    }
}
