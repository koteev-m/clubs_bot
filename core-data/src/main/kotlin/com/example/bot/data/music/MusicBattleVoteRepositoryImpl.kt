package com.example.bot.data.music

import com.example.bot.music.MusicBattleStatus
import com.example.bot.music.MusicBattleVote
import com.example.bot.music.MusicBattleVoteAggregate
import com.example.bot.music.MusicBattleVoteRepository
import com.example.bot.music.MusicVoteUpsertResult
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.ZoneOffset

class MusicBattleVoteRepositoryImpl(
    private val db: Database,
) : MusicBattleVoteRepository {
    override suspend fun upsertVote(
        battleId: Long,
        userId: Long,
        chosenItemId: Long,
        now: Instant,
    ): MusicVoteUpsertResult =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val battle =
                MusicBattlesTable
                    .select { MusicBattlesTable.id eq battleId }
                    .limit(1)
                    .firstOrNull()
                    ?: throw IllegalArgumentException("Battle $battleId not found")

            val itemAId = battle[MusicBattlesTable.itemAId]
            val itemBId = battle[MusicBattlesTable.itemBId]
            require(chosenItemId == itemAId || chosenItemId == itemBId) {
                "Item $chosenItemId is not in battle $battleId"
            }

            val existing =
                MusicBattleVotesTable
                    .select { (MusicBattleVotesTable.battleId eq battleId) and (MusicBattleVotesTable.userId eq userId) }
                    .limit(1)
                    .firstOrNull()

            if (existing != null && existing[MusicBattleVotesTable.chosenItemId] == chosenItemId) {
                return@newSuspendedTransaction MusicVoteUpsertResult.UNCHANGED
            }

            val isOpenForVoting =
                battle[MusicBattlesTable.status] == MusicBattleStatus.ACTIVE.name &&
                    now.atOffset(ZoneOffset.UTC) >= battle[MusicBattlesTable.startsAt] &&
                    now.atOffset(ZoneOffset.UTC) < battle[MusicBattlesTable.endsAt]

            if (!isOpenForVoting) {
                throw IllegalStateException("Battle $battleId is not open for vote changes")
            }

            if (existing == null) {
                val inserted =
                    MusicBattleVotesTable.insertIgnore {
                        it[this.battleId] = battleId
                        it[this.userId] = userId
                        it[this.chosenItemId] = chosenItemId
                        it[votedAt] = now.atOffset(ZoneOffset.UTC)
                    }
                if (inserted.insertedCount > 0) {
                    return@newSuspendedTransaction MusicVoteUpsertResult.CREATED
                }

                val currentVote =
                    MusicBattleVotesTable
                        .select { (MusicBattleVotesTable.battleId eq battleId) and (MusicBattleVotesTable.userId eq userId) }
                        .limit(1)
                        .firstOrNull()
                        ?: error("Failed to load vote for battle=$battleId user=$userId after insertIgnore")

                if (currentVote[MusicBattleVotesTable.chosenItemId] == chosenItemId) {
                    return@newSuspendedTransaction MusicVoteUpsertResult.UNCHANGED
                }

                val updated =
                    MusicBattleVotesTable.update({
                        (MusicBattleVotesTable.battleId eq battleId) and
                            (MusicBattleVotesTable.userId eq userId) and
                            (MusicBattleVotesTable.chosenItemId neq chosenItemId)
                    }) {
                        it[MusicBattleVotesTable.chosenItemId] = chosenItemId
                        it[votedAt] = now.atOffset(ZoneOffset.UTC)
                    }
                if (updated > 0) {
                    return@newSuspendedTransaction MusicVoteUpsertResult.UPDATED
                }

                val reloadedVote =
                    MusicBattleVotesTable
                        .select { (MusicBattleVotesTable.battleId eq battleId) and (MusicBattleVotesTable.userId eq userId) }
                        .limit(1)
                        .firstOrNull()
                        ?: error("Failed to load vote for battle=$battleId user=$userId after update fallback")
                return@newSuspendedTransaction if (reloadedVote[MusicBattleVotesTable.chosenItemId] == chosenItemId) {
                    MusicVoteUpsertResult.UNCHANGED
                } else {
                    MusicVoteUpsertResult.UPDATED
                }
            }

            MusicBattleVotesTable.update({ (MusicBattleVotesTable.battleId eq battleId) and (MusicBattleVotesTable.userId eq userId) }) {
                it[MusicBattleVotesTable.chosenItemId] = chosenItemId
                it[votedAt] = now.atOffset(ZoneOffset.UTC)
            }
            MusicVoteUpsertResult.UPDATED
        }

    override suspend fun findUserVote(battleId: Long, userId: Long): MusicBattleVote? =
        newSuspendedTransaction(Dispatchers.IO, db) {
            MusicBattleVotesTable
                .select { (MusicBattleVotesTable.battleId eq battleId) and (MusicBattleVotesTable.userId eq userId) }
                .limit(1)
                .firstOrNull()
                ?.toVote()
        }


    override suspend fun aggregateUserVotesSince(clubId: Long, since: Instant): Map<Long, Int> =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val countExpr = MusicBattleVotesTable.userId.count()
            MusicBattleVotesTable
                .innerJoin(MusicBattlesTable)
                .slice(MusicBattleVotesTable.userId, countExpr)
                .select {
                    (MusicBattlesTable.clubId eq clubId) and
                        (MusicBattleVotesTable.votedAt greaterEq since.atOffset(ZoneOffset.UTC))
                }
                .groupBy(MusicBattleVotesTable.userId)
                .associate { row ->
                    val count = row[countExpr].coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    row[MusicBattleVotesTable.userId] to count
                }
        }
    override suspend fun aggregateVotes(battleId: Long): MusicBattleVoteAggregate? =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val battle =
                MusicBattlesTable
                    .select { MusicBattlesTable.id eq battleId }
                    .limit(1)
                    .firstOrNull()
                    ?: return@newSuspendedTransaction null

            val itemAId = battle[MusicBattlesTable.itemAId]
            val itemBId = battle[MusicBattlesTable.itemBId]
            val countExpr = MusicBattleVotesTable.userId.count()
            val rows =
                MusicBattleVotesTable
                    .slice(
                        MusicBattleVotesTable.chosenItemId,
                        countExpr,
                    )
                    .select {
                        (MusicBattleVotesTable.battleId eq battleId) and
                            ((MusicBattleVotesTable.chosenItemId eq itemAId) or (MusicBattleVotesTable.chosenItemId eq itemBId))
                    }
                    .groupBy(MusicBattleVotesTable.chosenItemId)
                    .toList()

            var itemAVotes = 0
            var itemBVotes = 0
            for (row in rows) {
                val chosenItemId = row[MusicBattleVotesTable.chosenItemId]
                val count = row[countExpr].coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                if (chosenItemId == itemAId) itemAVotes = count
                if (chosenItemId == itemBId) itemBVotes = count
            }
            MusicBattleVoteAggregate(
                battleId = battleId,
                itemAId = itemAId,
                itemBId = itemBId,
                itemAVotes = itemAVotes,
                itemBVotes = itemBVotes,
            )
        }

    private fun ResultRow.toVote(): MusicBattleVote =
        MusicBattleVote(
            battleId = this[MusicBattleVotesTable.battleId],
            userId = this[MusicBattleVotesTable.userId],
            chosenItemId = this[MusicBattleVotesTable.chosenItemId],
            votedAt = this[MusicBattleVotesTable.votedAt].toInstant(),
        )
}
