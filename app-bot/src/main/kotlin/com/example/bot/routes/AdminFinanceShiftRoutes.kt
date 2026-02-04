package com.example.bot.routes

import com.example.bot.audit.AuditLogger
import com.example.bot.data.finance.ClubBraceletType
import com.example.bot.data.finance.ClubReportTemplateData
import com.example.bot.data.finance.ClubRevenueArticle
import com.example.bot.data.finance.ClubRevenueGroup
import com.example.bot.data.finance.DepositHints
import com.example.bot.data.finance.NonTotalIndicators
import com.example.bot.data.finance.RevenueEntryInput
import com.example.bot.data.finance.RevenueGroupTotal
import com.example.bot.data.finance.ShiftReport
import com.example.bot.data.finance.ShiftReportDetails
import com.example.bot.data.finance.ShiftReportRepository
import com.example.bot.data.finance.ShiftReportStatus
import com.example.bot.data.finance.ShiftReportTemplateRepository
import com.example.bot.data.finance.ShiftReportUpdatePayload
import com.example.bot.data.finance.nonTotalIndicators
import com.example.bot.data.finance.totalRevenue
import com.example.bot.data.finance.totalsPerGroup
import com.example.bot.data.security.Role
import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.respondError
import com.example.bot.plugins.envString
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.rbacContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.Clock
import java.time.Instant
import kotlin.math.abs
import kotlinx.serialization.Serializable

private const val MAX_NOTE_LENGTH = 500
private const val DEFAULT_DEPOSIT_MISMATCH_THRESHOLD_MINOR = 10_000L

@Serializable
data class ShiftReportTotalsResponse(
    val totalAmountMinor: Long,
    val groups: List<RevenueGroupTotalResponse>,
)

@Serializable
data class RevenueGroupTotalResponse(
    val groupId: Long,
    val groupName: String?,
    val amountMinor: Long,
)

@Serializable
data class NonTotalIndicatorsResponse(
    val all: List<ShiftReportRevenueEntryResponse>,
    val showSeparately: List<ShiftReportRevenueEntryResponse>,
)

@Serializable
data class DepositHintsResponse(
    val sumDepositsForNight: Long,
    val allocationSummaryForNight: Map<String, Long>,
)

@Serializable
data class ShiftReportResponse(
    val id: Long,
    val clubId: Long,
    val nightStartUtc: String,
    val status: String,
    val peopleWomen: Int,
    val peopleMen: Int,
    val peopleRejected: Int,
    val comment: String?,
    val closedAt: String?,
    val closedBy: Long?,
    val createdAt: String,
    val updatedAt: String,
    val bracelets: List<ShiftReportBraceletResponse>,
    val revenueEntries: List<ShiftReportRevenueEntryResponse>,
)

@Serializable
data class ShiftReportBraceletResponse(
    val braceletTypeId: Long,
    val count: Int,
)

@Serializable
data class ShiftReportRevenueEntryResponse(
    val id: Long,
    val articleId: Long?,
    val name: String,
    val groupId: Long,
    val amountMinor: Long,
    val includeInTotal: Boolean,
    val showSeparately: Boolean,
    val orderIndex: Int,
)

@Serializable
data class ClubReportTemplateResponse(
    val clubId: Long,
    val createdAt: String,
    val updatedAt: String,
    val bracelets: List<BraceletTypeResponse>,
    val revenueGroups: List<RevenueGroupResponse>,
    val revenueArticles: List<RevenueArticleResponse>,
)

@Serializable
data class BraceletTypeResponse(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val orderIndex: Int,
)

@Serializable
data class RevenueGroupResponse(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val orderIndex: Int,
)

@Serializable
data class RevenueArticleResponse(
    val id: Long,
    val groupId: Long,
    val name: String,
    val enabled: Boolean,
    val includeInTotal: Boolean,
    val showSeparately: Boolean,
    val orderIndex: Int,
)

