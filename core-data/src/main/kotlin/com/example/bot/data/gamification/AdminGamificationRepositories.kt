package com.example.bot.data.gamification

import com.example.bot.admin.AdminBadge
import com.example.bot.admin.AdminBadgeCreate
import com.example.bot.admin.AdminBadgeRepository
import com.example.bot.admin.AdminBadgeUpdate
import com.example.bot.admin.AdminGamificationSettings
import com.example.bot.admin.AdminGamificationSettingsRepository
import com.example.bot.admin.AdminGamificationSettingsUpdate
import com.example.bot.admin.AdminNightOverride
import com.example.bot.admin.AdminNightOverrideRepository
import com.example.bot.admin.AdminPrize
import com.example.bot.admin.AdminPrizeCreate
import com.example.bot.admin.AdminPrizeRepository
import com.example.bot.admin.AdminPrizeUpdate
import com.example.bot.admin.AdminRewardLadderLevel
import com.example.bot.admin.AdminRewardLadderLevelCreate
import com.example.bot.admin.AdminRewardLadderLevelUpdate
import com.example.bot.admin.AdminRewardLadderRepository
import com.example.bot.data.db.toOffsetDateTimeUtc
import com.example.bot.data.db.withRetriedTx
import com.example.bot.data.visits.NightOverrideRepository
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Clock
import java.time.Instant

class AdminGamificationSettingsRepositoryImpl(
    private val repository: GamificationSettingsRepository,
) : AdminGamificationSettingsRepository {
    override suspend fun getByClubId(clubId: Long): AdminGamificationSettings? =
        repository.getByClubId(clubId)?.toAdmin()

    override suspend fun upsert(settings: AdminGamificationSettingsUpdate): AdminGamificationSettings =
        repository
            .upsert(
                ClubGamificationSettings(
                    clubId = settings.clubId,
                    stampsEnabled = settings.stampsEnabled,
                    earlyEnabled = settings.earlyEnabled,
                    badgesEnabled = settings.badgesEnabled,
                    prizesEnabled = settings.prizesEnabled,
                    contestsEnabled = settings.contestsEnabled,
                    tablesLoyaltyEnabled = settings.tablesLoyaltyEnabled,
                    earlyWindowMinutes = settings.earlyWindowMinutes,
                    updatedAt = Instant.EPOCH,
                ),
            ).toAdmin()

    private fun ClubGamificationSettings.toAdmin(): AdminGamificationSettings =
        AdminGamificationSettings(
            clubId = clubId,
            stampsEnabled = stampsEnabled,
            earlyEnabled = earlyEnabled,
            badgesEnabled = badgesEnabled,
            prizesEnabled = prizesEnabled,
            contestsEnabled = contestsEnabled,
            tablesLoyaltyEnabled = tablesLoyaltyEnabled,
            earlyWindowMinutes = earlyWindowMinutes,
            updatedAt = updatedAt,
        )
}

