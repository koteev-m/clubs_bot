package com.example.bot.host

import com.example.bot.club.GuestListEntrySearch
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListRepository
import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.booking.core.BookingSearchRecord
import com.example.bot.data.club.GuestListDbRepository
import com.example.bot.data.club.GuestListRecord
import java.time.Instant
import java.util.Locale

enum class HostSearchKind {
    BOOKING,
    GUEST_LIST_ENTRY,
}

data class HostSearchItem(
    val kind: HostSearchKind,
    val displayName: String,
    val bookingId: String? = null,
    val guestListEntryId: Long? = null,
    val status: String,
    val guestCount: Int,
    val arrived: Boolean,
    val tableNumber: Int? = null,
    val arrivalWindowStart: Instant? = null,
    val arrivalWindowEnd: Instant? = null,
)

class HostSearchService(
    private val bookingRepository: BookingRepository,
    private val guestListRepository: GuestListRepository,
    private val guestListDbRepository: GuestListDbRepository,
) {
    suspend fun search(
        clubId: Long,
        eventId: Long,
        query: String,
        limit: Int,
    ): List<HostSearchItem> {
        val safeLimit = limit.coerceIn(1, 50)
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val lists = guestListDbRepository.listByClub(clubId).filter { it.eventId == eventId }
        val listMap = lists.associateBy { it.id }
        val listIds = lists.mapTo(mutableSetOf()) { it.id }

        val guestResults =
            if (listIds.isEmpty()) {
                emptyList()
            } else {
                val page = guestListRepository.searchEntries(
                    GuestListEntrySearch(listIds = listIds, nameQuery = trimmed),
                    page = 0,
                    size = safeLimit,
                )
                page.items.map { entry ->
                    val list = listMap[entry.listId]
                    entry.toSearchItem(list)
                }
            }

        val bookingResults =
            bookingRepository.searchByGuestName(clubId, eventId, trimmed, safeLimit)
                .map { it.toSearchItem() }

        return (guestResults + bookingResults)
            .sortedBy { it.displayName.lowercase(Locale.ROOT) }
            .take(safeLimit)
    }
}

private fun BookingSearchRecord.toSearchItem(): HostSearchItem =
    HostSearchItem(
        kind = HostSearchKind.BOOKING,
        displayName = guestName ?: "",
        bookingId = id.toString(),
        status = status.name,
        guestCount = guestsCount,
        arrived = status == BookingStatus.SEATED,
        tableNumber = tableNumber,
        arrivalWindowEnd = arrivalBy,
    )

private fun com.example.bot.club.GuestListEntryView.toSearchItem(list: GuestListRecord?): HostSearchItem =
    HostSearchItem(
        kind = HostSearchKind.GUEST_LIST_ENTRY,
        displayName = fullName,
        guestListEntryId = id,
        status = status.name,
        guestCount = guestsCount,
        arrived = status in ARRIVED_STATUSES,
        arrivalWindowStart = list?.arrivalWindowStart,
        arrivalWindowEnd = list?.arrivalWindowEnd,
    )

private val ARRIVED_STATUSES = setOf(
    GuestListEntryStatus.ARRIVED,
    GuestListEntryStatus.LATE,
    GuestListEntryStatus.CHECKED_IN,
)
