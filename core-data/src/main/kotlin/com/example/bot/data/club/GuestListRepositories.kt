package com.example.bot.data.club

import com.example.bot.club.CheckinMethod
import com.example.bot.club.CheckinResultStatus
import com.example.bot.club.CheckinSubjectType
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListStatus
import com.example.bot.club.InvitationChannel
import com.example.bot.data.db.withTxRetry
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

private const val DEFAULT_PLUS_ONES_ALLOWED: Int = 0

data class GuestListRecord(
    val id: Long,
    val clubId: Long,
    val eventId: Long,
    val promoterId: Long?,
    val ownerType: GuestListOwnerType,
    val ownerUserId: Long,
    val title: String,
    val capacity: Int,
    val arrivalWindowStart: Instant?,
    val arrivalWindowEnd: Instant?,
    val status: GuestListStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class GuestListEntryRecord(
    val id: Long,
    val guestListId: Long,
    val displayName: String,
    val fullName: String,
    val telegramUserId: Long?,
    val status: GuestListEntryStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class InvitationRecord(
    val id: Long,
    val guestListEntryId: Long,
    val tokenHash: String,
    val channel: InvitationChannel,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val usedAt: Instant?,
    val createdBy: Long?,
    val createdAt: Instant,
)

data class CheckinRecord(
    val id: Long,
    val clubId: Long?,
    val eventId: Long?,
    val subjectType: CheckinSubjectType,
    val subjectId: String,
    val checkedBy: Long?,
    val method: CheckinMethod,
    val resultStatus: CheckinResultStatus,
    val denyReason: String?,
    val occurredAt: Instant,
    val createdAt: Instant,
)

data class NewGuestList(
    val clubId: Long,
    val eventId: Long,
    val promoterId: Long?,
    val ownerType: GuestListOwnerType,
    val ownerUserId: Long,
    val title: String,
    val capacity: Int,
    val arrivalWindowStart: Instant?,
    val arrivalWindowEnd: Instant?,
    val status: GuestListStatus,
)

data class NewGuestListEntry(
    val displayName: String,
    val telegramUserId: Long?,
    val status: GuestListEntryStatus = GuestListEntryStatus.ADDED,
)

data class NewCheckin(
    val clubId: Long?,
    val eventId: Long?,
    val subjectType: CheckinSubjectType,
    val subjectId: String,
    val checkedBy: Long?,
    val method: CheckinMethod,
    val resultStatus: CheckinResultStatus,
    val denyReason: String?,
    val occurredAt: Instant,
)

class GuestListDbRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun create(list: NewGuestList): GuestListRecord {
        val now = now()
        return withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val id =
                    GuestListsTable.insert {
                        it[clubId] = list.clubId
                        it[eventId] = list.eventId
                        it[promoterId] = list.promoterId
                        it[ownerType] = list.ownerType.name
                        it[ownerUserId] = list.ownerUserId
                        it[title] = list.title
                        it[capacity] = list.capacity
                        it[arrivalWindowStart] = list.arrivalWindowStart?.toOffsetDateTime()
                        it[arrivalWindowEnd] = list.arrivalWindowEnd?.toOffsetDateTime()
                        it[status] = list.status.name
                        it[createdAt] = now
                        it[updatedAt] = now
                    } get GuestListsTable.id

                GuestListRecord(
                    id = id,
                    clubId = list.clubId,
                    eventId = list.eventId,
                    promoterId = list.promoterId,
                    ownerType = list.ownerType,
                    ownerUserId = list.ownerUserId,
                    title = list.title,
                    capacity = list.capacity,
                    arrivalWindowStart = list.arrivalWindowStart,
                    arrivalWindowEnd = list.arrivalWindowEnd,
                    status = list.status,
                    createdAt = now.toInstantUtc(),
                    updatedAt = now.toInstantUtc(),
                )
            }
        }
    }

    suspend fun findById(id: Long): GuestListRecord? =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                GuestListsTable
                    .selectAll()
                    .where { GuestListsTable.id eq id }
                    .limit(1)
                    .firstOrNull()
                    ?.toGuestListRecord()
            }
        }

    suspend fun listByPromoter(
        promoterId: Long,
        clubId: Long? = null,
        eventId: Long? = null,
        status: GuestListStatus? = null,
    ): List<GuestListRecord> =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val query =
                    GuestListsTable
                        .selectAll()
                        .where { GuestListsTable.promoterId eq promoterId }

                if (clubId != null) {
                    query.andWhere { GuestListsTable.clubId eq clubId }
                }
                if (eventId != null) {
                    query.andWhere { GuestListsTable.eventId eq eventId }
                }
                if (status != null) {
                    query.andWhere { GuestListsTable.status eq status.name }
                }

                query
                    .orderBy(GuestListsTable.createdAt to SortOrder.DESC)
                    .map { it.toGuestListRecord() }
            }
        }

    suspend fun updateStatus(
        id: Long,
        status: GuestListStatus,
    ): Boolean =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                GuestListsTable.update({ GuestListsTable.id eq id }) {
                    it[GuestListsTable.status] = status.name
                    it[updatedAt] = now()
                } > 0
            }
        }

    suspend fun touchUpdatedAt(id: Long): Boolean =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                GuestListsTable.update({ GuestListsTable.id eq id }) {
                    it[updatedAt] = now()
                } > 0
            }
        }

    private fun now(): OffsetDateTime = Instant.now(clock).toOffsetDateTime()
}

class GuestListEntryDbRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun insertOne(
        listId: Long,
        displayName: String,
        telegramUserId: Long? = null,
        status: GuestListEntryStatus = GuestListEntryStatus.ADDED,
    ): GuestListEntryRecord {
        val now = now()
        return withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val id =
                    GuestListEntriesTable.insert {
                        it[guestListId] = listId
                        it[GuestListEntriesTable.displayName] = displayName
                        it[GuestListEntriesTable.fullName] = displayName
                        it[GuestListEntriesTable.tgUsername] = null
                        it[GuestListEntriesTable.phone] = null
                        it[GuestListEntriesTable.telegramUserId] = telegramUserId
                        it[GuestListEntriesTable.plusOnesAllowed] = DEFAULT_PLUS_ONES_ALLOWED
                        it[GuestListEntriesTable.plusOnesUsed] = DEFAULT_PLUS_ONES_USED
                        it[GuestListEntriesTable.category] = DEFAULT_CATEGORY
                        it[GuestListEntriesTable.comment] = null
                        it[GuestListEntriesTable.status] = status.name
                        it[GuestListEntriesTable.checkedInAt] = null
                        it[GuestListEntriesTable.checkedInBy] = null
                        it[GuestListEntriesTable.createdAt] = now
                        it[GuestListEntriesTable.updatedAt] = now
                    } get GuestListEntriesTable.id

                GuestListEntryRecord(
                    id = id,
                    guestListId = listId,
                    displayName = displayName,
                    fullName = displayName,
                    telegramUserId = telegramUserId,
                    status = status,
                    createdAt = now.toInstantUtc(),
                    updatedAt = now.toInstantUtc(),
                )
            }
        }
    }

    suspend fun insertMany(
        listId: Long,
        entries: List<NewGuestListEntry>,
    ): List<GuestListEntryRecord> {
        if (entries.isEmpty()) return emptyList()
        val now = now()
        val createdAtInstant = now.toInstantUtc()
        return withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val rows =
                    GuestListEntriesTable.batchInsert(entries, shouldReturnGeneratedValues = true) { entry ->
                        this[GuestListEntriesTable.guestListId] = listId
                        this[GuestListEntriesTable.displayName] = entry.displayName
                        this[GuestListEntriesTable.fullName] = entry.displayName
                        this[GuestListEntriesTable.tgUsername] = null
                        this[GuestListEntriesTable.phone] = null
                        this[GuestListEntriesTable.telegramUserId] = entry.telegramUserId
                        this[GuestListEntriesTable.plusOnesAllowed] = DEFAULT_PLUS_ONES_ALLOWED
                        this[GuestListEntriesTable.plusOnesUsed] = DEFAULT_PLUS_ONES_USED
                        this[GuestListEntriesTable.category] = DEFAULT_CATEGORY
                        this[GuestListEntriesTable.comment] = null
                        this[GuestListEntriesTable.status] = entry.status.name
                        this[GuestListEntriesTable.checkedInAt] = null
                        this[GuestListEntriesTable.checkedInBy] = null
                        this[GuestListEntriesTable.createdAt] = now
                        this[GuestListEntriesTable.updatedAt] = now
                    }

                if (rows.size != entries.size) {
                    throw IllegalStateException("Expected ${entries.size} generated rows, but got ${rows.size}")
                }

                rows.zip(entries).map { (row, entry) ->
                    val id = row[GuestListEntriesTable.id]
                    if (id <= 0) {
                        throw IllegalStateException("Generated id must be positive for guestListId=$listId")
                    }
                    GuestListEntryRecord(
                        id = id,
                        guestListId = listId,
                        displayName = entry.displayName,
                        fullName = entry.displayName,
                        telegramUserId = entry.telegramUserId,
                        status = entry.status,
                        createdAt = createdAtInstant,
                        updatedAt = createdAtInstant,
                    )
                }
            }
        }
    }

    suspend fun findById(entryId: Long): GuestListEntryRecord? =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                GuestListEntriesTable
                    .selectAll()
                    .where { GuestListEntriesTable.id eq entryId }
                    .limit(1)
                    .firstOrNull()
                    ?.toGuestListEntryRecord()
            }
        }

    suspend fun listByGuestList(listId: Long): List<GuestListEntryRecord> =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                GuestListEntriesTable
                    .selectAll()
                    .where { GuestListEntriesTable.guestListId eq listId }
                    .orderBy(GuestListEntriesTable.createdAt to SortOrder.ASC)
                    .map { it.toGuestListEntryRecord() }
            }
        }

    suspend fun updateStatus(
        entryId: Long,
        status: GuestListEntryStatus,
    ): Boolean =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                GuestListEntriesTable.update({ GuestListEntriesTable.id eq entryId }) {
                    it[GuestListEntriesTable.status] = status.name
                    it[updatedAt] = now()
                } > 0
            }
        }

    suspend fun setTelegramUserIdIfNull(
        entryId: Long,
        telegramUserId: Long,
    ): Boolean =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                GuestListEntriesTable.update({
                    (GuestListEntriesTable.id eq entryId) and GuestListEntriesTable.telegramUserId.isNull()
                }) {
                    it[GuestListEntriesTable.telegramUserId] = telegramUserId
                    it[updatedAt] = now()
                } > 0
            }
        }

    private fun now(): OffsetDateTime = Instant.now(clock).toOffsetDateTime()
}

class InvitationDbRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun create(
        entryId: Long,
        tokenHash: String,
        channel: InvitationChannel,
        expiresAt: Instant,
        createdBy: Long?,
    ): InvitationRecord {
        val now = now()
        return withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val id =
                    InvitationsTable.insert {
                        it[guestListEntryId] = entryId
                        it[InvitationsTable.tokenHash] = tokenHash
                        it[InvitationsTable.channel] = channel.name
                        it[InvitationsTable.expiresAt] = expiresAt.toOffsetDateTime()
                        it[InvitationsTable.revokedAt] = null
                        it[InvitationsTable.usedAt] = null
                        it[InvitationsTable.createdBy] = createdBy
                        it[InvitationsTable.createdAt] = now
                    } get InvitationsTable.id

                InvitationRecord(
                    id = id,
                    guestListEntryId = entryId,
                    tokenHash = tokenHash,
                    channel = channel,
                    expiresAt = expiresAt,
                    revokedAt = null,
                    usedAt = null,
                    createdBy = createdBy,
                    createdAt = now.toInstantUtc(),
                )
            }
        }
    }

    suspend fun findByTokenHash(tokenHash: String): InvitationRecord? =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                InvitationsTable
                    .selectAll()
                    .where { InvitationsTable.tokenHash eq tokenHash }
                    .limit(1)
                    .firstOrNull()
                    ?.toInvitationRecord()
            }
        }

    suspend fun markUsed(
        invitationId: Long,
        usedAt: Instant,
    ): Boolean =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                InvitationsTable.update({
                    (InvitationsTable.id eq invitationId) and InvitationsTable.usedAt.isNull()
                }) {
                    it[InvitationsTable.usedAt] = usedAt.toOffsetDateTime()
                } > 0
            }
        }

    suspend fun revoke(
        invitationId: Long,
        revokedAt: Instant,
    ): Boolean =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                InvitationsTable.update({
                    (InvitationsTable.id eq invitationId) and InvitationsTable.revokedAt.isNull()
                }) {
                    it[InvitationsTable.revokedAt] = revokedAt.toOffsetDateTime()
                } > 0
            }
        }

    suspend fun findLatestByEntryId(entryId: Long): InvitationRecord? =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                InvitationsTable
                    .selectAll()
                    .where { InvitationsTable.guestListEntryId eq entryId }
                    .orderBy(InvitationsTable.createdAt to SortOrder.DESC)
                    .limit(1)
                    .firstOrNull()
                    ?.toInvitationRecord()
            }
        }

    private fun now(): OffsetDateTime = Instant.now(clock).toOffsetDateTime()
}

class CheckinDbRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun insert(checkin: NewCheckin): CheckinRecord {
        val createdAt = now()
        return withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val id =
                    CheckinsTable.insert {
                        it[clubId] = checkin.clubId
                        it[eventId] = checkin.eventId
                        it[subjectType] = checkin.subjectType.name
                        it[CheckinsTable.subjectId] = checkin.subjectId
                        it[checkedBy] = checkin.checkedBy
                        it[method] = checkin.method.name
                        it[resultStatus] = checkin.resultStatus.name
                        it[denyReason] = checkin.denyReason
                        it[occurredAt] = checkin.occurredAt.toOffsetDateTime()
                        it[CheckinsTable.createdAt] = createdAt
                    } get CheckinsTable.id

