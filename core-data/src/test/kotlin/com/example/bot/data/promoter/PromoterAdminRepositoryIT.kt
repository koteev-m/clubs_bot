package com.example.bot.data.promoter

import com.example.bot.data.club.PostgresClubIntegrationTest
import com.example.bot.data.security.Role
import com.example.bot.data.security.UserRolesTable
import com.example.bot.data.security.UsersTable
import com.example.bot.promoter.admin.PromoterAccessUpdateResult
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PromoterAdminRepositoryIT : PostgresClubIntegrationTest() {
    private val fixedInstant = Instant.parse("2024-08-01T12:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private lateinit var repository: PromoterAdminRepositoryImpl

    @BeforeEach
    fun setUpRepository() {
        repository = PromoterAdminRepositoryImpl(database, clock)
    }

    @Test
    fun `setPromoterAccess toggles access and roles`() =
        runBlocking {
            val clubId = insertClub(name = "Neon")
            val promoterId =
                insertUser(
                    telegramUserId = 777001,
                    username = "promo",
                    displayName = "Promo User",
                )

            val enabledResult = repository.setPromoterAccess(clubId, promoterId, true)
            assertTrue(enabledResult is PromoterAccessUpdateResult.Success)
            assertEquals(true, (enabledResult as PromoterAccessUpdateResult.Success).enabled)

            transaction(database) {
                val accessRow =
                    PromoterClubAccessTable
                        .selectAll()
                        .where {
                            (PromoterClubAccessTable.clubId eq clubId) and
                                (PromoterClubAccessTable.promoterUserId eq promoterId)
                        }.firstOrNull()
                assertNotNull(accessRow)
                assertEquals(true, accessRow!![PromoterClubAccessTable.accessEnabled])

                val roleRow =
                    UserRolesTable
                        .selectAll()
                        .where {
                            (UserRolesTable.userId eq promoterId) and
                                (UserRolesTable.roleCode eq Role.PROMOTER.name) and
                                (UserRolesTable.scopeType eq "CLUB") and
                                (UserRolesTable.scopeClubId eq clubId)
                        }.firstOrNull()
                assertNotNull(roleRow)
            }

            val promoters = repository.listPromotersByClub(clubId)
            assertEquals(1, promoters.size)
            val profile = promoters.first()
            assertEquals(promoterId, profile.promoterId)
            assertEquals(777001, profile.telegramUserId)
            assertEquals("promo", profile.username)
            assertEquals("Promo User", profile.displayName)
            assertEquals(true, profile.accessEnabled)

            val disabledResult = repository.setPromoterAccess(clubId, promoterId, false)
            assertTrue(disabledResult is PromoterAccessUpdateResult.Success)
            assertEquals(false, (disabledResult as PromoterAccessUpdateResult.Success).enabled)

            transaction(database) {
                val accessRow =
                    PromoterClubAccessTable
                        .selectAll()
                        .where {
                            (PromoterClubAccessTable.clubId eq clubId) and
                                (PromoterClubAccessTable.promoterUserId eq promoterId)
                        }.firstOrNull()
                assertNotNull(accessRow)
                assertEquals(false, accessRow!![PromoterClubAccessTable.accessEnabled])

                val roleRow =
                    UserRolesTable
                        .selectAll()
                        .where {
                            (UserRolesTable.userId eq promoterId) and
                                (UserRolesTable.roleCode eq Role.PROMOTER.name) and
                                (UserRolesTable.scopeType eq "CLUB") and
                                (UserRolesTable.scopeClubId eq clubId)
                        }.firstOrNull()
                assertNull(roleRow)
            }

            val afterDisable = repository.listPromotersByClub(clubId)
            assertEquals(1, afterDisable.size)
            assertEquals(false, afterDisable.first().accessEnabled)
        }

    private fun insertUser(
        telegramUserId: Long,
        username: String,
        displayName: String,
    ): Long =
        transaction(database) {
            UsersTable
                .insert {
                    it[UsersTable.telegramUserId] = telegramUserId
                    it[UsersTable.username] = username
                    it[UsersTable.displayName] = displayName
                    it[UsersTable.phoneE164] = null
                }.resultedValues!!
                .single()[UsersTable.id]
        }
}

private object PromoterClubAccessTable : Table("promoter_club_access") {
    val clubId = long("club_id")
    val promoterUserId = long("promoter_user_id")
    val accessEnabled = bool("access_enabled")

    override val primaryKey = PrimaryKey(clubId, promoterUserId)
}
