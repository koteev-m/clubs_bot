package com.example.bot.data.security

data class AuthContext(
    val userId: Long,
    val telegramUserId: Long?,
    val roles: Set<Role>,
)
