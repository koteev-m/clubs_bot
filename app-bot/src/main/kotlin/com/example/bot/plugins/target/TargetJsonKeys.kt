package com.example.bot.plugins.target

import kotlinx.serialization.json.Json

/** Rejects duplicate JSON object keys (including escaped spellings); Json validates full syntax afterwards. */
internal class TargetJsonKeys private constructor(
    private val text: String,
) {
    private var offset = 0

    private fun value(depth: Int) {
        require(depth <= MAX_DEPTH)
        whitespace()
        when (text.getOrNull(offset)) {
            '{' -> objectValue(depth)
            '[' -> arrayValue(depth)
            '"' -> stringValue()
            else -> primitive()
        }
    }

    private fun objectValue(depth: Int) {
        offset++
        val keys = mutableSetOf<String>()
        whitespace()
        if (consume('}')) return
        do {
            whitespace()
            val key = Json.decodeFromString<String>(stringValue())
            require(keys.add(key))
            whitespace()
            require(consume(':'))
            value(depth + 1)
            whitespace()
            if (consume('}')) return
            require(consume(','))
        } while (true)
    }

    private fun arrayValue(depth: Int) {
        offset++
        whitespace()
        if (consume(']')) return
        do {
            value(depth + 1)
            whitespace()
            if (consume(']')) return
            require(consume(','))
        } while (true)
    }

    private fun stringValue(): String {
        val start = offset
        require(consume('"'))
        while (offset < text.length) {
            when (text[offset++]) {
                '\\' -> offset++
                '"' -> return text.substring(start, offset)
            }
        }
        error("Invalid TARGET JSON")
    }

    private fun primitive() {
        val start = offset
        while (offset < text.length && text[offset] !in ",]} \t\n\r") offset++
        require(offset > start)
    }

    private fun whitespace() {
        while (text.getOrNull(offset) in listOf(' ', '\t', '\n', '\r')) offset++
    }

    private fun consume(character: Char): Boolean = (text.getOrNull(offset) == character).also { if (it) offset++ }

    companion object {
        private const val MAX_DEPTH = 32

        fun unique(text: String): Boolean {
            val parser = TargetJsonKeys(text)
            parser.value(0)
            parser.whitespace()
            return parser.offset == text.length
        }
    }
}
