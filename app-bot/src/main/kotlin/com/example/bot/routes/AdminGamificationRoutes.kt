package com.example.bot.routes

import com.example.bot.admin.AdminBadgeCreate
import com.example.bot.admin.AdminBadgeRepository
import com.example.bot.admin.AdminBadgeUpdate
import com.example.bot.admin.AdminGamificationSettings
import com.example.bot.admin.AdminGamificationSettingsRepository
import com.example.bot.admin.AdminGamificationSettingsUpdate
import com.example.bot.admin.AdminNightOverrideRepository
import com.example.bot.admin.AdminPrizeCreate
import com.example.bot.admin.AdminPrizeRepository
import com.example.bot.admin.AdminPrizeUpdate
import com.example.bot.admin.AdminRewardLadderLevelCreate
import com.example.bot.admin.AdminRewardLadderRepository
import com.example.bot.admin.AdminRewardLadderLevelUpdate
import com.example.bot.data.security.Role
import com.example.bot.gamification.BadgeConditionType
import com.example.bot.gamification.GamificationMetricType
import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.respondError
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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

@Serializable
private data class AdminGamificationSettingsRequest(
    val stampsEnabled: Boolean,
    val earlyEnabled: Boolean,
    val badgesEnabled: Boolean,
    val prizesEnabled: Boolean,
    val contestsEnabled: Boolean,
    val tablesLoyaltyEnabled: Boolean,
    val earlyWindowMinutes: Int? = null,
)

@Serializable
private data class AdminNightOverrideRequest(
    val earlyCutoffAt: String? = null,
)

@Serializable
private data class AdminNightOverrideResponse(
    val nightStartUtc: String,
    val earlyCutoffAt: String? = null,
    val effectiveEarlyCutoffAt: String? = null,
)

@Serializable
private data class AdminBadgeRequest(
    val code: String,
    val nameRu: String,
    val icon: String? = null,
    val enabled: Boolean = true,
    val conditionType: String,
    val threshold: Int,
    val windowDays: Int? = null,
)

@Serializable
private data class AdminBadgeUpdateRequest(
    val id: Long,
    val code: String,
    val nameRu: String,
    val icon: String? = null,
    val enabled: Boolean = true,
    val conditionType: String,
    val threshold: Int,
    val windowDays: Int? = null,
)

@Serializable
private data class AdminPrizeRequest(
    val code: String,
    val titleRu: String,
    val description: String? = null,
    val terms: String? = null,
    val enabled: Boolean = true,
    val limitTotal: Int? = null,
    val expiresInDays: Int? = null,
)

@Serializable
private data class AdminPrizeUpdateRequest(
    val id: Long,
    val code: String,
    val titleRu: String,
    val description: String? = null,
    val terms: String? = null,
    val enabled: Boolean = true,
    val limitTotal: Int? = null,
    val expiresInDays: Int? = null,
)

@Serializable
private data class AdminRewardLadderRequest(
    val metricType: String,
    val threshold: Int,
    val windowDays: Int,
    val prizeId: Long,
    val enabled: Boolean = true,
    val orderIndex: Int = 0,
)

@Serializable
private data class AdminRewardLadderUpdateRequest(
    val id: Long,
    val metricType: String,
    val threshold: Int,
    val windowDays: Int,
    val prizeId: Long,
    val enabled: Boolean = true,
    val orderIndex: Int = 0,
)

@Serializable
private data class AdminDeleteRequest(
    val id: Long,
)

