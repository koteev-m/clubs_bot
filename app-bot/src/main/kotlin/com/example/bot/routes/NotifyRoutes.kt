package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.notifications.NotifyMessage
import com.example.bot.notifications.ParseMode
import com.example.bot.telegram.NotifyDispatchHealth
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.security.rbac.authorize
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Status of campaign lifecycle.
 */
@Serializable
enum class CampaignStatus { DRAFT, SCHEDULED, SENDING, PAUSED }

/**
 * Representation of a notification campaign.
 */
@Serializable
data class CampaignDto(
    val id: Long,
    var title: String,
    var text: String,
    var status: CampaignStatus = CampaignStatus.DRAFT,
    var cron: String? = null,
    var startsAt: String? = null,
)

@Serializable
data class CampaignCreateRequest(val title: String, val text: String, val parseMode: ParseMode? = null)

@Serializable
data class CampaignUpdateRequest(val title: String? = null, val text: String? = null, val parseMode: ParseMode? = null)

@Serializable
data class ScheduleRequest(val cron: String? = null, val startsAt: String? = null)

/** Simple in-memory campaign service used by routes. */
class CampaignService {
    private val campaigns = ConcurrentHashMap<Long, CampaignDto>()
    private var seq = 0L

    fun create(req: CampaignCreateRequest): CampaignDto {
        val text = sanitize(req.text, req.parseMode)
        val id = ++seq
        val dto = CampaignDto(id, req.title, text)
        campaigns[id] = dto
        return dto
    }

    fun update(
        id: Long,
        req: CampaignUpdateRequest,
    ): CampaignDto? {
        val dto = campaigns[id] ?: return null
        req.title?.let { dto.title = it }
        req.text?.let { dto.text = sanitize(it, req.parseMode) }
        return dto
    }

    fun find(id: Long): CampaignDto? = campaigns[id]

    fun list(): List<CampaignDto> = campaigns.values.sortedBy { it.id }

    fun schedule(
        id: Long,
        req: ScheduleRequest,
    ): CampaignDto? {
        val dto = campaigns[id] ?: return null
        dto.cron = req.cron
        dto.startsAt = req.startsAt
        dto.status = CampaignStatus.SCHEDULED
        return dto
    }

    fun setStatus(
        id: Long,
        status: CampaignStatus,
    ): CampaignDto? {
        val dto = campaigns[id] ?: return null
        dto.status = status
        return dto
    }
}

/** Queue for transactional notifications. */
class TxNotifyService {
    private val messages = mutableListOf<NotifyMessage>()

    fun enqueue(msg: NotifyMessage) {
        messages += msg
    }

    fun size(): Int = messages.size
}

/** Escapes text for HTML or MarkdownV2. */
private fun sanitize(
    text: String,
    mode: ParseMode?,
): String {
    return when (mode) {
        ParseMode.HTML -> text.replace("<", "&lt;").replace(">", "&gt;")
        ParseMode.MARKDOWNV2 ->
            text
                .replace("_", "\\_")
                .replace("*", "\\*")
        else -> text
    }
}

fun Route.notifyHealthRoute(healthProvider: () -> NotifyDispatchHealth) {
    get("/notify/health") {
        call.respond(healthProvider())
    }
}

/**
 * Registers notification and campaign routes.
 */
@Suppress("LongMethod", "ThrowsCount")
fun Application.notifyRoutes(
    tx: TxNotifyService,
    campaigns: CampaignService,
) {
    if (attributes.contains(notifyRoutesInstalledKey)) {
        notifyRoutesLogger.info("notifyRoutes already registered")
        return
    }
    attributes.put(notifyRoutesInstalledKey, Unit)

    routing {
        route("/api") {
            post("/notify/tx") {
                val msg = call.receive<NotifyMessage>()
                tx.enqueue(msg)
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "queued"))
            }

            if (this@notifyRoutes.pluginOrNull(RbacPlugin) != null) {
                authorize(Role.OWNER, Role.GLOBAL_ADMIN, Role.CLUB_ADMIN, Role.MANAGER) {
                    campaignRoutes(campaigns)
                }
            } else {
                notifyRoutesLogger.warn("RbacPlugin not installed: /api/campaigns routes are disabled")
            }
        }
    }
    notifyRoutesLogger.info("notifyRoutes registered under /api")
}

private val notifyRoutesInstalledKey = AttributeKey<Unit>("notifyRoutesInstalled")

private val notifyRoutesLogger = LoggerFactory.getLogger("NotifyRoutes")

@Suppress("ThrowsCount")
private fun Route.campaignRoutes(campaigns: CampaignService) {
    route("/campaigns") {
        post {
            val req = call.receive<CampaignCreateRequest>()
            val dto = campaigns.create(req)
            call.respond(dto)
        }

        get {
            call.respond(campaigns.list())
        }

        route("/{id}") {
            get {
                val id = call.parameters.getOrFail("id").toLong()
                val dto = campaigns.find(id) ?: throw BadRequestException("not found")
                call.respond(dto)
            }

            put {
                val id = call.parameters.getOrFail("id").toLong()
                val req = call.receive<CampaignUpdateRequest>()
                val dto = campaigns.update(id, req) ?: throw BadRequestException("not found")
                call.respond(dto)
            }

            post(":preview") {
                val id = call.parameters.getOrFail("id").toLong()
                if (call.request.queryParameters["user_id"].isNullOrBlank()) {
                    throw BadRequestException("user_id required")
                }
                campaigns.find(id) ?: throw BadRequestException("not found")
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "preview"))
            }

            post(":schedule") {
                val id = call.parameters.getOrFail("id").toLong()
                val req = call.receive<ScheduleRequest>()
                val dto = campaigns.schedule(id, req) ?: throw BadRequestException("not found")
                call.respond(dto)
            }

            post(":send-now") {
                val id = call.parameters.getOrFail("id").toLong()
                val dto = campaigns.setStatus(id, CampaignStatus.SENDING) ?: throw BadRequestException("not found")
                call.respond(dto)
            }

            post(":pause") {
                val id = call.parameters.getOrFail("id").toLong()
                val dto = campaigns.setStatus(id, CampaignStatus.PAUSED) ?: throw BadRequestException("not found")
                call.respond(dto)
            }

            post(":resume") {
                val id = call.parameters.getOrFail("id").toLong()
                val dto = campaigns.setStatus(id, CampaignStatus.SENDING) ?: throw BadRequestException("not found")
                call.respond(dto)
            }
        }
    }
}
