package com.example.bot.data.music

import com.example.bot.data.TestDatabase
import com.example.bot.data.db.Clubs
import com.example.bot.data.security.UsersTable
import com.example.bot.music.MusicBattleStatus
import com.example.bot.music.MusicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class MusicBattleAndStemsRepositoriesTest {
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
    fun `vote upsert is idempotent updates in active and rejects changes in closed`() =
        runBlocking {
            val clubId = insertClub("Battle Club")
            val userId = insertUser("voter")
            val (itemA, itemB) = insertItems(clubId)
            val battleRepo = MusicBattleRepositoryImpl(testDb.database)
            val voteRepo = MusicBattleVoteRepositoryImpl(testDb.database)
            val startsAt = Instant.parse("2024-04-01T20:00:00Z")
            val endsAt = startsAt.plusSeconds(3600)
            val battle =
                battleRepo.create(
                    clubId = clubId,
                    itemAId = itemA,
                    itemBId = itemB,
                    status = MusicBattleStatus.ACTIVE,
                    startsAt = startsAt,
                    endsAt = endsAt,
                )

            val first = voteRepo.upsertVote(battle.id, userId, itemA, startsAt.plusSeconds(30))
            val second = voteRepo.upsertVote(battle.id, userId, itemA, startsAt.plusSeconds(60))
            val changed = voteRepo.upsertVote(battle.id, userId, itemB, startsAt.plusSeconds(120))

            assertEquals(com.example.bot.music.MusicVoteUpsertResult.CREATED, first)
            assertEquals(com.example.bot.music.MusicVoteUpsertResult.UNCHANGED, second)
            assertEquals(com.example.bot.music.MusicVoteUpsertResult.UPDATED, changed)
            assertEquals(itemB, voteRepo.findUserVote(battle.id, userId)?.chosenItemId)

            battleRepo.setStatus(battle.id, MusicBattleStatus.CLOSED, endsAt.plusSeconds(1))
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    voteRepo.upsertVote(battle.id, userId, itemA, endsAt.plusSeconds(2))
                }
            }
        }

    @Test
    fun `aggregates return counts for both battle participants`() =
        runBlocking {
            val clubId = insertClub("Aggregate Club")
            val voterOne = insertUser("voter-one")
            val voterTwo = insertUser("voter-two")
            val voterThree = insertUser("voter-three")
            val (itemA, itemB) = insertItems(clubId)
            val battle = createActiveBattle(clubId, itemA, itemB)
            val voteRepo = MusicBattleVoteRepositoryImpl(testDb.database)
            val now = Instant.parse("2024-04-01T21:00:00Z")

            voteRepo.upsertVote(battle.id, voterOne, itemA, now)
            voteRepo.upsertVote(battle.id, voterTwo, itemA, now.plusSeconds(10))
            voteRepo.upsertVote(battle.id, voterThree, itemB, now.plusSeconds(20))

            val aggregate = voteRepo.aggregateVotes(battle.id)
            assertNotNull(aggregate)
            assertEquals(itemA, aggregate?.itemAId)
            assertEquals(itemB, aggregate?.itemBId)
            assertEquals(2, aggregate?.itemAVotes)
            assertEquals(1, aggregate?.itemBVotes)
        }


    @Test
    fun `aggregate returns zero counts for battle without votes`() =
        runBlocking {
            val clubId = insertClub("No Votes Club")
            val (itemA, itemB) = insertItems(clubId)
            val battle = createActiveBattle(clubId, itemA, itemB)
            val voteRepo = MusicBattleVoteRepositoryImpl(testDb.database)

            val aggregate = voteRepo.aggregateVotes(battle.id)

            assertNotNull(aggregate)
            assertEquals(itemA, aggregate?.itemAId)
            assertEquals(itemB, aggregate?.itemBId)
            assertEquals(0, aggregate?.itemAVotes)
            assertEquals(0, aggregate?.itemBVotes)
        }

    @Test
    fun `aggregate returns zero for side without votes`() =
        runBlocking {
            val clubId = insertClub("One Side Club")
            val voterOne = insertUser("one-side-voter-one")
            val voterTwo = insertUser("one-side-voter-two")
            val (itemA, itemB) = insertItems(clubId)
            val battle = createActiveBattle(clubId, itemA, itemB)
            val voteRepo = MusicBattleVoteRepositoryImpl(testDb.database)
            val now = Instant.parse("2024-04-01T21:00:00Z")

            voteRepo.upsertVote(battle.id, voterOne, itemA, now)
            voteRepo.upsertVote(battle.id, voterTwo, itemA, now.plusSeconds(10))

            val aggregate = voteRepo.aggregateVotes(battle.id)

            assertNotNull(aggregate)
            assertEquals(2, aggregate?.itemAVotes)
            assertEquals(0, aggregate?.itemBVotes)
        }

    @Test
    fun `list recent battles uses stable ordering by startsAt and id`() =
        runBlocking {
            val clubId = insertClub("Ordering Club")
            val (itemA, itemB) = insertItems(clubId)
            val battleRepo = MusicBattleRepositoryImpl(testDb.database)
            val olderStart = Instant.parse("2024-01-01T20:00:00Z")
            val newerStart = Instant.parse("2024-01-02T20:00:00Z")

            val newest = battleRepo.create(clubId, itemA, itemB, MusicBattleStatus.DRAFT, newerStart, newerStart.plusSeconds(600))
            val older = battleRepo.create(clubId, itemA, itemB, MusicBattleStatus.DRAFT, olderStart, olderStart.plusSeconds(600))
            val sameStartLaterInsert =
                battleRepo.create(clubId, itemA, itemB, MusicBattleStatus.DRAFT, newerStart, newerStart.plusSeconds(700))

            val listed = battleRepo.listRecent(clubId, limit = 10, offset = 0)
            assertEquals(listOf(sameStartLaterInsert.id, newest.id, older.id), listed.map { it.id })
        }

    @Test
    fun `stems link and read returns latest linked asset`() =
        runBlocking {
            val clubId = insertClub("Stems Club")
            val (itemId, _) = insertItems(clubId)
            val stemsRepo = MusicStemsRepositoryImpl(testDb.database)
            val firstAsset = insertAsset()
            val secondAsset = insertAsset()
            val now = Instant.parse("2024-04-02T10:00:00Z")

            stemsRepo.linkStemAsset(itemId, firstAsset, now)
            val updated = stemsRepo.linkStemAsset(itemId, secondAsset, now.plusSeconds(5))

            assertEquals(secondAsset, updated.assetId)
            assertEquals(secondAsset, stemsRepo.getStemAsset(itemId)?.assetId)
        }

    private suspend fun createActiveBattle(clubId: Long, itemAId: Long, itemBId: Long) =
        MusicBattleRepositoryImpl(testDb.database).create(
            clubId = clubId,
            itemAId = itemAId,
            itemBId = itemBId,
            status = MusicBattleStatus.ACTIVE,
            startsAt = Instant.parse("2024-04-01T20:00:00Z"),
            endsAt = Instant.parse("2024-04-01T22:00:00Z"),
        )

    private suspend fun insertClub(name: String): Long =
        newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
            Clubs.insert {
                it[Clubs.name] = name
            }[Clubs.id].value.toLong()
        }

    private suspend fun insertUser(username: String): Long =
        newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
            UsersTable.insert {
                it[telegramUserId] = System.nanoTime()
                it[UsersTable.username] = username
                it[displayName] = username
            }[UsersTable.id]
        }

    private suspend fun insertItems(clubId: Long): Pair<Long, Long> =
        newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
            val itemA =
                MusicItemsTable.insert {
                    it[MusicItemsTable.clubId] = clubId
                    it[title] = "Track A"
                    it[dj] = "DJ A"
                    it[sourceType] = MusicSource.FILE.name
                    it[sourceUrl] = null
                    it[telegramFileId] = null
                    it[durationSec] = 180
                    it[coverUrl] = null
                    it[tags] = "test"
                    it[publishedAt] = null
                    it[isActive] = true
                    it[createdBy] = null
                }[MusicItemsTable.id]
            val itemB =
                MusicItemsTable.insert {
                    it[MusicItemsTable.clubId] = clubId
                    it[title] = "Track B"
                    it[dj] = "DJ B"
                    it[sourceType] = MusicSource.FILE.name
                    it[sourceUrl] = null
                    it[telegramFileId] = null
                    it[durationSec] = 200
                    it[coverUrl] = null
                    it[tags] = "test"
                    it[publishedAt] = null
                    it[isActive] = true
                    it[createdBy] = null
                }[MusicItemsTable.id]
            itemA to itemB
        }

    private suspend fun insertAsset(): Long =
        newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
            MusicAssetsTable.insert {
                it[kind] = "AUDIO"
                it[bytes] = byteArrayOf(1, 2, 3)
                it[contentType] = "audio/mpeg"
                it[sha256] = "f".repeat(64)
                it[sizeBytes] = 3
            }[MusicAssetsTable.id]
        }
}
