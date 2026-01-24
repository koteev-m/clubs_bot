package com.example.bot.data.club

import com.example.bot.data.db.isUniqueViolation
import com.example.bot.data.db.withTxRetry
import com.example.bot.opschat.ClubOpsChatConfig
import com.example.bot.opschat.ClubOpsChatConfigRepository
import com.example.bot.opschat.ClubOpsChatConfigUpsert
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ClubOpsChatConfigRepositoryImpl(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) : ClubOpsChatConfigRepository {
    override suspend fun getByClubId(clubId: Long): ClubOpsChatConfig? {
        require(clubId > 0) { "clubId must be positive" }
        return withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                ClubOpsChatConfigTable
                    .selectAll()
                    .where { ClubOpsChatConfigTable.clubId eq clubId }
                    .limit(1)
                    .firstOrNull()
                    ?.toClubOpsChatConfig()
            }
        }
    }

    override suspend fun upsert(config: ClubOpsChatConfigUpsert): ClubOpsChatConfig {
        val now = Instant.now(clock).toOffsetDateTime()
        return withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val updated = updateConfig(config, now)
                if (updated == 0) {
                    insertConfig(config, now)
                }
                ClubOpsChatConfigTable
                    .selectAll()
                    .where { ClubOpsChatConfigTable.clubId eq config.clubId }
                    .limit(1)
                    .firstOrNull()
                    ?.toClubOpsChatConfig()
                    ?: error("Failed to load club ops chat config for clubId=${config.clubId}")
            }
        }
    }

    private fun updateConfig(config: ClubOpsChatConfigUpsert, now: OffsetDateTime): Int =
        ClubOpsChatConfigTable.update({ ClubOpsChatConfigTable.clubId eq config.clubId }) {
            it[chatId] = config.chatId
            it[bookingsThreadId] = config.bookingsThreadId
            it[checkinThreadId] = config.checkinThreadId
            it[supportThreadId] = config.supportThreadId
            it[updatedAt] = now
        }

    private fun insertConfig(config: ClubOpsChatConfigUpsert, now: OffsetDateTime) {
        try {
            ClubOpsChatConfigTable.insert {
                it[clubId] = config.clubId
                it[chatId] = config.chatId
                it[bookingsThreadId] = config.bookingsThreadId
                it[checkinThreadId] = config.checkinThreadId
                it[supportThreadId] = config.supportThreadId
                it[updatedAt] = now
            }
        } catch (error: Exception) {
            if (error.isUniqueViolation()) {
                updateConfig(config, now)
            } else {
                throw error
            }
        }
    }
}

private fun ResultRow.toClubOpsChatConfig(): ClubOpsChatConfig =
    ClubOpsChatConfig(
        clubId = this[ClubOpsChatConfigTable.clubId],
        chatId = this[ClubOpsChatConfigTable.chatId],
        bookingsThreadId = this[ClubOpsChatConfigTable.bookingsThreadId],
        checkinThreadId = this[ClubOpsChatConfigTable.checkinThreadId],
        supportThreadId = this[ClubOpsChatConfigTable.supportThreadId],
        updatedAt = this[ClubOpsChatConfigTable.updatedAt].toInstant(),
    )

private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