@Serializable
private data class AdminBadgeResponse(
    val id: Long,
    val clubId: Long,
    val code: String,
    val nameRu: String,
    val icon: String? = null,
    val enabled: Boolean,
    val conditionType: String,
    val threshold: Int,
    val windowDays: Int? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
private data class AdminPrizeResponse(
    val id: Long,
    val clubId: Long,
    val code: String,
    val titleRu: String,
    val description: String? = null,
    val terms: String? = null,
    val enabled: Boolean,
    val limitTotal: Int? = null,
    val expiresInDays: Int? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
private data class AdminRewardLadderResponse(
    val id: Long,
    val clubId: Long,
    val metricType: String,
    val threshold: Int,
    val windowDays: Int,
    val prizeId: Long,
    val enabled: Boolean,
    val orderIndex: Int,
    val createdAt: String,
    val updatedAt: String,
)

fun Application.adminGamificationRoutes(
    settingsRepository: AdminGamificationSettingsRepository,
    nightOverrideRepository: AdminNightOverrideRepository,
    badgeRepository: AdminBadgeRepository,
    prizeRepository: AdminPrizeRepository,
    rewardLadderRepository: AdminRewardLadderRepository,
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    val logger = LoggerFactory.getLogger("AdminGamificationRoutes")

    routing {
        route("/api/admin") {
            intercept(ApplicationCallPipeline.Setup) { call.ensureMiniAppNoStoreHeaders() }
            withMiniAppAuth { botTokenProvider() }

            authorize(Role.OWNER, Role.GLOBAL_ADMIN, Role.CLUB_ADMIN) {
                route("/clubs/{clubId}/gamification/settings") {
                    get {
                        val clubId = call.requireClubIdPath() ?: return@get
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@get call.respondForbidden()
                        }

                        val settings =
                            settingsRepository.getByClubId(clubId) ?: defaultSettings(clubId)
                        call.respond(HttpStatusCode.OK, settings.toResponse())
                    }

                    put {
                        val clubId = call.requireClubIdPath() ?: return@put
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@put call.respondForbidden()
                        }

                        val payload = runCatching { call.receive<AdminGamificationSettingsRequest>() }.getOrNull()
                            ?: return@put call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                        val validationErrors = payload.validateSettings()
                        if (validationErrors.isNotEmpty()) {
                            return@put call.respondValidationErrors(validationErrors)
                        }

                        val updated =
                            settingsRepository.upsert(
                                AdminGamificationSettingsUpdate(
                                    clubId = clubId,
                                    stampsEnabled = payload.stampsEnabled,
                                    earlyEnabled = payload.earlyEnabled,
                                    badgesEnabled = payload.badgesEnabled,
                                    prizesEnabled = payload.prizesEnabled,
                                    contestsEnabled = payload.contestsEnabled,
                                    tablesLoyaltyEnabled = payload.tablesLoyaltyEnabled,
                                    earlyWindowMinutes = payload.earlyWindowMinutes,
                                ),
                            )
                        logger.info(
                            "admin.gamification.settings.update club_id={} by={}",
                            clubId,
                            call.rbacContext().user.id,
                        )
                        call.respond(HttpStatusCode.OK, updated.toResponse())
                    }
                }

                route("/clubs/{clubId}/nights/{nightStartUtc}/gamification") {
                    get {
                        val clubId = call.requireClubIdPath() ?: return@get
                        val nightStartUtc = call.requireNightStartUtc() ?: return@get
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@get call.respondForbidden()
                        }

                        val override = nightOverrideRepository.getOverride(clubId, nightStartUtc)
                        val settings = settingsRepository.getByClubId(clubId)
                        val effective = computeEffectiveEarlyCutoff(override?.earlyCutoffAt, settings, nightStartUtc)
                        call.respond(
                            HttpStatusCode.OK,
                            AdminNightOverrideResponse(
                                nightStartUtc = nightStartUtc.toString(),
                                earlyCutoffAt = override?.earlyCutoffAt?.toString(),
                                effectiveEarlyCutoffAt = effective?.toString(),
                            ),
                        )
                    }

                    put {
                        val clubId = call.requireClubIdPath() ?: return@put
                        val nightStartUtc = call.requireNightStartUtc() ?: return@put
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@put call.respondForbidden()
                        }

                        val payload = runCatching { call.receive<AdminNightOverrideRequest>() }.getOrNull()
                            ?: return@put call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        val earlyCutoffAt = payload.earlyCutoffAt?.let { raw ->
                            runCatching { Instant.parse(raw) }.getOrNull()
                                ?: return@put call.respondValidationErrors(mapOf("earlyCutoffAt" to "invalid_format"))
                        }

                        val updated = nightOverrideRepository.upsertOverride(clubId, nightStartUtc, earlyCutoffAt)
                        val settings = settingsRepository.getByClubId(clubId)
                        val effective = computeEffectiveEarlyCutoff(updated.earlyCutoffAt, settings, nightStartUtc)
                        logger.info(
                            "admin.gamification.night_override.upsert club_id={} night_start={} by={}",
                            clubId,
                            nightStartUtc,
                            call.rbacContext().user.id,
                        )
                        call.respond(
                            HttpStatusCode.OK,
                            AdminNightOverrideResponse(
                                nightStartUtc = nightStartUtc.toString(),
                                earlyCutoffAt = updated.earlyCutoffAt?.toString(),
                                effectiveEarlyCutoffAt = effective?.toString(),
                            ),
                        )
                    }
                }

                route("/clubs/{clubId}/gamification/badges") {
                    get {
                        val clubId = call.requireClubIdPath() ?: return@get
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@get call.respondForbidden()
                        }
                        val badges = badgeRepository.listForClub(clubId)
                        call.respond(HttpStatusCode.OK, badges.map { it.toResponse() })
                    }

                    post {
                        val clubId = call.requireClubIdPath() ?: return@post
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@post call.respondForbidden()
                        }

                        val payload = runCatching { call.receive<AdminBadgeRequest>() }.getOrNull()
                            ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        val validationErrors = payload.validateBadge()
                        if (validationErrors.isNotEmpty()) {
                            return@post call.respondValidationErrors(validationErrors)
                        }
                        val created =
                            badgeRepository.create(
                                clubId,
                                AdminBadgeCreate(
                                    code = payload.code.trim(),
                                    nameRu = payload.nameRu.trim(),
                                    icon = payload.icon?.trim(),
                                    enabled = payload.enabled,
                                    conditionType = payload.conditionType.trim(),
                                    threshold = payload.threshold,
                                    windowDays = payload.windowDays,
                                ),
                            )
                        logger.info("admin.gamification.badges.create club_id={} badge_id={} by={}", clubId, created.id, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.Created, created.toResponse())
                    }

                    put {
                        val clubId = call.requireClubIdPath() ?: return@put
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@put call.respondForbidden()
                        }

                        val payload = runCatching { call.receive<AdminBadgeUpdateRequest>() }.getOrNull()
                            ?: return@put call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        if (payload.id <= 0) {
                            return@put call.respondValidationErrors(mapOf("id" to "must_be_positive"))
                        }
                        val validationErrors = payload.validateBadge()
                        if (validationErrors.isNotEmpty()) {
                            return@put call.respondValidationErrors(validationErrors)
                        }
                        val updated =
                            badgeRepository.update(
                                clubId,
                                AdminBadgeUpdate(
                                    id = payload.id,
                                    code = payload.code.trim(),
                                    nameRu = payload.nameRu.trim(),
                                    icon = payload.icon?.trim(),
                                    enabled = payload.enabled,
                                    conditionType = payload.conditionType.trim(),
                                    threshold = payload.threshold,
                                    windowDays = payload.windowDays,
                                ),
                            )
                                ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        logger.info("admin.gamification.badges.update club_id={} badge_id={} by={}", clubId, updated.id, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.OK, updated.toResponse())
                    }

                    delete {
                        val clubId = call.requireClubIdPath() ?: return@delete
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@delete call.respondForbidden()
                        }
                        val payload = runCatching { call.receive<AdminDeleteRequest>() }.getOrNull()
                            ?: return@delete call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        if (payload.id <= 0) {
                            return@delete call.respondValidationErrors(mapOf("id" to "must_be_positive"))
                        }
                        val deleted = badgeRepository.delete(clubId, payload.id)
                        if (!deleted) {
                            return@delete call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        }
                        logger.info("admin.gamification.badges.delete club_id={} badge_id={} by={}", clubId, payload.id, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }

                route("/clubs/{clubId}/gamification/prizes") {
                    get {
                        val clubId = call.requireClubIdPath() ?: return@get
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@get call.respondForbidden()
                        }
                        val prizes = prizeRepository.listForClub(clubId)
                        call.respond(HttpStatusCode.OK, prizes.map { it.toResponse() })
                    }

                    post {
                        val clubId = call.requireClubIdPath() ?: return@post
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@post call.respondForbidden()
                        }

                        val payload = runCatching { call.receive<AdminPrizeRequest>() }.getOrNull()
                            ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        val validationErrors = payload.validatePrize()
                        if (validationErrors.isNotEmpty()) {
                            return@post call.respondValidationErrors(validationErrors)
                        }
                        val created =
                            prizeRepository.create(
                                clubId,
                                AdminPrizeCreate(
                                    code = payload.code.trim(),
                                    titleRu = payload.titleRu.trim(),
                                    description = payload.description?.trim(),
                                    terms = payload.terms?.trim(),
                                    enabled = payload.enabled,
                                    limitTotal = payload.limitTotal,
                                    expiresInDays = payload.expiresInDays,
                                ),
                            )
                        logger.info("admin.gamification.prizes.create club_id={} prize_id={} by={}", clubId, created.id, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.Created, created.toResponse())
                    }

                    put {
                        val clubId = call.requireClubIdPath() ?: return@put
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@put call.respondForbidden()
                        }

                        val payload = runCatching { call.receive<AdminPrizeUpdateRequest>() }.getOrNull()
                            ?: return@put call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        if (payload.id <= 0) {
                            return@put call.respondValidationErrors(mapOf("id" to "must_be_positive"))
                        }
                        val validationErrors = payload.validatePrize()
                        if (validationErrors.isNotEmpty()) {
                            return@put call.respondValidationErrors(validationErrors)
                        }
                        val updated =
                            prizeRepository.update(
                                clubId,
                                AdminPrizeUpdate(
                                    id = payload.id,
                                    code = payload.code.trim(),
                                    titleRu = payload.titleRu.trim(),
                                    description = payload.description?.trim(),
                                    terms = payload.terms?.trim(),
                                    enabled = payload.enabled,
                                    limitTotal = payload.limitTotal,
                                    expiresInDays = payload.expiresInDays,
                                ),
                            )
                                ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        logger.info("admin.gamification.prizes.update club_id={} prize_id={} by={}", clubId, updated.id, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.OK, updated.toResponse())
                    }

                    delete {
                        val clubId = call.requireClubIdPath() ?: return@delete
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@delete call.respondForbidden()
                        }
                        val payload = runCatching { call.receive<AdminDeleteRequest>() }.getOrNull()
                            ?: return@delete call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        if (payload.id <= 0) {
                            return@delete call.respondValidationErrors(mapOf("id" to "must_be_positive"))
                        }
                        val deleted = prizeRepository.delete(clubId, payload.id)
                        if (!deleted) {
                            return@delete call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        }
                        logger.info("admin.gamification.prizes.delete club_id={} prize_id={} by={}", clubId, payload.id, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }

                route("/clubs/{clubId}/gamification/ladder-levels") {
                    get {
                        val clubId = call.requireClubIdPath() ?: return@get
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@get call.respondForbidden()
                        }
                        val levels = rewardLadderRepository.listForClub(clubId)
                        call.respond(HttpStatusCode.OK, levels.map { it.toResponse() })
                    }

                    post {
                        val clubId = call.requireClubIdPath() ?: return@post
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@post call.respondForbidden()
                        }

                        val payload = runCatching { call.receive<AdminRewardLadderRequest>() }.getOrNull()
                            ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        val validationErrors = payload.validateLadder()
                        if (validationErrors.isNotEmpty()) {
                            return@post call.respondValidationErrors(validationErrors)
                        }
                        val created =
                            rewardLadderRepository.create(
                                clubId,
                                AdminRewardLadderLevelCreate(
                                    metricType = payload.metricType.trim(),
                                    threshold = payload.threshold,
                                    windowDays = payload.windowDays,
                                    prizeId = payload.prizeId,
                                    enabled = payload.enabled,
                                    orderIndex = payload.orderIndex,
                                ),
                            )
                        logger.info("admin.gamification.ladder.create club_id={} level_id={} by={}", clubId, created.id, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.Created, created.toResponse())
                    }

                    put {
                        val clubId = call.requireClubIdPath() ?: return@put
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@put call.respondForbidden()
                        }

                        val payload = runCatching { call.receive<AdminRewardLadderUpdateRequest>() }.getOrNull()
                            ?: return@put call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        if (payload.id <= 0) {
                            return@put call.respondValidationErrors(mapOf("id" to "must_be_positive"))
                        }
                        val validationErrors = payload.validateLadder()
                        if (validationErrors.isNotEmpty()) {
                            return@put call.respondValidationErrors(validationErrors)
                        }
                        val updated =
                            rewardLadderRepository.update(
                                clubId,
                                AdminRewardLadderLevelUpdate(
                                    id = payload.id,
                                    metricType = payload.metricType.trim(),
                                    threshold = payload.threshold,
                                    windowDays = payload.windowDays,
                                    prizeId = payload.prizeId,
                                    enabled = payload.enabled,
                                    orderIndex = payload.orderIndex,
                                ),
                            )
                                ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        logger.info("admin.gamification.ladder.update club_id={} level_id={} by={}", clubId, updated.id, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.OK, updated.toResponse())
                    }

                    delete {
                        val clubId = call.requireClubIdPath() ?: return@delete
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@delete call.respondForbidden()
                        }
                        val payload = runCatching { call.receive<AdminDeleteRequest>() }.getOrNull()
                            ?: return@delete call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        if (payload.id <= 0) {
                            return@delete call.respondValidationErrors(mapOf("id" to "must_be_positive"))
                        }
                        val deleted = rewardLadderRepository.delete(clubId, payload.id)
                        if (!deleted) {
                            return@delete call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        }
                        logger.info("admin.gamification.ladder.delete club_id={} level_id={} by={}", clubId, payload.id, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }
}

