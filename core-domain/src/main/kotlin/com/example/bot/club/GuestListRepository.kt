package com.example.bot.club

import java.time.Instant

/**
 * Repository managing guest lists and their entries.
 */
interface GuestListRepository {
    @Suppress("LongParameterList")
    suspend fun createList(
        clubId: Long,
        eventId: Long,
        ownerType: GuestListOwnerType,
        ownerUserId: Long,
        title: String,
        capacity: Int,
        arrivalWindowStart: Instant?,
        arrivalWindowEnd: Instant?,
        status: GuestListStatus = GuestListStatus.ACTIVE,
    ): GuestList

    suspend fun getList(id: Long): GuestList?

    suspend fun findEntry(id: Long): GuestListEntry?

    suspend fun listListsByClub(
        clubId: Long,
        page: Int,
        size: Int,
    ): List<GuestList>

    suspend fun addEntry(
        listId: Long,
        fullName: String,
        phone: String?,
        guestsCount: Int,
        notes: String?,
        status: GuestListEntryStatus = GuestListEntryStatus.PLANNED,
    ): GuestListEntry

    suspend fun setEntryStatus(
        entryId: Long,
        status: GuestListEntryStatus,
        checkedInBy: Long? = null,
        at: Instant? = null,
    ): GuestListEntry?

    suspend fun listEntries(
        listId: Long,
        page: Int,
        size: Int,
        statusFilter: GuestListEntryStatus? = null,
    ): List<GuestListEntry>

    suspend fun markArrived(
        entryId: Long,
        at: Instant,
    ): Boolean

    suspend fun bulkImport(
        listId: Long,
        rows: List<ParsedGuest>,
        dryRun: Boolean,
    ): GuestListEntryPage

    suspend fun searchEntries(
        filter: GuestListEntrySearch,
        page: Int,
        size: Int,
    ): GuestListEntryPage
}

enum class GuestListOwnerType {
    PROMOTER,
    ADMIN,
    MANAGER,
}

enum class GuestListStatus {
    ACTIVE,
    CLOSED,
    CANCELLED,
}

enum class GuestListEntryStatus {
    ADDED,
    INVITED,
    CONFIRMED,
    DECLINED,
    ARRIVED,
    LATE,
    DENIED,

    PLANNED,
    CHECKED_IN,
    NO_SHOW,

    /** Гость в листе ожидания (для совместимости отображаем в списках промоутеров). */
    WAITLISTED,

    /** Оператор позвал гостя; действует короткий резерв окна прибытия. */
    CALLED,

    /** Истёк резерв/окно или запись закрыта. */
    EXPIRED,
}

data class GuestList(
    val id: Long,
    val clubId: Long,
    val eventId: Long,
    val ownerType: GuestListOwnerType,
    val ownerUserId: Long,
    val title: String,
    val capacity: Int,
    val arrivalWindowStart: Instant?,
    val arrivalWindowEnd: Instant?,
    val status: GuestListStatus,
    val createdAt: Instant,
)

data class GuestListEntry(
    val id: Long,
    val listId: Long,
    val fullName: String,
    val phone: String?,
    val guestsCount: Int,
    val notes: String?,
    val status: GuestListEntryStatus,
    val checkedInAt: Instant?,
    val checkedInBy: Long?,
)

data class ParsedGuest(
    val lineNumber: Int,
    val name: String,
    val phone: String?,
    val guestsCount: Int,
    val notes: String?,
)

data class RejectedRow(
    val line: Int,
    val reason: String,
)

data class GuestListEntrySearch(
    val clubIds: Set<Long>? = null,
    val listIds: Set<Long>? = null,
    val ownerUserId: Long? = null,
    val nameQuery: String? = null,
    val phoneQuery: String? = null,
    val status: GuestListEntryStatus? = null,
    val createdFrom: Instant? = null,
    val createdTo: Instant? = null,
)

data class GuestListEntryView(
    val id: Long,
    val listId: Long,
    val listTitle: String,
    val clubId: Long,
    val ownerType: GuestListOwnerType,
    val ownerUserId: Long,
    val fullName: String,
    val phone: String?,
    val guestsCount: Int,
    val notes: String?,
    val status: GuestListEntryStatus,
    val listCreatedAt: Instant,
)

data class GuestListEntryPage(
    val items: List<GuestListEntryView>,
    val total: Long,
)

enum class InvitationChannel {
    TELEGRAM,
    EXTERNAL,
}

enum class CheckinSubjectType {
    BOOKING,
    GUEST_LIST_ENTRY,
}

enum class CheckinMethod {
    QR,
    NAME,
}

enum class CheckinResultStatus {
    ARRIVED,
    LATE,
    DENIED,
}
