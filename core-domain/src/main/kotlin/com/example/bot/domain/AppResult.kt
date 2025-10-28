package com.example.bot.domain

sealed class AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>()

    data class Err(val reason: String) : AppResult<Nothing>()
}
