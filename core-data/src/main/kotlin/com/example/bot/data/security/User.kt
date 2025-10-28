package com.example.bot.data.security

/**
 * Representation of an authenticated user stored in the database.
 */
data class User(
    val id: Long,
    val telegramId: Long,
    val username: String?,
)
