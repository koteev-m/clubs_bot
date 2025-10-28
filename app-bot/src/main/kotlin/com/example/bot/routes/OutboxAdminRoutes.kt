package com.example.bot.routes

import com.example.bot.data.repo.AdminFilter
import com.example.bot.data.repo.AdminStats
import com.example.bot.data.repo.OutboxAdminRepository
import com.example.bot.data.repo.OutboxRecord
import com.example.bot.data.repo.Page
import com.example.bot.data.repo.Paged
import com.example.bot.data.repo.Sort
import com.example.bot.data.repo.SortDirection
import com.example.bot.data.repo.SortField
import com.example.bot.observability.MetricsProvider
import com.example.bot.plugins.envBool
import com.example.bot.plugins.envString
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.security.rbac.authorize
import com.example.bot.data.security.Role
import com.example.bot.telemetry.spanSuspending
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.Timer
import io.micrometer.tracing.Tracer
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.koin.ktor.ext.getKoin

private val logger = KotlinLogging.logger("OutboxAdminRoutes")

@Serializable
data class OutboxAdminQuery(
    val topic: String? = null,
    val status: String? = null,
    val attemptsMin: Int? = null,
    val createdAfter: String? = null,
    val idIn: List<Long>? = null,
    val limit: Int? = 50,
    val offset: Int? = 0,
    val sort: String? = "created_at",
    val dir: String? = "desc",
)

@Serializable
data class OutboxRecordDto(
    val id: Long,
    val topic: String,
    val status: String,
    val attempts: Int,
    val nextAttemptAt: String?,
    val lastError: String?,
    val createdAt: String,
    val source: String,
)

@Serializable
data class AdminStatsDto(
    val total: Long,
    val byStatus: Map<String, Long>,
    val byTopic: Map<String, Long>,
)

@Serializable
data class OutboxAdminPage(
    val items: List<OutboxRecordDto>,
    val total: Long,
    val limit: Int,
    val offset: Int,
    val sort: String,
    val dir: String,
    val stats: AdminStatsDto?,
)

@Serializable
data class ReplayRequest(
    val ids: List<Long>? = null,
    val filter: OutboxAdminQuery? = null,
    val dryRun: Boolean? = false,
    val maxRows: Int? = 1000,
)

@Serializable
data class ReplayResponse(
    val candidates: Int,
    val affected: Int,
    val dryRun: Boolean,
    val topic: String?,
)