@Serializable
data class ShiftReportDetailsResponse(
    val report: ShiftReportResponse,
    val template: ClubReportTemplateResponse,
    val totals: ShiftReportTotalsResponse,
    val nonTotalIndicators: NonTotalIndicatorsResponse,
    val depositHints: DepositHintsResponse,
)

@Serializable
data class ShiftReportUpdateRequest(
    val peopleWomen: Int,
    val peopleMen: Int,
    val peopleRejected: Int,
    val comment: String? = null,
    val bracelets: List<ShiftReportBraceletRequest>,
    val revenueEntries: List<RevenueEntryRequest>,
)

@Serializable
data class ShiftReportBraceletRequest(
    val braceletTypeId: Long,
    val count: Int,
)

@Serializable
data class RevenueEntryRequest(
    val articleId: Long? = null,
    val name: String? = null,
    val groupId: Long? = null,
    val amountMinor: Long,
    val includeInTotal: Boolean? = null,
    val showSeparately: Boolean? = null,
    val orderIndex: Int? = null,
)

@Serializable
data class ShiftReportCloseResponse(
    val report: ShiftReportResponse,
    val totals: ShiftReportTotalsResponse,
)

fun Application.adminFinanceShiftRoutes(
    shiftReportRepository: ShiftReportRepository,
    templateRepository: ShiftReportTemplateRepository,
    auditLogger: AuditLogger,
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
    clock: Clock = Clock.systemUTC(),
) {
    val mismatchThresholdMinor = envLong("SHIFT_REPORT_DEPOSIT_MISMATCH_THRESHOLD_MINOR", DEFAULT_DEPOSIT_MISMATCH_THRESHOLD_MINOR)

    routing {
        route("/api/admin") {
            intercept(ApplicationCallPipeline.Setup) { call.ensureMiniAppNoStoreHeaders() }
            withMiniAppAuth { botTokenProvider() }

            authorize(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER, Role.CLUB_ADMIN) {
                route("/clubs/{clubId}") {
                    route("/nights/{nightStartUtc}/finance") {
                        get("/shift") {
                            val clubId = call.requireClubIdPath() ?: return@get
                            val nightStartUtc = call.requireNightStartUtc() ?: return@get
                            if (!call.isAdminClubAllowed(clubId)) {
                                return@get call.respondForbidden()
                            }

                            val report = shiftReportRepository.getOrCreateDraft(clubId, nightStartUtc)
                            val details = shiftReportRepository.getDetails(report.id)
                                ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                            val template = templateRepository.getTemplateData(clubId)
                            val depositHints = shiftReportRepository.getDepositHints(clubId, nightStartUtc)
                            val response = buildShiftReportDetailsResponse(details, template, depositHints)
                            call.respond(HttpStatusCode.OK, response)
                        }
                    }

                    route("/finance") {
                        put("/shift/{reportId}") {
                            val clubId = call.requireClubIdPath() ?: return@put
                            val reportId = call.requireReportId() ?: return@put
                            if (!call.isAdminClubAllowed(clubId)) {
                                return@put call.respondForbidden()
                            }

                            val payload = runCatching { call.receive<ShiftReportUpdateRequest>() }.getOrNull()
                                ?: return@put call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                            val validationErrors = validateShiftReportUpdate(payload)
                            if (validationErrors.isNotEmpty()) {
                                return@put call.respondValidationErrors(validationErrors)
                            }

                            val existing = shiftReportRepository.getDetails(reportId)
                                ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                            if (existing.report.clubId != clubId) {
                                return@put call.respondForbidden()
                            }
                            if (existing.report.status == ShiftReportStatus.CLOSED) {
                                return@put call.respondError(HttpStatusCode.Conflict, ErrorCodes.invalid_state)
                            }

                            val updatePayload = payload.toUpdatePayload()
                            val updated =
                                try {
                                    shiftReportRepository.updateDraft(reportId, updatePayload)
                                } catch (ex: IllegalArgumentException) {
                                    return@put call.respondValidationErrors(mapIllegalArgument(ex))
                                }
                            if (updated == null) {
                                return@put call.respondError(HttpStatusCode.Conflict, ErrorCodes.invalid_state)
                            }
                            val details = shiftReportRepository.getDetails(reportId)
                                ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                            val template = templateRepository.getTemplateData(clubId)
                            val depositHints = shiftReportRepository.getDepositHints(clubId, details.report.nightStartUtc)
                            val response = buildShiftReportDetailsResponse(details, template, depositHints)
                            call.respond(HttpStatusCode.OK, response)
                        }

                        post("/shift/{reportId}/close") {
                            val clubId = call.requireClubIdPath() ?: return@post
                            val reportId = call.requireReportId() ?: return@post
                            if (!call.isAdminClubAllowed(clubId)) {
                                return@post call.respondForbidden()
                            }

                            val details = shiftReportRepository.getDetails(reportId)
                                ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                            if (details.report.clubId != clubId) {
                                return@post call.respondForbidden()
                            }
                            if (details.report.status == ShiftReportStatus.CLOSED) {
                                return@post call.respondError(HttpStatusCode.Conflict, ErrorCodes.invalid_state)
                            }

                            val depositHints = shiftReportRepository.getDepositHints(clubId, details.report.nightStartUtc)
                            val mismatch = depositMismatch(details, depositHints)
                            if (mismatch > mismatchThresholdMinor && details.report.comment.isNullOrBlank()) {
                                return@post call.respondValidationErrors(mapOf("comment" to "required_for_mismatch"))
                            }

                            val actorId = call.rbacContext().user.id
                            val actorRole = call.rbacContext().roles.firstOrNull()?.name
                            val now = Instant.now(clock)
                            val closed =
                                try {
                                    shiftReportRepository.close(reportId, actorId, now)
                                } catch (ex: IllegalArgumentException) {
                                    return@post call.respondValidationErrors(mapIllegalArgument(ex))
                                }
                            if (!closed) {
                                return@post call.respondError(HttpStatusCode.Conflict, ErrorCodes.invalid_state)
                            }
                            val refreshed = shiftReportRepository.getDetails(reportId)
                                ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                            val totals = buildTotalsResponse(refreshed.revenueEntries, templateRepository.getTemplateData(clubId))
                            val groupTotals = totals.groups.map { it.groupId to it.amountMinor }
                            auditLogger.shiftReportClosed(
                                clubId = clubId,
                                nightStartUtc = refreshed.report.nightStartUtc,
                                reportId = reportId,
                                totalAmountMinor = totals.totalAmountMinor,
                                groupTotals = groupTotals,
                                depositSumMinor = depositHints.sumDepositsForNight,
                                allocationSummary = depositHints.allocationSummaryForNight,
                                diffMinor = mismatch,
                                actorUserId = actorId,
                                actorRole = actorRole,
                            )
                            call.respond(
                                HttpStatusCode.OK,
                                ShiftReportCloseResponse(
                                    report = refreshed.toResponse(),
                                    totals = totals,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildShiftReportDetailsResponse(
    details: ShiftReportDetails,
    template: ClubReportTemplateData,
    depositHints: DepositHints,
): ShiftReportDetailsResponse {
    val totals = buildTotalsResponse(details.revenueEntries, template)
    val nonTotals = nonTotalIndicators(details.revenueEntries).toResponse()
    return ShiftReportDetailsResponse(
        report = details.toResponse(),
        template = template.toResponse(),
        totals = totals,
        nonTotalIndicators = nonTotals,
        depositHints = depositHints.toResponse(),
    )
}

private fun buildTotalsResponse(
    entries: List<com.example.bot.data.finance.ShiftReportRevenueEntry>,
    template: ClubReportTemplateData,
): ShiftReportTotalsResponse {
    val groupNames = template.revenueGroups.associateBy({ it.id }, { it.name })
    val groups = totalsPerGroup(entries, groupNames).map { it.toResponse() }
    return ShiftReportTotalsResponse(
        totalAmountMinor = totalRevenue(entries),
        groups = groups,
    )
}

private fun ShiftReportDetails.toResponse(): ShiftReportResponse {
    val base = report.toResponse()
    return base.copy(
        bracelets = bracelets.map { ShiftReportBraceletResponse(it.braceletTypeId, it.count) },
        revenueEntries = revenueEntries.map { it.toResponse() },
    )
}

private fun ShiftReport.toResponse(): ShiftReportResponse =
    ShiftReportResponse(
        id = id,
        clubId = clubId,
        nightStartUtc = nightStartUtc.toString(),
        status = status.name,
        peopleWomen = peopleWomen,
        peopleMen = peopleMen,
        peopleRejected = peopleRejected,
        comment = comment,
        closedAt = closedAt?.toString(),
        closedBy = closedBy,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        bracelets = emptyList(),
        revenueEntries = emptyList(),
    )

private fun com.example.bot.data.finance.ShiftReportRevenueEntry.toResponse(): ShiftReportRevenueEntryResponse =
    ShiftReportRevenueEntryResponse(
        id = id,
        articleId = articleId,
        name = name,
        groupId = groupId,
        amountMinor = amountMinor,
        includeInTotal = includeInTotal,
        showSeparately = showSeparately,
        orderIndex = orderIndex,
    )

private fun ClubReportTemplateData.toResponse(): ClubReportTemplateResponse =
    ClubReportTemplateResponse(
        clubId = template.clubId,
        createdAt = template.createdAt.toString(),
        updatedAt = template.updatedAt.toString(),
        bracelets = bracelets.map { it.toResponse() },
        revenueGroups = revenueGroups.map { it.toResponse() },
        revenueArticles = revenueArticles.map { it.toResponse() },
    )

private fun ClubBraceletType.toResponse(): BraceletTypeResponse =
    BraceletTypeResponse(
        id = id,
        name = name,
        enabled = enabled,
        orderIndex = orderIndex,
    )

private fun ClubRevenueGroup.toResponse(): RevenueGroupResponse =
    RevenueGroupResponse(
        id = id,
        name = name,
        enabled = enabled,
        orderIndex = orderIndex,
    )

private fun ClubRevenueArticle.toResponse(): RevenueArticleResponse =
    RevenueArticleResponse(
        id = id,
        groupId = groupId,
        name = name,
        enabled = enabled,
        includeInTotal = includeInTotal,
        showSeparately = showSeparately,
        orderIndex = orderIndex,
    )

private fun RevenueGroupTotal.toResponse(): RevenueGroupTotalResponse =
    RevenueGroupTotalResponse(
        groupId = groupId,
        groupName = groupName,
        amountMinor = amountMinor,
    )

private fun DepositHints.toResponse(): DepositHintsResponse =
    DepositHintsResponse(
        sumDepositsForNight = sumDepositsForNight,
        allocationSummaryForNight = allocationSummaryForNight,
    )

private fun NonTotalIndicators.toResponse(): NonTotalIndicatorsResponse =
    NonTotalIndicatorsResponse(
        all = all.map { it.toResponse() },
        showSeparately = showSeparately.map { it.toResponse() },
    )

private fun ShiftReportUpdateRequest.toUpdatePayload(): ShiftReportUpdatePayload =
    ShiftReportUpdatePayload(
        peopleWomen = peopleWomen,
        peopleMen = peopleMen,
        peopleRejected = peopleRejected,
        comment = comment?.trim()?.ifEmpty { null },
        bracelets = bracelets.map { com.example.bot.data.finance.ShiftReportBraceletInput(it.braceletTypeId, it.count) },
        revenueEntries = revenueEntries.map { it.toInput() },
    )

private fun RevenueEntryRequest.toInput(): RevenueEntryInput =
    RevenueEntryInput(
        articleId = articleId,
        name = name,
        groupId = groupId,
        amountMinor = amountMinor,
        includeInTotal = includeInTotal,
        showSeparately = showSeparately,
        orderIndex = orderIndex,
    )

private fun validateShiftReportUpdate(payload: ShiftReportUpdateRequest): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    if (payload.peopleWomen < 0 || payload.peopleMen < 0 || payload.peopleRejected < 0) {
        errors["people"] = "must_be_non_negative"
    }
    if (payload.comment != null && payload.comment.length > MAX_NOTE_LENGTH) {
        errors["comment"] = "too_long"
    }
    payload.bracelets.forEachIndexed { index, entry ->
        if (entry.count < 0) {
            errors["bracelets[$index].count"] = "must_be_non_negative"
        }
        if (entry.braceletTypeId <= 0) {
            errors["bracelets[$index].braceletTypeId"] = "must_be_positive"
        }
    }
    payload.revenueEntries.forEachIndexed { index, entry ->
        if (entry.amountMinor < 0) {
            errors["revenueEntries[$index].amountMinor"] = "must_be_non_negative"
        }
        if (entry.groupId != null && entry.groupId <= 0) {
            errors["revenueEntries[$index].groupId"] = "must_be_positive"
        }
        if (entry.orderIndex != null && entry.orderIndex < 0) {
            errors["revenueEntries[$index].orderIndex"] = "must_be_non_negative"
        }
    }
    return errors
}

private fun depositMismatch(
    details: ShiftReportDetails,
    depositHints: DepositHints,
): Long {
    val tableDepositsSum = depositHints.sumDepositsForNight
    val depositIndicatorsSum =
        details.revenueEntries
            .filter { !it.includeInTotal && it.showSeparately }
            .sumOf { it.amountMinor }
    return abs(tableDepositsSum - depositIndicatorsSum)
}

private fun Application.envLong(
    name: String,
    default: Long,
): Long {
    val value = envString(name)
    return value?.toLongOrNull() ?: default
}

private fun mapIllegalArgument(ex: IllegalArgumentException): Map<String, String> {
    val key =
        when (ex.message) {
            "people_count_negative" -> "people"
            "duplicate_bracelet_type" -> "bracelets"
            "bracelet_count_negative" -> "bracelets"
            "unknown_bracelet_type" -> "bracelets"
            "duplicate_revenue_article" -> "revenueEntries"
            "unknown_revenue_article" -> "revenueEntries"
            "revenue_article_group_mismatch" -> "revenueEntries"
            "revenue_amount_negative" -> "revenueEntries"
            "revenue_entry_name_required" -> "revenueEntries"
            "revenue_entry_group_required" -> "revenueEntries"
            "revenue_entry_include_in_total_required" -> "revenueEntries"
            "revenue_entry_show_separately_required" -> "revenueEntries"
            "unknown_revenue_group" -> "revenueEntries"
            else -> "payload"
        }
    val code = ex.message ?: "invalid"
    return mapOf(key to code)
}

private suspend fun ApplicationCall.requireClubIdPath(): Long? {
    val clubId = parameters["clubId"]?.toLongOrNull()
    if (clubId == null || clubId <= 0) {
        respondValidationErrors(mapOf("clubId" to "must_be_positive"))
        return null
    }
    return clubId
}

private suspend fun ApplicationCall.requireNightStartUtc(): Instant? {
    val raw = parameters["nightStartUtc"]
    val instant = raw?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (instant == null) {
        respondValidationErrors(mapOf("nightStartUtc" to "invalid_format"))
        return null
    }
    return instant
}

private suspend fun ApplicationCall.requireReportId(): Long? {
    val id = parameters["reportId"]?.toLongOrNull()
    if (id == null || id <= 0) {
        respondValidationErrors(mapOf("reportId" to "must_be_positive"))
        return null
    }
    return id
}

private suspend fun ApplicationCall.respondValidationErrors(details: Map<String, String>) {
    ensureMiniAppNoStoreHeaders()
    respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error, details = details)
}

private suspend fun ApplicationCall.respondForbidden() {
    ensureMiniAppNoStoreHeaders()
    respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
}
