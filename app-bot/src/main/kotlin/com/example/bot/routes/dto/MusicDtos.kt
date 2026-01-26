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
    val audioUrl: String? = null,
    val isTrackOfNight: Boolean = false,
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

@Serializable
data class MusicSetDto(
    val id: Long,
    val title: String,
    val dj: String? = null,
    val description: String? = null,
    val durationSec: Int? = null,
    val coverUrl: String? = null,
    val audioUrl: String? = null,
    val tags: List<String>? = null,
    val likesCount: Int = 0,
    val likedByMe: Boolean = false,
)