fun Application.outboxAdminRoutes(
    repository: OutboxAdminRepository,
    metricsProvider: MetricsProvider? = null,
    tracer: Tracer? = null,
) {
    val enabled = envBool("OUTBOX_ADMIN_ENABLED", default = false)
    if (!enabled) {
        logger.info { "Outbox admin routes disabled" }
        return
    }

    val rbacEnabled = envBool("RBAC_ENABLED", default = false)
    val rbacAvailable = rbacEnabled && pluginOrNull(RbacPlugin) != null

    val resolvedMetrics = metricsProvider ?: runCatching { getKoin().get<MetricsProvider>() }.getOrNull()
    val resolvedTracer = tracer ?: runCatching { getKoin().get<Tracer>() }.getOrNull()
    val actorBase = envString("OUTBOX_ADMIN_ACTOR", default = "outbox-admin") ?: "outbox-admin"

    logger.info { "Outbox admin routes enabled (defaultMaxRows=1000, maxRowsCap=10000, rbac=${rbacAvailable})" }

    routing {
        route("/api/admin/outbox") {
            val register: Route.() -> Unit = {
                get {
                    val query = call.parseAdminQuery()
                    val filter = query.toAdminFilter()
                    val page = query.toPage()

                    val topicTag = query.topic ?: "ALL"
                    val statusTag = query.status ?: "ALL"

                    val timer = resolvedMetrics?.timer("outbox.admin.list.timer", "topic", topicTag, "status", statusTag)
                    val sample = if (timer != null) {
                        resolvedMetrics?.registry?.let { registry -> Timer.start(registry) }
                    } else {
                        null
                    }

                    try {
                        val (paged, stats) =
                            resolvedTracer.spanSuspending("outbox.admin.list") {
                                query.topic?.let { setAttribute("outbox.filter.topic", it) }
                                query.status?.let { setAttribute("outbox.filter.status", it) }
                                val pagedResult = repository.list(filter, page)
                                val statsResult = repository.stats(filter)
                                setAttribute("outbox.list.count", pagedResult.items.size.toLong())
                                setAttribute("outbox.total.count", statsResult.total)
                                pagedResult to statsResult
                            }

                        val response = paged.toDto(page, stats)
                        call.respond(response)

                        val counter =
                            resolvedMetrics?.counter(
                                "outbox.admin.list.total",
                                "topic",
                                topicTag,
                                "status",
                                statusTag,
                            )
                        counter?.increment()
                    } finally {
                        if (timer != null) {
                            sample?.stop(timer)
                        }
                    }
                }

                post("/replay") {
                    val request = call.receive<ReplayRequest>()
                    val ids = request.ids?.filter { it > 0 }?.distinct().orEmpty()
                    val hasFilter = request.filter != null
                    if (ids.isEmpty() && !hasFilter) {
                        throw BadRequestException("ids or filter must be provided")
                    }
                    if (ids.isNotEmpty() && hasFilter) {
                        throw BadRequestException("provide either ids or filter, not both")
                    }

                    val dryRun = request.dryRun ?: false
                    val actor = call.actorLabel(actorBase)
                    val adminFilter = request.filter?.toAdminFilter()
                    val clamp = (request.maxRows ?: 1000).coerceIn(1, 10_000)
                    if (request.maxRows != null && request.maxRows != clamp) {
                        logger.info { "Outbox replay maxRows clamped from ${request.maxRows} to $clamp" }
                    }
                    val filterSignature =
                        request.filter?.let {
                            "topic=${it.topic ?: "ANY"},status=${it.status ?: "ANY"},createdAfter=${it.createdAfter ?: "NONE"}"
                        } ?: "topic=ANY,status=ANY,createdAfter=NONE"
                    logger.info {
                        "Outbox replay request actor=$actor dryRun=$dryRun maxRows=$clamp ids=${ids.size} filter={$filterSignature}"
                    }

                    val timerTopic = request.filter?.topic ?: if (ids.isNotEmpty()) "ids" else "ALL"
                    val timerStatus = request.filter?.status ?: "ALL"
                    val replayTimer =
                        resolvedMetrics?.timer(
                            "outbox.admin.replay.timer",
                            "topic",
                            timerTopic,
                            "status",
                            timerStatus,
                        )
                    val sample = if (replayTimer != null) {
                        resolvedMetrics?.registry?.let { registry -> Timer.start(registry) }
                    } else {
                        null
                    }

                    val resultTagSuccess = if (dryRun) "dry_run" else "success"
                    val resultTagError = "error"

                    try {
                        val result =
                            resolvedTracer.spanSuspending("outbox.admin.replay") {
                                setAttribute("outbox.actor", actor)
                                setAttribute("outbox.dry_run", dryRun)
                                adminFilter?.topic?.let { setAttribute("outbox.filter.topic", it) }
                                adminFilter?.status?.let { setAttribute("outbox.filter.status", it) }
                                if (ids.isNotEmpty()) {
                                    setAttribute("outbox.replay.mode", "ids")
                                    repository.markForReplayByIds(ids, actor, dryRun)
                                } else {
                                    setAttribute("outbox.replay.mode", "filter")
                                    setAttribute("outbox.max_rows", clamp.toLong())
                                    repository.markForReplayByFilter(
                                        filter = adminFilter ?: throw BadRequestException("filter required"),
                                        maxRows = clamp,
                                        actor = actor,
                                        dryRun = dryRun,
                                    )
                                }.also { replay ->
                                    setAttribute("outbox.candidate.count", replay.totalCandidates.toLong())
                                    setAttribute("outbox.affected.count", replay.affected.toLong())
                                    setAttribute("outbox.result.topic", (replay.topic ?: "MULTI"))
                                }
                            }

                        if (!result.dryRun) {
                            logger.info {
                                "Outbox replay completed actor=$actor topic=${result.topic ?: adminFilter?.topic ?: "MULTI"} " +
                                    "candidates=${result.totalCandidates} affected=${result.affected}"
                            }
                        }

                        val topicTag = result.topic ?: adminFilter?.topic ?: if (ids.isNotEmpty()) "ids" else "ALL"
                        val statusTag = adminFilter?.status ?: "ALL"
                        resolvedMetrics
                            ?.counter(
                                "outbox.admin.replay.total",
                                "result",
                                resultTagSuccess,
                                "topic",
                                topicTag,
                                "status",
                                statusTag,
                            )?.increment()

                        call.respond(
                            ReplayResponse(
                                candidates = result.totalCandidates,
                                affected = result.affected,
                                dryRun = result.dryRun,
                                topic = result.topic,
                            ),
                        )
                        if (replayTimer != null) {
                            sample?.stop(replayTimer)
                        }
                    } catch (ex: Throwable) {
                        val topicTag = adminFilter?.topic ?: if (ids.isNotEmpty()) "ids" else "ALL"
                        val statusTag = adminFilter?.status ?: "ALL"
                        resolvedMetrics
                            ?.counter(
                                "outbox.admin.replay.total",
                                "result",
                                resultTagError,
                                "topic",
                                topicTag,
                                "status",
                                statusTag,
                            )?.increment()
                        if (replayTimer != null) {
                            sample?.stop(replayTimer)
                        }
                        throw ex
                    }
                }
            }

            if (rbacAvailable) {
                authorize(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER) { register() }
            } else {
                register()
            }
        }
    }
}

