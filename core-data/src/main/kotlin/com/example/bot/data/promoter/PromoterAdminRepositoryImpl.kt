package com.example.bot.data.promoter

import com.example.bot.data.db.withTxRetry
import com.example.bot.data.security.Role
import com.example.bot.promoter.admin.PromoterAccessUpdateResult
import com.example.bot.promoter.admin.PromoterAdminProfile
import com.example.bot.promoter.admin.PromoterAdminRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

private object PromoterUsersTable : Table("users") {
    val id = long("id")
    val telegramUserId = long("telegram_user_id")
    val username = text("username").nullable()
    val displayName = text("display_name").nullable()

    override val primaryKey = PrimaryKey(id)
}

private object PromoterUserRolesTable : Table("user_roles") {
    val id = long("id")
    val userId = long("user_id")
    val roleCode = text("role_code")
    val scopeType = text("scope_type")
    val scopeClubId = long("scope_club_id").nullable()

    override val primaryKey = PrimaryKey(id)
}

private object PromoterClubAccessTable : Table("promoter_club_access") {
    val clubId = long("club_id")
    val promoterUserId = long("promoter_user_id")
    val accessEnabled = bool("access_enabled")
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(clubId, promoterUserId)
}

class PromoterAdminRepositoryImpl(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) : PromoterAdminRepository {
    override suspend fun listPromotersByClub(clubId: Long): List<PromoterAdminProfile> =
        withTxRetry {
            transaction(db) {
                val accessByPromoter =
                    PromoterClubAccessTable
                        .selectAll()
                        .where { PromoterClubAccessTable.clubId eq clubId }
                        .associate { row ->
                            row[PromoterClubAccessTable.promoterUserId] to row[PromoterClubAccessTable.accessEnabled]
                        }

                val promotersByRole =
                    PromoterUserRolesTable
                        .selectAll()
                        .where {
                            (PromoterUserRolesTable.roleCode eq Role.PROMOTER.name) and
                                (PromoterUserRolesTable.scopeType eq "CLUB") and
                                (PromoterUserRolesTable.scopeClubId eq clubId)
                        }.mapTo(mutableSetOf()) { row ->
                            row[PromoterUserRolesTable.userId]
                        }

                val promoterIds = (accessByPromoter.keys + promotersByRole).distinct()
                if (promoterIds.isEmpty()) return@transaction emptyList()

                val usersById =
                    PromoterUsersTable
                        .selectAll()
                        .where { PromoterUsersTable.id inList promoterIds }
                        .associateBy { it[PromoterUsersTable.id] }

                promoterIds.mapNotNull { promoterId ->
                    val userRow = usersById[promoterId] ?: return@mapNotNull null
                    PromoterAdminProfile(
                        promoterId = promoterId,
                        telegramUserId = userRow[PromoterUsersTable.telegramUserId],
                        username = userRow[PromoterUsersTable.username],
                        displayName = userRow[PromoterUsersTable.displayName],
                        accessEnabled = accessByPromoter[promoterId] ?: promotersByRole.contains(promoterId),
                    )
                }
            }
        }

    override suspend fun setPromoterAccess(
        clubId: Long,
        promoterId: Long,
        enabled: Boolean,
    ): PromoterAccessUpdateResult =
        withTxRetry {
            transaction(db) {
                PromoterUsersTable
                    .selectAll()
                    .where { PromoterUsersTable.id eq promoterId }
                    .limit(1)
                    .firstOrNull()
                    ?: return@transaction PromoterAccessUpdateResult.NotFound

                upsertAccessRow(clubId, promoterId, enabled)
                syncUserRole(clubId, promoterId, enabled)

                PromoterAccessUpdateResult.Success(enabled)
            }
        }

    private fun upsertAccessRow(
        clubId: Long,
        promoterId: Long,
        enabled: Boolean,
    ) {
        val now = Instant.now(clock).atOffset(ZoneOffset.UTC)
        val updated =
            PromoterClubAccessTable.update(
                where = { (PromoterClubAccessTable.clubId eq clubId) and (PromoterClubAccessTable.promoterUserId eq promoterId) },
            ) {
                it[accessEnabled] = enabled
                it[updatedAt] = now
            }
        if (updated == 0) {
            PromoterClubAccessTable.insert {
                it[this.clubId] = clubId
                it[this.promoterUserId] = promoterId
                it[this.accessEnabled] = enabled
                it[this.updatedAt] = now
            }
        }
    }

    private fun syncUserRole(
        clubId: Long,
        promoterId: Long,
        enabled: Boolean,
    ) {
        if (enabled) {
            val exists =
                PromoterUserRolesTable
                    .selectAll()
                    .where {
                        (PromoterUserRolesTable.userId eq promoterId) and
                            (PromoterUserRolesTable.roleCode eq Role.PROMOTER.name) and
                            (PromoterUserRolesTable.scopeType eq "CLUB") and
                            (PromoterUserRolesTable.scopeClubId eq clubId)
                    }.limit(1)
                    .firstOrNull() != null
            if (!exists) {
                PromoterUserRolesTable.insert {
                    it[userId] = promoterId
                    it[roleCode] = Role.PROMOTER.name
                    it[scopeType] = "CLUB"
                    it[scopeClubId] = clubId
                }
            }
        } else {
            PromoterUserRolesTable.deleteWhere {
                (PromoterUserRolesTable.userId eq promoterId) and
                    (PromoterUserRolesTable.roleCode eq Role.PROMOTER.name) and
                    (PromoterUserRolesTable.scopeType eq "CLUB") and
                    (PromoterUserRolesTable.scopeClubId eq clubId)
            }
        }
        }
}
