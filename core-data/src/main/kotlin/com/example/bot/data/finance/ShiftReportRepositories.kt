package com.example.bot.data.finance

import com.example.bot.data.booking.TableDepositRepository
import com.example.bot.data.db.isUniqueViolation
import com.example.bot.data.db.toOffsetDateTimeUtc
import com.example.bot.data.db.withRetriedTx
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

class ShiftReportTemplateRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun getOrCreateTemplate(clubId: Long): ClubReportTemplate {
        val now = Instant.now(clock).toOffsetDateTimeUtc()
        return withRetriedTx(name = "shiftReportTemplate.getOrCreate", database = database) {
            var lastConflict: Throwable? = null
            repeat(2) {
                try {
                    return@withRetriedTx newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                        ClubReportTemplatesTable.insert {
                            it[ClubReportTemplatesTable.clubId] = clubId
                            it[ClubReportTemplatesTable.createdAt] = now
                            it[ClubReportTemplatesTable.updatedAt] = now
                        }
                        ClubReportTemplatesTable
                            .selectAll()
                            .where { ClubReportTemplatesTable.clubId eq clubId }
                            .limit(1)
                            .firstOrNull()
                            ?.toClubReportTemplate()
                            ?: error("Failed to load club report template for clubId=$clubId")
                    }
                } catch (ex: Throwable) {
                    if (!ex.isUniqueViolation()) throw ex
                    val existing =
                        newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                            ClubReportTemplatesTable
                                .selectAll()
                                .where { ClubReportTemplatesTable.clubId eq clubId }
                                .limit(1)
                                .firstOrNull()
                                ?.toClubReportTemplate()
                        }
                    if (existing != null) return@withRetriedTx existing
                    lastConflict = ex
                }
            }
            throw lastConflict ?: error("Failed to get or create club report template for clubId=$clubId")
        }
    }

    suspend fun getTemplateData(clubId: Long): ClubReportTemplateData {
        val template = getOrCreateTemplate(clubId)
        return withRetriedTx(name = "shiftReportTemplate.getData", database = database) {
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                val bracelets =
                    ClubBraceletTypesTable
                        .selectAll()
                        .where { ClubBraceletTypesTable.clubId eq clubId }
                        .orderBy(ClubBraceletTypesTable.orderIndex, SortOrder.ASC)
                        .map { it.toClubBraceletType() }
                val groups =
                    ClubRevenueGroupsTable
                        .selectAll()
                        .where { ClubRevenueGroupsTable.clubId eq clubId }
                        .orderBy(ClubRevenueGroupsTable.orderIndex, SortOrder.ASC)
                        .map { it.toClubRevenueGroup() }
                val articles =
                    ClubRevenueArticlesTable
                        .selectAll()
                        .where { ClubRevenueArticlesTable.clubId eq clubId }
                        .orderBy(ClubRevenueArticlesTable.orderIndex, SortOrder.ASC)
                        .map { it.toClubRevenueArticle() }
                ClubReportTemplateData(
                    template = template,
                    bracelets = bracelets,
                    revenueGroups = groups,
                    revenueArticles = articles,
                )
            }
        }
    }

    suspend fun createBraceletType(
        clubId: Long,
        name: String,
        orderIndex: Int,
    ): ClubBraceletType {
        val now = Instant.now(clock).toOffsetDateTimeUtc()
        return withRetriedTx(name = "shiftReportTemplate.createBracelet", database = database) {
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                val id =
                    ClubBraceletTypesTable.insert {
                        it[ClubBraceletTypesTable.clubId] = clubId
                        it[ClubBraceletTypesTable.name] = name
                        it[ClubBraceletTypesTable.orderIndex] = orderIndex
                        it[ClubBraceletTypesTable.enabled] = true
                        it[ClubBraceletTypesTable.createdAt] = now
                        it[ClubBraceletTypesTable.updatedAt] = now
                    }[ClubBraceletTypesTable.id]
                ClubBraceletTypesTable
                    .selectAll()
                    .where { ClubBraceletTypesTable.id eq id }
                    .limit(1)
                    .firstOrNull()
                    ?.toClubBraceletType()
                    ?: error("Failed to load bracelet type id=$id")
            }
        }
    }

    suspend fun updateBraceletType(
        clubId: Long,
        id: Long,
        name: String,
    ): ClubBraceletType? {
        val now = Instant.now(clock).toOffsetDateTimeUtc()
        return withRetriedTx(name = "shiftReportTemplate.updateBracelet", database = database) {
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                val updated =
                    ClubBraceletTypesTable.update({
                        (ClubBraceletTypesTable.id eq id) and
                            (ClubBraceletTypesTable.clubId eq clubId)
                    }) {
                        it[ClubBraceletTypesTable.name] = name
                        it[ClubBraceletTypesTable.updatedAt] = now
                    }
                if (updated == 0) return@newSuspendedTransaction null
                ClubBraceletTypesTable
                    .selectAll()
                    .where { ClubBraceletTypesTable.id eq id }
                    .limit(1)
                    .firstOrNull()
                    ?.toClubBraceletType()
            }
        }
    }

    suspend fun disableBraceletType(
        clubId: Long,
        id: Long,
    ): Boolean {
        val now = Instant.now(clock).toOffsetDateTimeUtc()
        return withRetriedTx(name = "shiftReportTemplate.disableBracelet", database = database) {
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                ClubBraceletTypesTable.update({
                    (ClubBraceletTypesTable.id eq id) and
                        (ClubBraceletTypesTable.clubId eq clubId)
                }) {
                    it[enabled] = false
                    it[updatedAt] = now
                } > 0
            }
        }
    }

    suspend fun reorderBraceletTypes(
        clubId: Long,
        orderedIds: List<Long>,
    ): Int {
        val now = Instant.now(clock).toOffsetDateTimeUtc()
        return withRetriedTx(name = "shiftReportTemplate.reorderBracelets", database = database) {
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                orderedIds.mapIndexed { index, id ->
                    ClubBraceletTypesTable.update({
                        (ClubBraceletTypesTable.id eq id) and
                            (ClubBraceletTypesTable.clubId eq clubId)
                    }) {
                        it[orderIndex] = index
                        it[updatedAt] = now
                    }
                }.sum()
            }
        }
    }

    suspend fun createRevenueGroup(
        clubId: Long,
        name: String,
        orderIndex: Int,
    ): ClubRevenueGroup {
        val now = Instant.now(clock).toOffsetDateTimeUtc()
        return withRetriedTx(name = "shiftReportTemplate.createGroup", database = database) {
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                val id =
                    ClubRevenueGroupsTable.insert {
                        it[ClubRevenueGroupsTable.clubId] = clubId
                        it[ClubRevenueGroupsTable.name] = name
                        it[ClubRevenueGroupsTable.orderIndex] = orderIndex
                        it[ClubRevenueGroupsTable.enabled] = true
                        it[ClubRevenueGroupsTable.createdAt] = now
                        it[ClubRevenueGroupsTable.updatedAt] = now
                    }[ClubRevenueGroupsTable.id]
                ClubRevenueGroupsTable
                    .selectAll()
                    .where { ClubRevenueGroupsTable.id eq id }
                    .limit(1)
                    .firstOrNull()
                    ?.toClubRevenueGroup()
                    ?: error("Failed to load revenue group id=$id")
            }
        }
    }

    suspend fun updateRevenueGroup(
        clubId: Long,
        id: Long,
        name: String,
    ): ClubRevenueGroup? {
        val now = Instant.now(clock).toOffsetDateTimeUtc()
        return withRetriedTx(name = "shiftReportTemplate.updateGroup", database = database) {
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                val updated =
                    ClubRevenueGroupsTable.update({
                        (ClubRevenueGroupsTable.id eq id) and
                            (ClubRevenueGroupsTable.clubId eq clubId)
                    }) {
                        it[ClubRevenueGroupsTable.name] = name
                        it[ClubRevenueGroupsTable.updatedAt] = now
                    }
                if (updated == 0) return@newSuspendedTransaction null
                ClubRevenueGroupsTable
                    .selectAll()
                    .where { ClubRevenueGroupsTable.id eq id }
                    .limit(1)
                    .firstOrNull()
                    ?.toClubRevenueGroup()
            }
        }
    }

    suspend fun disableRevenueGroup(
        clubId: Long,
        id: Long,
    ): Boolean {
        val now = Instant.now(clock).toOffsetDateTimeUtc()
        return withRetriedTx(name = "shiftReportTemplate.disableGroup", database = database) {
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                ClubRevenueGroupsTable.update({
                    (ClubRevenueGroupsTable.id eq id) and
                        (ClubRevenueGroupsTable.clubId eq clubId)
                }) {
                    it[enabled] = false
                    it[updatedAt] = now
                } > 0
            }
        }
    }

    suspend fun reorderRevenueGroups(
        clubId: Long,
        orderedIds: List<Long>,
    ): Int {
        val now = Instant.now(clock).toOffsetDateTimeUtc()
        return withRetriedTx(name = "shiftReportTemplate.reorderGroups", database = database) {
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                orderedIds.mapIndexed { index, id ->
                    ClubRevenueGroupsTable.update({
                        (ClubRevenueGroupsTable.id eq id) and
                            (ClubRevenueGroupsTable.clubId eq clubId)
                    }) {
                        it[orderIndex] = index
                        it[updatedAt] = now
                    }
                }.sum()
            }
        }
    }

    suspend fun createRevenueArticle(
        clubId: Long,
        groupId: Long,
        name: String,
        includeInTotal: Boolean,
        showSeparately: Boolean,
        orderIndex: Int,
    ): ClubRevenueArticle {
        val now = Instant.now(clock).toOffsetDateTimeUtc()
        return withRetriedTx(name = "shiftReportTemplate.createArticle", database = database) {
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                ensureGroupExists(clubId, groupId)
                val id =
                    ClubRevenueArticlesTable.insert {
                        it[ClubRevenueArticlesTable.clubId] = clubId
                        it[ClubRevenueArticlesTable.groupId] = groupId
                        it[ClubRevenueArticlesTable.name] = name
                        it[ClubRevenueArticlesTable.includeInTotal] = includeInTotal
                        it[ClubRevenueArticlesTable.showSeparately] = showSeparately
                        it[ClubRevenueArticlesTable.orderIndex] = orderIndex
                        it[ClubRevenueArticlesTable.enabled] = true
                        it[ClubRevenueArticlesTable.createdAt] = now
                        it[ClubRevenueArticlesTable.updatedAt] = now
                    }[ClubRevenueArticlesTable.id]
                ClubRevenueArticlesTable
                    .selectAll()
                    .where { ClubRevenueArticlesTable.id eq id }
                    .limit(1)
                    .firstOrNull()
                    ?.toClubRevenueArticle()
                    ?: error("Failed to load revenue article id=$id")
            }
        }
    }

    suspend fun updateRevenueArticle(
        clubId: Long,
        id: Long,
        name: String,
        includeInTotal: Boolean,
        showSeparately: Boolean,
        groupId: Long,
    ): ClubRevenueArticle? {
        val now = Instant.now(clock).toOffsetDateTimeUtc()
        return withRetriedTx(name = "shiftReportTemplate.updateArticle", database = database) {
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                ensureGroupExists(clubId, groupId)
                val updated =
                    ClubRevenueArticlesTable.update({
                        (ClubRevenueArticlesTable.id eq id) and
                            (ClubRevenueArticlesTable.clubId eq clubId)
                    }) {
                        it[ClubRevenueArticlesTable.name] = name
                        it[ClubRevenueArticlesTable.includeInTotal] = includeInTotal
                        it[ClubRevenueArticlesTable.showSeparately] = showSeparately
                        it[ClubRevenueArticlesTable.groupId] = groupId
                        it[ClubRevenueArticlesTable.updatedAt] = now
                    }
                if (updated == 0) return@newSuspendedTransaction null
                ClubRevenueArticlesTable
                    .selectAll()
                    .where { ClubRevenueArticlesTable.id eq id }
                    .limit(1)
                    .firstOrNull()
                    ?.toClubRevenueArticle()
            }
        }
    }

    suspend fun disableRevenueArticle(
        clubId: Long,
        id: Long,
    ): Boolean {
        val now = Instant.now(clock).toOffsetDateTimeUtc()
        return withRetriedTx(name = "shiftReportTemplate.disableArticle", database = database) {
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                ClubRevenueArticlesTable.update({
                    (ClubRevenueArticlesTable.id eq id) and
                        (ClubRevenueArticlesTable.clubId eq clubId)
                }) {
                    it[enabled] = false
                    it[updatedAt] = now
                } > 0
            }
        }
    }

    suspend fun reorderRevenueArticles(
        clubId: Long,
        orderedIds: List<Long>,
    ): Int {
        val now = Instant.now(clock).toOffsetDateTimeUtc()
        return withRetriedTx(name = "shiftReportTemplate.reorderArticles", database = database) {
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                orderedIds.mapIndexed { index, id ->
                    ClubRevenueArticlesTable.update({
                        (ClubRevenueArticlesTable.id eq id) and
                            (ClubRevenueArticlesTable.clubId eq clubId)
                    }) {
                        it[orderIndex] = index
                        it[updatedAt] = now
                    }
                }.sum()
            }
        }
    }

    private fun ensureGroupExists(clubId: Long, groupId: Long) {
        val exists =
            ClubRevenueGroupsTable
                .selectAll()
                .where { (ClubRevenueGroupsTable.id eq groupId) and (ClubRevenueGroupsTable.clubId eq clubId) }
                .limit(1)
                .any()
        require(exists) { "unknown_revenue_group" }
    }
}

class ShiftReportRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun getByClubAndNight(
        clubId: Long,
        nightStartUtc: Instant,
    ): ShiftReport? {
        val nightStart = nightStartUtc.toOffsetDateTimeUtc()
        return withRetriedTx(name = "shiftReport.getByClubNight", readOnly = true, database = database) {
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                ShiftReportsTable
                    .selectAll()
                    .where {
                        (ShiftReportsTable.clubId eq clubId) and
                            (ShiftReportsTable.nightStartUtc eq nightStart)
                    }.limit(1)
                    .firstOrNull()
                    ?.toShiftReport()
            }
        }
    }

    suspend fun getOrCreateDraft(
        clubId: Long,
        nightStartUtc: Instant,
    ): ShiftReport {
        val now = Instant.now(clock).toOffsetDateTimeUtc()
        val nightStart = nightStartUtc.toOffsetDateTimeUtc()
        return withRetriedTx(name = "shiftReport.getOrCreateDraft", database = database) {
            var lastConflict: Throwable? = null
            repeat(2) {
                try {
                    return@withRetriedTx newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                        val id =
                            ShiftReportsTable.insert {
                                it[ShiftReportsTable.clubId] = clubId
                                it[ShiftReportsTable.nightStartUtc] = nightStart
                                it[ShiftReportsTable.status] = ShiftReportStatus.DRAFT.name
                                it[ShiftReportsTable.peopleWomen] = 0
                                it[ShiftReportsTable.peopleMen] = 0
                                it[ShiftReportsTable.peopleRejected] = 0
                                it[ShiftReportsTable.comment] = null
                                it[ShiftReportsTable.closedAt] = null
                                it[ShiftReportsTable.closedBy] = null
                                it[ShiftReportsTable.createdAt] = now
                                it[ShiftReportsTable.updatedAt] = now
                            }[ShiftReportsTable.id]
                        ShiftReportsTable
                            .selectAll()
                            .where { ShiftReportsTable.id eq id }
                            .limit(1)
                            .firstOrNull()
                            ?.toShiftReport()
                            ?: error("Failed to load shift report for clubId=$clubId nightStart=$nightStartUtc")
                    }
                } catch (ex: Throwable) {
                    if (!ex.isUniqueViolation()) throw ex
                    val existing =
                        newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                            ShiftReportsTable
                                .selectAll()
                                .where {
                                    (ShiftReportsTable.clubId eq clubId) and
                                        (ShiftReportsTable.nightStartUtc eq nightStart)
                                }.limit(1)
                                .firstOrNull()
                                ?.toShiftReport()
                        }
                    if (existing != null) return@withRetriedTx existing
                    lastConflict = ex
                }
            }
            throw lastConflict ?: error("Failed to get or create shift report for clubId=$clubId nightStart=$nightStartUtc")
        }
    }

    suspend fun updateDraft(
        reportId: Long,
        payload: ShiftReportUpdatePayload,
    ): ShiftReport? {
        validatePeople(payload.peopleWomen, payload.peopleMen, payload.peopleRejected)
        val duplicateBraceletTypes =
            payload.bracelets
                .groupingBy { it.braceletTypeId }
                .eachCount()
                .filter { it.value > 1 }
                .keys
        require(duplicateBraceletTypes.isEmpty()) {
            "duplicate_bracelet_type"
        }
        payload.bracelets.forEach { require(it.count >= 0) { "bracelet_count_negative" } }
        val now = Instant.now(clock).toOffsetDateTimeUtc()
        return withRetriedTx(name = "shiftReport.updateDraft", database = database) {
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                val reportRow =
                    ShiftReportsTable
                        .selectAll()
                        .where { ShiftReportsTable.id eq reportId }
                        .forUpdate()
                        .limit(1)
                        .firstOrNull()
                        ?: return@newSuspendedTransaction null
                if (ShiftReportStatus.valueOf(reportRow[ShiftReportsTable.status]) == ShiftReportStatus.CLOSED) {
                    return@newSuspendedTransaction null
                }
                val clubId = reportRow[ShiftReportsTable.clubId]
                val braceletTypeIds = payload.bracelets.map { it.braceletTypeId }
                ensureBraceletTypesExist(clubId, braceletTypeIds)

                val resolvedEntries = resolveRevenueEntries(clubId, payload.revenueEntries)

                val updated =
                    ShiftReportsTable.update({
                        (ShiftReportsTable.id eq reportId) and
                            (ShiftReportsTable.status eq ShiftReportStatus.DRAFT.name)
                    }) {
                        it[peopleWomen] = payload.peopleWomen
                        it[peopleMen] = payload.peopleMen
                        it[peopleRejected] = payload.peopleRejected
                        it[comment] = payload.comment
                        it[updatedAt] = now
                    }
                if (updated == 0) return@newSuspendedTransaction null

                ShiftReportBraceletsTable.deleteWhere { ShiftReportBraceletsTable.reportId eq reportId }
                if (payload.bracelets.isNotEmpty()) {
                    payload.bracelets.forEach {
                        ShiftReportBraceletsTable.insert { row ->
                            row[ShiftReportBraceletsTable.reportId] = reportId
                            row[ShiftReportBraceletsTable.braceletTypeId] = it.braceletTypeId
                            row[ShiftReportBraceletsTable.count] = it.count
                        }
                    }
                }

                ShiftReportRevenueEntriesTable.deleteWhere { ShiftReportRevenueEntriesTable.reportId eq reportId }
                resolvedEntries.forEach { entry ->
                    ShiftReportRevenueEntriesTable.insert { row ->
                        row[ShiftReportRevenueEntriesTable.reportId] = reportId
                        row[ShiftReportRevenueEntriesTable.articleId] = entry.articleId
                        row[ShiftReportRevenueEntriesTable.name] = entry.name
                        row[ShiftReportRevenueEntriesTable.groupId] = entry.groupId
                        row[ShiftReportRevenueEntriesTable.amountMinor] = entry.amountMinor
                        row[ShiftReportRevenueEntriesTable.includeInTotal] = entry.includeInTotal
                        row[ShiftReportRevenueEntriesTable.showSeparately] = entry.showSeparately
                        row[ShiftReportRevenueEntriesTable.orderIndex] = entry.orderIndex
                    }
                }

                ShiftReportsTable
                    .selectAll()
                    .where { ShiftReportsTable.id eq reportId }
                    .limit(1)
                    .firstOrNull()
                    ?.toShiftReport()
            }
        }
    }

    suspend fun getDetails(reportId: Long): ShiftReportDetails? =
        withRetriedTx(name = "shiftReport.getDetails", database = database) {
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                val report =
                    ShiftReportsTable
                        .selectAll()
                        .where { ShiftReportsTable.id eq reportId }
                        .limit(1)
                        .firstOrNull()
                        ?.toShiftReport()
                        ?: return@newSuspendedTransaction null
                val bracelets =
                    ShiftReportBraceletsTable
                        .selectAll()
                        .where { ShiftReportBraceletsTable.reportId eq reportId }
                        .map { row ->
                            ShiftReportBracelet(
                                reportId = row[ShiftReportBraceletsTable.reportId],
                                braceletTypeId = row[ShiftReportBraceletsTable.braceletTypeId],
                                count = row[ShiftReportBraceletsTable.count],
                            )
                        }
                val revenueEntries = loadRevenueEntries(reportId)
                ShiftReportDetails(report, bracelets, revenueEntries)
            }
        }

    suspend fun close(
        reportId: Long,
        actorId: Long,
        now: Instant,
    ): Boolean {
        val nowUtc = now.toOffsetDateTimeUtc()
        return withRetriedTx(name = "shiftReport.close", database = database) {
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                val reportRow =
                    ShiftReportsTable
                        .selectAll()
                        .where { ShiftReportsTable.id eq reportId }
                        .forUpdate()
                        .limit(1)
                        .firstOrNull()
                        ?: return@newSuspendedTransaction false
                val status = ShiftReportStatus.valueOf(reportRow[ShiftReportsTable.status])
                if (status == ShiftReportStatus.CLOSED) return@newSuspendedTransaction false
                val clubId = reportRow[ShiftReportsTable.clubId]
                validatePeople(
                    reportRow[ShiftReportsTable.peopleWomen],
                    reportRow[ShiftReportsTable.peopleMen],
                    reportRow[ShiftReportsTable.peopleRejected],
                )
                val bracelets =
                    ShiftReportBraceletsTable
                        .selectAll()
                        .where { ShiftReportBraceletsTable.reportId eq reportId }
                        .map { it[ShiftReportBraceletsTable.count] }
                bracelets.forEach { require(it >= 0) { "bracelet_count_negative" } }
                val revenueEntries = loadRevenueEntries(reportId)
                revenueEntries.forEach { require(it.amountMinor >= 0) { "revenue_amount_negative" } }
                validateRevenueGroups(clubId, revenueEntries.map { it.groupId })
                revenueEntries.forEach { require(it.name.isNotBlank()) { "revenue_entry_name_required" } }

                ShiftReportsTable.update({
                    (ShiftReportsTable.id eq reportId) and
                        (ShiftReportsTable.status eq ShiftReportStatus.DRAFT.name)
                }) {
                    it[ShiftReportsTable.status] = ShiftReportStatus.CLOSED.name
                    it[ShiftReportsTable.closedAt] = nowUtc
                    it[ShiftReportsTable.closedBy] = actorId
                    it[ShiftReportsTable.updatedAt] = nowUtc
                } > 0
            }
        }
    }

    suspend fun getDepositHints(
        clubId: Long,
        nightStartUtc: Instant,
    ): DepositHints {
        val repo = TableDepositRepository(database)
        return DepositHints(
            sumDepositsForNight = repo.sumDepositsForNight(clubId, nightStartUtc),
            allocationSummaryForNight = repo.allocationSummaryForNight(clubId, nightStartUtc),
        )
    }

    private fun validatePeople(women: Int, men: Int, rejected: Int) {
        require(women >= 0 && men >= 0 && rejected >= 0) { "people_count_negative" }
    }

    private fun ensureBraceletTypesExist(clubId: Long, braceletTypeIds: List<Long>) {
        if (braceletTypeIds.isEmpty()) return
        val existing =
            ClubBraceletTypesTable
                .selectAll()
                .where { (ClubBraceletTypesTable.clubId eq clubId) and (ClubBraceletTypesTable.id inList braceletTypeIds) }
                .map { it[ClubBraceletTypesTable.id] }
                .toSet()
        val missing = braceletTypeIds.filterNot { existing.contains(it) }
        require(missing.isEmpty()) { "unknown_bracelet_type" }
    }

    private fun resolveRevenueEntries(
        clubId: Long,
        inputs: List<RevenueEntryInput>,
    ): List<ShiftReportRevenueEntry> {
        val payloadArticleIds = inputs.mapNotNull { it.articleId }
        val duplicateArticleIds =
            payloadArticleIds
                .groupingBy { it }
                .eachCount()
                .filter { it.value > 1 }
                .keys
        require(duplicateArticleIds.isEmpty()) {
            "duplicate_revenue_article"
        }
        val articleIds = payloadArticleIds.distinct()
        val articles = loadArticles(clubId, articleIds)
        val adHocGroupIds =
            inputs
                .filter { it.articleId == null }
                .mapNotNull { it.groupId }
                .toSet()
        val groupIds = adHocGroupIds + articles.values.map { it.groupId }
        validateRevenueGroups(clubId, groupIds.toList())

        return inputs.mapIndexed { index, input ->
            require(input.amountMinor >= 0) { "revenue_amount_negative" }
            if (input.articleId != null) {
                val article =
                    articles[input.articleId]
                        ?: throw IllegalArgumentException(
                            "unknown_revenue_article",
                        )
                if (input.groupId != null && input.groupId != article.groupId) {
                    throw IllegalArgumentException("revenue_article_group_mismatch")
                }
                val nameOverride = input.name?.trim()?.takeIf { it.isNotEmpty() }
                val name = nameOverride ?: article.name
                ShiftReportRevenueEntry(
                    id = 0,
                    reportId = 0,
                    articleId = input.articleId,
                    name = name,
                    groupId = article.groupId,
                    amountMinor = input.amountMinor,
                    includeInTotal = input.includeInTotal ?: article.includeInTotal,
                    showSeparately = input.showSeparately ?: article.showSeparately,
                    orderIndex = input.orderIndex ?: article.orderIndex,
                )
            } else {
                val name = input.name?.trim().orEmpty()
                require(name.isNotEmpty()) { "revenue_entry_name_required" }
                val groupId = requireNotNull(input.groupId) { "revenue_entry_group_required" }
                val includeInTotal =
                    requireNotNull(input.includeInTotal) { "revenue_entry_include_in_total_required" }
                val showSeparately =
                    requireNotNull(input.showSeparately) { "revenue_entry_show_separately_required" }
                ShiftReportRevenueEntry(
                    id = 0,
                    reportId = 0,
                    articleId = null,
                    name = name,
                    groupId = groupId,
                    amountMinor = input.amountMinor,
                    includeInTotal = includeInTotal,
                    showSeparately = showSeparately,
                    orderIndex = input.orderIndex ?: index,
                )
            }
        }
    }

    private fun validateRevenueGroups(clubId: Long, groupIds: List<Long>) {
        if (groupIds.isEmpty()) return
        val existing =
            ClubRevenueGroupsTable
                .selectAll()
                .where { (ClubRevenueGroupsTable.clubId eq clubId) and (ClubRevenueGroupsTable.id inList groupIds) }
                .map { it[ClubRevenueGroupsTable.id] }
                .toSet()
        val missing = groupIds.filterNot { existing.contains(it) }
        require(missing.isEmpty()) { "unknown_revenue_group" }
    }

    private fun loadArticles(
        clubId: Long,
        articleIds: List<Long>,
    ): Map<Long, ClubRevenueArticle> {
        if (articleIds.isEmpty()) return emptyMap()
        return ClubRevenueArticlesTable
            .selectAll()
            .where { (ClubRevenueArticlesTable.clubId eq clubId) and (ClubRevenueArticlesTable.id inList articleIds) }
            .map { it.toClubRevenueArticle() }
            .associateBy { it.id }
    }

    private fun loadRevenueEntries(reportId: Long): List<ShiftReportRevenueEntry> =
        ShiftReportRevenueEntriesTable
            .selectAll()
            .where { ShiftReportRevenueEntriesTable.reportId eq reportId }
            .orderBy(ShiftReportRevenueEntriesTable.orderIndex, SortOrder.ASC)
            .map { it.toShiftReportRevenueEntry() }
}

