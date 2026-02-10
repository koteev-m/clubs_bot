package com.example.bot.data.music

import com.example.bot.music.MusicBattle
import com.example.bot.music.MusicBattleRepository
import com.example.bot.music.MusicBattleStatus
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.ZoneOffset

class MusicBattleRepositoryImpl(
    private val db: Database,
) : MusicBattleRepository {
    override suspend fun create(
        clubId: Long?,
        itemAId: Long,
        itemBId: Long,
        status: MusicBattleStatus,
        startsAt: Instant,
        endsAt: Instant,
    ): MusicBattle =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val now = Instant.now().atOffset(ZoneOffset.UTC)
            MusicBattlesTable
                .insert {
                    it[this.clubId] = clubId
                    it[this.itemAId] = itemAId
                    it[this.itemBId] = itemBId
                    it[this.status] = status.name
                    it[this.startsAt] = startsAt.atOffset(ZoneOffset.UTC)
                    it[this.endsAt] = endsAt.atOffset(ZoneOffset.UTC)
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                .resultedValues
                .orEmpty()
                .first()
                .toMusicBattle()
        }

    override suspend fun getById(id: Long): MusicBattle? =
        newSuspendedTransaction(Dispatchers.IO, db) {
            MusicBattlesTable
                .select { MusicBattlesTable.id eq id }
                .limit(1)
                .firstOrNull()
                ?.toMusicBattle()
        }

    override suspend fun findCurrentActive(clubId: Long?, now: Instant): MusicBattle? =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val nowOffset = now.atOffset(ZoneOffset.UTC)
            MusicBattlesTable
                .select {
                    (MusicBattlesTable.clubId eq clubId) and
                        (MusicBattlesTable.status eq MusicBattleStatus.ACTIVE.name) and
                        (MusicBattlesTable.startsAt lessEq nowOffset) and
                        (MusicBattlesTable.endsAt greater nowOffset)
                }
                .orderBy(MusicBattlesTable.startsAt to SortOrder.DESC, MusicBattlesTable.id to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.toMusicBattle()
        }

    override suspend fun listRecent(
        clubId: Long?,
        limit: Int,
        offset: Int,
    ): List<MusicBattle> =
        newSuspendedTransaction(Dispatchers.IO, db) {
            MusicBattlesTable
                .select { MusicBattlesTable.clubId eq clubId }
                .orderBy(MusicBattlesTable.startsAt to SortOrder.DESC, MusicBattlesTable.id to SortOrder.DESC)
                .limit(limit, offset.toLong())
                .map { it.toMusicBattle() }
        }

    override suspend fun setStatus(id: Long, status: MusicBattleStatus, updatedAt: Instant): Boolean =
        newSuspendedTransaction(Dispatchers.IO, db) {
            MusicBattlesTable.update({ MusicBattlesTable.id eq id }) {
                it[MusicBattlesTable.status] = status.name
                it[MusicBattlesTable.updatedAt] = updatedAt.atOffset(ZoneOffset.UTC)
            } > 0
        }

    private fun ResultRow.toMusicBattle(): MusicBattle =
        MusicBattle(
            id = this[MusicBattlesTable.id],
            clubId = this[MusicBattlesTable.clubId],
            itemAId = this[MusicBattlesTable.itemAId],
            itemBId = this[MusicBattlesTable.itemBId],
            status = MusicBattleStatus.valueOf(this[MusicBattlesTable.status]),
            startsAt = this[MusicBattlesTable.startsAt].toInstant(),
            endsAt = this[MusicBattlesTable.endsAt].toInstant(),
            createdAt = this[MusicBattlesTable.createdAt].toInstant(),
            updatedAt = this[MusicBattlesTable.updatedAt].toInstant(),
        )
}
