package com.example.bot.data.club

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GuestListBulkParserTest {
    private val parser = GuestListBulkParser()

    @Test
    fun `bulk parser handles russian names and newlines`() {
        val result = parser.parse("Иван Иванов / Пётр Петров\nСаша")

        assertEquals(listOf("Иван Иванов", "Пётр Петров", "Саша"), result.entries)
        assertEquals(0, result.skippedDuplicates)
    }

    @Test
    fun `bulk parser splits, normalizes and deduplicates`() {
        val raw = " Alice, Bob /Carol;  alice\nBOB /  Dana   /dana "

        val result = parser.parse(raw)

        assertEquals(listOf("Alice", "Bob", "Carol", "Dana"), result.entries)
        assertEquals(3, result.skippedDuplicates)
    }

    @Test
    fun `bulk parser handles slashes and repeated delimiters`() {
        val slashWithoutSpaces = parser.parse("Alice/Bob")
        assertEquals(listOf("Alice", "Bob"), slashWithoutSpaces.entries)
        assertEquals(0, slashWithoutSpaces.skippedDuplicates)

        val slashWithSpaces = parser.parse("Alice / Bob")
        assertEquals(listOf("Alice", "Bob"), slashWithSpaces.entries)
        assertEquals(0, slashWithSpaces.skippedDuplicates)

        val consecutiveSeparators = parser.parse("Alice,,Bob;\n\nCarol")
        assertEquals(listOf("Alice", "Bob", "Carol"), consecutiveSeparators.entries)
        assertEquals(0, consecutiveSeparators.skippedDuplicates)
    }

    @Test
    fun `bulk parser ignores blank tokens and trailing separators`() {
        val result = parser.parse("  Alice /  / Bob //  \n/  ")

        assertEquals(listOf("Alice", "Bob"), result.entries)
        assertEquals(0, result.skippedDuplicates)
    }

    @Test
    fun `bulk parser deduplicates after collapsing spaces`() {
        val result = parser.parse("Иван  Иванов / иван иванов")

        assertEquals(listOf("Иван Иванов"), result.entries)
        assertEquals(1, result.skippedDuplicates)
    }

    @Test
    fun `bulk parser counts duplicates case-insensitively`() {
        val result = parser.parse("Alice, alice,ALICE")

        assertEquals(listOf("Alice"), result.entries)
        assertEquals(2, result.skippedDuplicates)
    }
}
