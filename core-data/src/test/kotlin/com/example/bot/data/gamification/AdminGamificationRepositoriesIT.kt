package com.example.bot.data.gamification

import com.example.bot.admin.AdminBadgeCreate
import com.example.bot.admin.AdminBadgeUpdate
import com.example.bot.admin.AdminGamificationSettingsUpdate
import com.example.bot.admin.AdminPrizeCreate
import com.example.bot.admin.AdminPrizeUpdate
import com.example.bot.admin.AdminRewardLadderLevelCreate
import com.example.bot.admin.AdminRewardLadderLevelUpdate
import com.example.bot.data.club.PostgresClubIntegrationTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AdminGamificationRepositoriesIT : PostgresClubIntegrationTest() {
    @BeforeEach
    fun cleanGamificationTables() {
        transaction(database) {
            exec(
                """
                TRUNCATE TABLE
                    reward_coupons,
                    reward_ladder_levels,
                    prizes,
                    user_badges,
                    badges,
                    club_gamification_settings
                RESTART IDENTITY CASCADE
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `badge create list update delete respects club scoping`() =
        runBlocking {
            val clubId = insertClub("Badge Club")
            val otherClubId = insertClub("Other Club")
            val createClock = Clock.fixed(INITIAL_INSTANT, ZoneOffset.UTC)
            val updateClock = Clock.fixed(UPDATED_INSTANT, ZoneOffset.UTC)
            val repo = AdminBadgeRepositoryImpl(database, createClock)

            val created =
                repo.create(
                    clubId,
                    AdminBadgeCreate(
                        code = "visits",
                        nameRu = "Визиты",
                        icon = "star",
                        enabled = true,
                        conditionType = "VISITS",
                        threshold = 1,
                        windowDays = 30,
                    ),
                )

            assertEquals(clubId, created.clubId)
            assertEquals("visits", created.code)
            assertEquals("Визиты", created.nameRu)
            assertEquals("star", created.icon)
            assertEquals(true, created.enabled)
            assertEquals("VISITS", created.conditionType)
            assertEquals(1, created.threshold)
            assertEquals(30, created.windowDays)
            assertEquals(INITIAL_INSTANT, created.createdAt)
            assertEquals(INITIAL_INSTANT, created.updatedAt)

            val list = repo.listForClub(clubId)
            assertEquals(1, list.size)
            assertEquals(created.id, list.first().id)
            assertTrue(repo.listForClub(otherClubId).isEmpty())

            val updateRepo = AdminBadgeRepositoryImpl(database, updateClock)
            val updated =
                updateRepo.update(
                    clubId,
                    AdminBadgeUpdate(
                        id = created.id,
                        code = "early",
                        nameRu = "Ранние",
                        icon = null,
                        enabled = false,
                        conditionType = "EARLY",
                        threshold = 2,
                        windowDays = null,
                    ),
                )
            assertNotNull(updated)
            assertEquals("early", updated?.code)
            assertEquals("Ранние", updated?.nameRu)
            assertNull(updated?.icon)
            assertEquals(false, updated?.enabled)
            assertEquals("EARLY", updated?.conditionType)
            assertEquals(2, updated?.threshold)
            assertNull(updated?.windowDays)
            assertEquals(UPDATED_INSTANT, updated?.updatedAt)

            val foreignUpdate =
                updateRepo.update(
                    otherClubId,
                    AdminBadgeUpdate(
                        id = created.id,
                        code = "fail",
                        nameRu = "Не должно",
                        icon = null,
                        enabled = true,
                        conditionType = "VISITS",
                        threshold = 1,
                        windowDays = null,
                    ),
                )
            assertNull(foreignUpdate)

            assertFalse(updateRepo.delete(otherClubId, created.id))
            assertTrue(updateRepo.delete(clubId, created.id))
            assertTrue(updateRepo.listForClub(clubId).isEmpty())
        }

    @Test
    fun `prize create list update delete preserves nullable fields`() =
        runBlocking {
            val clubId = insertClub("Prize Club")
            val otherClubId = insertClub("Other Prize Club")
            val createClock = Clock.fixed(INITIAL_INSTANT, ZoneOffset.UTC)
            val updateClock = Clock.fixed(UPDATED_INSTANT, ZoneOffset.UTC)
            val repo = AdminPrizeRepositoryImpl(database, createClock)

            val created =
                repo.create(
                    clubId,
                    AdminPrizeCreate(
                        code = "free_entry",
                        titleRu = "Бесплатный вход",
                        description = "Разово",
                        terms = null,
                        enabled = true,
                        limitTotal = 10,
                        expiresInDays = 7,
                    ),
                )

            assertEquals(clubId, created.clubId)
            assertEquals(10, created.limitTotal)
            assertEquals(7, created.expiresInDays)
            assertEquals(INITIAL_INSTANT, created.createdAt)
            assertEquals(INITIAL_INSTANT, created.updatedAt)

            val list = repo.listForClub(clubId)
            assertEquals(1, list.size)
            assertEquals(created.id, list.first().id)

            val updateRepo = AdminPrizeRepositoryImpl(database, updateClock)
            val updated =
                updateRepo.update(
                    clubId,
                    AdminPrizeUpdate(
                        id = created.id,
                        code = "free_entry",
                        titleRu = "Бесплатный вход",
                        description = null,
                        terms = "Условия",
                        enabled = false,
                        limitTotal = null,
                        expiresInDays = null,
                    ),
                )

            assertNotNull(updated)
            assertEquals(false, updated?.enabled)
            assertNull(updated?.limitTotal)
            assertNull(updated?.expiresInDays)
            assertEquals(UPDATED_INSTANT, updated?.updatedAt)

            assertFalse(updateRepo.delete(otherClubId, created.id))
            assertTrue(updateRepo.delete(clubId, created.id))
            assertTrue(updateRepo.listForClub(clubId).isEmpty())
        }

    @Test
    fun `reward ladder list ordered by orderIndex and supports update delete`() =
        runBlocking {
            val clubId = insertClub("Ladder Club")
            val prizeId =
                AdminPrizeRepositoryImpl(database, Clock.fixed(INITIAL_INSTANT, ZoneOffset.UTC))
                    .create(
                        clubId,
                        AdminPrizeCreate(
                            code = "welcome",
                            titleRu = "Приз",
                            description = null,
                            terms = null,
                            enabled = true,
                            limitTotal = null,
                            expiresInDays = null,
                        ),
                    ).id

            val repo = AdminRewardLadderRepositoryImpl(database, Clock.fixed(INITIAL_INSTANT, ZoneOffset.UTC))
            val first =
                repo.create(
                    clubId,
                    AdminRewardLadderLevelCreate(
                        metricType = "VISITS",
                        threshold = 5,
                        windowDays = 30,
                        prizeId = prizeId,
                        enabled = true,
                        orderIndex = 2,
                    ),
                )
            val second =
                repo.create(
                    clubId,
                    AdminRewardLadderLevelCreate(
                        metricType = "VISITS",
                        threshold = 10,
                        windowDays = 30,
                        prizeId = prizeId,
                        enabled = true,
                        orderIndex = 1,
                    ),
                )

            val list = repo.listForClub(clubId)
            assertEquals(listOf(second.id, first.id), list.map { it.id })
            assertEquals(listOf(1, 2), list.map { it.orderIndex })

            val updated =
                AdminRewardLadderRepositoryImpl(database, Clock.fixed(UPDATED_INSTANT, ZoneOffset.UTC))
                    .update(
                        clubId,
                        AdminRewardLadderLevelUpdate(
                            id = first.id,
                            metricType = "TABLE_NIGHTS",
                            threshold = 3,
                            windowDays = 15,
                            prizeId = prizeId,
                            enabled = false,
                            orderIndex = 0,
                        ),
                    )

            assertNotNull(updated)
            assertEquals("TABLE_NIGHTS", updated?.metricType)
            assertEquals(0, updated?.orderIndex)
            assertEquals(UPDATED_INSTANT, updated?.updatedAt)

            val reordered = repo.listForClub(clubId)
            assertEquals(listOf(first.id, second.id), reordered.map { it.id })

            assertTrue(repo.delete(clubId, second.id))
            assertFalse(repo.delete(999, first.id))
        }

    @Test
    fun `gamification settings upsert updates updatedAt`() =
        runBlocking {
            val clubId = insertClub("Settings Club")
            val clock = Clock.fixed(UPDATED_INSTANT, ZoneOffset.UTC)
            val repo = GamificationSettingsRepository(database)
            val adminRepo = AdminGamificationSettingsRepositoryImpl(repo, clock)

            val saved =
                adminRepo.upsert(
                    AdminGamificationSettingsUpdate(
                        clubId = clubId,
                        stampsEnabled = true,
                        earlyEnabled = false,
                        badgesEnabled = true,
                        prizesEnabled = true,
                        contestsEnabled = false,
                        tablesLoyaltyEnabled = false,
                        earlyWindowMinutes = 60,
                    ),
                )

            assertEquals(UPDATED_INSTANT, saved.updatedAt)
            assertNotEquals(Instant.EPOCH, saved.updatedAt)
        }

    private companion object {
        private val INITIAL_INSTANT = Instant.parse("2024-01-01T10:00:00Z")
        private val UPDATED_INSTANT = Instant.parse("2024-01-01T12:00:00Z")
    }
}