private fun ResultRow.toClubReportTemplate(): ClubReportTemplate =
    ClubReportTemplate(
        clubId = this[ClubReportTemplatesTable.clubId],
        createdAt = this[ClubReportTemplatesTable.createdAt].toInstant(),
        updatedAt = this[ClubReportTemplatesTable.updatedAt].toInstant(),
    )

private fun ResultRow.toClubBraceletType(): ClubBraceletType =
    ClubBraceletType(
        id = this[ClubBraceletTypesTable.id],
        clubId = this[ClubBraceletTypesTable.clubId],
        name = this[ClubBraceletTypesTable.name],
        enabled = this[ClubBraceletTypesTable.enabled],
        orderIndex = this[ClubBraceletTypesTable.orderIndex],
        createdAt = this[ClubBraceletTypesTable.createdAt].toInstant(),
        updatedAt = this[ClubBraceletTypesTable.updatedAt].toInstant(),
    )

private fun ResultRow.toClubRevenueGroup(): ClubRevenueGroup =
    ClubRevenueGroup(
        id = this[ClubRevenueGroupsTable.id],
        clubId = this[ClubRevenueGroupsTable.clubId],
        name = this[ClubRevenueGroupsTable.name],
        enabled = this[ClubRevenueGroupsTable.enabled],
        orderIndex = this[ClubRevenueGroupsTable.orderIndex],
        createdAt = this[ClubRevenueGroupsTable.createdAt].toInstant(),
        updatedAt = this[ClubRevenueGroupsTable.updatedAt].toInstant(),
    )

