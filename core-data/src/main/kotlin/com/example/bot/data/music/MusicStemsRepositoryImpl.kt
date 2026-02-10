package com.example.bot.data.music

import com.example.bot.music.MusicStemsPackage
import com.example.bot.music.MusicStemsRepository
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.ZoneOffset

class MusicStemsRepositoryImpl(
    private val db: Database,
) : MusicStemsRepository {
    override suspend fun linkStemAsset(itemId: Long, assetId: Long, now: Instant): MusicStemsPackage =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val nowOffset = now.atOffset(ZoneOffset.UTC)
            val inserted =
                MusicItemStemsAssetsTable.insertIgnore {
                    it[this.itemId] = itemId
                    it[this.assetId] = assetId
                    it[createdAt] = nowOffset
                    it[updatedAt] = nowOffset
                }
            if (inserted.insertedCount == 0) {
                MusicItemStemsAssetsTable.update({ MusicItemStemsAssetsTable.itemId eq itemId }) {
                    it[MusicItemStemsAssetsTable.assetId] = assetId
                    it[updatedAt] = nowOffset
                }
            }

            MusicItemStemsAssetsTable
                .select { MusicItemStemsAssetsTable.itemId eq itemId }
                .limit(1)
                .first()
                .toMusicStemsPackage()
        }

    override suspend fun unlinkStemAsset(itemId: Long): Boolean =
        newSuspendedTransaction(Dispatchers.IO, db) {
            MusicItemStemsAssetsTable.deleteWhere { MusicItemStemsAssetsTable.itemId eq itemId } > 0
        }

    override suspend fun getStemAsset(itemId: Long): MusicStemsPackage? =
        newSuspendedTransaction(Dispatchers.IO, db) {
            MusicItemStemsAssetsTable
                .select { MusicItemStemsAssetsTable.itemId eq itemId }
                .limit(1)
                .firstOrNull()
                ?.toMusicStemsPackage()
        }

    private fun ResultRow.toMusicStemsPackage(): MusicStemsPackage =
        MusicStemsPackage(
            itemId = this[MusicItemStemsAssetsTable.itemId],
            assetId = this[MusicItemStemsAssetsTable.assetId],
            createdAt = this[MusicItemStemsAssetsTable.createdAt].toInstant(),
            updatedAt = this[MusicItemStemsAssetsTable.updatedAt].toInstant(),
        )
}
