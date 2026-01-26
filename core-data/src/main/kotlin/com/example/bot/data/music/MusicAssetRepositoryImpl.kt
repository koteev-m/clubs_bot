package com.example.bot.data.music

import com.example.bot.music.MusicAsset
import com.example.bot.music.MusicAssetKind
import com.example.bot.music.MusicAssetMeta
import com.example.bot.music.MusicAssetRepository
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.time.ZoneOffset

class MusicAssetRepositoryImpl(
    private val db: Database,
) : MusicAssetRepository {
    override suspend fun createAsset(
        kind: MusicAssetKind,
        bytes: ByteArray,
        contentType: String,
        sha256: String,
        sizeBytes: Long,
    ): MusicAsset =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val now = Instant.now().atOffset(ZoneOffset.UTC)
            val row =
                MusicAssetsTable
                    .insert {
                        it[this.kind] = kind.name
                        it[this.bytes] = bytes
                        it[this.contentType] = contentType
                        it[this.sha256] = sha256
                        it[this.sizeBytes] = sizeBytes
                        it[createdAt] = now
                        it[updatedAt] = now
                    }.resultedValues!!
                    .first()
            row.toAsset()
        }

    override suspend fun getAsset(id: Long): MusicAsset? =
        newSuspendedTransaction(Dispatchers.IO, db) {
            MusicAssetsTable
                .selectAll()
                .where { MusicAssetsTable.id eq id }
                .firstOrNull()
                ?.toAsset()
        }

    override suspend fun getAssetMeta(id: Long): MusicAssetMeta? =
        newSuspendedTransaction(Dispatchers.IO, db) {
            MusicAssetsTable
                .selectAll()
                .where { MusicAssetsTable.id eq id }
                .firstOrNull()
                ?.toAssetMeta()
        }

    private fun ResultRow.toAsset(): MusicAsset =
        MusicAsset(
            id = this[MusicAssetsTable.id],
            kind = MusicAssetKind.valueOf(this[MusicAssetsTable.kind]),
            bytes = this[MusicAssetsTable.bytes],
            contentType = this[MusicAssetsTable.contentType],
            sha256 = this[MusicAssetsTable.sha256],
            sizeBytes = this[MusicAssetsTable.sizeBytes],
            createdAt = this[MusicAssetsTable.createdAt].toInstant(),
            updatedAt = this[MusicAssetsTable.updatedAt].toInstant(),
        )

    private fun ResultRow.toAssetMeta(): MusicAssetMeta =
        MusicAssetMeta(
            id = this[MusicAssetsTable.id],
            kind = MusicAssetKind.valueOf(this[MusicAssetsTable.kind]),
            contentType = this[MusicAssetsTable.contentType],
            sha256 = this[MusicAssetsTable.sha256],
            sizeBytes = this[MusicAssetsTable.sizeBytes],
            updatedAt = this[MusicAssetsTable.updatedAt].toInstant(),
        )
}
