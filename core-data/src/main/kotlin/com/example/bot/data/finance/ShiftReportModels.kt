package com.example.bot.data.finance

import java.time.Instant

data class ClubReportTemplate(
    val clubId: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ClubBraceletType(
    val id: Long,
    val clubId: Long,
    val name: String,
    val enabled: Boolean,
    val orderIndex: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ClubRevenueGroup(
    val id: Long,
    val clubId: Long,
    val name: String,
    val enabled: Boolean,
    val orderIndex: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ClubRevenueArticle(
    val id: Long,
    val clubId: Long,
    val groupId: Long,
    val name: String,
    val enabled: Boolean,
    val includeInTotal: Boolean,
    val showSeparately: Boolean,
    val orderIndex: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class ShiftReportStatus {
    DRAFT,
    CLOSED,
}

data class ShiftReport(
    val id: Long,
    val clubId: Long,
    val nightStartUtc: Instant,
    val status: ShiftReportStatus,
    val peopleWomen: Int,
    val peopleMen: Int,
    val peopleRejected: Int,
    val comment: String?,
    val closedAt: Instant?,
    val closedBy: Long?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ShiftReportBracelet(
    val reportId: Long,
    val braceletTypeId: Long,
    val count: Int,
)

data class ShiftReportRevenueEntry(
    val id: Long,
    val reportId: Long,
    val articleId: Long?,
    val name: String,
    val groupId: Long,
    val amountMinor: Long,
    val includeInTotal: Boolean,
    val showSeparately: Boolean,
    val orderIndex: Int,
)

data class RevenueEntryInput(
    val articleId: Long?,
    val name: String?,
    val groupId: Long?,
    val amountMinor: Long,
    val includeInTotal: Boolean?,
    val showSeparately: Boolean?,
    val orderIndex: Int?,
)

data class ShiftReportUpdatePayload(
    val peopleWomen: Int,
    val peopleMen: Int,
    val peopleRejected: Int,
    val comment: String?,
    val bracelets: List<ShiftReportBraceletInput>,
    val revenueEntries: List<RevenueEntryInput>,
)

data class ShiftReportBraceletInput(
    val braceletTypeId: Long,
    val count: Int,
)

data class DepositHints(
    val sumDepositsForNight: Long,
    val allocationSummaryForNight: Map<String, Long>,
)
