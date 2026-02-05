package com.example.bot.data.stories

import com.example.bot.data.TestDatabase
import com.example.bot.data.db.Clubs
import com.example.bot.data.db.toOffsetDateTimeUtc
import com.example.bot.data.security.UsersTable
import com.example.bot.data.visits.ClubVisitsTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class PostEventStoryAndGuestSegmentsRepositoryTest {
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
    fun `story upsert is idempotent for same club night and schema`() =
        runBlocking {
            val clubId = insertClub()
            val repository = PostEventStoryRepository(testDb.database)
            val nightStart = Instant.parse("2024-05-01T21:00:00Z")
            val generatedAt1 = Instant.parse("2024-05-02T06:00:00Z")
            val generatedAt2 = Instant.parse("2024-05-02T06:30:00Z")

            val first =
                repository.upsert(
                    clubId = clubId,
                    nightStartUtc = nightStart,
                    schemaVersion = 1,
                    status = PostEventStoryStatus.READY,
                    payloadJson = "{\"summary\":\"ok\"}",
                    generatedAt = generatedAt1,
                    now = generatedAt1,
                )
            val second =
                repository.upsert(
                    clubId = clubId,
                    nightStartUtc = nightStart,
                    schemaVersion = 1,
                    status = PostEventStoryStatus.FAILED,
                    payloadJson = "{\"summary\":\"retry\"}",
                    generatedAt = generatedAt2,
                    now = generatedAt2,
                    errorCode = "GEN_TIMEOUT",
                )

            assertEquals(first.id, second.id)
            assertEquals(PostEventStoryStatus.FAILED, second.status)
            assertEquals("GEN_TIMEOUT", second.errorCode)

            val rowCount =
                newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
                    PostEventStoriesTable.selectAll().count()
                }
            assertEquals(1, rowCount)
        }

    @Test
    fun `listByClub uses stable ordering by night desc then id desc`() =
        runBlocking {
            val clubId = insertClub()
            val repository = PostEventStoryRepository(testDb.database)
            val now = Instant.parse("2024-05-03T09:00:00Z")

            val newerNight =
                repository.upsert(
                    clubId = clubId,
                    nightStartUtc = Instant.parse("2024-05-02T21:00:00Z"),
                    schemaVersion = 1,
                    status = PostEventStoryStatus.READY,
                    payloadJson = "{\"n\":1}",
                    generatedAt = now,
                    now = now,
                )
            val sameNightFirst =
                repository.upsert(
                    clubId = clubId,
                    nightStartUtc = Instant.parse("2024-05-01T21:00:00Z"),
                    schemaVersion = 1,
                    status = PostEventStoryStatus.READY,
                    payloadJson = "{\"n\":2}",
                    generatedAt = now,
                    now = now,
                )
            val sameNightSecond =
                repository.upsert(
                    clubId = clubId,
                    nightStartUtc = Instant.parse("2024-05-01T21:00:00Z"),
                    schemaVersion = 2,
                    status = PostEventStoryStatus.READY,
                    payloadJson = "{\"n\":3}",
                    generatedAt = now,
                    now = now,
                )
            val olderInsertedLater =
                repository.upsert(
                    clubId = clubId,
                    nightStartUtc = Instant.parse("2024-04-28T21:00:00Z"),
                    schemaVersion = 1,
                    status = PostEventStoryStatus.READY,
                    payloadJson = "{\"n\":4}",
                    generatedAt = now,
                    now = now,
                )

            val listed = repository.listByClub(clubId = clubId, limit = 10, offset = 0)

            assertEquals(4, listed.size)
            assertEquals(
                listOf(newerNight.id, sameNightSecond.id, sameNightFirst.id, olderInsertedLater.id),
                listed.map { it.id },
            )
        }

    @Test
    fun `computeSegments classifies users and getSummary returns counts`() =
        runBlocking {
            val clubId = insertClub()
            val anotherClub = insertClub()
            val now = Instant.parse("2024-05-10T00:00:00Z")
            val repository = GuestSegmentsRepository(testDb.database)
            val actorId = insertUser()

            val newUser = insertUser()
            val frequentUser = insertUser()
            val sleepingUser = insertUser()

            insertVisit(clubId, newUser, actorId, Instant.parse("2024-05-07T20:00:00Z"))

            insertVisit(clubId, frequentUser, actorId, Instant.parse("2024-04-20T20:00:00Z"))
            insertVisit(clubId, frequentUser, actorId, Instant.parse("2024-05-09T20:00:00Z"))

            insertVisit(clubId, sleepingUser, actorId, Instant.parse("2024-04-01T20:00:00Z"))

            insertVisit(anotherClub, insertUser(), actorId, Instant.parse("2024-05-09T21:00:00Z"))

            val result = repository.computeSegments(clubId = clubId, windowDays = 7, now = now)
            val summary = repository.getSummary(clubId = clubId, windowDays = 7)

            assertEquals(1, result.counts[SegmentType.NEW])
            assertEquals(1, result.counts[SegmentType.FREQUENT])
            assertEquals(1, result.counts[SegmentType.SLEEPING])

            assertEquals(result.counts, summary)

            val rowCount =
                newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
                    GuestSegmentsTable
                        .selectAll()
                        .where { GuestSegmentsTable.clubId eq clubId }
                        .count()
                }
            assertEquals(3, rowCount)

            val computedAt =
                newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
                    GuestSegmentsTable
                        .selectAll()
                        .where {
                            (GuestSegmentsTable.clubId eq clubId) and
                                (GuestSegmentsTable.userId eq frequentUser) and
                                (GuestSegmentsTable.windowDays eq 7)
                        }.single()[GuestSegmentsTable.computedAt]
                }
            assertNotNull(computedAt)
        }

    private suspend fun insertClub(): Long =
        newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
            Clubs.insert {
                it[name] = "Club ${System.nanoTime()}"
            }[Clubs.id].value.toLong()
        }

    private suspend fun insertUser(): Long =
        newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
            UsersTable.insert {
                it[telegramUserId] = System.nanoTime()
                it[username] = "user_${System.nanoTime()}"
                it[displayName] = "User"
            }[UsersTable.id]
        }

    private suspend fun insertVisit(
        clubId: Long,
        userId: Long,
        actorId: Long,
        checkinAt: Instant,
    ) {
        newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
            ClubVisitsTable.insert {
                it[ClubVisitsTable.clubId] = clubId
                it[nightStartUtc] = checkinAt.minusSeconds(3600).toOffsetDateTimeUtc()
                it[eventId] = null
                it[ClubVisitsTable.userId] = userId
                it[firstCheckinAt] = checkinAt.toOffsetDateTimeUtc()
                it[actorUserId] = actorId
                it[actorRole] = null
                it[entryType] = "QR"
                it[isEarly] = false
                it[hasTable] = false
                it[createdAt] = checkinAt.toOffsetDateTimeUtc()
                it[updatedAt] = checkinAt.toOffsetDateTimeUtc()
            }
        }
    }
}
