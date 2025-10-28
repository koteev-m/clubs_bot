package com.example.bot.music

import com.example.bot.routes.dto.MusicItemDto
import com.example.bot.routes.dto.MusicPlaylistDetailsDto
import com.example.bot.routes.dto.MusicPlaylistDto
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64

class MusicService(
    private val itemsRepo: MusicItemRepository,
    private val playlistsRepo: MusicPlaylistRepository,
    private val clock: Clock,
) {
    suspend fun listItems(limit: Int = 100): Pair<String, List<MusicItemDto>> {
        val items =
            itemsRepo.listActive(
                clubId = null,
                limit = limit,
                offset = 0,
                tag = null,
                q = null,
            )

        val etag =
            etagFor(
                updatedAt = itemsRepo.lastUpdatedAt(),
                count = items.size,
                seed = "items",
            )

        val payload =
            items.map {
                MusicItemDto(
                    id = it.id,
                    title = it.title,
                    artist = it.dj,
                    durationSec = it.durationSec,
                    coverUrl = it.coverUrl,
                )
            }

        return etag to payload
    }

    suspend fun listPlaylists(limit: Int = 100): Pair<String, List<MusicPlaylistDto>> {
        val playlists = playlistsRepo.listActive(limit)
        val counts = playlistsRepo.itemsCount(playlists.map { it.id })

        val etag =
            etagFor(
                updatedAt = playlistsRepo.lastUpdatedAt(),
                count = playlists.size,
                seed = "playlists",
            )

        val payload =
            playlists.map {
                MusicPlaylistDto(
                    id = it.id,
                    name = it.title,
                    description = it.description,
                    coverUrl = it.coverUrl,
                    itemsCount = counts[it.id] ?: 0,
                )
            }

        return etag to payload
    }

    suspend fun getPlaylist(id: Long): Pair<String, MusicPlaylistDetailsDto>? {
        val playlist = playlistsRepo.getFull(id) ?: return null

        val etag =
            etagFor(
                updatedAt = playlistsRepo.lastUpdatedAt(),
                count = playlist.items.size,
                seed = "playlist:$id",
            )

        val payload =
            MusicPlaylistDetailsDto(
                id = playlist.id,
                name = playlist.title,
                description = playlist.description,
                coverUrl = playlist.coverUrl,
                items =
                    playlist.items.map {
                        MusicItemDto(
                            id = it.id,
                            title = it.title,
                            artist = it.dj,
                            durationSec = it.durationSec,
                            coverUrl = it.coverUrl,
                        )
                    },
            )

        return etag to payload
    }

    private fun etagFor(
        updatedAt: Instant?,
        count: Int,
        seed: String,
    ): String {
        val window = clock.millis() / (MILLIS_IN_SECOND * SECONDS_IN_MINUTE * ETAG_WINDOW_MINUTES)
        val source = "${updatedAt ?: Instant.EPOCH}|$count|$seed|$window"
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(source.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).take(ETAG_LENGTH)
    }
}

private const val MILLIS_IN_SECOND = 1_000L
private const val SECONDS_IN_MINUTE = 60L
private const val ETAG_WINDOW_MINUTES = 5L
private const val ETAG_LENGTH = 27
