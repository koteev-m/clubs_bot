package com.example.bot.data.club

import com.example.bot.club.GuestList
import com.example.bot.club.GuestListEntry
import com.example.bot.club.GuestListEntryPage
import com.example.bot.club.GuestListEntrySearch
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListEntryView
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListRepository
import com.example.bot.club.GuestListStatus
import com.example.bot.club.ParsedGuest
import com.example.bot.data.db.withTxRetry
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class GuestListRepositoryImpl(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : GuestListRepository {
    override suspend fun createList(
        clubId: Long,
        eventId: Long,
        ownerType: GuestListOwnerType,
        ownerUserId: Long,
        title: String,
        capacity: Int,
        arrivalWindowStart: Instant?,
        arrivalWindowEnd: Instant?,
        status: GuestListStatus,
    ): GuestList {
        return withTxRetry {
            transaction(database) {
                val createdAt = clock.instant().atOffset(ZoneOffset.UTC)
                GuestListsTable
                    .insert {
                        it[GuestListsTable.clubId] = clubId
                        it[GuestListsTable.eventId] = eventId
                        it[GuestListsTable.ownerType] = ownerType.name
                        it[GuestListsTable.ownerUserId] = ownerUserId
                        it[GuestListsTable.title] = title
                        it[GuestListsTable.capacity] = capacity
                        it[GuestListsTable.arrivalWindowStart] = arrivalWindowStart?.atOffset(ZoneOffset.UTC)
                        it[GuestListsTable.arrivalWindowEnd] = arrivalWindowEnd?.atOffset(ZoneOffset.UTC)
                        it[GuestListsTable.status] = status.name
                        it[GuestListsTable.createdAt] = createdAt
                    }
                    .resultedValues!!
                    .single()
                    .toGuestList()
            }
        }
    }

    override suspend fun getList(id: Long): GuestList? {
        return withTxRetry {
            transaction(database) {
                GuestListsTable
                    .selectAll()
                    .where { GuestListsTable.id eq id }
                    .firstOrNull()
                    ?.toGuestList()
            }
        }
    }

    override suspend fun findEntry(id: Long): GuestListEntry? {
        return withTxRetry {
            transaction(database) {
                GuestListEntriesTable
                    .selectAll()
                    .where { GuestListEntriesTable.id eq id }
                    .firstOrNull()
                    ?.toGuestListEntry()
            }
        }
    }

    override suspend fun listListsByClub(
        clubId: Long,
        page: Int,
        size: Int,
    ): List<GuestList> {
        require(page >= 0) { "page must be non-negative" }
        require(size > 0) { "size must be positive" }
        val offset = page.toLong() * size
        require(offset <= Int.MAX_VALUE) { "page too large" }
        return withTxRetry {
            transaction(database) {
                GuestListsTable
                    .selectAll()
                    .where { GuestListsTable.clubId eq clubId }
                    .orderBy(GuestListsTable.createdAt, SortOrder.DESC)
                    .limit(size, offset)
                    .map { it.toGuestList() }
            }
        }
    }

    override suspend fun addEntry(
        listId: Long,
        fullName: String,
        phone: String?,
        guestsCount: Int,
        notes: String?,
        status: GuestListEntryStatus,
    ): GuestListEntry {
        val validation = validateEntryInput(fullName, phone, guestsCount, notes, status)
        val valid =
            when (validation) {
                is EntryValidationOutcome.Invalid -> throw IllegalArgumentException(validation.reason)
                is EntryValidationOutcome.Valid -> validation
            }
        return withTxRetry {
            transaction(database) {
                val inserted =
                    GuestListEntriesTable
                        .insert {
                            it[GuestListEntriesTable.guestListId] = listId
                            it[GuestListEntriesTable.fullName] = valid.name
                            it[GuestListEntriesTable.phone] = valid.phone
                            it[GuestListEntriesTable.plusOnesAllowed] = valid.guestsCount - MIN_GUESTS_PER_ENTRY
                            it[GuestListEntriesTable.plusOnesUsed] = DEFAULT_PLUS_ONES_USED
                            it[GuestListEntriesTable.category] = DEFAULT_CATEGORY
                            it[GuestListEntriesTable.comment] = valid.notes
                            it[GuestListEntriesTable.status] = valid.status.name
                            if (valid.status == GuestListEntryStatus.CHECKED_IN) {
                                it[GuestListEntriesTable.checkedInAt] = clock.instant().atOffset(ZoneOffset.UTC)
                                it[GuestListEntriesTable.checkedInBy] = null
                            } else {
                                it[GuestListEntriesTable.checkedInAt] = null
                                it[GuestListEntriesTable.checkedInBy] = null
                            }
                        }
                        .resultedValues!!
                        .single()
                inserted.toGuestListEntry()
            }
        }
    }

    override suspend fun setEntryStatus(
        entryId: Long,
        status: GuestListEntryStatus,
        checkedInBy: Long?,
        at: Instant?,
    ): GuestListEntry? {
        return withTxRetry {
            transaction(database) {
                val checkedAt =
                    if (status == GuestListEntryStatus.CHECKED_IN) {
                        (at ?: clock.instant()).atOffset(ZoneOffset.UTC)
                    } else {
                        null
                    }
                val actorId = checkedInBy
                val updated =
                    GuestListEntriesTable.update({ GuestListEntriesTable.id eq entryId }) {
                        it[GuestListEntriesTable.status] = status.name
                        it[checkedInAt] = checkedAt
                        it[GuestListEntriesTable.checkedInBy] = if (checkedAt != null) actorId else null
                    }
                if (updated == 0) {
                    null
                } else {
                    GuestListEntriesTable
                        .selectAll()
                        .where { GuestListEntriesTable.id eq entryId }
                        .single()
                        .toGuestListEntry()
                }
            }
        }
    }

    override suspend fun listEntries(
        listId: Long,
        page: Int,
        size: Int,
        statusFilter: GuestListEntryStatus?,
    ): List<GuestListEntry> {
        require(page >= 0) { "page must be non-negative" }
        require(size > 0) { "size must be positive" }
        val offset = page.toLong() * size
        require(offset <= Int.MAX_VALUE) { "page too large" }
        return withTxRetry {
            transaction(database) {
                val baseQuery =
                    GuestListEntriesTable
                        .selectAll()
                        .where { GuestListEntriesTable.guestListId eq listId }

                val filteredQuery =
                    if (statusFilter != null) {
                        baseQuery.andWhere { GuestListEntriesTable.status eq statusFilter.name }
                    } else {
                        baseQuery
                    }

                filteredQuery
                    .orderBy(GuestListEntriesTable.id, SortOrder.ASC)
                    .limit(size, offset)
                    .map { it.toGuestListEntry() }
            }
        }
    }

    override suspend fun markArrived(
        entryId: Long,
        at: Instant,
    ): Boolean {
        return withTxRetry {
            transaction(database) {
                val existing =
                    GuestListEntriesTable
                        .selectAll()
                        .where { GuestListEntriesTable.id eq entryId }
                        .firstOrNull()
                        ?: return@transaction false

                val alreadyCheckedIn =
                    existing[GuestListEntriesTable.status] == GuestListEntryStatus.CHECKED_IN.name &&
                        existing[GuestListEntriesTable.checkedInAt] != null

                if (!alreadyCheckedIn) {
                    GuestListEntriesTable.update({ GuestListEntriesTable.id eq entryId }) {
                        it[status] = GuestListEntryStatus.CHECKED_IN.name
                        it[checkedInAt] = at.atOffset(ZoneOffset.UTC)
                        it[checkedInBy] = null
                    }
                }
                true
            }
        }
    }

    /**
     * Вставляет валидные строки, если [dryRun] = false, и возвращает пустую страницу,
     * т.к. вызовы этого метода не полагаются на его результат (см. слой маршрутов).
     * Возвратный тип соответствует контракту интерфейса.
     */
    override suspend fun bulkImport(
        listId: Long,
        rows: List<ParsedGuest>,
        dryRun: Boolean,
    ): GuestListEntryPage {
        return withTxRetry {
            transaction(database) {
                val validRows = mutableListOf<EntryValidationOutcome.Valid>()
                rows.forEach { row ->
                    when (
                        val outcome =
                            validateEntryInput(
                                name = row.name,
                                phone = row.phone,
                                guestsCount = row.guestsCount,
                                notes = row.notes,
                                status = GuestListEntryStatus.PLANNED,
                            )
                    ) {
                        is EntryValidationOutcome.Invalid -> {
                            // Отбрасываем — rejected обрабатывается на уровне парсера/роутов.
                        }
                        is EntryValidationOutcome.Valid -> validRows += outcome
                    }
                }

                if (!dryRun && validRows.isNotEmpty()) {
                    GuestListEntriesTable.batchInsert(validRows) { valid ->
                        this[GuestListEntriesTable.guestListId] = listId
                        this[GuestListEntriesTable.fullName] = valid.name
                        this[GuestListEntriesTable.phone] = valid.phone
                        this[GuestListEntriesTable.plusOnesAllowed] = valid.guestsCount - MIN_GUESTS_PER_ENTRY
                        this[GuestListEntriesTable.plusOnesUsed] = DEFAULT_PLUS_ONES_USED
                        this[GuestListEntriesTable.category] = DEFAULT_CATEGORY
                        this[GuestListEntriesTable.comment] = valid.notes
                        this[GuestListEntriesTable.status] = GuestListEntryStatus.PLANNED.name
                        this[GuestListEntriesTable.checkedInAt] = null
                        this[GuestListEntriesTable.checkedInBy] = null
                    }
                }

                // Возвращаем пустую страницу — вызывающая сторона не использует содержимое.
                GuestListEntryPage(emptyList(), 0)
            }
        }
    }

    override suspend fun searchEntries(
        filter: GuestListEntrySearch,
        page: Int,
        size: Int,
    ): GuestListEntryPage {
        require(page >= 0) { "page must be non-negative" }
        require(size > 0) { "size must be positive" }
        val offset = page.toLong() * size
        require(offset <= Int.MAX_VALUE) { "page too large" }
        return withTxRetry {
            transaction(database) {
                var condition: Op<Boolean> = Op.TRUE
                filter.clubIds?.takeIf { it.isNotEmpty() }?.let { clubs ->
                    condition = condition and (GuestListsTable.clubId inList clubs)
                }
                filter.listIds?.takeIf { it.isNotEmpty() }?.let { ids ->
                    condition = condition and (GuestListsTable.id inList ids)
                }
                filter.ownerUserId?.let { ownerId ->
                    condition = condition and (GuestListsTable.ownerUserId eq ownerId)
                }
                filter.status?.let { status ->
                    condition = condition and (GuestListEntriesTable.status eq status.name)
                }
                filter.createdFrom?.let { from ->
                    condition =
                        condition and (GuestListsTable.createdAt greaterEq from.atOffset(ZoneOffset.UTC))
                }
                filter.createdTo?.let { to ->
                    condition =
                        condition and (GuestListsTable.createdAt lessEq to.atOffset(ZoneOffset.UTC))
                }
                filter.nameQuery?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                    val like = "%${escapeLike(name.lowercase())}%"
                    condition = condition and (GuestListEntriesTable.fullName.lowerCase() like like)
                }
                filter.phoneQuery?.trim()?.takeIf { it.isNotEmpty() }?.let { phone ->
                    val normalized = sanitizePhoneQuery(phone)
                    if (normalized.isNotEmpty()) {
                        val like = "%${escapeLike(normalized)}%"
                        condition = condition and (GuestListEntriesTable.phone like like)
                    }
                }

                val joined =
                    GuestListEntriesTable.innerJoin(
                        otherTable = GuestListsTable,
                        onColumn = { GuestListEntriesTable.guestListId },
                        otherColumn = { GuestListsTable.id },
                    )
                val total =
                    joined
                        .selectAll()
                        .where { condition }
                        .count()

                val rows =
                    joined
                        .selectAll()
                        .where { condition }
                        .orderBy(GuestListEntriesTable.id, SortOrder.ASC)
                        .limit(size, offset)
                        .map { it.toEntryView() }

                GuestListEntryPage(rows, total)
            }
        }
    }

    private fun ResultRow.toGuestList(): GuestList {
        return GuestList(
            id = this[GuestListsTable.id],
            clubId = this[GuestListsTable.clubId],
            eventId = this[GuestListsTable.eventId],
            ownerType = GuestListOwnerType.valueOf(this[GuestListsTable.ownerType]),
            ownerUserId = this[GuestListsTable.ownerUserId],
            title = this[GuestListsTable.title],
            capacity = this[GuestListsTable.capacity],
            arrivalWindowStart = this[GuestListsTable.arrivalWindowStart]?.toInstant(),
            arrivalWindowEnd = this[GuestListsTable.arrivalWindowEnd]?.toInstant(),
            status = GuestListStatus.valueOf(this[GuestListsTable.status]),
            createdAt = this[GuestListsTable.createdAt].toInstant(),
        )
    }

    private fun ResultRow.toGuestListEntry(): GuestListEntry {
        return GuestListEntry(
            id = this[GuestListEntriesTable.id],
            listId = this[GuestListEntriesTable.guestListId],
            fullName = this[GuestListEntriesTable.fullName],
            phone = this[GuestListEntriesTable.phone],
            guestsCount = this[GuestListEntriesTable.plusOnesAllowed] + MIN_GUESTS_PER_ENTRY,
            notes = this[GuestListEntriesTable.comment],
            status = GuestListEntryStatus.valueOf(this[GuestListEntriesTable.status]),
            checkedInAt = this[GuestListEntriesTable.checkedInAt]?.toInstant(),
            checkedInBy = this[GuestListEntriesTable.checkedInBy],
        )
    }

    private fun ResultRow.toEntryView(): GuestListEntryView {
        return GuestListEntryView(
            id = this[GuestListEntriesTable.id],
            listId = this[GuestListEntriesTable.guestListId],
            listTitle = this[GuestListsTable.title],
            clubId = this[GuestListsTable.clubId],
            ownerType = GuestListOwnerType.valueOf(this[GuestListsTable.ownerType]),
            ownerUserId = this[GuestListsTable.ownerUserId],
            fullName = this[GuestListEntriesTable.fullName],
            phone = this[GuestListEntriesTable.phone],
            guestsCount = this[GuestListEntriesTable.plusOnesAllowed] + MIN_GUESTS_PER_ENTRY,
            notes = this[GuestListEntriesTable.comment],
            status = GuestListEntryStatus.valueOf(this[GuestListEntriesTable.status]),
            listCreatedAt = this[GuestListsTable.createdAt].toInstant(),
        )
    }
}

private fun escapeLike(value: String): String {
    return value
        .replace("%", "\\%")
        .replace("_", "\\_")
}

private fun sanitizePhoneQuery(raw: String): String {
    return raw.filter { it.isDigit() || it == '+' }
}
