package com.example.bot.music

import com.example.bot.routes.dto.MusicItemDto
import com.example.bot.routes.dto.MusicPlaylistDetailsDto
import com.example.bot.routes.dto.MusicPlaylistDto
import com.example.bot.routes.dto.MusicSetDto
import com.example.bot.music.TrackOfNightRepository
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64

class MusicService(
    private val itemsRepo: MusicItemRepository,
    private val playlistsRepo: MusicPlaylistRepository,
    private val likesRepository: MusicLikesRepository,
    private val clock: Clock,
    private val trackOfNightRepository: TrackOfNightRepository,
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

        val trackOfNight = trackOfNightRepository.currentGlobal()
        val trackOfNightId = trackOfNight?.trackId

        val etag =
            etagFor(
                updatedAt = listOfNotNull(itemsRepo.lastUpdatedAt(), trackOfNightRepository.lastUpdatedAt()).maxOrNull(),
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
                    coverUrl = it.coverUrlFor(),
                    audioUrl = it.audioUrlFor(),
                    isTrackOfNight = it.id == trackOfNightId,
                )
            }

        return etag to payload
    }

    suspend fun listSets(
        limit: Int = 20,
        offset: Int = 0,
        tag: String? = null,
        q: String? = null,
        userId: Long?,
    ): Pair<String, List<MusicSetDto>> {
        val items =
            itemsRepo.listActive(
                clubId = null,
                limit = limit,
                offset = offset,
                tag = tag,
                q = q,
                type = MusicItemType.SET,
            )
        val counts = likesRepository.countsForItems(items.map { it.id })
        val likedByUser =
            if (userId != null) {
                likesRepository.likedItemsForUser(userId, items.map { it.id })
            } else {
                emptySet()
            }
        val updatedAt = itemsRepo.lastUpdatedAt()
        val etag = etagForSets(items, counts, likedByUser, userId, updatedAt, limit, offset, tag, q)
        val payload =
            items.map {
                MusicSetDto(
                    id = it.id,
                    title = it.title,
                    dj = it.dj,
                    description = it.description,
                    durationSec = it.durationSec,
                    coverUrl = it.coverUrlFor(),
                    audioUrl = it.audioUrlFor(),
                    tags = it.tags,
                    likesCount = counts[it.id] ?: 0,
                    likedByMe = it.id in likedByUser,
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
        val trackOfNight = trackOfNightRepository.currentForSet(id)
        val trackOfNightId = trackOfNight?.trackId

        val etag =
            etagFor(
                updatedAt = listOfNotNull(playlistsRepo.lastUpdatedAt(), trackOfNightRepository.lastUpdatedAt()).maxOrNull(),
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
                            coverUrl = it.coverUrlFor(),
                            audioUrl = it.audioUrlFor(),
                            isTrackOfNight = it.id == trackOfNightId,
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
        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(hash)
            .take(ETAG_LENGTH)
    }

    private fun etagForSets(
        items: List<MusicItemView>,
        counts: Map<Long, Int>,
        likedByUser: Set<Long>,
        userId: Long?,
        updatedAt: Instant?,
        limit: Int,
        offset: Int,
        tag: String?,
        q: String?,
    ): String {
        val countsFingerprint =
            counts
                .entries
                .sortedBy { it.key }
                .joinToString("|") { "${it.key}:${it.value}" }
        val likedFingerprint = likedByUser.sorted().joinToString(",")
        val seed =
            "sets|limit=$limit|offset=$offset|tag=${tag.orEmpty()}|q=${q.orEmpty()}|" +
                "likes=$countsFingerprint|liked=$likedFingerprint|user=${userId ?: 0}"
        return etagFor(updatedAt, items.size, seed)
    }

    private fun MusicItemView.coverUrlFor(): String? {
        return coverAssetId?.let { "/api/music/items/$id/cover" } ?: coverUrl
    }

    private fun MusicItemView.audioUrlFor(): String? {
        return when {
            audioAssetId != null -> "/api/music/items/$id/audio"
            sourceUrl != null -> sourceUrl
            else -> null
        }
    }
}

private const val MILLIS_IN_SECOND = 1_000L
private const val SECONDS_IN_MINUTE = 60L
private const val ETAG_WINDOW_MINUTES = 5L
private const val ETAG_LENGTH = 27