private fun OutboxRecord.toDto(): OutboxRecordDto {
    val formatter = DateTimeFormatter.ISO_INSTANT
    return OutboxRecordDto(
        id = id,
        topic = topic,
        status = status,
        attempts = attempts,
        nextAttemptAt = nextAttemptAt?.let(formatter::format),
        lastError = lastError,
        createdAt = formatter.format(createdAt),
        source = source.name,
    )
}

private fun Paged<OutboxRecord>.toDto(page: Page, stats: AdminStats?): OutboxAdminPage {
    val orderField =
        when (page.sort.field) {
            SortField.CreatedAt -> "created_at"
            SortField.Attempts -> "attempts"
            SortField.Id -> "id"
        }
    val direction = if (page.sort.direction == SortDirection.ASC) "asc" else "desc"
    return OutboxAdminPage(
        items = items.map { it.toDto() },
        total = total,
        limit = page.limit,
        offset = page.offset,
        sort = orderField,
        dir = direction,
        stats = stats?.let { AdminStatsDto(it.total, it.byStatus, it.byTopic) },
    )
}

private fun ApplicationCall.parseAdminQuery(): OutboxAdminQuery {
    val params = request.queryParameters
    val topic = params["topic"]?.takeIf { it.isNotBlank() }
    val status = params["status"]?.takeIf { it.isNotBlank() }
    val attempts = params["attemptsMin"]?.toIntOrNull()
    val createdAfter = params["createdAfter"]?.takeIf { it.isNotBlank() }
    val limit = params["limit"]?.toIntOrNull()
    val offset = params["offset"]?.toIntOrNull()
    val sort = params["sort"]?.takeIf { it.isNotBlank() }
    val dir = params["dir"]?.takeIf { it.isNotBlank() }
    val idValues = params.getAll("idIn") ?: params.getAll("id")
    val ids =
        idValues
            ?.flatMap { raw -> raw.split(',') }
            ?.mapNotNull { token -> token.trim().takeIf { it.isNotEmpty() }?.toLongOrNull() }
            ?.takeIf { it.isNotEmpty() }
    return OutboxAdminQuery(topic, status, attempts, createdAfter, ids, limit, offset, sort, dir)
}

private fun OutboxAdminQuery.toAdminFilter(): AdminFilter {
    val parsedCreatedAfter =
        createdAfter?.let {
            try {
                Instant.parse(it)
            } catch (ex: DateTimeParseException) {
                throw BadRequestException("createdAfter must be ISO-8601 timestamp")
            }
        }
    return AdminFilter(
        topic = topic?.takeIf { it.isNotBlank() },
        status = status?.takeIf { it.isNotBlank() },
        attemptsMin = attemptsMin?.takeIf { it > 0 },
        createdAfter = parsedCreatedAfter,
        idIn = idIn?.filter { it > 0 },
    )
}

private fun OutboxAdminQuery.toPage(): Page {
    val limitValue = (limit ?: 50).coerceIn(1, 500)
    val offsetValue = (offset ?: 0).coerceAtLeast(0)
    val sortField =
        when (sort?.lowercase()) {
            null, "created_at" -> SortField.CreatedAt
            "attempts" -> SortField.Attempts
            "id" -> SortField.Id
            else -> throw BadRequestException("sort must be one of created_at, attempts, id")
        }
    val direction =
        when (dir?.lowercase()) {
            null, "desc" -> SortDirection.DESC
            "asc" -> SortDirection.ASC
            else -> throw BadRequestException("dir must be asc or desc")
        }
    return Page(limit = limitValue, offset = offsetValue, sort = Sort(sortField, direction))
}

private fun ApplicationCall.actorLabel(base: String): String {
    val requestId = request.header(HttpHeaders.XRequestId) ?: callId ?: "unknown"
    return "$base#$requestId"
}
