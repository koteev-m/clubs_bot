package com.example.bot.data.club

import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.ParsedGuest
import com.example.bot.club.RejectedRow
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/** Result of parsing a guest list import file. */
data class GuestListParseResult(val rows: List<ParsedGuest>, val rejected: List<RejectedRow>)

/**
 * Parser for guest list CSV/TSV files with header `name,phone,guests_count,notes`.
 */
class GuestListCsvParser {
    fun parse(input: InputStream): GuestListParseResult {
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
            val headerLine = requireNotNull(reader.readLine()) { "Empty import file" }
            val delimiter = detectDelimiter(headerLine)
            val headers = headerLine.split(delimiter).map { it.trim().lowercase() }
            require(headers == EXPECTED_HEADER) { "Invalid header: $headerLine" }
            val parsed = mutableListOf<ParsedGuest>()
            val rejected = mutableListOf<RejectedRow>()
            var lineNumber = 1
            reader.lineSequence().forEach { rawLine ->
                lineNumber += 1
                val line = rawLine.trimEnd()
                if (line.isEmpty()) {
                    return@forEach
                }
                val tokens = line.split(delimiter, ignoreCase = false, limit = COLUMN_COUNT)
                if (tokens.size < MIN_COLUMNS) {
                    rejected += RejectedRow(lineNumber, "Expected at least $MIN_COLUMNS columns")
                    return@forEach
                }
                val name = tokens[NAME_INDEX].trim()
                val phoneRaw = tokens.getOrNull(PHONE_INDEX)?.trim()
                val guestsToken = tokens.getOrNull(GUESTS_INDEX)?.trim()
                val notes = tokens.getOrNull(NOTES_INDEX)?.trim()?.takeIf { it.isNotEmpty() }
                val guestsCount = guestsToken?.toIntOrNull()
                if (guestsCount == null) {
                    rejected += RejectedRow(lineNumber, "guests_count must be an integer")
                    return@forEach
                }
                val validation = validateEntryInput(name, phoneRaw, guestsCount, notes, DEFAULT_STATUS)
                when (validation) {
                    is EntryValidationOutcome.Invalid -> rejected += RejectedRow(lineNumber, validation.reason)
                    is EntryValidationOutcome.Valid ->
                        parsed +=
                            ParsedGuest(
                                lineNumber = lineNumber,
                                name = validation.name,
                                phone = validation.phone,
                                guestsCount = validation.guestsCount,
                                notes = validation.notes,
                            )
                }
            }
            return GuestListParseResult(parsed.toList(), rejected.toList())
        }
    }

    private fun detectDelimiter(header: String): Char {
        return when {
            header.contains('\t') -> '\t'
            header.contains(',') -> ','
            else -> error("Unsupported delimiter in header")
        }
    }

    companion object {
        private val EXPECTED_HEADER = listOf("name", "phone", "guests_count", "notes")
        private const val COLUMN_COUNT: Int = 4
        private const val MIN_COLUMNS: Int = 3
        private const val NAME_INDEX: Int = 0
        private const val PHONE_INDEX: Int = 1
        private const val GUESTS_INDEX: Int = 2
        private const val NOTES_INDEX: Int = 3
        private val DEFAULT_STATUS = GuestListEntryStatus.PLANNED
    }
}