private fun AdminGamificationSettingsRequest.validateSettings(): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    if (earlyWindowMinutes != null && earlyWindowMinutes < 0) {
        errors["earlyWindowMinutes"] = "must_be_non_negative"
    }
    return errors
}

private fun AdminBadgeRequest.validateBadge(): Map<String, String> = validateBadgeFields(
    code = code,
    nameRu = nameRu,
    conditionType = conditionType,
    threshold = threshold,
    windowDays = windowDays,
)

private fun AdminBadgeUpdateRequest.validateBadge(): Map<String, String> = validateBadgeFields(
    code = code,
    nameRu = nameRu,
    conditionType = conditionType,
    threshold = threshold,
    windowDays = windowDays,
)

private fun validateBadgeFields(
    code: String,
    nameRu: String,
    conditionType: String,
    threshold: Int,
    windowDays: Int?,
): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    if (code.isBlank()) errors["code"] = "must_not_be_blank"
    if (nameRu.isBlank()) errors["nameRu"] = "must_not_be_blank"
    if (!BadgeConditionType.isAllowed(conditionType)) errors["conditionType"] = "unsupported"
    if (threshold < 1) errors["threshold"] = "must_be_at_least_1"
    if (windowDays != null && windowDays < 1) errors["windowDays"] = "must_be_at_least_1"
    return errors
}

