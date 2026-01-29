package com.example.bot.data.gamification

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object ClubGamificationSettingsTable : Table("club_gamification_settings") {
    val clubId = long("club_id")
    val stampsEnabled = bool("stamps_enabled").default(true)
    val earlyEnabled = bool("early_enabled").default(false)
    val badgesEnabled = bool("badges_enabled").default(false)
    val prizesEnabled = bool("prizes_enabled").default(false)
    val contestsEnabled = bool("contests_enabled").default(false)
    val tablesLoyaltyEnabled = bool("tables_loyalty_enabled").default(false)
    val earlyWindowMinutes = integer("early_window_minutes").nullable()
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(clubId)
}

object BadgesTable : Table("badges") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val code = text("code")
    val nameRu = text("name_ru")
    val icon = text("icon").nullable()
    val enabled = bool("enabled").default(true)
    val conditionType = text("condition_type")
    val threshold = integer("threshold")
    val windowDays = integer("window_days").nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_badges_club_code", clubId, code)
        index("idx_badges_club_enabled", false, clubId, enabled)
    }
}

object UserBadgesTable : Table("user_badges") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val userId = long("user_id")
    val badgeId = long("badge_id")
    val earnedAt = timestampWithTimeZone("earned_at")
    val fingerprint = text("fingerprint")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_user_badges_fingerprint", fingerprint)
        uniqueIndex("uq_user_badges_club_user_badge", clubId, userId, badgeId)
        index("idx_user_badges_club_user", false, clubId, userId)
    }
}

object PrizesTable : Table("prizes") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val code = text("code")
    val titleRu = text("title_ru")
    val description = text("description").nullable()
    val terms = text("terms").nullable()
    val enabled = bool("enabled").default(true)
    val limitTotal = integer("limit_total").nullable()
    val expiresInDays = integer("expires_in_days").nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_prizes_club_code", clubId, code)
        index("idx_prizes_club_enabled", false, clubId, enabled)
    }
}

object RewardLadderLevelsTable : Table("reward_ladder_levels") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val metricType = text("metric_type")
    val threshold = integer("threshold")
    val windowDays = integer("window_days")
    val prizeId = long("prize_id")
    val enabled = bool("enabled").default(true)
    val orderIndex = integer("order_index").default(0)
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_reward_ladder_levels", clubId, metricType, threshold, windowDays)
        index("idx_reward_ladder_levels_club_metric", false, clubId, metricType, enabled)
    }
}

object RewardCouponsTable : Table("reward_coupons") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val userId = long("user_id")
    val prizeId = long("prize_id")
    val status = text("status")
    val reasonCode = text("reason_code").nullable()
    val fingerprint = text("fingerprint")
    val issuedAt = timestampWithTimeZone("issued_at")
    val expiresAt = timestampWithTimeZone("expires_at").nullable()
    val redeemedAt = timestampWithTimeZone("redeemed_at").nullable()
    val issuedBy = long("issued_by").nullable()
    val redeemedBy = long("redeemed_by").nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_reward_coupons_fingerprint", fingerprint)
        index("idx_reward_coupons_club_user_status", false, clubId, userId, status)
        index("idx_reward_coupons_club_status_issued", false, clubId, status, issuedAt)
    }
}