private fun ResultRow.toClubRevenueArticle(): ClubRevenueArticle =
    ClubRevenueArticle(
        id = this[ClubRevenueArticlesTable.id],
        clubId = this[ClubRevenueArticlesTable.clubId],
        groupId = this[ClubRevenueArticlesTable.groupId],
        name = this[ClubRevenueArticlesTable.name],
        enabled = this[ClubRevenueArticlesTable.enabled],
        includeInTotal = this[ClubRevenueArticlesTable.includeInTotal],
        showSeparately = this[ClubRevenueArticlesTable.showSeparately],
        orderIndex = this[ClubRevenueArticlesTable.orderIndex],
        createdAt = this[ClubRevenueArticlesTable.createdAt].toInstant(),
        updatedAt = this[ClubRevenueArticlesTable.updatedAt].toInstant(),
    )

private fun ResultRow.toShiftReport(): ShiftReport =
    ShiftReport(
        id = this[ShiftReportsTable.id],
        clubId = this[ShiftReportsTable.clubId],
        nightStartUtc = this[ShiftReportsTable.nightStartUtc].toInstant(),
        status = ShiftReportStatus.valueOf(this[ShiftReportsTable.status]),
        peopleWomen = this[ShiftReportsTable.peopleWomen],
        peopleMen = this[ShiftReportsTable.peopleMen],
        peopleRejected = this[ShiftReportsTable.peopleRejected],
        comment = this[ShiftReportsTable.comment],
        closedAt = this[ShiftReportsTable.closedAt]?.toInstant(),
        closedBy = this[ShiftReportsTable.closedBy],
        createdAt = this[ShiftReportsTable.createdAt].toInstant(),
        updatedAt = this[ShiftReportsTable.updatedAt].toInstant(),
    )

private fun ResultRow.toShiftReportRevenueEntry(): ShiftReportRevenueEntry =
    ShiftReportRevenueEntry(
        id = this[ShiftReportRevenueEntriesTable.id],
        reportId = this[ShiftReportRevenueEntriesTable.reportId],
        articleId = this[ShiftReportRevenueEntriesTable.articleId],
        name = this[ShiftReportRevenueEntriesTable.name],
        groupId = this[ShiftReportRevenueEntriesTable.groupId],
        amountMinor = this[ShiftReportRevenueEntriesTable.amountMinor],
        includeInTotal = this[ShiftReportRevenueEntriesTable.includeInTotal],
        showSeparately = this[ShiftReportRevenueEntriesTable.showSeparately],
        orderIndex = this[ShiftReportRevenueEntriesTable.orderIndex],
    )
