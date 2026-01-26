package com.example.bot.data.music

import com.example.bot.music.Like
import com.example.bot.music.MusicLikesRepository
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.slice
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.time.ZoneOffset

/** Exposed implementation of [MusicLikesRepository]. */
class MusicLikesRepositoryImpl(
    private val db: Database,
) : MusicLikesRepository {
    override suspend fun like(userId: Long, itemId: Long, now: Instant): Boolean =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val inserted =
                MusicLikesTable.insertIgnore {
                    it[this.userId] = userId
                    it[this.itemId] = itemId
                    it[likedAt] = now.atOffset(ZoneOffset.UTC)
                }
            (inserted.insertedCount > 0)
        }

    override suspend fun unlike(userId: Long, itemId: Long): Boolean =
        newSuspendedTransaction(Dispatchers.IO, db) {
            MusicLikesTable.deleteWhere {
                (MusicLikesTable.userId eq userId) and (MusicLikesTable.itemId eq itemId)
            } > 0
        }

    override suspend fun findUserLikesSince(userId: Long, since: Instant): List<Like> =
        newSuspendedTransaction(Dispatchers.IO, db) {
            MusicLikesTable
                .select {
                    (MusicLikesTable.userId eq userId) and
                        (MusicLikesTable.likedAt greaterEq since.atOffset(ZoneOffset.UTC))
                }
                .orderBy(MusicLikesTable.likedAt, SortOrder.DESC)
                .map { it.toLike() }
        }

    override suspend fun findAllLikesSince(since: Instant): List<Like> =
        newSuspendedTransaction(Dispatchers.IO, db) {
            MusicLikesTable
                .select { MusicLikesTable.likedAt greaterEq since.atOffset(ZoneOffset.UTC) }
                .orderBy(MusicLikesTable.likedAt, SortOrder.DESC)
                .map { it.toLike() }
        }

    override suspend fun find(userId: Long, itemId: Long): Like? =
        newSuspendedTransaction(Dispatchers.IO, db) {
            MusicLikesTable
                .select { (MusicLikesTable.userId eq userId) and (MusicLikesTable.itemId eq itemId) }
                .limit(1)
                .firstOrNull()
                ?.toLike()
        }

    override suspend fun countsForItems(itemIds: Collection<Long>): Map<Long, Int> {
        if (itemIds.isEmpty()) return emptyMap()
        return newSuspendedTransaction(Dispatchers.IO, db) {
            val countExpr = MusicLikesTable.userId.count()
            MusicLikesTable
                .slice(MusicLikesTable.itemId, countExpr)
                .selectAll()
                .where { MusicLikesTable.itemId inList itemIds }
                .groupBy(MusicLikesTable.itemId)
                .associate { row ->
                    val itemId = row[MusicLikesTable.itemId]
                    val countLong: Long = row[countExpr]
                    val count = if (countLong > Int.MAX_VALUE) Int.MAX_VALUE else countLong.toInt()
                    itemId to count
                }
        }
    }

    override suspend fun likedItemsForUser(userId: Long, itemIds: Collection<Long>): Set<Long> {
        if (itemIds.isEmpty()) return emptySet()
        return newSuspendedTransaction(Dispatchers.IO, db) {
            MusicLikesTable
                .slice(MusicLikesTable.itemId)
                .selectAll()
                .where { (MusicLikesTable.userId eq userId) and (MusicLikesTable.itemId inList itemIds) }
                .map { it[MusicLikesTable.itemId] }
                .toSet()
        }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toLike(): Like =
        Like(
            userId = this[MusicLikesTable.userId],
            itemId = this[MusicLikesTable.itemId],
            createdAt = this[MusicLikesTable.likedAt].toInstant(),
        )
}
