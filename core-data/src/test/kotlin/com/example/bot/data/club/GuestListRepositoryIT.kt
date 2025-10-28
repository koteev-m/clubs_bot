package com.example.bot.data.club

import com.example.bot.club.GuestListEntrySearch
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListRepository
import com.example.bot.club.GuestListStatus
import com.example.bot.club.ParsedGuest
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class GuestListRepositoryIT : PostgresClubIntegrationTest() {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2024-05-01T10:15:30Z"), ZoneOffset.UTC)
    private lateinit var repository: GuestListRepository

    @BeforeEach
    fun initRepository() {
        repository = GuestListRepositoryImpl(database, fixedClock)
    }

    @Test
    fun `create guest list and manage entries`() =
        runBlocking {
            val clubId = insertClub(name = "Aurora")
            val eventId =
                insertEvent(
                    clubId = clubId,
                    title = "Opening Night",
                    startAt = Instant.parse("2024-05-10T18:00:00Z"),
                    endAt = Instant.parse("2024-05-11T02:00:00Z"),
                )
            val ownerId = insertUser(username = "manager", displayName = "Manager One")

            val created =
                repository.createList(
                    clubId = clubId,
                    eventId = eventId,
                    ownerType = GuestListOwnerType.MANAGER,
                    ownerUserId = ownerId,
                    title = "VIP",
                    capacity = 50,
                    arrivalWindowStart = Instant.parse("2024-05-10T17:30:00Z"),
                    arrivalWindowEnd = Instant.parse("2024-05-10T19:00:00Z"),
                    status = GuestListStatus.ACTIVE,
                )
            assertEquals("VIP", created.title)

            val fetched = repository.getList(created.id)
            assertEquals(created, fetched)

            val lists = repository.listListsByClub(clubId, page = 0, size = 10)
            assertEquals(listOf(created), lists)

            val entry =
                repository.addEntry(
                    listId = created.id,
                    fullName = "John Doe",
                    phone = "+1 234 567 890",
                    guestsCount = 3,
                    notes = "all access",
                    status = GuestListEntryStatus.PLANNED,
                )
            assertEquals(3, entry.guestsCount)

            val updated = repository.setEntryStatus(entry.id, GuestListEntryStatus.CHECKED_IN, checkedInBy = ownerId)
            assertNotNull(updated)
            assertEquals(GuestListEntryStatus.CHECKED_IN, updated!!.status)
            assertEquals(fixedClock.instant(), updated.checkedInAt)

            val entries = repository.listEntries(created.id, page = 0, size = 10)
            assertEquals(listOf(updated), entries)
        }

    @Test
    fun `bulk import inserts valid rows`() =
        runBlocking {
            val clubId = insertClub(name = "Aurora")
            val eventId =
                insertEvent(
                    clubId = clubId,
                    title = "Showcase",
                    startAt = Instant.parse("2024-05-20T18:00:00Z"),
                    endAt = Instant.parse("2024-05-21T02:00:00Z"),
                )
            val ownerId = insertUser(username = "host", displayName = "Host")
            val list =
                repository.createList(
                    clubId = clubId,
                    eventId = eventId,
                    ownerType = GuestListOwnerType.ADMIN,
                    ownerUserId = ownerId,
                    title = "Press",
                    capacity = 40,
                    arrivalWindowStart = null,
                    arrivalWindowEnd = null,
                    status = GuestListStatus.ACTIVE,
                )
            val rows =
                listOf(
                    ParsedGuest(
                        lineNumber = 2,
                        name = "Alice Smith",
                        phone = "+1 (234) 555-0000",
                        guestsCount = 2,
                        notes = "VIP",
                    ),
                    ParsedGuest(lineNumber = 3, name = "Bob Stone", phone = "1234567", guestsCount = 1, notes = null),
                )

            // вызывем импорт (возвращаемую "страницу" здесь не используем)
            repository.bulkImport(list.id, rows, dryRun = false)

            val entries = repository.listEntries(list.id, page = 0, size = 10)
            assertEquals(2, entries.size)
            val first = entries[0]
            assertEquals("Alice Smith", first.fullName)
            assertEquals("+12345550000", first.phone)
            assertEquals(2, first.guestsCount)
        }

    @Test
    fun `bulk import rejects invalid data`() =
        runBlocking {
            val clubId = insertClub(name = "Aurora")
            val eventId =
                insertEvent(
                    clubId = clubId,
                    title = "Club Night",
                    startAt = Instant.parse("2024-06-01T18:00:00Z"),
                    endAt = Instant.parse("2024-06-02T02:00:00Z"),
                )
            val ownerId = insertUser(username = "crew", displayName = "Crew")
            val list =
                repository.createList(
                    clubId = clubId,
                    eventId = eventId,
                    ownerType = GuestListOwnerType.MANAGER,
                    ownerUserId = ownerId,
                    title = "Friends",
                    capacity = 30,
                    arrivalWindowStart = null,
                    arrivalWindowEnd = null,
                    status = GuestListStatus.ACTIVE,
                )
            val rows =
                listOf(
                    ParsedGuest(lineNumber = 2, name = "Charlie", phone = "invalid", guestsCount = 2, notes = null),
                    ParsedGuest(lineNumber = 3, name = "Diana", phone = "", guestsCount = -1, notes = null),
                    ParsedGuest(lineNumber = 4, name = "Eve", phone = null, guestsCount = 4, notes = "+1"),
                )

            // импорт с ошибочными строками: вставится только валидная ("Eve")
            repository.bulkImport(list.id, rows, dryRun = false)

            val entries = repository.listEntries(list.id, page = 0, size = 10)
            assertEquals(1, entries.size)
            assertEquals("Eve", entries.single().fullName)
            assertEquals(4, entries.single().guestsCount)
        }

    @Test
    fun `bulk import dry run does not persist`() =
        runBlocking {
            val clubId = insertClub(name = "Aurora")
            val eventId =
                insertEvent(
                    clubId = clubId,
                    title = "Preview",
                    startAt = Instant.parse("2024-06-10T18:00:00Z"),
                    endAt = Instant.parse("2024-06-11T02:00:00Z"),
                )
            val ownerId = insertUser(username = "dry", displayName = "Dry Run")
            val list =
                repository.createList(
                    clubId = clubId,
                    eventId = eventId,
                    ownerType = GuestListOwnerType.ADMIN,
                    ownerUserId = ownerId,
                    title = "Guests",
                    capacity = 20,
                    arrivalWindowStart = null,
                    arrivalWindowEnd = null,
                    status = GuestListStatus.ACTIVE,
                )
            val rows =
                listOf(
                    ParsedGuest(
                        lineNumber = 2,
                        name = "Frank",
                        phone = "+7 999 111 22 33",
                        guestsCount = 2,
                        notes = null,
                    ),
                )

            repository.bulkImport(list.id, rows, dryRun = true)

            val entries = repository.listEntries(list.id, page = 0, size = 10)
            assertTrue(entries.isEmpty())
        }

    @Test
    fun `list entries pagination and status filter`() =
        runBlocking {
            val clubId = insertClub(name = "Aurora")
            val eventId =
                insertEvent(
                    clubId = clubId,
                    title = "Festival",
                    startAt = Instant.parse("2024-06-15T18:00:00Z"),
                    endAt = Instant.parse("2024-06-16T02:00:00Z"),
                )
            val ownerId = insertUser(username = "organizer", displayName = "Organizer")
            val list =
                repository.createList(
                    clubId = clubId,
                    eventId = eventId,
                    ownerType = GuestListOwnerType.ADMIN,
                    ownerUserId = ownerId,
                    title = "Line",
                    capacity = 100,
                    arrivalWindowStart = null,
                    arrivalWindowEnd = null,
                    status = GuestListStatus.ACTIVE,
                )
            val first = repository.addEntry(list.id, "Guest A", null, guestsCount = 1, notes = null)
            val second = repository.addEntry(list.id, "Guest B", null, guestsCount = 2, notes = null)
            repository.setEntryStatus(second.id, GuestListEntryStatus.CHECKED_IN, checkedInBy = ownerId)
            val third = repository.addEntry(list.id, "Guest C", null, guestsCount = 3, notes = null)
            repository.setEntryStatus(third.id, GuestListEntryStatus.NO_SHOW)

            val pageOne = repository.listEntries(list.id, page = 0, size = 2)
            assertEquals(listOf("Guest A", "Guest B"), pageOne.map { it.fullName })
            assertEquals(
                listOf(GuestListEntryStatus.PLANNED, GuestListEntryStatus.CHECKED_IN),
                pageOne.map { it.status },
            )
            assertEquals(listOf(null, fixedClock.instant()), pageOne.map { it.checkedInAt })
            assertEquals(listOf(null, ownerId), pageOne.map { it.checkedInBy })

            val pageTwo = repository.listEntries(list.id, page = 1, size = 2)
            assertEquals(1, pageTwo.size)
            assertEquals("Guest C", pageTwo.single().fullName)
            assertEquals(GuestListEntryStatus.NO_SHOW, pageTwo.single().status)

            val checkedIn =
                repository.listEntries(
                    list.id,
                    page = 0,
                    size = 10,
                    statusFilter = GuestListEntryStatus.CHECKED_IN,
                )
            assertEquals(listOf(pageOne[1]), checkedIn)
        }

    @Test
    fun `search entries supports filters`() =
        runBlocking {
            val clubA = insertClub(name = "Aurora")
            val clubB = insertClub(name = "Nebula")
            val eventA =
                insertEvent(
                    clubId = clubA,
                    title = "Launch",
                    startAt = Instant.parse("2024-07-01T18:00:00Z"),
                    endAt = Instant.parse("2024-07-02T02:00:00Z"),
                )
            val eventB =
                insertEvent(
                    clubId = clubB,
                    title = "Afterparty",
                    startAt = Instant.parse("2024-07-05T18:00:00Z"),
                    endAt = Instant.parse("2024-07-06T02:00:00Z"),
                )
            val manager = insertUser(username = "manager1", displayName = "Manager One")
            val promoter = insertUser(username = "promoter1", displayName = "Promoter One")

            val managerList =
                repository.createList(
                    clubId = clubA,
                    eventId = eventA,
                    ownerType = GuestListOwnerType.MANAGER,
                    ownerUserId = manager,
                    title = "Managers",
                    capacity = 30,
                    arrivalWindowStart = null,
                    arrivalWindowEnd = null,
                    status = GuestListStatus.ACTIVE,
                )
            val promoterList =
                repository.createList(
                    clubId = clubB,
                    eventId = eventB,
                    ownerType = GuestListOwnerType.PROMOTER,
                    ownerUserId = promoter,
                    title = "Promos",
                    capacity = 25,
                    arrivalWindowStart = null,
                    arrivalWindowEnd = null,
                    status = GuestListStatus.ACTIVE,
                )

            transaction(database) {
                GuestListsTable.update({ GuestListsTable.id eq managerList.id }) {
                    it[createdAt] = Instant.parse("2024-07-01T10:00:00Z").atOffset(ZoneOffset.UTC)
                }
                GuestListsTable.update({ GuestListsTable.id eq promoterList.id }) {
                    it[createdAt] = Instant.parse("2024-07-05T12:00:00Z").atOffset(ZoneOffset.UTC)
                }
            }

            repository.addEntry(managerList.id, "Eve Adams", "+1 555 1000", guestsCount = 2, notes = "vip")
            repository.addEntry(managerList.id, "Mallory Stone", "+1 555 1001", guestsCount = 1, notes = null)
            val promoEntry =
                repository.addEntry(promoterList.id, "Bob Promo", "+1 555 2000", guestsCount = 3, notes = "friends")
            repository.setEntryStatus(promoEntry.id, GuestListEntryStatus.CHECKED_IN)

            val managerResult =
                repository.searchEntries(
                    GuestListEntrySearch(clubIds = setOf(clubA)),
                    page = 0,
                    size = 10,
                )
            assertEquals(2, managerResult.total)
            assertTrue(managerResult.items.all { it.clubId == clubA })

            val nameFilter =
                repository.searchEntries(
                    GuestListEntrySearch(
                        clubIds = setOf(clubA),
                        nameQuery = "eve",
                        status = GuestListEntryStatus.PLANNED,
                    ),
                    page = 0,
                    size = 10,
                )
            assertEquals(1, nameFilter.items.size)
            assertEquals("Eve Adams", nameFilter.items.single().fullName)

            val promoterResult =
                repository.searchEntries(
                    GuestListEntrySearch(ownerUserId = promoter),
                    page = 0,
                    size = 10,
                )
            assertEquals(1, promoterResult.items.size)
            assertEquals(promoterList.id, promoterResult.items.single().listId)

            val dateFiltered =
                repository.searchEntries(
                    GuestListEntrySearch(
                        createdFrom = Instant.parse("2024-07-04T00:00:00Z"),
                        createdTo = Instant.parse("2024-07-06T23:59:59Z"),
                    ),
                    page = 0,
                    size = 10,
                )
            assertEquals(1, dateFiltered.items.size)
            assertEquals(promoterList.id, dateFiltered.items.single().listId)
        }
}
