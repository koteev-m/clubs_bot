package com.example.bot.data.security

/**
 * Repository for accessing user records.
 */
interface UserRepository {
    /**
     * Returns a user by Telegram identifier or null when not registered.
     */
    suspend fun getByTelegramId(id: Long): User?
}
