package com.example.bot.data.finance

data class RevenueGroupTotal(
    val groupId: Long,
    val groupName: String?,
    val amountMinor: Long,
)

data class NonTotalIndicators(
    val all: List<ShiftReportRevenueEntry>,
    val showSeparately: List<ShiftReportRevenueEntry>,
)

fun totalsPerGroup(
    entries: List<ShiftReportRevenueEntry>,
    groupNames: Map<Long, String> = emptyMap(),
): List<RevenueGroupTotal> {
    val totals = entries.filter { it.includeInTotal }.groupBy { it.groupId }
    return totals
        .map { (groupId, groupEntries) ->
            RevenueGroupTotal(
                groupId = groupId,
                groupName = groupNames[groupId],
                amountMinor = groupEntries.sumOf { it.amountMinor },
            )
        }.sortedBy { it.groupId }
}

fun totalRevenue(entries: List<ShiftReportRevenueEntry>): Long =
    entries.filter { it.includeInTotal }.sumOf { it.amountMinor }

fun nonTotalIndicators(entries: List<ShiftReportRevenueEntry>): NonTotalIndicators {
    val nonTotals = entries.filter { !it.includeInTotal }
    return NonTotalIndicators(
        all = nonTotals,
        showSeparately = nonTotals.filter { it.showSeparately },
    )
}
