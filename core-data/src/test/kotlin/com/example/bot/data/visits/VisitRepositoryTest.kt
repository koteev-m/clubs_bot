package com.example.bot.data.visits

import com.example.bot.data.TestDatabase
import com.example.bot.data.db.Clubs
import com.example.bot.data.security.UsersTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class VisitRepositoryTest {
    private lateinit var testDb: TestDatabase

    @BeforeEach
    fun setUp() {
        testDb = TestDatabase()
    }

    @AfterEach
    fun tearDown() {
        testDb.close()
    }

    @Test
    fun `duplicate tryCheckIn returns existing visit`() =
        runBlocking {
            val clubId = insertClub()
            val userId = insertUser()
            val actorId = insertUser()
            val repo = VisitRepository(testDb.database)
            val nightStart = Instant.parse("2024-01-10T21:00:00Z")
            val firstCheckin = Instant.parse("2024-01-10T21:05:00Z")
            val cutoff = firstCheckin.plus(10, ChronoUnit.MINUTES)
            val input =
                VisitCheckInInput(
                    clubId = clubId,
                    nightStartUtc = nightStart,
                    eventId = null,
                    userId = userId,
                    actorUserId = actorId,
                    actorRole = null,
                    entryType = "QR",
                    firstCheckinAt = firstCheckin,
                    effectiveEarlyCutoffAt = cutoff,
                )

            val first = repo.tryCheckIn(input)
            val second = repo.tryCheckIn(input)

            assertTrue(first.created)
            assertFalse(second.created)
            assertEquals(first.visit.id, second.visit.id)
            assertEquals(first.visit.firstCheckinAt, second.visit.firstCheckinAt)

            val total =
                newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
                    ClubVisitsTable.selectAll().count()
                }
            assertEquals(1, total)
        }

    private suspend fun insertClub(): Long =
        newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
            Clubs.insert {
                it[name] = "Test Club"
            }[Clubs.id].value.toLong()
        }

    private suspend fun insertUser(): Long =
        newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
            UsersTable.insert {
                it[telegramUserId] = System.nanoTime()
                it[username] = "tester"
                it[displayName] = "Tester"
            }[UsersTable.id]
        }
}
