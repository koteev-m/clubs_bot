package com.example.bot.gamification

import java.util.Locale

enum class GamificationMetricType(
    private val aliases: Set<String>,
) {
    VISITS(setOf("VISITS", "VISIT", "STAMPS")),
    EARLY_VISITS(setOf("EARLY_VISITS", "EARLY_VISIT", "EARLY")),
    TABLE_NIGHTS(setOf("TABLE_NIGHTS", "TABLE_VISITS", "TABLE_VISIT", "TABLE")),
    ;

    fun matches(value: String): Boolean = value.trim().uppercase(Locale.ROOT) in aliases

    companion object {
        fun isAllowed(value: String): Boolean = entries.any { it.matches(value) }

        fun allowedValues(): Set<String> = entries.flatMap { it.aliases }.toSet()
    }
}

enum class BadgeConditionType(
    private val aliases: Set<String>,
) {
    VISITS(setOf("VISITS", "VISIT", "STAMPS")),
    EARLY_VISITS(setOf("EARLY_VISITS", "EARLY_VISIT", "EARLY")),
    TABLE_NIGHTS(setOf("TABLE_NIGHTS", "TABLE_VISITS", "TABLE_VISIT", "TABLE")),
    ;

    fun matches(value: String): Boolean = value.trim().uppercase(Locale.ROOT) in aliases

    companion object {
        fun isAllowed(value: String): Boolean = entries.any { it.matches(value) }

        fun allowedValues(): Set<String> = entries.flatMap { it.aliases }.toSet()
    }
}
