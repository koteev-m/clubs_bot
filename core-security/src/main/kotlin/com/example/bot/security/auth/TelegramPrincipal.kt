package com.example.bot.security.auth

/**
 * Аутентифицированный Telegram-пользователь.
 *
 * @property userId Telegram numeric identifier
 * @property username Optional Telegram username
 */
data class TelegramPrincipal(
    val userId: Long,
    val username: String?,
)
