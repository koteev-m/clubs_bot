package com.example.bot.routes

import com.example.bot.data.booking.TableDepositRepository
import com.example.bot.data.finance.ShiftReportRepository
import com.example.bot.data.finance.totalRevenue
import com.example.bot.data.security.Role
import com.example.bot.data.stories.GuestSegmentsRepository
import com.example.bot.data.stories.PostEventStoryRepository
import com.example.bot.data.stories.PostEventStoryStatus
import com.example.bot.data.stories.SegmentType
import com.example.bot.data.visits.VisitRepository
import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.respondError
import com.example.bot.owner.AttendanceHealth
import com.example.bot.owner.OwnerHealthService
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.authorize
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.Clock
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private const val DEFAULT_WINDOW_DAYS = 30
private const val MAX_WINDOW_DAYS = 365
private const val DEFAULT_STORIES_LIMIT = 20
private const val MAX_STORIES_LIMIT = 50
private const val STORY_SCHEMA_VERSION = 1

@Serializable
data class AnalyticsMeta(
    val hasIncompleteData: Boolean,
    val caveats: List<String>,
)

@Serializable
data class VisitSummary(
    val uniqueVisitors: Long,
    val earlyVisits: Long,
    val tableNights: Long,
)

@Serializable
data class DepositSummary(
    val totalMinor: Long,
    val allocationSummary: Map<String, Long>,
)

@Serializable
data class ShiftSummary(
    val status: String,
    val peopleWomen: Int,
    val peopleMen: Int,
    val peopleRejected: Int,
    val revenueTotalMinor: Long,
)

@Serializable
data class SegmentSummary(
    val counts: Map<String, Int>,
)

@Serializable
data class AnalyticsResponse(
    val schemaVersion: Int,
    val clubId: Long,
    val nightStartUtc: String,
    val generatedAt: String,
    val meta: AnalyticsMeta,
    val attendance: AttendanceHealth?,
    val visits: VisitSummary,
    val deposits: DepositSummary,
    val shift: ShiftSummary?,
    val segments: SegmentSummary,
)

@Serializable
data class StoryListItem(
    val id: Long,
    val nightStartUtc: String,
    val schemaVersion: Int,
    val status: String,
    val generatedAt: String,
    val updatedAt: String,
)

@Serializable
data class StoryListResponse(
    val stories: List<StoryListItem>,
    val limit: Int,
    val offset: Long,
)

@Serializable
data class StoryDetailsResponse(
    val id: Long,
    val clubId: Long,
    val nightStartUtc: String,
    val schemaVersion: Int,
    val status: String,
    val payload: JsonElement,
    val generatedAt: String,
    val updatedAt: String,
)