private fun AdminPrizeRequest.validatePrize(): Map<String, String> = validatePrizeFields(
    code = code,
    titleRu = titleRu,
    limitTotal = limitTotal,
    expiresInDays = expiresInDays,
)

private fun AdminPrizeUpdateRequest.validatePrize(): Map<String, String> = validatePrizeFields(
    code = code,
    titleRu = titleRu,
    limitTotal = limitTotal,
    expiresInDays = expiresInDays,
)

private fun validatePrizeFields(
    code: String,
    titleRu: String,
    limitTotal: Int?,
    expiresInDays: Int?,
): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    if (code.isBlank()) errors["code"] = "must_not_be_blank"
    if (titleRu.isBlank()) errors["titleRu"] = "must_not_be_blank"
    if (limitTotal != null && limitTotal < 1) errors["limitTotal"] = "must_be_at_least_1"
    if (expiresInDays != null && expiresInDays < 1) errors["expiresInDays"] = "must_be_at_least_1"
    return errors
}

private fun AdminRewardLadderRequest.validateLadder(): Map<String, String> = validateLadderFields(
    metricType = metricType,
    threshold = threshold,
    windowDays = windowDays,
    prizeId = prizeId,
    orderIndex = orderIndex,
)

private fun AdminRewardLadderUpdateRequest.validateLadder(): Map<String, String> = validateLadderFields(
    metricType = metricType,
    threshold = threshold,
    windowDays = windowDays,
    prizeId = prizeId,
    orderIndex = orderIndex,
)

