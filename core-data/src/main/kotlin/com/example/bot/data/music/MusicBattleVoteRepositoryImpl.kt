package com.example.bot.data.music

import com.example.bot.music.MusicBattleStatus
import com.example.bot.music.MusicBattleVote
import com.example.bot.music.MusicBattleVoteAggregate
import com.example.bot.music.MusicBattleVoteRepository
import com.example.bot.music.MusicVoteUpsertResult
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insert
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
                MusicBattleVotesTable.insert {
                    it[this.battleId] = battleId
                    it[this.userId] = userId
                    it[this.chosenItemId] = chosenItemId
                    it[votedAt] = now.atOffset(ZoneOffset.UTC)
                }
                return@newSuspendedTransaction MusicVoteUpsertResult.CREATED
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

    override suspend fun aggregateVotes(battleId: Long): MusicBattleVoteAggregate? =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val countExpr = MusicBattleVotesTable.userId.count()
            val rows =
                MusicBattlesTable
                    .join(MusicBattleVotesTable, JoinType.LEFT, additionalConstraint = { MusicBattlesTable.id eq MusicBattleVotesTable.battleId })
                    .slice(
                        MusicBattlesTable.id,
                        MusicBattlesTable.itemAId,
                        MusicBattlesTable.itemBId,
                        MusicBattleVotesTable.chosenItemId,
                        countExpr,
                    )
                    .select { MusicBattlesTable.id eq battleId }
                    .groupBy(MusicBattlesTable.id, MusicBattlesTable.itemAId, MusicBattlesTable.itemBId, MusicBattleVotesTable.chosenItemId)
                    .toList()

            val first = rows.firstOrNull() ?: return@newSuspendedTransaction null
            val itemAId = first[MusicBattlesTable.itemAId]
            val itemBId = first[MusicBattlesTable.itemBId]
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
