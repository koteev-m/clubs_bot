package com.example.bot.domain

import kotlinx.serialization.Serializable

@Serializable
data class User(val id: Long, val name: String, val role: String) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
    }
}
