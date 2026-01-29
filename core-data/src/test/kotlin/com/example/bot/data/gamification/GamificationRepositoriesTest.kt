package com.example.bot.data.gamification

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

class GamificationRepositoriesTest {
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
    fun `tryIssue is idempotent and redeem is guarded`() =
        runBlocking {
            val clubId = insertClub()
            val userId = insertUser()
            val prizeId = insertPrize(clubId)
            val repo = CouponRepository(testDb.database)
            val issuedAt = Instant.parse("2024-01-11T10:00:00Z")

            val first = repo.tryIssue("coupon-fp", clubId, userId, prizeId, issuedAt)
            val second = repo.tryIssue("coupon-fp", clubId, userId, prizeId, issuedAt)

            assertEquals(first.id, second.id)

            val redeemed = repo.redeem(first.id, managerId = userId, now = issuedAt.plusSeconds(60))
            val redeemedAgain = repo.redeem(first.id, managerId = userId, now = issuedAt.plusSeconds(120))

            assertTrue(redeemed)
            assertFalse(redeemedAgain)

            val coupons = repo.listForUser(clubId, userId, setOf(CouponStatus.REDEEMED))
            assertEquals(1, coupons.size)
            assertEquals(CouponStatus.REDEEMED, coupons.first().status)
        }

    @Test
    fun `tryEarn is idempotent`() =
        runBlocking {
            val clubId = insertClub()
            val userId = insertUser()
            val badgeId = insertBadge(clubId)
            val repo = UserBadgeRepository(testDb.database)
            val earnedAt = Instant.parse("2024-02-01T12:00:00Z")

            val first = repo.tryEarn("badge-fp", clubId, userId, badgeId, earnedAt)
            val second = repo.tryEarn("badge-fp", clubId, userId, badgeId, earnedAt)

            assertEquals(first.id, second.id)

            val total =
                newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
                    UserBadgesTable.selectAll().count()
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

    private suspend fun insertPrize(clubId: Long): Long =
        newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
            PrizesTable.insert {
                it[PrizesTable.clubId] = clubId
                it[code] = "WELCOME"
                it[titleRu] = "Приз"
                it[enabled] = true
            }[PrizesTable.id]
        }

    private suspend fun insertBadge(clubId: Long): Long =
        newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
            BadgesTable.insert {
                it[BadgesTable.clubId] = clubId
                it[code] = "EARLY"
                it[nameRu] = "Ранняя пташка"
                it[conditionType] = "EARLY_VISIT"
                it[threshold] = 1
                it[enabled] = true
            }[BadgesTable.id]
        }
}
