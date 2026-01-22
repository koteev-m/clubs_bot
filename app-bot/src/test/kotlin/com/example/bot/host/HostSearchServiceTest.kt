package com.example.bot.host

import com.example.bot.club.GuestListEntryPage
import com.example.bot.club.GuestListEntrySearch
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListEntryView
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListRepository
import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.booking.core.BookingSearchRecord
import com.example.bot.data.club.GuestListDbRepository
import com.example.bot.data.club.GuestListRecord
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostSearchServiceTest {
    @Test
    fun `search returns unified results within club and event`() = runBlocking {
        val bookingRepository = mockk<BookingRepository>()
        val guestListRepository = mockk<GuestListRepository>()
        val guestListDbRepository = mockk<GuestListDbRepository>()
        val listRecord = guestListRecord(id = 1, eventId = 10)
        val otherList = guestListRecord(id = 2, eventId = 11)
        coEvery { guestListDbRepository.listByClub(1) } returns listOf(listRecord, otherList)

        val filterSlot = slot<GuestListEntrySearch>()
        val entryView =
            GuestListEntryView(
                id = 101,
                listId = 1,
                listTitle = "List",
                clubId = 1,
                ownerType = GuestListOwnerType.MANAGER,
                ownerUserId = 9,
                fullName = "Alice",
                phone = null,
                guestsCount = 2,
                notes = null,
                status = GuestListEntryStatus.PLANNED,
                listCreatedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
        coEvery { guestListRepository.searchEntries(capture(filterSlot), 0, 20) } returns
            GuestListEntryPage(items = listOf(entryView), total = 1)

        val bookingRecord =
            BookingSearchRecord(
                id = UUID(0L, 15L),
                clubId = 1,
                eventId = 10,
                guestName = "Bob",
                guestsCount = 3,
                status = BookingStatus.BOOKED,
                arrivalBy = Instant.parse("2024-01-01T02:00:00Z"),
                tableNumber = 4,
            )
        coEvery { bookingRepository.searchByGuestName(1, 10, "Al", 20) } returns listOf(bookingRecord)

        val service = HostSearchService(bookingRepository, guestListRepository, guestListDbRepository)
        val results = service.search(1, 10, "Al", 20)

        assertEquals(2, results.size)
        assertTrue(results.any { it.kind == HostSearchKind.GUEST_LIST_ENTRY && it.displayName == "Alice" })
        assertTrue(results.any { it.kind == HostSearchKind.BOOKING && it.displayName == "Bob" })
        assertEquals(setOf(1L), filterSlot.captured.listIds)
        coVerify(exactly = 1) { bookingRepository.searchByGuestName(1, 10, "Al", 20) }
    }

    private fun guestListRecord(id: Long, eventId: Long): GuestListRecord =
        GuestListRecord(
            id = id,
            clubId = 1,
            eventId = eventId,
            promoterId = null,
            ownerType = GuestListOwnerType.MANAGER,
            ownerUserId = 9,
            title = "List",
            capacity = 10,
            arrivalWindowStart = null,
            arrivalWindowEnd = Instant.parse("2024-01-01T01:00:00Z"),
            status = com.example.bot.club.GuestListStatus.ACTIVE,
            createdAt = Instant.parse("2024-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )
}
