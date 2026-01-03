package com.example.bot.data.club

import java.util.Locale

private val SEPARATORS: Set<Char> = setOf('\n', ',', ';', '/')
private val WHITESPACE_RE = Regex("\\s+")

data class BulkParseResult(
    val entries: List<String>,
    val skippedDuplicates: Int,
)

/** Парсер для массового добавления гостей из произвольного текста. */
class GuestListBulkParser {
    fun parse(rawText: String): BulkParseResult {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            return BulkParseResult(emptyList(), skippedDuplicates = 0)
        }
        val tokens = tokenize(trimmed)
        val deduped = mutableListOf<String>()
        val seen = LinkedHashSet<String>()
        var duplicates = 0
        for (token in tokens) {
            val cleaned = collapseSpaces(token)
            if (cleaned.isEmpty()) continue
            val normalizedKey = cleaned.lowercase(Locale.ROOT)
            if (!seen.add(normalizedKey)) {
                duplicates += 1
                continue
            }
            deduped += cleaned
        }
        return BulkParseResult(deduped, duplicates)
    }

    private fun tokenize(input: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        for (index in input.indices) {
            val char = input[index]
            if (char in SEPARATORS) {
                if (start < index) {
                    val token = input.substring(start, index).trim()
                    if (token.isNotEmpty()) {
                        result += token
                    }
                }
                start = index + 1
            }
        }
        if (start < input.length) {
            val token = input.substring(start).trim()
            if (token.isNotEmpty()) {
                result += token
            }
        }
        return result
    }
}

internal fun collapseSpaces(value: String): String = value.trim().replace(WHITESPACE_RE, " ")

internal fun normalizeNameKey(value: String): String = collapseSpaces(value).lowercase(Locale.ROOT)