class AdminNightOverrideRepositoryImpl(
    private val repository: NightOverrideRepository,
) : AdminNightOverrideRepository {
    override suspend fun getOverride(clubId: Long, nightStartUtc: Instant): AdminNightOverride? =
        repository.getOverride(clubId, nightStartUtc)?.toAdmin()

    override suspend fun upsertOverride(
        clubId: Long,
        nightStartUtc: Instant,
        earlyCutoffAt: Instant?,
    ): AdminNightOverride =
        repository.upsertOverride(clubId, nightStartUtc, earlyCutoffAt).toAdmin()

    private fun com.example.bot.data.visits.NightOverride.toAdmin(): AdminNightOverride =
        AdminNightOverride(
            clubId = clubId,
            nightStartUtc = nightStartUtc,
            earlyCutoffAt = earlyCutoffAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}

class AdminBadgeRepositoryImpl(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) : AdminBadgeRepository {
    override suspend fun listForClub(clubId: Long): List<AdminBadge> =
        withRetriedTx(name = "admin.badges.list", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                BadgesTable
                    .selectAll()
                    .where { BadgesTable.clubId eq clubId }
                    .orderBy(BadgesTable.id, SortOrder.ASC)
                    .map { it.toAdminBadge() }
            }
        }

    override suspend fun create(clubId: Long, request: AdminBadgeCreate): AdminBadge =
        withRetriedTx(name = "admin.badges.create", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val now = Instant.now(clock).toOffsetDateTimeUtc()
                val id =
                    BadgesTable.insert {
                        it[BadgesTable.clubId] = clubId
                        it[code] = request.code
                        it[nameRu] = request.nameRu
                        it[icon] = request.icon
                        it[enabled] = request.enabled
                        it[conditionType] = request.conditionType
                        it[threshold] = request.threshold
                        it[windowDays] = request.windowDays
                        it[createdAt] = now
                        it[updatedAt] = now
                    } get BadgesTable.id
                BadgesTable
                    .selectAll()
                    .where { BadgesTable.id eq id }
                    .limit(1)
                    .first()
                    .toAdminBadge()
            }
        }

    override suspend fun update(clubId: Long, request: AdminBadgeUpdate): AdminBadge? =
        withRetriedTx(name = "admin.badges.update", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val now = Instant.now(clock).toOffsetDateTimeUtc()
                val updated =
                    BadgesTable.update({
                        (BadgesTable.id eq request.id) and (BadgesTable.clubId eq clubId)
                    }) {
                        it[code] = request.code
                        it[nameRu] = request.nameRu
                        it[icon] = request.icon
                        it[enabled] = request.enabled
                        it[conditionType] = request.conditionType
                        it[threshold] = request.threshold
                        it[windowDays] = request.windowDays
                        it[updatedAt] = now
                    }
                if (updated == 0) {
                    null
                } else {
                    BadgesTable
                        .selectAll()
                        .where { BadgesTable.id eq request.id }
                        .limit(1)
                        .firstOrNull()
                        ?.toAdminBadge()
                }
            }
        }

    override suspend fun delete(clubId: Long, id: Long): Boolean =
        withRetriedTx(name = "admin.badges.delete", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                BadgesTable.deleteWhere { (BadgesTable.id eq id) and (BadgesTable.clubId eq clubId) } > 0
            }
        }

    private fun ResultRow.toAdminBadge(): AdminBadge =
        AdminBadge(
            id = this[BadgesTable.id],
            clubId = this[BadgesTable.clubId],
            code = this[BadgesTable.code],
            nameRu = this[BadgesTable.nameRu],
            icon = this[BadgesTable.icon],
            enabled = this[BadgesTable.enabled],
            conditionType = this[BadgesTable.conditionType],
            threshold = this[BadgesTable.threshold],
            windowDays = this[BadgesTable.windowDays],
            createdAt = this[BadgesTable.createdAt].toInstant(),
            updatedAt = this[BadgesTable.updatedAt].toInstant(),
        )
}

class AdminPrizeRepositoryImpl(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) : AdminPrizeRepository {
    override suspend fun listForClub(clubId: Long): List<AdminPrize> =
        withRetriedTx(name = "admin.prizes.list", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                PrizesTable
                    .selectAll()
                    .where { PrizesTable.clubId eq clubId }
                    .orderBy(PrizesTable.id, SortOrder.ASC)
                    .map { it.toAdminPrize() }
            }
        }

    override suspend fun create(clubId: Long, request: AdminPrizeCreate): AdminPrize =
        withRetriedTx(name = "admin.prizes.create", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val now = Instant.now(clock).toOffsetDateTimeUtc()
                val id =
                    PrizesTable.insert {
                        it[PrizesTable.clubId] = clubId
                        it[code] = request.code
                        it[titleRu] = request.titleRu
                        it[description] = request.description
                        it[terms] = request.terms
                        it[enabled] = request.enabled
                        it[limitTotal] = request.limitTotal
                        it[expiresInDays] = request.expiresInDays
                        it[createdAt] = now
                        it[updatedAt] = now
                    } get PrizesTable.id
                PrizesTable
                    .selectAll()
                    .where { PrizesTable.id eq id }
                    .limit(1)
                    .first()
                    .toAdminPrize()
            }
        }

    override suspend fun update(clubId: Long, request: AdminPrizeUpdate): AdminPrize? =
        withRetriedTx(name = "admin.prizes.update", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val now = Instant.now(clock).toOffsetDateTimeUtc()
                val updated =
                    PrizesTable.update({
                        (PrizesTable.id eq request.id) and (PrizesTable.clubId eq clubId)
                    }) {
                        it[code] = request.code
                        it[titleRu] = request.titleRu
                        it[description] = request.description
                        it[terms] = request.terms
                        it[enabled] = request.enabled
                        it[limitTotal] = request.limitTotal
                        it[expiresInDays] = request.expiresInDays
                        it[updatedAt] = now
                    }
                if (updated == 0) {
                    null
                } else {
                    PrizesTable
                        .selectAll()
                        .where { PrizesTable.id eq request.id }
                        .limit(1)
                        .firstOrNull()
                        ?.toAdminPrize()
                }
            }
        }

    override suspend fun delete(clubId: Long, id: Long): Boolean =
        withRetriedTx(name = "admin.prizes.delete", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                PrizesTable.deleteWhere { (PrizesTable.id eq id) and (PrizesTable.clubId eq clubId) } > 0
            }
        }

    private fun ResultRow.toAdminPrize(): AdminPrize =
        AdminPrize(
            id = this[PrizesTable.id],
            clubId = this[PrizesTable.clubId],
            code = this[PrizesTable.code],
            titleRu = this[PrizesTable.titleRu],
            description = this[PrizesTable.description],
            terms = this[PrizesTable.terms],
            enabled = this[PrizesTable.enabled],
            limitTotal = this[PrizesTable.limitTotal],
            expiresInDays = this[PrizesTable.expiresInDays],
            createdAt = this[PrizesTable.createdAt].toInstant(),
            updatedAt = this[PrizesTable.updatedAt].toInstant(),
        )
}

