package com.example.bot.data.finance

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object ClubReportTemplatesTable : Table("club_report_templates") {
    val clubId = long("club_id")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(clubId)
}

object ClubBraceletTypesTable : Table("club_bracelet_types") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val name = text("name")
    val enabled = bool("enabled").default(true)
    val orderIndex = integer("order_index").default(0)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_club_bracelet_types_club_order", false, clubId, orderIndex)
        index("idx_club_bracelet_types_club", false, clubId)
    }
}

object ClubRevenueGroupsTable : Table("club_revenue_groups") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val name = text("name")
    val enabled = bool("enabled").default(true)
    val orderIndex = integer("order_index").default(0)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_club_revenue_groups_club_order", false, clubId, orderIndex)
        index("idx_club_revenue_groups_club", false, clubId)
    }
}

object ClubRevenueArticlesTable : Table("club_revenue_articles") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val groupId = long("group_id")
    val name = text("name")
    val enabled = bool("enabled").default(true)
    val includeInTotal = bool("include_in_total").default(false)
    val showSeparately = bool("show_separately").default(false)
    val orderIndex = integer("order_index").default(0)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_club_revenue_articles_club_group_order", false, clubId, groupId, orderIndex)
        index("idx_club_revenue_articles_club", false, clubId)
    }
}

object ShiftReportsTable : Table("shift_reports") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val nightStartUtc = timestampWithTimeZone("night_start_utc")
    val status = text("status")
    val peopleWomen = integer("people_women").default(0)
    val peopleMen = integer("people_men").default(0)
    val peopleRejected = integer("people_rejected").default(0)
    val comment = text("comment").nullable()
    val closedAt = timestampWithTimeZone("closed_at").nullable()
    val closedBy = long("closed_by").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_shift_reports_club_night", clubId, nightStartUtc)
        index("idx_shift_reports_club_night", false, clubId, nightStartUtc)
        index("idx_shift_reports_club_status", false, clubId, status)
    }
}

object ShiftReportBraceletsTable : Table("shift_report_bracelets") {
    val reportId = long("report_id")
    val braceletTypeId = long("bracelet_type_id")
    val count = integer("count").default(0)

    override val primaryKey = PrimaryKey(reportId, braceletTypeId)

    init {
        index("idx_shift_report_bracelets_type", false, braceletTypeId)
    }
}

object ShiftReportRevenueEntriesTable : Table("shift_report_revenue_entries") {
    val id = long("id").autoIncrement()
    val reportId = long("report_id")
    val articleId = long("article_id").nullable()
    val name = text("name")
    val groupId = long("group_id")
    val amountMinor = long("amount_minor").default(0)
    val includeInTotal = bool("include_in_total").default(false)
    val showSeparately = bool("show_separately").default(false)
    val orderIndex = integer("order_index").default(0)

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_shift_report_revenue_entries_report_article", reportId, articleId)
        index("idx_shift_report_revenue_entries_report_group_order", false, reportId, groupId, orderIndex)
    }
}
