package com.example.bot.data.club

import com.example.bot.club.GuestListConfig
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListServiceError
import com.example.bot.club.GuestListServiceResult
import com.example.bot.club.GuestListStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class GuestListServiceTest {
    private val guestListRepo: GuestListDbRepository = mockk()
    private val entryRepo: GuestListEntryDbRepository = mockk()
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2024-06-01T20:00:00Z"), ZoneOffset.UTC)
    private val config = GuestListConfig(bulkMaxChars = 20_000, noShowGraceMinutes = 30)

    @Test
    fun `bulk parser splits, normalizes and deduplicates`() {
        val parser = GuestListBulkParser()
        val raw = " Alice, Bob /Carol;  alice\nBOB /  Dana   /dana "

        val result = parser.parse(raw)

        assertEquals(listOf("Alice", "Bob", "Carol", "Dana"), result.entries)
        assertEquals(3, result.skippedDuplicates)
    }

    @Test
    fun `bulk add fails on limit after dedup`() = runBlocking {
        val list = listRecord(capacity = 3)
        coEvery { guestListRepo.findById(list.id) } returns list
        coEvery { entryRepo.listByGuestList(list.id) } returns
            listOf(
                entryRecord(id = 1, listId = list.id, status = GuestListEntryStatus.ADDED, name = "Alice"),
                entryRecord(id = 2, listId = list.id, status = GuestListEntryStatus.ADDED, name = "Bob"),
            )

        val service = GuestListServiceImpl(guestListRepo, entryRepo, config, fixedClock, GuestListBulkParser())
        val result = service.addEntriesBulk(list.id, rawText = "Charlie, Alice, Dana")

        when (result) {
            is GuestListServiceResult.Failure ->
                assertEquals(GuestListServiceError.GuestListLimitExceeded, result.error)
            is GuestListServiceResult.Success -> fail("Expected limit failure but got success: ${result.value}")
        }
        coVerify(exactly = 1) { entryRepo.listByGuestList(list.id) }
        coVerify(exactly = 0) { entryRepo.insertMany(any(), any()) }
        confirmVerified(entryRepo)
    }

    @Test
    fun `stats include arrivals and no shows after grace`() = runBlocking {
        val arrivalEnd = Instant.parse("2024-06-01T21:00:00Z")
        val list = listRecord(capacity = 10, arrivalWindowEnd = arrivalEnd)
        coEvery { guestListRepo.findById(list.id) } returns list
        coEvery { entryRepo.listByGuestList(list.id) } returns
            listOf(
                entryRecord(id = 1, listId = list.id, status = GuestListEntryStatus.ADDED, name = "Raw"),
                entryRecord(id = 2, listId = list.id, status = GuestListEntryStatus.INVITED, name = "Invited"),
                entryRecord(id = 3, listId = list.id, status = GuestListEntryStatus.CONFIRMED, name = "Confirmed"),
                entryRecord(id = 4, listId = list.id, status = GuestListEntryStatus.DECLINED, name = "Declined"),
                entryRecord(id = 5, listId = list.id, status = GuestListEntryStatus.ARRIVED, name = "Arrived"),
                entryRecord(id = 6, listId = list.id, status = GuestListEntryStatus.LATE, name = "Late"),
                entryRecord(id = 7, listId = list.id, status = GuestListEntryStatus.DENIED, name = "Denied"),
            )

        val service = GuestListServiceImpl(guestListRepo, entryRepo, config, fixedClock, GuestListBulkParser())
        val result = service.getStats(list.id, now = arrivalEnd.plusSeconds(3600))

        check(result is GuestListServiceResult.Success)
        val stats = result.value
        assertEquals(7, stats.added)
        assertEquals(6, stats.invited)
        assertEquals(1, stats.confirmed)
        assertEquals(1, stats.declined)
        assertEquals(2, stats.arrived)
        assertEquals(3, stats.noShow)
    }

    private fun listRecord(
        id: Long = 10L,
        capacity: Int,
        arrivalWindowEnd: Instant? = null,
    ) =
        GuestListRecord(
            id = id,
            clubId = 1,
            eventId = 1,
            promoterId = 5,
            ownerType = GuestListOwnerType.PROMOTER,
            ownerUserId = 5,
            title = "List",
            capacity = capacity,
            arrivalWindowStart = null,
            arrivalWindowEnd = arrivalWindowEnd,
            status = GuestListStatus.ACTIVE,
            createdAt = fixedClock.instant(),
            updatedAt = fixedClock.instant(),
        )

    private fun entryRecord(
        id: Long,
        listId: Long,
        status: GuestListEntryStatus,
        name: String,
    ) =
        GuestListEntryRecord(
            id = id,
            guestListId = listId,
            displayName = name,
            fullName = name,
            telegramUserId = null,
            status = status,
            createdAt = fixedClock.instant(),
            updatedAt = fixedClock.instant(),
        )
}
