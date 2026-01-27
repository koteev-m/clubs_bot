package com.example.bot.music

import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Builds deterministic weekly mixtapes based on likes from the last 7 days and recommendations.
 *
 * Likes are pulled from [MusicLikesRepository] for the last week, sorted by recency. Recommendations are
 * taken from [MusicService] without randomization to keep ETag stable for identical inputs. The week start is
 * calculated as the beginning of the current ISO week (Monday 00:00 UTC) relative to [clock].
 */
class MixtapeService(
    private val likesRepository: MusicLikesRepository,
    private val musicService: MusicService,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Builds a weekly mixtape for [userId].
     *
     * Likes from the last [LIKES_WINDOW_DAYS] days are taken in reverse chronological order (with item id as a
     * tiebreaker) and deduplicated. Recommendations are appended afterward, filtered from the like set and sorted by
     * id to keep results deterministic. The playlist is capped at [MAX_ITEMS] items. [Mixtape.weekStart] is aligned to
     * Monday 00:00 UTC of the current week relative to [clock].
     */
    suspend fun buildWeeklyMixtape(userId: Long): Mixtape {
        val now = Instant.now(clock)
        val since = now.minus(Duration.ofDays(LIKES_WINDOW_DAYS))

        val likedIds =
            likesRepository.findUserLikesSince(userId, since)
                .sortedWith(compareByDescending<Like> { it.createdAt }.thenBy { it.itemId })
                .map { it.itemId }
                .distinct()

        val recommendations = recommend(exclude = likedIds.toSet(), limit = MAX_ITEMS - likedIds.size)
        val items = (likedIds + recommendations).take(MAX_ITEMS)
        val weekStart = computeWeekStart(now)

        return Mixtape(
            userId = userId,
            items = items,
            weekStart = weekStart,
        )
    }

    /**
     * Builds the global mixtape of the week based on likes from all users.
     */
    suspend fun buildWeeklyGlobalMixtape(): Mixtape {
        val now = Instant.now(clock)
        val since = now.minus(Duration.ofDays(LIKES_WINDOW_DAYS))

        val likedIds =
            likesRepository.findAllLikesSince(since)
                .groupBy { it.itemId }
                .mapValues { (_, likes) -> likes.sortedByDescending { it.createdAt } }
                .toList()
                .sortedWith(
                    compareByDescending<Pair<Long, List<Like>>> { it.second.size }
                        .thenByDescending { it.second.first().createdAt }
                        .thenBy { it.first },
                )
                .map { it.first }

        val recommendations = recommend(exclude = likedIds.toSet(), limit = MAX_ITEMS - likedIds.size)
        val items = (likedIds + recommendations).take(MAX_ITEMS)
        val weekStart = computeWeekStart(now)

        return Mixtape(
            userId = 0L,
            items = items,
            weekStart = weekStart,
        )
    }

    private suspend fun recommend(exclude: Set<Long>, limit: Int): List<Long> {
        if (limit <= 0) return emptyList()
        val items =
            musicService
                .listSets(
                    limit = RECOMMENDATION_POOL,
                    offset = 0,
                    tag = null,
                    q = null,
                    userId = null,
                ).second
        return items
            .sortedBy { it.id }
            .map { it.id }
            .filterNot { it in exclude }
            .take(limit)
    }

    private fun computeWeekStart(now: Instant): Instant {
        val zdt = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
        val daysFromMonday = (zdt.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong()
        val monday = zdt.toLocalDate().minusDays(daysFromMonday)
        return monday.atStartOfDay().toInstant(ZoneOffset.UTC)
    }
}

private const val LIKES_WINDOW_DAYS = 7L
private const val MAX_ITEMS = 100
private const val RECOMMENDATION_POOL = 200
