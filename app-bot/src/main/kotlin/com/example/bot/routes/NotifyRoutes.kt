package com.example.bot.routes

import com.example.bot.data.notifications.NotifyCampaignAudit
import com.example.bot.data.notifications.NotifyCampaigns
import com.example.bot.data.security.Role
import com.example.bot.notifications.NotifyMessage
import com.example.bot.notifications.ParseMode
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.rbacContext
import com.example.bot.telegram.NotifyDispatchHealth
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
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class CampaignStatus { DRAFT, SCHEDULED, SENDING, PAUSED }

@Serializable
data class CampaignDto(
    val id: Long,
    val title: String,
    val text: String,
    val status: CampaignStatus = CampaignStatus.DRAFT,
    val cron: String? = null,
    val startsAt: String? = null,
    val version: Long = 0,
)

@Serializable
data class CampaignCreateRequest(
    val title: String,
    val text: String,
    val parseMode: ParseMode? = null,
)

@Serializable
data class CampaignUpdateRequest(
    val title: String? = null,
    val text: String? = null,
    val parseMode: ParseMode? = null,
)

@Serializable
data class ScheduleRequest(
    val cron: String? = null,
    val startsAt: String? = null,
)

sealed interface CampaignMutationResult {
    data class Success(
        val campaign: CampaignDto,
    ) : CampaignMutationResult

    data object NotFound : CampaignMutationResult

    data object Conflict : CampaignMutationResult
}

open class CampaignService {
    private val campaigns = ConcurrentHashMap<Long, CampaignDto>()
    private var seq = 0L

    open suspend fun create(
        req: CampaignCreateRequest,
        actorId: Long,
    ): CampaignDto {
        val text = sanitize(req.text, req.parseMode)
        val id = ++seq
        val dto = CampaignDto(id = id, title = req.title, text = text)
        campaigns[id] = dto
        return dto
    }

    open suspend fun update(
        id: Long,
        req: CampaignUpdateRequest,
        actorId: Long,
    ): CampaignMutationResult {
        val dto = campaigns[id] ?: return CampaignMutationResult.NotFound
        val updated =
            dto.copy(
                title = req.title ?: dto.title,
                text = req.text?.let { sanitize(it, req.parseMode) } ?: dto.text,
                version = dto.version + 1,
            )
        campaigns[id] = updated
        return CampaignMutationResult.Success(updated)
    }

    open suspend fun find(id: Long): CampaignDto? = campaigns[id]

    open suspend fun list(): List<CampaignDto> = campaigns.values.sortedBy { it.id }

    open suspend fun schedule(
        id: Long,
        req: ScheduleRequest,
        actorId: Long,
    ): CampaignMutationResult {
        val dto = campaigns[id] ?: return CampaignMutationResult.NotFound
        val updated = dto.copy(cron = req.cron, startsAt = req.startsAt, status = CampaignStatus.SCHEDULED, version = dto.version + 1)
        campaigns[id] = updated
        return CampaignMutationResult.Success(updated)
    }

    open suspend fun setStatus(
        id: Long,
        status: CampaignStatus,
        actorId: Long,
    ): CampaignMutationResult {
        val dto = campaigns[id] ?: return CampaignMutationResult.NotFound
        val updated = dto.copy(status = status, version = dto.version + 1)
        campaigns[id] = updated
        return CampaignMutationResult.Success(updated)
    }
}

