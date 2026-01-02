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
        val normalized = rawText.trim()
        if (normalized.isEmpty()) {
            return BulkParseResult(emptyList(), skippedDuplicates = 0)
        }
        val tokens = tokenize(normalized)
        val deduped = mutableListOf<String>()
        val seen = LinkedHashSet<String>()
        var duplicates = 0
        for (token in tokens) {
            val cleaned = collapseSpaces(token)
            if (cleaned.isEmpty()) continue
            val normalizedKey = normalizeNameKey(cleaned)
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
                    result += input.substring(start, index).trim()
                }
                start = index + 1
            }
        }
        if (start < input.length) {
            result += input.substring(start).trim()
        }
        return result
    }
}

internal fun collapseSpaces(value: String): String = value.trim().replace(WHITESPACE_RE, " ")

internal fun normalizeNameKey(value: String): String = collapseSpaces(value).lowercase(Locale.ROOT)
