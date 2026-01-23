package com.example.bot.data.promoter

import com.example.bot.data.db.isUniqueViolation
import com.example.bot.data.db.withTxRetry
import com.example.bot.data.security.Role
import com.example.bot.data.security.UserRolesTable
import com.example.bot.data.security.UsersTable
import com.example.bot.promoter.admin.PromoterAccessUpdateResult
import com.example.bot.promoter.admin.PromoterAdminProfile
import com.example.bot.promoter.admin.PromoterAdminRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

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
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val accessByPromoter =
                    PromoterClubAccessTable
                        .selectAll()
                        .where { PromoterClubAccessTable.clubId eq clubId }
                        .associate { row ->
                            row[PromoterClubAccessTable.promoterUserId] to row[PromoterClubAccessTable.accessEnabled]
                        }

                val promotersByRole =
                    UserRolesTable
                        .selectAll()
                        .where {
                            (UserRolesTable.roleCode eq Role.PROMOTER.name) and
                                (UserRolesTable.scopeType eq "CLUB") and
                                (UserRolesTable.scopeClubId eq clubId)
                        }.mapTo(mutableSetOf()) { row ->
                            row[UserRolesTable.userId]
                        }

                val promoterIds = (accessByPromoter.keys + promotersByRole).distinct()
                if (promoterIds.isEmpty()) return@newSuspendedTransaction emptyList()

                val usersById =
                    UsersTable
                        .selectAll()
                        .where { UsersTable.id inList promoterIds }
                        .associateBy { it[UsersTable.id] }

                promoterIds.mapNotNull { promoterId ->
                    val userRow = usersById[promoterId] ?: return@mapNotNull null
                    PromoterAdminProfile(
                        promoterId = promoterId,
                        telegramUserId = userRow[UsersTable.telegramUserId],
                        username = userRow[UsersTable.username],
                        displayName = userRow[UsersTable.displayName],
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
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                UsersTable
                    .selectAll()
                    .where { UsersTable.id eq promoterId }
                    .limit(1)
                    .firstOrNull()
                    ?: return@newSuspendedTransaction PromoterAccessUpdateResult.NotFound

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
            tryInsertAccessRow(clubId, promoterId, enabled, now)
        }
    }

    private fun tryInsertAccessRow(
        clubId: Long,
        promoterId: Long,
        enabled: Boolean,
        now: java.time.OffsetDateTime,
    ) {
        try {
            PromoterClubAccessTable.insert {
                it[this.clubId] = clubId
                it[this.promoterUserId] = promoterId
                it[this.accessEnabled] = enabled
                it[this.updatedAt] = now
            }
        } catch (ex: Exception) {
            if (!ex.isUniqueViolation()) {
                throw ex
            }
            PromoterClubAccessTable.update(
                where = { (PromoterClubAccessTable.clubId eq clubId) and (PromoterClubAccessTable.promoterUserId eq promoterId) },
            ) {
                it[accessEnabled] = enabled
                it[updatedAt] = now
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
                UserRolesTable
                    .selectAll()
                    .where {
                        (UserRolesTable.userId eq promoterId) and
                            (UserRolesTable.roleCode eq Role.PROMOTER.name) and
                            (UserRolesTable.scopeType eq "CLUB") and
                            (UserRolesTable.scopeClubId eq clubId)
                    }.limit(1)
                    .firstOrNull() != null
            if (!exists) {
                try {
                    UserRolesTable.insert {
                        it[userId] = promoterId
                        it[roleCode] = Role.PROMOTER.name
                        it[scopeType] = "CLUB"
                        it[scopeClubId] = clubId
                    }
                } catch (ex: Exception) {
                    if (!ex.isUniqueViolation()) {
                        throw ex
                    }
                }
            }
        } else {
            UserRolesTable.deleteWhere {
                (UserRolesTable.userId eq promoterId) and
                    (UserRolesTable.roleCode eq Role.PROMOTER.name) and
                    (UserRolesTable.scopeType eq "CLUB") and
                    (UserRolesTable.scopeClubId eq clubId)
            }
        }
    }
}
