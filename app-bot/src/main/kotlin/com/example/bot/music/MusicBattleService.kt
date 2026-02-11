package com.example.bot.music

import java.time.Clock
import java.time.Instant

class MusicBattleService(
    private val battlesRepository: MusicBattleRepository,
    private val votesRepository: MusicBattleVoteRepository,
    private val itemsRepository: MusicItemRepository,
    private val likesRepository: MusicLikesRepository,
    private val clock: Clock,
) {
    suspend fun getCurrent(clubId: Long, userId: Long?): BattleDetails? {
        val battle = battlesRepository.findCurrentActive(clubId = clubId, now = Instant.now(clock)) ?: return null
        return toDetailsOrNull(battle, userId)
    }

    suspend fun getById(battleId: Long, userId: Long?): BattleDetails? {
        val battle = battlesRepository.getById(battleId) ?: return null
        return toDetailsOrNull(battle, userId)
    }

    suspend fun list(clubId: Long, limit: Int, offset: Int, userId: Long?): List<BattleDetails> {
        val battles = battlesRepository.listRecent(clubId = clubId, limit = limit, offset = offset)
        return battles.mapNotNull { battle ->
            toDetailsOrNull(battle, userId)
        }
    }

    suspend fun vote(battleId: Long, userId: Long, chosenItemId: Long): VoteResult {
        val now = Instant.now(clock)
        val battle = battlesRepository.getById(battleId) ?: throw BattleNotFoundException()
        val visibleItems = loadVisibleBattleItems(battle) ?: throw BattleNotFoundException()
        if (chosenItemId != battle.itemAId && chosenItemId != battle.itemBId) throw InvalidBattleChoiceException()
        try {
            votesRepository.upsertVote(battleId = battleId, userId = userId, chosenItemId = chosenItemId, now = now)
        } catch (e: IllegalStateException) {
            if (e.message?.contains("not open for vote changes") == true) throw BattleInvalidStateException()
            throw e
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("Battle ") == true && e.message?.contains(" not found") == true) throw BattleNotFoundException()
            throw e
        }
        val (itemA, itemB) = visibleItems
        return VoteResult(details = toDetails(battle, itemA, itemB, userId), chosenItemId = chosenItemId)
    }

    suspend fun fanRanking(clubId: Long, windowDays: Int, userId: Long): FanRanking {
        val now = Instant.now(clock)
        val since = now.minusSeconds(windowDays.toLong() * 24L * 3600L)
        val likesByUser = likesRepository.aggregateUserLikesSince(clubId = clubId, since = since)
        val votesByUser = votesRepository.aggregateUserVotesSince(clubId = clubId, since = since)

        val userIds = (likesByUser.keys + votesByUser.keys).toSet()
        val pointsByUser =
            userIds.associateWith { id ->
                (likesByUser[id] ?: 0) * LIKE_POINT + (votesByUser[id] ?: 0) * VOTE_POINT
            }

        val myLikes = likesByUser[userId] ?: 0
        val myVotes = votesByUser[userId] ?: 0
        val myPoints = myLikes * LIKE_POINT + myVotes * VOTE_POINT
        val rank = if (pointsByUser.isEmpty()) 1 else pointsByUser.values.count { it > myPoints } + 1
        val sortedPoints = pointsByUser.values.sortedDescending()

        return FanRanking(
            myStats = FanStats(myVotes, myLikes, myPoints, rank),
            distribution =
                FanDistribution(
                    totalFans = pointsByUser.size,
                    topPoints = sortedPoints.take(TOP_POINTS_LIMIT),
                    p50 = percentile(sortedPoints, 0.50),
                    p90 = percentile(sortedPoints, 0.90),
                    p99 = percentile(sortedPoints, 0.99),
                ),
        )
    }

    private suspend fun toDetailsOrNull(battle: MusicBattle, userId: Long?): BattleDetails? {
        val (itemA, itemB) = loadVisibleBattleItems(battle) ?: return null
        return toDetails(battle, itemA, itemB, userId)
    }

    private suspend fun toDetails(battle: MusicBattle, itemA: MusicItemView, itemB: MusicItemView, userId: Long?): BattleDetails {
        val aggregate =
            votesRepository.aggregateVotes(battle.id)
                ?: MusicBattleVoteAggregate(
                    battleId = battle.id,
                    itemAId = battle.itemAId,
                    itemBId = battle.itemBId,
                    itemAVotes = 0,
                    itemBVotes = 0,
                )
        val total = aggregate.itemAVotes + aggregate.itemBVotes
        val percentA = if (total == 0) 0 else (aggregate.itemAVotes * 100) / total
        val percentB = if (total == 0) 0 else 100 - percentA
        val myVote = userId?.let { votesRepository.findUserVote(battle.id, it)?.chosenItemId }

        return BattleDetails(
            id = battle.id,
            clubId = battle.clubId,
            status = battle.status,
            startsAt = battle.startsAt,
            endsAt = battle.endsAt,
            itemA = itemA,
            itemB = itemB,
            votes = VoteAggregateView(aggregate.itemAVotes, aggregate.itemBVotes, percentA, percentB, myVote),
        )
    }

    private suspend fun loadVisibleBattleItems(battle: MusicBattle): Pair<MusicItemView, MusicItemView>? {
        val items = itemsRepository.findByIds(listOf(battle.itemAId, battle.itemBId)).associateBy { it.id }
        val itemA = items[battle.itemAId] ?: return null
        val itemB = items[battle.itemBId] ?: return null
        if (itemA.itemType != MusicItemType.SET || itemB.itemType != MusicItemType.SET) return null
        if (itemA.publishedAt == null || itemB.publishedAt == null) return null
        if (battle.clubId != null && (itemA.clubId != battle.clubId || itemB.clubId != battle.clubId)) return null
        return itemA to itemB
    }

    private fun percentile(points: List<Int>, ratio: Double): Int {
        if (points.isEmpty()) return 0
        val asc = points.sorted()
        val index = ((asc.size - 1) * ratio).toInt().coerceIn(0, asc.lastIndex)
        return asc[index]
    }

    data class BattleDetails(
        val id: Long,
        val clubId: Long?,
        val status: MusicBattleStatus,
        val startsAt: Instant,
        val endsAt: Instant,
        val itemA: MusicItemView,
        val itemB: MusicItemView,
        val votes: VoteAggregateView,
    )

    data class VoteAggregateView(
        val countA: Int,
        val countB: Int,
        val percentA: Int,
        val percentB: Int,
        val myVote: Long?,
    )

    data class VoteResult(
        val details: BattleDetails,
        val chosenItemId: Long,
    )

    data class FanRanking(
        val myStats: FanStats,
        val distribution: FanDistribution,
    )

    data class FanStats(
        val votesCast: Int,
        val likesGiven: Int,
        val points: Int,
        val rank: Int,
    )

    data class FanDistribution(
        val totalFans: Int,
        val topPoints: List<Int>,
        val p50: Int,
        val p90: Int,
        val p99: Int,
    )

    class BattleNotFoundException : RuntimeException()

    class InvalidBattleChoiceException : RuntimeException()

    class BattleInvalidStateException : RuntimeException()

    companion object {
        private const val LIKE_POINT = 1
        private const val VOTE_POINT = 2
        private const val TOP_POINTS_LIMIT = 10
    }
}