                CheckinRecord(
                    id = id,
                    clubId = checkin.clubId,
                    eventId = checkin.eventId,
                    subjectType = checkin.subjectType,
                    subjectId = checkin.subjectId,
                    checkedBy = checkin.checkedBy,
                    method = checkin.method,
                    resultStatus = checkin.resultStatus,
                    denyReason = checkin.denyReason,
                    occurredAt = checkin.occurredAt,
                    createdAt = createdAt.toInstantUtc(),
                )
            }
        }
    }

    suspend fun findBySubject(
        subjectType: CheckinSubjectType,
        subjectId: String,
    ): CheckinRecord? =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                CheckinsTable
                    .selectAll()
                    .where {
                        (CheckinsTable.subjectType eq subjectType.name) and
                            (CheckinsTable.subjectId eq subjectId)
                    }
                    .limit(1)
                    .firstOrNull()
                    ?.toCheckinRecord()
            }
        }

    suspend fun listByEvent(
        clubId: Long? = null,
        eventId: Long? = null,
    ): List<CheckinRecord> =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val query = CheckinsTable.selectAll()
                if (clubId != null) {
                    query.andWhere { CheckinsTable.clubId eq clubId }
                }
                if (eventId != null) {
                    query.andWhere { CheckinsTable.eventId eq eventId }
                }

                query
                    .orderBy(CheckinsTable.occurredAt to SortOrder.DESC)
                    .map { it.toCheckinRecord() }
            }
        }

    private fun now(): OffsetDateTime = Instant.now(clock).toOffsetDateTime()
}

private fun OffsetDateTime.toInstantUtc(): Instant = toInstant()

private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

private fun ResultRow.toGuestListRecord(): GuestListRecord =
    GuestListRecord(
        id = this[GuestListsTable.id],
        clubId = this[GuestListsTable.clubId],
        eventId = this[GuestListsTable.eventId],
        promoterId = this[GuestListsTable.promoterId],
        ownerType = GuestListOwnerType.valueOf(this[GuestListsTable.ownerType]),
        ownerUserId = this[GuestListsTable.ownerUserId],
        title = this[GuestListsTable.title],
        capacity = this[GuestListsTable.capacity],
        arrivalWindowStart = this[GuestListsTable.arrivalWindowStart]?.toInstantUtc(),
        arrivalWindowEnd = this[GuestListsTable.arrivalWindowEnd]?.toInstantUtc(),
        status = GuestListStatus.valueOf(this[GuestListsTable.status]),
        createdAt = this[GuestListsTable.createdAt].toInstantUtc(),
        updatedAt = this[GuestListsTable.updatedAt].toInstantUtc(),
    )

private fun ResultRow.toGuestListEntryRecord(): GuestListEntryRecord =
    GuestListEntryRecord(
        id = this[GuestListEntriesTable.id],
        guestListId = this[GuestListEntriesTable.guestListId],
        displayName = this[GuestListEntriesTable.displayName],
        fullName = this[GuestListEntriesTable.fullName],
        telegramUserId = this[GuestListEntriesTable.telegramUserId],
        status = GuestListEntryStatus.valueOf(this[GuestListEntriesTable.status]),
        createdAt = this[GuestListEntriesTable.createdAt].toInstantUtc(),
        updatedAt = this[GuestListEntriesTable.updatedAt].toInstantUtc(),
    )

private fun ResultRow.toInvitationRecord(): InvitationRecord =
    InvitationRecord(
        id = this[InvitationsTable.id],
        guestListEntryId = this[InvitationsTable.guestListEntryId],
        tokenHash = this[InvitationsTable.tokenHash],
        channel = InvitationChannel.valueOf(this[InvitationsTable.channel]),
        expiresAt = this[InvitationsTable.expiresAt].toInstantUtc(),
        revokedAt = this[InvitationsTable.revokedAt]?.toInstantUtc(),
        usedAt = this[InvitationsTable.usedAt]?.toInstantUtc(),
        createdBy = this[InvitationsTable.createdBy],
        createdAt = this[InvitationsTable.createdAt].toInstantUtc(),
    )

private fun ResultRow.toCheckinRecord(): CheckinRecord =
    CheckinRecord(
        id = this[CheckinsTable.id],
        clubId = this[CheckinsTable.clubId],
        eventId = this[CheckinsTable.eventId],
        subjectType = CheckinSubjectType.valueOf(this[CheckinsTable.subjectType]),
        subjectId = this[CheckinsTable.subjectId],
        checkedBy = this[CheckinsTable.checkedBy],
        method = CheckinMethod.valueOf(this[CheckinsTable.method]),
        resultStatus = CheckinResultStatus.valueOf(this[CheckinsTable.resultStatus]),
        denyReason = this[CheckinsTable.denyReason],
        occurredAt = this[CheckinsTable.occurredAt].toInstantUtc(),
        createdAt = this[CheckinsTable.createdAt].toInstantUtc(),
    )
