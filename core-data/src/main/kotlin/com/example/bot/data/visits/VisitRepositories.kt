package com.example.bot.data.visits

import com.example.bot.data.db.isUniqueViolation
import com.example.bot.data.db.toOffsetDateTimeUtc
import com.example.bot.data.db.withRetriedTx
import com.example.bot.data.security.Role
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Clock
import java.time.Instant

data class NightOverride(
    val clubId: Long,
    val nightStartUtc: Instant,
    val earlyCutoffAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

class NightOverrideRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun getOverride(
        clubId: Long,
        nightStartUtc: Instant,
    ): NightOverride? =
        withRetriedTx(name = "night.override.get", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                OperationalNightOverridesTable
                    .selectAll()
                    .where {
                        (OperationalNightOverridesTable.clubId eq clubId) and
                            (OperationalNightOverridesTable.nightStartUtc eq nightStartUtc.toOffsetDateTimeUtc())
                    }.limit(1)
                    .firstOrNull()
                    ?.toNightOverride()
            }
        }

    suspend fun upsertOverride(
        clubId: Long,
        nightStartUtc: Instant,
        earlyCutoffAt: Instant?,
    ): NightOverride {
        val now = Instant.now(clock).toOffsetDateTimeUtc()
        val nightStart = nightStartUtc.toOffsetDateTimeUtc()
        val earlyCutoff = earlyCutoffAt?.toOffsetDateTimeUtc()
        return withRetriedTx(name = "night.override.upsert", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val updated =
                    OperationalNightOverridesTable.update({
                        (OperationalNightOverridesTable.clubId eq clubId) and
                            (OperationalNightOverridesTable.nightStartUtc eq nightStart)
                    }) {
                        it[OperationalNightOverridesTable.earlyCutoffAt] = earlyCutoff
                        it[OperationalNightOverridesTable.updatedAt] = now
                    }
                if (updated == 0) {
                    try {
                        OperationalNightOverridesTable.insert {
                            it[OperationalNightOverridesTable.clubId] = clubId
                            it[OperationalNightOverridesTable.nightStartUtc] = nightStart
                            it[OperationalNightOverridesTable.earlyCutoffAt] = earlyCutoff
                            it[OperationalNightOverridesTable.createdAt] = now
                            it[OperationalNightOverridesTable.updatedAt] = now
                        }
                    } catch (ex: Exception) {
                        if (ex.isUniqueViolation()) {
                            OperationalNightOverridesTable.update({
                                (OperationalNightOverridesTable.clubId eq clubId) and
                                    (OperationalNightOverridesTable.nightStartUtc eq nightStart)
                            }) {
                                it[OperationalNightOverridesTable.earlyCutoffAt] = earlyCutoff
                                it[OperationalNightOverridesTable.updatedAt] = now
                            }
                        } else {
                            throw ex
                        }
                    }
                }
                OperationalNightOverridesTable
                    .selectAll()
                    .where {
                        (OperationalNightOverridesTable.clubId eq clubId) and
                            (OperationalNightOverridesTable.nightStartUtc eq nightStart)
                    }.limit(1)
                    .firstOrNull()
                    ?.toNightOverride()
                    ?: error("Failed to load night override for clubId=$clubId nightStartUtc=$nightStartUtc")
            }
        }
    }
}

