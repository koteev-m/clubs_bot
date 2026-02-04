package com.example.bot.routes

import com.example.bot.data.finance.ClubBraceletType
import com.example.bot.data.finance.ClubReportTemplateData
import com.example.bot.data.finance.ClubRevenueArticle
import com.example.bot.data.finance.ClubRevenueGroup
import com.example.bot.data.finance.ShiftReportTemplateRepository
import com.example.bot.data.security.Role
import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.respondError
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.authorize
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
import kotlinx.serialization.Serializable

@Serializable
data class BraceletTypeCreateRequest(
    val name: String,
    val orderIndex: Int? = null,
)

@Serializable
data class BraceletTypeUpdateRequest(
    val name: String,
)

@Serializable
data class RevenueGroupCreateRequest(
    val name: String,
    val orderIndex: Int? = null,
)

@Serializable
data class RevenueGroupUpdateRequest(
    val name: String,
)

@Serializable
data class RevenueArticleCreateRequest(
    val groupId: Long,
    val name: String,
    val includeInTotal: Boolean? = null,
    val showSeparately: Boolean? = null,
    val orderIndex: Int? = null,
)

@Serializable
data class RevenueArticleUpdateRequest(
    val groupId: Long,
    val name: String,
    val includeInTotal: Boolean,
    val showSeparately: Boolean,
)

@Serializable
data class ReorderRequest(
    val ids: List<Long>,
)