fun Application.adminAnalyticsRoutes(
    ownerHealthService: OwnerHealthService,
    visitRepository: VisitRepository,
    tableDepositRepository: TableDepositRepository,
    shiftReportRepository: ShiftReportRepository,
    storyRepository: PostEventStoryRepository,
    guestSegmentsRepository: GuestSegmentsRepository,
    clock: Clock = Clock.systemUTC(),
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
    json: Json = Json { encodeDefaults = true; explicitNulls = false },
) {
    routing {
        route("/api/admin") {
            withMiniAppAuth { botTokenProvider() }
            authorize(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER) {
                intercept(ApplicationCallPipeline.Setup) { call.ensureMiniAppNoStoreHeaders() }

                route("/clubs/{clubId}") {
                    get("/analytics") {
                        val clubId = call.requireClubIdPath() ?: return@get
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@get call.respondForbidden()
                        }

                        val nightStartUtc = call.requireNightStartUtcQuery() ?: return@get
                        val windowDays = call.requireWindowDays() ?: return@get
                        val generatedAt = Instant.now(clock)

                        val caveats = mutableListOf<String>()
                        var hasIncomplete = false

                        fun recordCaveat(code: String) {
                            if (code !in caveats) {
                                caveats += code
                            }
                            hasIncomplete = true
                        }

                        val attendance =
                            runCatching { ownerHealthService.attendanceForNight(clubId, nightStartUtc) }
                                .getOrElse {
                                    recordCaveat("attendance_unavailable")
                                    null
                                }

                        if (attendance == null) {
                            recordCaveat("attendance_unavailable")
                        } else if (attendance.channels.promoterBookings.plannedGuests > 0 && attendance.channels.promoterBookings.arrivedGuests == 0) {
                            recordCaveat("promoter_arrived_unavailable")
                        }

                        val visits =
                            runCatching {
                                VisitSummary(
                                    uniqueVisitors = visitRepository.countNightUniqueVisitors(clubId, nightStartUtc),
                                    earlyVisits = visitRepository.countNightEarlyVisits(clubId, nightStartUtc),
                                    tableNights = visitRepository.countNightTableNights(clubId, nightStartUtc),
                                )
                            }.getOrElse {
                                recordCaveat("visits_unavailable")
                                VisitSummary(uniqueVisitors = 0, earlyVisits = 0, tableNights = 0)
                            }

                        val deposits =
                            runCatching {
                                DepositSummary(
                                    totalMinor = tableDepositRepository.sumDepositsForNight(clubId, nightStartUtc),
                                    allocationSummary = tableDepositRepository.allocationSummaryForNight(clubId, nightStartUtc),
                                )
                            }.getOrElse {
                                recordCaveat("deposits_unavailable")
                                DepositSummary(totalMinor = 0, allocationSummary = emptyMap())
                            }

                        val shift =
                            runCatching {
                                val report = shiftReportRepository.getByClubAndNight(clubId, nightStartUtc)
                                    ?: return@runCatching null.also { recordCaveat("shift_report_missing") }
                                val details = shiftReportRepository.getDetails(report.id)
                                    ?: return@runCatching null.also { recordCaveat("shift_report_unavailable") }
                                ShiftSummary(
                                    status = report.status.name,
                                    peopleWomen = report.peopleWomen,
                                    peopleMen = report.peopleMen,
                                    peopleRejected = report.peopleRejected,
                                    revenueTotalMinor = totalRevenue(details.revenueEntries),
                                )
                            }.getOrElse {
                                recordCaveat("shift_report_unavailable")
                                null
                            }

                        val segments =
                            runCatching {
                                val result = guestSegmentsRepository.computeSegments(clubId, windowDays, generatedAt)
                                SegmentSummary(counts = result.counts.mapKeys { it.key.name.lowercase() })
                            }.getOrElse {
                                recordCaveat("segments_unavailable")
                                SegmentSummary(
                                    counts = SegmentType.entries.associate { it.name.lowercase() to 0 },
                                )
                            }

                        val response =
                            AnalyticsResponse(
                                schemaVersion = STORY_SCHEMA_VERSION,
                                clubId = clubId,
                                nightStartUtc = nightStartUtc.toString(),
                                generatedAt = generatedAt.toString(),
                                meta = AnalyticsMeta(hasIncompleteData = hasIncomplete, caveats = caveats),
                                attendance = attendance,
                                visits = visits,
                                deposits = deposits,
                                shift = shift,
                                segments = segments,
                            )

                        val payloadJson = json.encodeToString(response)
                        runCatching {
                            storyRepository.upsert(
                                clubId = clubId,
                                nightStartUtc = nightStartUtc,
                                schemaVersion = STORY_SCHEMA_VERSION,
                                status = PostEventStoryStatus.READY,
                                payloadJson = payloadJson,
                                generatedAt = generatedAt,
                                now = generatedAt,
                            )
                        }.onFailure {
                            recordCaveat("story_upsert_failed")
                        }

                        val finalResponse =
                            if (response.meta.hasIncompleteData == hasIncomplete && response.meta.caveats == caveats) {
                                response
                            } else {
                                response.copy(meta = AnalyticsMeta(hasIncompleteData = hasIncomplete, caveats = caveats))
                            }
                        call.respond(HttpStatusCode.OK, finalResponse)
                    }

                    get("/stories") {
                        val clubId = call.requireClubIdPath() ?: return@get
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@get call.respondForbidden()
                        }

                        val limit = call.requireStoriesLimit() ?: return@get
                        val offset = call.requireStoriesOffset() ?: return@get

                        val stories = storyRepository.listByClub(clubId, limit, offset)
                        call.respond(
                            HttpStatusCode.OK,
                            StoryListResponse(
                                stories = stories.map { story ->
                                    StoryListItem(
                                        id = story.id,
                                        nightStartUtc = story.nightStartUtc.toString(),
                                        schemaVersion = story.schemaVersion,
                                        status = story.status.name,
                                        generatedAt = story.generatedAt.toString(),
                                        updatedAt = story.updatedAt.toString(),
                                    )
                                },
                                limit = limit,
                                offset = offset,
                            ),
                        )
                    }

                    get("/stories/{nightStartUtc}") {
                        val clubId = call.requireClubIdPath() ?: return@get
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@get call.respondForbidden()
                        }

                        val nightStartUtc = call.requireNightStartUtcPath() ?: return@get
                        val story = storyRepository.getByClubAndNight(clubId, nightStartUtc, STORY_SCHEMA_VERSION)
                            ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        val payload = json.parseToJsonElement(story.payloadJson)
                        call.respond(
                            HttpStatusCode.OK,
                            StoryDetailsResponse(
                                id = story.id,
                                clubId = story.clubId,
                                nightStartUtc = story.nightStartUtc.toString(),
                                schemaVersion = story.schemaVersion,
                                status = story.status.name,
                                payload = payload,
                                generatedAt = story.generatedAt.toString(),
                                updatedAt = story.updatedAt.toString(),
                            ),
                        )
                    }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.requireClubIdPath(): Long? {
    val clubId = parameters["clubId"]?.toLongOrNull()
    if (clubId == null || clubId <= 0) {
        respondValidationErrors(mapOf("clubId" to "must_be_positive"))
        return null
    }
    return clubId
}

private suspend fun ApplicationCall.requireNightStartUtcQuery(): Instant? {
    val raw = request.queryParameters["nightStartUtc"]
    val instant = raw?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (instant == null) {
        respondValidationErrors(mapOf("nightStartUtc" to "invalid_format"))
        return null
    }
    return instant
}

private suspend fun ApplicationCall.requireNightStartUtcPath(): Instant? {
    val raw = parameters["nightStartUtc"]
    val instant = raw?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (instant == null) {
        respondValidationErrors(mapOf("nightStartUtc" to "invalid_format"))
        return null
    }
    return instant
}

private suspend fun ApplicationCall.requireWindowDays(): Int? {
    val raw = request.queryParameters["windowDays"] ?: return DEFAULT_WINDOW_DAYS
    val value = raw.toIntOrNull()
    if (value == null || value <= 0 || value > MAX_WINDOW_DAYS) {
        respondValidationErrors(mapOf("windowDays" to "must_be_between_1_$MAX_WINDOW_DAYS"))
        return null
    }
    return value
}

private suspend fun ApplicationCall.requireStoriesLimit(): Int? {
    val raw = request.queryParameters["limit"] ?: return DEFAULT_STORIES_LIMIT
    val value = raw.toIntOrNull()
    if (value == null || value <= 0 || value > MAX_STORIES_LIMIT) {
        respondValidationErrors(mapOf("limit" to "must_be_between_1_$MAX_STORIES_LIMIT"))
        return null
    }
    return value
}

private suspend fun ApplicationCall.requireStoriesOffset(): Long? {
    val raw = request.queryParameters["offset"] ?: return 0L
    val value = raw.toLongOrNull()
    if (value == null || value < 0) {
        respondValidationErrors(mapOf("offset" to "must_be_non_negative"))
        return null
    }
    return value
}

private suspend fun ApplicationCall.respondValidationErrors(details: Map<String, String>) {
    ensureMiniAppNoStoreHeaders()
    respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error, details = details)
}

private suspend fun ApplicationCall.respondForbidden() {
    ensureMiniAppNoStoreHeaders()
    respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
}
