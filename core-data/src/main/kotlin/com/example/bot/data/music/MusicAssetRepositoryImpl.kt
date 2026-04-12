package com.example.bot.data.music

import com.example.bot.music.MusicAsset
import com.example.bot.music.MusicAssetKind
import com.example.bot.music.MusicAssetMeta
import com.example.bot.music.MusicAssetRepository
import com.example.bot.music.MusicAssetSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.io.InputStream
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.OffsetDateTime

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

    override suspend fun createAssetStream(
        kind: MusicAssetKind,
        contentType: String,
        sha256: String,
        sizeBytes: Long,
        openStream: () -> InputStream,
    ): MusicAsset =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val connection = TransactionManager.current().connection.connection as java.sql.Connection
            val sql =
                """
                INSERT INTO music_assets(kind, bytes, content_type, sha256, size_bytes, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { stmt ->
                stmt.setString(1, kind.name)
                openStream().use { input ->
                    stmt.setBinaryStream(2, input, sizeBytes)
                    stmt.setString(3, contentType)
                    stmt.setString(4, sha256)
                    stmt.setLong(5, sizeBytes)
                    stmt.setObject(6, now)
                    stmt.setObject(7, now)
                    stmt.executeUpdate()
                }
                val id =
                    stmt.generatedKeys.use { keys ->
                        if (!keys.next()) error("Failed to read generated id for music asset")
                        keys.getLong(1)
                    }
                MusicAsset(
                    id = id,
                    kind = kind,
                    bytes = byteArrayOf(),
                    contentType = contentType,
                    sha256 = sha256,
                    sizeBytes = sizeBytes,
                    createdAt = now.toInstant(),
                    updatedAt = now.toInstant(),
                )
            }
        }

    override suspend fun openAssetSource(id: Long): MusicAssetSource? =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val connection = TransactionManager.current().connection.connection as java.sql.Connection
            val sql =
                """
                SELECT kind, bytes, content_type, sha256, size_bytes, updated_at
                FROM music_assets
                WHERE id = ?
                """.trimIndent()
            connection.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, id)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return@newSuspendedTransaction null
                    val tempFile = Files.createTempFile("music-asset-$id-", ".bin")
                    try {
                        rs.getBinaryStream("bytes").use { input ->
                            Files.newOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (t: Throwable) {
                        runCatching { Files.deleteIfExists(tempFile) }
                        throw t
                    }
                    val meta =
                        MusicAssetMeta(
                            id = id,
                            kind = MusicAssetKind.valueOf(rs.getString("kind")),
                            contentType = rs.getString("content_type"),
                            sha256 = rs.getString("sha256"),
                            sizeBytes = rs.getLong("size_bytes"),
                            updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
                        )
                    MusicAssetSource(
                        meta = meta,
                        openStream = { Files.newInputStream(tempFile) },
                        close = { runCatching { Files.deleteIfExists(tempFile) } },
                    )
                }
            }
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