data class ClubVisit(
    val id: Long,
    val clubId: Long,
    val nightStartUtc: Instant,
    val eventId: Long?,
    val userId: Long,
    val firstCheckinAt: Instant,
    val actorUserId: Long,
    val actorRole: Role?,
    val entryType: String,
    val isEarly: Boolean,
    val hasTable: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class VisitCheckInInput(
    val clubId: Long,
    val nightStartUtc: Instant,
    val eventId: Long?,
    val userId: Long,
    val actorUserId: Long,
    val actorRole: Role?,
    val entryType: String,
    val firstCheckinAt: Instant,
    val effectiveEarlyCutoffAt: Instant?,
)

data class VisitCheckInResult(
    val created: Boolean,
    val visit: ClubVisit,
)

class VisitRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun tryCheckIn(input: VisitCheckInInput): VisitCheckInResult {
        val now = Instant.now(clock).toOffsetDateTimeUtc()
        val nightStart = input.nightStartUtc.toOffsetDateTimeUtc()
        val firstCheckin = input.firstCheckinAt.toOffsetDateTimeUtc()
        val cutoff = input.effectiveEarlyCutoffAt
        val isEarly = cutoff != null && !input.firstCheckinAt.isAfter(cutoff)
        return withRetriedTx(name = "visit.checkin", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                try {
                    ClubVisitsTable.insert {
                        it[clubId] = input.clubId
                        it[nightStartUtc] = nightStart
                        it[eventId] = input.eventId
                        it[userId] = input.userId
                        it[firstCheckinAt] = firstCheckin
                        it[actorUserId] = input.actorUserId
                        it[actorRole] = input.actorRole?.name
                        it[entryType] = input.entryType
                        it[ClubVisitsTable.isEarly] = isEarly
                        it[hasTable] = false
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                    val createdVisit =
                        findVisit(input.clubId, nightStart, input.userId)
                            ?: error("Failed to load club visit after insert")
                    VisitCheckInResult(created = true, visit = createdVisit)
                } catch (ex: Exception) {
                    if (!ex.isUniqueViolation()) {
                        throw ex
                    }
                    val existingVisit =
                        findVisit(input.clubId, nightStart, input.userId)
                            ?: error("Failed to load existing club visit after unique violation")
                    VisitCheckInResult(created = false, visit = existingVisit)
                }
            }
        }
    }

    suspend fun markHasTable(
        clubId: Long,
        nightStartUtc: Instant,
        userId: Long,
        hasTable: Boolean = true,
    ): Boolean =
        withRetriedTx(name = "visit.markHasTable", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                ClubVisitsTable.update({
                    (ClubVisitsTable.clubId eq clubId) and
                        (ClubVisitsTable.nightStartUtc eq nightStartUtc.toOffsetDateTimeUtc()) and
                        (ClubVisitsTable.userId eq userId) and
                        (ClubVisitsTable.hasTable neq hasTable)
                }) {
                    it[ClubVisitsTable.hasTable] = hasTable
                    it[updatedAt] = Instant.now(clock).toOffsetDateTimeUtc()
                } > 0
            }
        }

    suspend fun countVisits(
        userId: Long,
        clubId: Long,
        sinceUtc: Instant? = null,
    ): Long =
        countVisitsBase(userId, clubId, sinceUtc) { it }

    suspend fun countEarlyVisits(
        userId: Long,
        clubId: Long,
        sinceUtc: Instant? = null,
    ): Long =
        countVisitsBase(userId, clubId, sinceUtc) { query -> query.andWhere { ClubVisitsTable.isEarly eq true } }

    suspend fun countTableNights(
        userId: Long,
        clubId: Long,
        sinceUtc: Instant? = null,
    ): Long =
        countVisitsBase(userId, clubId, sinceUtc) { query -> query.andWhere { ClubVisitsTable.hasTable eq true } }

    private suspend fun countVisitsBase(
        userId: Long,
        clubId: Long,
        sinceUtc: Instant?,
        extraFilter: (org.jetbrains.exposed.sql.Query) -> org.jetbrains.exposed.sql.Query,
    ): Long =
        withRetriedTx(name = "visit.count", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val baseQuery =
                    ClubVisitsTable
                        .selectAll()
                        .where { (ClubVisitsTable.userId eq userId) and (ClubVisitsTable.clubId eq clubId) }
                val filtered =
                    sinceUtc?.let { since ->
                        baseQuery.andWhere { ClubVisitsTable.firstCheckinAt greaterEq since.toOffsetDateTimeUtc() }
                    } ?: baseQuery
                extraFilter(filtered).count()
            }
        }

    private fun findVisit(
        clubId: Long,
        nightStartUtc: java.time.OffsetDateTime,
        userId: Long,
    ): ClubVisit? =
        ClubVisitsTable
            .selectAll()
            .where {
                (ClubVisitsTable.clubId eq clubId) and
                    (ClubVisitsTable.nightStartUtc eq nightStartUtc) and
                    (ClubVisitsTable.userId eq userId)
            }.orderBy(ClubVisitsTable.id, SortOrder.ASC)
            .limit(1)
            .firstOrNull()
            ?.toClubVisit()
}

private fun ResultRow.toNightOverride(): NightOverride =
    NightOverride(
        clubId = this[OperationalNightOverridesTable.clubId],
        nightStartUtc = this[OperationalNightOverridesTable.nightStartUtc].toInstant(),
        earlyCutoffAt = this[OperationalNightOverridesTable.earlyCutoffAt]?.toInstant(),
        createdAt = this[OperationalNightOverridesTable.createdAt].toInstant(),
        updatedAt = this[OperationalNightOverridesTable.updatedAt].toInstant(),
    )

private fun ResultRow.toClubVisit(): ClubVisit =
    ClubVisit(
        id = this[ClubVisitsTable.id],
        clubId = this[ClubVisitsTable.clubId],
        nightStartUtc = this[ClubVisitsTable.nightStartUtc].toInstant(),
        eventId = this[ClubVisitsTable.eventId],
        userId = this[ClubVisitsTable.userId],
        firstCheckinAt = this[ClubVisitsTable.firstCheckinAt].toInstant(),
        actorUserId = this[ClubVisitsTable.actorUserId],
        actorRole = this[ClubVisitsTable.actorRole]?.let(Role::valueOf),
        entryType = this[ClubVisitsTable.entryType],
        isEarly = this[ClubVisitsTable.isEarly],
        hasTable = this[ClubVisitsTable.hasTable],
        createdAt = this[ClubVisitsTable.createdAt].toInstant(),
        updatedAt = this[ClubVisitsTable.updatedAt].toInstant(),
    )