fun Application.adminFinanceTemplateRoutes(
    templateRepository: ShiftReportTemplateRepository,
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    routing {
        route("/api/admin") {
            intercept(ApplicationCallPipeline.Setup) { call.ensureMiniAppNoStoreHeaders() }
            withMiniAppAuth { botTokenProvider() }

            authorize(Role.OWNER, Role.GLOBAL_ADMIN) {
                route("/clubs/{clubId}/finance/template") {
                    get {
                        val clubId = call.requireClubIdPath() ?: return@get
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@get call.respondForbidden()
                        }
                        val template = templateRepository.getTemplateData(clubId)
                        call.respond(HttpStatusCode.OK, template.toResponse())
                    }

                    route("/bracelets") {
                        post {
                            val clubId = call.requireClubIdPath() ?: return@post
                            if (!call.isAdminClubAllowed(clubId)) {
                                return@post call.respondForbidden()
                            }
                            val payload = runCatching { call.receive<BraceletTypeCreateRequest>() }.getOrNull()
                                ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                            val errors = mutableMapOf<String, String>()
                            val name = payload.name.trim()
                            if (name.isEmpty()) {
                                errors["name"] = "required"
                            }
                            val orderIndex = payload.orderIndex ?: 0
                            if (orderIndex < 0) {
                                errors["orderIndex"] = "must_be_non_negative"
                            }
                            if (errors.isNotEmpty()) {
                                return@post call.respondValidationErrors(errors)
                            }
                            val created = templateRepository.createBraceletType(clubId, name, orderIndex)
                            call.respond(HttpStatusCode.OK, created.toResponse())
                        }

                        put("/{braceletId}") {
                            val clubId = call.requireClubIdPath() ?: return@put
                            val braceletId = call.requireEntityId("braceletId") ?: return@put
                            if (!call.isAdminClubAllowed(clubId)) {
                                return@put call.respondForbidden()
                            }
                            val payload = runCatching { call.receive<BraceletTypeUpdateRequest>() }.getOrNull()
                                ?: return@put call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                            val name = payload.name.trim()
                            if (name.isEmpty()) {
                                return@put call.respondValidationErrors(mapOf("name" to "required"))
                            }
                            val updated = templateRepository.updateBraceletType(clubId, braceletId, name)
                                ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                            call.respond(HttpStatusCode.OK, updated.toResponse())
                        }

                        post("/{braceletId}/disable") {
                            val clubId = call.requireClubIdPath() ?: return@post
                            val braceletId = call.requireEntityId("braceletId") ?: return@post
                            if (!call.isAdminClubAllowed(clubId)) {
                                return@post call.respondForbidden()
                            }
                            val disabled = templateRepository.disableBraceletType(clubId, braceletId)
                            if (!disabled) {
                                return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                            }
                            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                        }

                        post("/reorder") {
                            val clubId = call.requireClubIdPath() ?: return@post
                            if (!call.isAdminClubAllowed(clubId)) {
                                return@post call.respondForbidden()
                            }
                            val payload = runCatching { call.receive<ReorderRequest>() }.getOrNull()
                                ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                            val errors = validateReorder(payload)
                            if (errors.isNotEmpty()) {
                                return@post call.respondValidationErrors(errors)
                            }
                            val updated = templateRepository.reorderBraceletTypes(clubId, payload.ids)
                            if (updated < payload.ids.size) {
                                return@post call.respondValidationErrors(mapOf("ids" to "not_found"))
                            }
                            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                        }
                    }

                    route("/revenue-groups") {
                        post {
                            val clubId = call.requireClubIdPath() ?: return@post
                            if (!call.isAdminClubAllowed(clubId)) {
                                return@post call.respondForbidden()
                            }
                            val payload = runCatching { call.receive<RevenueGroupCreateRequest>() }.getOrNull()
                                ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                            val errors = mutableMapOf<String, String>()
                            val name = payload.name.trim()
                            if (name.isEmpty()) {
                                errors["name"] = "required"
                            }
                            val orderIndex = payload.orderIndex ?: 0
                            if (orderIndex < 0) {
                                errors["orderIndex"] = "must_be_non_negative"
                            }
                            if (errors.isNotEmpty()) {
                                return@post call.respondValidationErrors(errors)
                            }
                            val created = templateRepository.createRevenueGroup(clubId, name, orderIndex)
                            call.respond(HttpStatusCode.OK, created.toResponse())
                        }

                        put("/{groupId}") {
                            val clubId = call.requireClubIdPath() ?: return@put
                            val groupId = call.requireEntityId("groupId") ?: return@put
                            if (!call.isAdminClubAllowed(clubId)) {
                                return@put call.respondForbidden()
                            }
                            val payload = runCatching { call.receive<RevenueGroupUpdateRequest>() }.getOrNull()
                                ?: return@put call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                            val name = payload.name.trim()
                            if (name.isEmpty()) {
                                return@put call.respondValidationErrors(mapOf("name" to "required"))
                            }
                            val updated = templateRepository.updateRevenueGroup(clubId, groupId, name)
                                ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                            call.respond(HttpStatusCode.OK, updated.toResponse())
                        }

                        post("/{groupId}/disable") {
                            val clubId = call.requireClubIdPath() ?: return@post
                            val groupId = call.requireEntityId("groupId") ?: return@post
                            if (!call.isAdminClubAllowed(clubId)) {
                                return@post call.respondForbidden()
                            }
                            val disabled = templateRepository.disableRevenueGroup(clubId, groupId)
                            if (!disabled) {
                                return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                            }
                            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                        }

                        post("/reorder") {
                            val clubId = call.requireClubIdPath() ?: return@post
                            if (!call.isAdminClubAllowed(clubId)) {
                                return@post call.respondForbidden()
                            }
                            val payload = runCatching { call.receive<ReorderRequest>() }.getOrNull()
                                ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                            val errors = validateReorder(payload)
                            if (errors.isNotEmpty()) {
                                return@post call.respondValidationErrors(errors)
                            }
                            val updated = templateRepository.reorderRevenueGroups(clubId, payload.ids)
                            if (updated < payload.ids.size) {
                                return@post call.respondValidationErrors(mapOf("ids" to "not_found"))
                            }
                            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                        }
                    }

                    route("/revenue-articles") {
                        post {
                            val clubId = call.requireClubIdPath() ?: return@post
                            if (!call.isAdminClubAllowed(clubId)) {
                                return@post call.respondForbidden()
                            }
                            val payload = runCatching { call.receive<RevenueArticleCreateRequest>() }.getOrNull()
                                ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                            val errors = mutableMapOf<String, String>()
                            val name = payload.name.trim()
                            if (name.isEmpty()) {
                                errors["name"] = "required"
                            }
                            if (payload.groupId <= 0) {
                                errors["groupId"] = "must_be_positive"
                            }
                            val orderIndex = payload.orderIndex ?: 0
                            if (orderIndex < 0) {
                                errors["orderIndex"] = "must_be_non_negative"
                            }
                            if (errors.isNotEmpty()) {
                                return@post call.respondValidationErrors(errors)
                            }
                            val includeInTotal = payload.includeInTotal ?: false
                            val showSeparately = payload.showSeparately ?: false
                            val created =
                                try {
                                    templateRepository.createRevenueArticle(
                                        clubId = clubId,
                                        groupId = payload.groupId,
                                        name = name,
                                        includeInTotal = includeInTotal,
                                        showSeparately = showSeparately,
                                        orderIndex = orderIndex,
                                    )
                                } catch (ex: IllegalArgumentException) {
                                    return@post call.respondValidationErrors(mapOf("groupId" to (ex.message ?: "invalid")))
                                }
                            call.respond(HttpStatusCode.OK, created.toResponse())
                        }

                        put("/{articleId}") {
                            val clubId = call.requireClubIdPath() ?: return@put
                            val articleId = call.requireEntityId("articleId") ?: return@put
                            if (!call.isAdminClubAllowed(clubId)) {
                                return@put call.respondForbidden()
                            }
                            val payload = runCatching { call.receive<RevenueArticleUpdateRequest>() }.getOrNull()
                                ?: return@put call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                            val errors = mutableMapOf<String, String>()
                            val name = payload.name.trim()
                            if (name.isEmpty()) {
                                errors["name"] = "required"
                            }
                            if (payload.groupId <= 0) {
                                errors["groupId"] = "must_be_positive"
                            }
                            if (errors.isNotEmpty()) {
                                return@put call.respondValidationErrors(errors)
                            }
                            val updated =
                                try {
                                    templateRepository.updateRevenueArticle(
                                        clubId = clubId,
                                        id = articleId,
                                        name = name,
                                        includeInTotal = payload.includeInTotal,
                                        showSeparately = payload.showSeparately,
                                        groupId = payload.groupId,
                                    )
                                } catch (ex: IllegalArgumentException) {
                                    return@put call.respondValidationErrors(mapOf("groupId" to (ex.message ?: "invalid")))
                                }
                            updated ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                            call.respond(HttpStatusCode.OK, updated.toResponse())
                        }

                        post("/{articleId}/disable") {
                            val clubId = call.requireClubIdPath() ?: return@post
                            val articleId = call.requireEntityId("articleId") ?: return@post
                            if (!call.isAdminClubAllowed(clubId)) {
                                return@post call.respondForbidden()
                            }
                            val disabled = templateRepository.disableRevenueArticle(clubId, articleId)
                            if (!disabled) {
                                return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                            }
                            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                        }

                        post("/reorder") {
                            val clubId = call.requireClubIdPath() ?: return@post
                            if (!call.isAdminClubAllowed(clubId)) {
                                return@post call.respondForbidden()
                            }
                            val payload = runCatching { call.receive<ReorderRequest>() }.getOrNull()
                                ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                            val errors = validateReorder(payload)
                            if (errors.isNotEmpty()) {
                                return@post call.respondValidationErrors(errors)
                            }
                            val updated = templateRepository.reorderRevenueArticles(clubId, payload.ids)
                            if (updated < payload.ids.size) {
                                return@post call.respondValidationErrors(mapOf("ids" to "not_found"))
                            }
                            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                        }
                    }
                }
            }
        }
    }
}

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

private fun validateReorder(payload: ReorderRequest): Map<String, String> {
    if (payload.ids.isEmpty()) {
        return mapOf("ids" to "required")
    }
    val invalid = payload.ids.indexOfFirst { it <= 0 }
    if (invalid >= 0) {
        return mapOf("ids[$invalid]" to "must_be_positive")
    }
    if (payload.ids.toSet().size != payload.ids.size) {
        return mapOf("ids" to "must_be_unique")
    }
    return emptyMap()
}

private suspend fun ApplicationCall.requireClubIdPath(): Long? {
    val clubId = parameters["clubId"]?.toLongOrNull()
    if (clubId == null || clubId <= 0) {
        respondValidationErrors(mapOf("clubId" to "must_be_positive"))
        return null
    }
    return clubId
}

private suspend fun ApplicationCall.requireEntityId(name: String): Long? {
    val id = parameters[name]?.toLongOrNull()
    if (id == null || id <= 0) {
        respondValidationErrors(mapOf(name to "must_be_positive"))
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
