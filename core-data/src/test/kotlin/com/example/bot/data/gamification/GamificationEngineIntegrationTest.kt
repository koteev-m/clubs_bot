package com.example.bot.data.gamification

import com.example.bot.audit.AuditLogEvent
import com.example.bot.audit.AuditLogRecord
import com.example.bot.audit.AuditLogRepository
import com.example.bot.audit.AuditLogger
import com.example.bot.data.TestDatabase
import com.example.bot.data.db.Clubs
import com.example.bot.data.security.UsersTable
import com.example.bot.data.visits.VisitCheckInInput
import com.example.bot.data.visits.VisitRepository
import com.example.bot.gamification.GamificationEngine
import com.example.bot.gamification.GamificationSettingsRepository as DomainGamificationSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class GamificationEngineIntegrationTest {
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
    fun `creating visit twice produces delta once`() = runBlocking {
        val clubId = insertClub()
        val userId = insertUser()
        val settingsRepo = GamificationSettingsRepository(testDb.database)
        settingsRepo.upsert(
            ClubGamificationSettings(
                clubId = clubId,
                stampsEnabled = true,
                earlyEnabled = false,
                badgesEnabled = true,
                prizesEnabled = true,
                contestsEnabled = false,
                tablesLoyaltyEnabled = false,
                earlyWindowMinutes = null,
                updatedAt = Instant.parse("2024-02-01T00:00:00Z"),
            ),
        )

        val badgeId = insertBadge(clubId)
        val prizeId = insertPrize(clubId)
        insertRewardLevel(clubId, prizeId)

        val badgeRepo = BadgeRepository(testDb.database)
        val userBadgeRepo = UserBadgeRepository(testDb.database)
        val rewardLadderRepo = RewardLadderRepository(testDb.database)
        val prizeRepo = PrizeRepository(testDb.database)
        val couponRepo = CouponRepository(testDb.database)
        val visitRepo = VisitRepository(testDb.database)

        val engine =
            GamificationEngine(
                settingsRepository = GamificationSettingsRepositoryAdapter(settingsRepo),
                badgeRepository = BadgeRepositoryAdapter(badgeRepo),
                userBadgeRepository = UserBadgeRepositoryAdapter(userBadgeRepo),
                rewardLadderRepository = RewardLadderRepositoryAdapter(rewardLadderRepo),
                prizeRepository = PrizeRepositoryAdapter(prizeRepo),
                couponRepository = CouponRepositoryAdapter(couponRepo),
                visitMetricsRepository = VisitMetricsRepositoryAdapter(visitRepo),
                auditLogger = AuditLogger(FakeAuditLogRepository()),
            )

        val nightStart = Instant.parse("2024-02-10T20:00:00Z")
        val firstCheckin = Instant.parse("2024-02-10T21:00:00Z")
        val input =
            VisitCheckInInput(
                clubId = clubId,
                nightStartUtc = nightStart,
                eventId = null,
                userId = userId,
                actorUserId = userId,
                actorRole = null,
                entryType = "GUEST_LIST_ENTRY",
                firstCheckinAt = firstCheckin,
                effectiveEarlyCutoffAt = null,
            )
        val firstResult = visitRepo.tryCheckIn(input)
        val firstDelta =
            if (firstResult.created) engine.onVisitCreated(firstResult.visit.toDomain(), firstCheckin) else null

        val secondResult = visitRepo.tryCheckIn(input.copy(firstCheckinAt = firstCheckin.plusSeconds(60)))
        val secondDelta =
            if (secondResult.created) engine.onVisitCreated(secondResult.visit.toDomain(), firstCheckin) else null

        assertTrue(firstResult.created)
        assertEquals(1, firstDelta?.earnedBadges?.size)
        assertEquals(1, firstDelta?.issuedCoupons?.size)
        assertTrue(secondDelta == null || secondDelta.earnedBadges.isEmpty())
        assertTrue(secondDelta == null || secondDelta.issuedCoupons.isEmpty())
        assertEquals(badgeId, firstDelta?.earnedBadges?.first()?.badgeId)
        assertEquals(prizeId, firstDelta?.issuedCoupons?.first()?.prizeId)
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

    private suspend fun insertBadge(clubId: Long): Long =
        newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
            BadgesTable.insert {
                it[BadgesTable.clubId] = clubId
                it[code] = "VISITS"
                it[nameRu] = "Посещений"
                it[conditionType] = "VISITS"
                it[threshold] = 1
                it[enabled] = true
            }[BadgesTable.id]
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

    private suspend fun insertRewardLevel(
        clubId: Long,
        prizeId: Long,
    ) {
        newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
            RewardLadderLevelsTable.insert {
                it[RewardLadderLevelsTable.clubId] = clubId
                it[metricType] = "VISITS"
                it[threshold] = 1
                it[windowDays] = 0
                it[RewardLadderLevelsTable.prizeId] = prizeId
                it[enabled] = true
                it[orderIndex] = 0
            }
        }
    }
}

private class FakeAuditLogRepository : AuditLogRepository {
    override suspend fun append(event: AuditLogEvent): Long = 1L

    override suspend fun listForClub(clubId: Long, limit: Int, offset: Int): List<AuditLogRecord> = emptyList()

    override suspend fun listForUser(userId: Long, limit: Int, offset: Int): List<AuditLogRecord> = emptyList()
}