class DbCampaignService(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
    private val beforeOptimisticMutation: (suspend () -> Unit)? = null,
) : CampaignService() {
    override suspend fun create(
        req: CampaignCreateRequest,
        actorId: Long,
    ): CampaignDto =
        newSuspendedTransaction(db = db) {
            val now = Instant.now(clock).atOffset(ZoneOffset.UTC)
            val text = sanitize(req.text, req.parseMode)
            val id =
                NotifyCampaigns.insert {
                    it[title] = req.title
                    it[NotifyCampaigns.text] = text
                    it[status] = CampaignStatus.DRAFT.name
                    it[kind] = "MANUAL"
                    it[createdBy] = actorId
                    it[createdAt] = now
                    it[updatedAt] = now
                    it[version] = 0
                }[NotifyCampaigns.id]
            appendAudit(id, action = "CREATE", reason = null, actorId = actorId)
            rowToDto(NotifyCampaigns.selectAll().where { NotifyCampaigns.id eq id }.first())
        }

    override suspend fun update(
        id: Long,
        req: CampaignUpdateRequest,
        actorId: Long,
    ): CampaignMutationResult =
        newSuspendedTransaction(db = db) {
            val current = readCampaign(id) ?: return@newSuspendedTransaction null
            val nextTitle = req.title ?: current[NotifyCampaigns.title]
            val nextText = req.text?.let { sanitize(it, req.parseMode) } ?: current[NotifyCampaigns.text]
            val now = Instant.now(clock).atOffset(ZoneOffset.UTC)
            val version = current[NotifyCampaigns.version]
            beforeOptimisticMutation?.invoke()
            val updatedRows =
                NotifyCampaigns.update({ (NotifyCampaigns.id eq id) and (NotifyCampaigns.version eq version) }) {
                    it[title] = nextTitle
                    it[text] = nextText
                    it[NotifyCampaigns.version] = version + 1
                    it[updatedAt] = now
                }
            if (updatedRows == 0) {
                return@newSuspendedTransaction conflictOrNotFound(id)
            }
            appendAudit(id, action = "UPDATE", reason = null, actorId = actorId)
            CampaignMutationResult.Success(rowToDto(readCampaign(id)!!))
        }.let { it ?: CampaignMutationResult.NotFound }

    override suspend fun find(id: Long): CampaignDto? =
        newSuspendedTransaction(db = db) { readCampaign(id)?.let { rowToDto(it) } }

    override suspend fun list(): List<CampaignDto> =
        newSuspendedTransaction(db = db) {
            NotifyCampaigns.selectAll().orderBy(NotifyCampaigns.id to SortOrder.ASC).map { rowToDto(it) }
        }

    override suspend fun schedule(
        id: Long,
        req: ScheduleRequest,
        actorId: Long,
    ): CampaignMutationResult =
        updateState(id = id, status = CampaignStatus.SCHEDULED, cron = req.cron, startsAt = req.startsAt, reason = "SCHEDULE", actorId = actorId)

    override suspend fun setStatus(
        id: Long,
        status: CampaignStatus,
        actorId: Long,
    ): CampaignMutationResult =
        updateState(id = id, status = status, cron = null, startsAt = null, reason = status.name, actorId = actorId)

    private fun readCampaign(id: Long): ResultRow? =
        NotifyCampaigns.selectAll().where { NotifyCampaigns.id eq id }.limit(1).firstOrNull()

    private fun rowToDto(row: ResultRow): CampaignDto =
        CampaignDto(
            id = row[NotifyCampaigns.id],
            title = row[NotifyCampaigns.title],
            text = row[NotifyCampaigns.text],
            status = CampaignStatus.valueOf(row[NotifyCampaigns.status]),
            cron = row[NotifyCampaigns.scheduleCron],
            startsAt = row[NotifyCampaigns.startsAt]?.toString(),
            version = row[NotifyCampaigns.version],
        )

    private fun appendAudit(
        id: Long,
        action: String,
        reason: String?,
        actorId: Long,
    ) {
        NotifyCampaignAudit.insert {
            it[campaignId] = id
            it[auditAction] = action
            it[actor] = actorId.toString()
            it[NotifyCampaignAudit.reason] = reason
        }
    }

    private suspend fun updateState(
        id: Long,
        status: CampaignStatus,
        cron: String?,
        startsAt: String?,
        reason: String,
        actorId: Long,
    ): CampaignMutationResult =
        newSuspendedTransaction(db = db) {
            val current = readCampaign(id) ?: return@newSuspendedTransaction null
            val version = current[NotifyCampaigns.version]
            val now = Instant.now(clock).atOffset(ZoneOffset.UTC)
            beforeOptimisticMutation?.invoke()
            val updatedRows =
                NotifyCampaigns.update({ (NotifyCampaigns.id eq id) and (NotifyCampaigns.version eq version) }) {
                    it[NotifyCampaigns.status] = status.name
                    it[NotifyCampaigns.version] = version + 1
                    if (cron != null) {
                        it[scheduleCron] = cron
                    }
                    if (startsAt != null) {
                        it[NotifyCampaigns.startsAt] = Instant.parse(startsAt).atOffset(ZoneOffset.UTC)
                    }
                    it[updatedAt] = now
                }
            if (updatedRows == 0) {
                return@newSuspendedTransaction conflictOrNotFound(id)
            }
            appendAudit(id, action = "STATUS", reason = reason, actorId = actorId)
            CampaignMutationResult.Success(rowToDto(readCampaign(id)!!))
        }.let { it ?: CampaignMutationResult.NotFound }

    private fun conflictOrNotFound(id: Long): CampaignMutationResult =
        if (readCampaign(id) == null) {
            CampaignMutationResult.NotFound
        } else {
            CampaignMutationResult.Conflict
        }
}

class TxNotifyService {
    private val messages = mutableListOf<NotifyMessage>()

    fun enqueue(msg: NotifyMessage) {
        messages += msg
    }

    fun size(): Int = messages.size
}

private fun sanitize(
    text: String,
    mode: ParseMode?,
): String =
    when (mode) {
        ParseMode.HTML -> text.replace("<", "&lt;").replace(">", "&gt;")
        ParseMode.MARKDOWNV2 -> text.replace("_", "\\_").replace("*", "\\*")
        else -> text
    }

fun Route.notifyHealthRoute(healthProvider: () -> NotifyDispatchHealth) {
    get("/notify/health") {
        call.respond(healthProvider())
    }
}

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
            val dto = campaigns.create(req, actorId = call.rbacContext().user.id)
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
                call.respondMutationResult(campaigns.update(id, req, actorId = call.rbacContext().user.id))
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
                call.respondMutationResult(campaigns.schedule(id, req, actorId = call.rbacContext().user.id))
            }

            post(":send-now") {
                val id = call.parameters.getOrFail("id").toLong()
                call.respondMutationResult(campaigns.setStatus(id, CampaignStatus.SENDING, actorId = call.rbacContext().user.id))
            }

            post(":pause") {
                val id = call.parameters.getOrFail("id").toLong()
                call.respondMutationResult(campaigns.setStatus(id, CampaignStatus.PAUSED, actorId = call.rbacContext().user.id))
            }

            post(":resume") {
                val id = call.parameters.getOrFail("id").toLong()
                call.respondMutationResult(campaigns.setStatus(id, CampaignStatus.SENDING, actorId = call.rbacContext().user.id))
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondMutationResult(result: CampaignMutationResult) {
    when (result) {
        CampaignMutationResult.NotFound -> respond(HttpStatusCode.NotFound, mapOf("error" to "not_found"))
        CampaignMutationResult.Conflict -> respond(HttpStatusCode.Conflict, mapOf("error" to "version_conflict"))
        is CampaignMutationResult.Success -> respond(result.campaign)
    }
}