private fun validateLadderFields(
    metricType: String,
    threshold: Int,
    windowDays: Int,
    prizeId: Long,
    orderIndex: Int,
): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    if (!GamificationMetricType.isAllowed(metricType)) errors["metricType"] = "unsupported"
    if (threshold < 1) errors["threshold"] = "must_be_at_least_1"
    if (windowDays < 1) errors["windowDays"] = "must_be_at_least_1"
    if (prizeId <= 0) errors["prizeId"] = "must_be_positive"
    if (orderIndex < 0) errors["orderIndex"] = "must_be_non_negative"
    return errors
}

private fun AdminGamificationSettings.toResponse(): AdminGamificationSettingsRequest =
    AdminGamificationSettingsRequest(
        stampsEnabled = stampsEnabled,
        earlyEnabled = earlyEnabled,
        badgesEnabled = badgesEnabled,
        prizesEnabled = prizesEnabled,
        contestsEnabled = contestsEnabled,
        tablesLoyaltyEnabled = tablesLoyaltyEnabled,
        earlyWindowMinutes = earlyWindowMinutes,
    )

private fun com.example.bot.admin.AdminBadge.toResponse(): AdminBadgeResponse =
    AdminBadgeResponse(
        id = id,
        clubId = clubId,
        code = code,
        nameRu = nameRu,
        icon = icon,
        enabled = enabled,
        conditionType = conditionType,
        threshold = threshold,
        windowDays = windowDays,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

private fun com.example.bot.admin.AdminPrize.toResponse(): AdminPrizeResponse =
    AdminPrizeResponse(
        id = id,
        clubId = clubId,
        code = code,
        titleRu = titleRu,
        description = description,
        terms = terms,
        enabled = enabled,
        limitTotal = limitTotal,
        expiresInDays = expiresInDays,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

private fun com.example.bot.admin.AdminRewardLadderLevel.toResponse(): AdminRewardLadderResponse =
    AdminRewardLadderResponse(
        id = id,
        clubId = clubId,
        metricType = metricType,
        threshold = threshold,
        windowDays = windowDays,
        prizeId = prizeId,
        enabled = enabled,
        orderIndex = orderIndex,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

private fun defaultSettings(clubId: Long): AdminGamificationSettings =
    AdminGamificationSettings(
        clubId = clubId,
        stampsEnabled = true,
        earlyEnabled = false,
        badgesEnabled = false,
        prizesEnabled = false,
        contestsEnabled = false,
        tablesLoyaltyEnabled = false,
        earlyWindowMinutes = null,
        updatedAt = Instant.EPOCH,
    )

private fun computeEffectiveEarlyCutoff(
    overrideCutoff: Instant?,
    settings: AdminGamificationSettings?,
    nightStartUtc: Instant,
): Instant? =
    overrideCutoff
        ?: settings?.earlyWindowMinutes?.let { minutes -> nightStartUtc.plus(Duration.ofMinutes(minutes.toLong())) }

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

private suspend fun ApplicationCall.respondValidationErrors(details: Map<String, String>) {
    ensureMiniAppNoStoreHeaders()
    respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error, details = details)
}

private suspend fun ApplicationCall.respondForbidden() {
    ensureMiniAppNoStoreHeaders()
    respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
}
