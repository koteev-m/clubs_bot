package com.example.bot.testing

import com.example.bot.domain.User

object UserFactory {
    fun create(
        id: Long = 1,
        name: String = "test",
        role: String = "USER",
    ): User = User(id = id, name = name, role = role)
}
