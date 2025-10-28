@file:Suppress("MagicNumber")

package com.example.bot.routes.dto

import kotlinx.serialization.Serializable

@Serializable
data class MusicItemDto(
    val id: Long,
    val title: String,
    val artist: String? = null,
    val durationSec: Int? = null,
    val coverUrl: String? = null,
)

@Serializable
data class MusicPlaylistDto(
    val id: Long,
    val name: String,
    val description: String? = null,
    val coverUrl: String? = null,
    val itemsCount: Int = 0,
)

@Serializable
data class MusicPlaylistDetailsDto(
    val id: Long,
    val name: String,
    val description: String? = null,
    val coverUrl: String? = null,
    val items: List<MusicItemDto> = emptyList(),
)
