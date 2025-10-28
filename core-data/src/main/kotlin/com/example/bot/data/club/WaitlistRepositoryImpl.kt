package com.example.bot.data.club

import com.example.bot.club.WaitlistEntry
import com.example.bot.club.WaitlistRepository
import com.example.bot.club.WaitlistStatus
import com.example.bot.data.db.withTxRetry
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Clock
import java.time.ZoneOffset

@Suppress("unused")
class WaitlistRepositoryImpl(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : WaitlistRepository {

    override suspend fun enqueue(
        clubId: Long,
        eventId: Long,
        userId: Long,
        partySize: Int,
    ): WaitlistEntry {
        require(partySize > 0) { "partySize must be positive" }
        return withTxRetry {
            transaction(database) {
                val now = clock.instant().atOffset(ZoneOffset.UTC)
                val inserted =
                    WaitlistTable
                        .insert {
                            it[WaitlistTable.clubId] = clubId
                            it[WaitlistTable.eventId] = eventId
                            it[WaitlistTable.userId] = userId
                            it[WaitlistTable.partySize] = partySize
                            it[WaitlistTable.createdAt] = now
                            it[WaitlistTable.calledAt] = null
                            it[WaitlistTable.expiresAt] = null
                            it[WaitlistTable.status] = WaitlistStatus.WAITING.name
                        }
                        .resultedValues!!
                        .single()
                inserted.toDomain()
            }
        }
    }

    override suspend fun listQueue(
        clubId: Long,
        eventId: Long,
    ): List<WaitlistEntry> {
        return withTxRetry {
            transaction(database) {
                WaitlistTable
                    .selectAll()
                    .where {
                        (WaitlistTable.clubId eq clubId) and
                            (WaitlistTable.eventId eq eventId) and
                            (WaitlistTable.status neq WaitlistStatus.CANCELLED.name) and
                            (WaitlistTable.status neq WaitlistStatus.EXPIRED.name)
                    }
                    .orderBy(WaitlistTable.createdAt, SortOrder.ASC)
                    .map { it.toDomain() }
            }
        }
    }

    override suspend fun callEntry(
        clubId: Long,
        id: Long,
        reserveMinutes: Int,
    ): WaitlistEntry? {
        require(reserveMinutes in 5..120) { "reserveMinutes must be between 5 and 120" }
        return withTxRetry {
            transaction(database) {
                val now = clock.instant().atOffset(ZoneOffset.UTC)
                val updated =
                    WaitlistTable.update({
                        (WaitlistTable.id eq id) and
                            (WaitlistTable.clubId eq clubId) and
                            (WaitlistTable.status eq WaitlistStatus.WAITING.name)
                    }) {
                        it[status] = WaitlistStatus.CALLED.name
                        it[calledAt] = now
                        it[expiresAt] = now.plusMinutes(reserveMinutes.toLong())
                    }
                if (updated == 0) return@transaction null

                WaitlistTable
                    .selectAll()
                    .where { WaitlistTable.id eq id }
                    .single()
                    .toDomain()
            }
        }
    }

    override suspend fun expireEntry(
        clubId: Long,
        id: Long,
        close: Boolean,
    ): WaitlistEntry? {
        return withTxRetry {
            transaction(database) {
                val newStatus = if (close) WaitlistStatus.EXPIRED.name else WaitlistStatus.WAITING.name
                val updated =
                    WaitlistTable.update({
                        (WaitlistTable.id eq id) and
                            (WaitlistTable.clubId eq clubId)
                    }) {
                        it[status] = newStatus
                        it[calledAt] = null
                        it[expiresAt] = null
                    }
                if (updated == 0) return@transaction null

                WaitlistTable
                    .selectAll()
                    .where { WaitlistTable.id eq id }
                    .single()
                    .toDomain()
            }
        }
    }

    override suspend fun get(id: Long): WaitlistEntry? {
        return withTxRetry {
            transaction(database) {
                WaitlistTable
                    .selectAll()
                    .where { WaitlistTable.id eq id }
                    .firstOrNull()
                    ?.toDomain()
            }
        }
    }

    private fun ResultRow.toDomain(): WaitlistEntry =
        WaitlistEntry(
            id = this[WaitlistTable.id],
            clubId = this[WaitlistTable.clubId],
            eventId = this[WaitlistTable.eventId],
            userId = this[WaitlistTable.userId],
            partySize = this[WaitlistTable.partySize],
            createdAt = this[WaitlistTable.createdAt].toInstant(),
            calledAt = this[WaitlistTable.calledAt]?.toInstant(),
            expiresAt = this[WaitlistTable.expiresAt]?.toInstant(),
            status = WaitlistStatus.valueOf(this[WaitlistTable.status]),
        )
}