class AdminRewardLadderRepositoryImpl(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) : AdminRewardLadderRepository {
    override suspend fun listForClub(clubId: Long): List<AdminRewardLadderLevel> =
        withRetriedTx(name = "admin.ladder.list", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                RewardLadderLevelsTable
                    .selectAll()
                    .where { RewardLadderLevelsTable.clubId eq clubId }
                    .orderBy(RewardLadderLevelsTable.orderIndex, SortOrder.ASC)
                    .map { it.toAdminLevel() }
            }
        }

    override suspend fun create(
        clubId: Long,
        request: AdminRewardLadderLevelCreate,
    ): AdminRewardLadderLevel =
        withRetriedTx(name = "admin.ladder.create", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val now = Instant.now(clock).toOffsetDateTimeUtc()
                val id =
                    RewardLadderLevelsTable.insert {
                        it[RewardLadderLevelsTable.clubId] = clubId
                        it[metricType] = request.metricType
                        it[threshold] = request.threshold
                        it[windowDays] = request.windowDays
                        it[prizeId] = request.prizeId
                        it[enabled] = request.enabled
                        it[orderIndex] = request.orderIndex
                        it[createdAt] = now
                        it[updatedAt] = now
                    } get RewardLadderLevelsTable.id
                RewardLadderLevelsTable
                    .selectAll()
                    .where { RewardLadderLevelsTable.id eq id }
                    .limit(1)
                    .first()
                    .toAdminLevel()
            }
        }

    override suspend fun update(
        clubId: Long,
        request: AdminRewardLadderLevelUpdate,
    ): AdminRewardLadderLevel? =
        withRetriedTx(name = "admin.ladder.update", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val now = Instant.now(clock).toOffsetDateTimeUtc()
                val updated =
                    RewardLadderLevelsTable.update({
                        (RewardLadderLevelsTable.id eq request.id) and (RewardLadderLevelsTable.clubId eq clubId)
                    }) {
                        it[metricType] = request.metricType
                        it[threshold] = request.threshold
                        it[windowDays] = request.windowDays
                        it[prizeId] = request.prizeId
                        it[enabled] = request.enabled
                        it[orderIndex] = request.orderIndex
                        it[updatedAt] = now
                    }
                if (updated == 0) {
                    null
                } else {
                    RewardLadderLevelsTable
                        .selectAll()
                        .where { RewardLadderLevelsTable.id eq request.id }
                        .limit(1)
                        .firstOrNull()
                        ?.toAdminLevel()
                }
            }
        }

    override suspend fun delete(clubId: Long, id: Long): Boolean =
        withRetriedTx(name = "admin.ladder.delete", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                RewardLadderLevelsTable.deleteWhere {
                    (RewardLadderLevelsTable.id eq id) and (RewardLadderLevelsTable.clubId eq clubId)
                } > 0
            }
        }

    private fun ResultRow.toAdminLevel(): AdminRewardLadderLevel =
        AdminRewardLadderLevel(
            id = this[RewardLadderLevelsTable.id],
            clubId = this[RewardLadderLevelsTable.clubId],
            metricType = this[RewardLadderLevelsTable.metricType],
            threshold = this[RewardLadderLevelsTable.threshold],
            windowDays = this[RewardLadderLevelsTable.windowDays],
            prizeId = this[RewardLadderLevelsTable.prizeId],
            enabled = this[RewardLadderLevelsTable.enabled],
            orderIndex = this[RewardLadderLevelsTable.orderIndex],
            createdAt = this[RewardLadderLevelsTable.createdAt].toInstant(),
            updatedAt = this[RewardLadderLevelsTable.updatedAt].toInstant(),
        )
}
