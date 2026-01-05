package com.example.bot.routes

import com.example.bot.club.GuestList
import com.example.bot.club.GuestListEntrySearch
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListEntryView
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListRepository
import com.example.bot.club.RejectedRow
import com.example.bot.data.club.GuestListCsvParser
import com.example.bot.data.security.Role
import com.example.bot.metrics.UiCheckinMetrics
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.RbacContext
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.rbacContext
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.acceptItems
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.use
import kotlin.math.max

/** Registers guest list management routes. */
fun Application.guestListRoutes(
    repository: GuestListRepository,
    parser: GuestListCsvParser,
    botTokenProvider: () -> String = { System.getenv("TELEGRAM_BOT_TOKEN")!! },
) {
    routing {
        // Плагин ставим ТОЛЬКО на ветку /api/guest-lists, чтобы /health, /ready и пр. остались публичными
        route("/api/guest-lists") {
            withMiniAppAuth { botTokenProvider() }

            authorize(
                Role.OWNER,
                Role.GLOBAL_ADMIN,
                Role.HEAD_MANAGER,
                Role.CLUB_ADMIN,
                Role.MANAGER,
                Role.ENTRY_MANAGER,
                Role.PROMOTER,
            ) {
                // GET /api/guest-lists
                get {
                    val context = call.rbacContext()
                    val query = call.extractSearch(context)

                    if (query.forbidden) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                        return@get
                    }
                    if (query.empty) {
                        call.respond(
                            GuestListPageResponse(items = emptyList(), total = 0, page = query.page, size = query.size),
                        )
                        return@get
                    }

                    val result = repository.searchEntries(query.filter!!, page = query.page, size = query.size)
                    val response =
                        GuestListPageResponse(
                            items = result.items.map { it.toResponse() },
                            total = result.total,
                            page = query.page,
                            size = query.size,
                        )
                    call.respond(response)
                }

                // GET /api/guest-lists/export
                get("export") {
                    val context = call.rbacContext()
                    val query = call.extractSearch(context)

                    if (query.forbidden) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                        return@get
                    }

                    val items =
                        if (query.empty) {
                            emptyList()
                        } else {
                            repository.searchEntries(query.filter!!, page = query.page, size = query.size).items
                        }

                    val csv = items.toExportCsv()
                    call.respondText(csv, ContentType.Text.CSV)
                }

                // POST /api/guest-lists/{listId}/import
                post("{listId}/import") {
                    val listId =
                        call.parameters.getOrFail("listId").toLongOrNull()
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid listId"))

                    val list =
                        repository.getList(listId)
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "List not found"))

                    val context = call.rbacContext()
                    if (!context.canAccess(list)) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                        return@post
                    }

                    val dryRun = call.request.queryParameters["dry_run"].toBooleanStrictOrNull() ?: false
                    val type = call.request.contentType()
                    if (!type.match(ContentType.Text.CSV) && type != TSV_CONTENT_TYPE) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Expected text/csv body"))
                        return@post
                    }

                    val payload = call.receiveText()
                    if (payload.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Empty body"))
                        return@post
                    }

                    val report =
                        try {
                            performGuestListImport(
                                repository = repository,
                                parser = parser,
                                listId = listId,
                                input = payload.byteInputStream(StandardCharsets.UTF_8),
                                dryRun = dryRun,
                            )
                        } catch (ex: Exception) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (ex.message ?: "Import failed")))
                            return@post
                        }

                    val wantsCsv = call.wantsCsv()
                    val acceptInvalid =
                        if (call.attributes.contains(AcceptInvalidAttribute)) {
                            call.attributes[AcceptInvalidAttribute]
                        } else {
                            false
                        }

                    if (wantsCsv) {
                        call.respondText(report.toCsv(), ContentType.Text.CSV)
                    } else {
                        if (acceptInvalid) {
                            val json = Json.encodeToString(report.toResponse())
                            call.respondText(json, ContentType.Application.Json)
                        } else {
                            call.respond(report.toResponse())
                        }
                    }
                }

                // --- POST /api/guest-lists/entries/{entryId}/arriveByName (алиас ручного чек-ина)
                post("/entries/{entryId}/arriveByName") {
                    val entryId =
                        call.parameters["entryId"]?.toLongOrNull()
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_entry_id"))

                    val entry =
                        repository.findEntry(entryId)
                            ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "entry_not_found"))

                    val list =
                        repository.getList(entry.listId)
                            ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "list_not_found"))

                    // Проверка club scope: доступ разрешён если пользователь имеет право на этот клуб
                    val context = call.rbacContext()
                    val hasGlobal = context.roles.any { it in setOf(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER) }
                    val allowed = hasGlobal || (list.clubId in context.clubIds) || (Role.ENTRY_MANAGER in context.roles)
                    if (!allowed) {
                        return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                    }

                    val now = Instant.now()
                    val withinWindow = isWithinWindow(now, list.arrivalWindowStart, list.arrivalWindowEnd)
                    if (!withinWindow) {
                        if (entry.status == GuestListEntryStatus.CALLED) {
                            UiCheckinMetrics.incLateOverride()
                        } else {
                            return@post call.respond(
                                HttpStatusCode.Conflict,
                                mapOf("error" to "outside_arrival_window"),
                            )
                        }
                    }

                    val ok = repository.markArrived(entry.id, now)
                    if (!ok) {
                        return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "unable_to_mark"))
                    }
                    call.respond(HttpStatusCode.OK, mapOf("status" to "ARRIVED"))
                }
            }
        }
    }
}

internal data class GuestListImportReport(
    val accepted: Int,
    val rejected: List<RejectedRow>,
)

internal suspend fun performGuestListImport(
    repository: GuestListRepository,
    parser: GuestListCsvParser,
    listId: Long,
    input: InputStream,
    dryRun: Boolean,
): GuestListImportReport =
    input.use { stream ->
        val rows = parser.parse(stream).rows
        repository.bulkImport(listId, rows, dryRun)
        GuestListImportReport(accepted = rows.size, rejected = emptyList())
    }

internal fun GuestListImportReport.toSummary(dryRun: Boolean): String {
    val prefix = if (dryRun) "Dry run: $accepted rows would be imported" else "Imported $accepted rows"
    return if (this.rejected.isEmpty()) "$prefix. No errors." else "$prefix. Rejected ${this.rejected.size} rows."
}

internal fun GuestListImportReport.toCsv(): String {
    val b = StringBuilder()
    b.appendLine("accepted_count,rejected_count")
    b.appendLine("$accepted,${this.rejected.size}")
    if (this.rejected.isNotEmpty()) {
        b.appendLine("line,reason")
        this.rejected.forEach { row ->
            val reason = row.reason.replace("\"", "\"\"")
            b.appendLine("${row.line},\"$reason\"")
        }
    }
    return b.toString()
}

@Serializable
private data class GuestListEntryResponse(
    val id: Long,
    val listId: Long,
    val listTitle: String,
    val clubId: Long,
    val ownerType: String,
    val ownerUserId: Long,
    val fullName: String,
    val phone: String?,
    val guestsCount: Int,
    val notes: String?,
    val status: String,
    val listCreatedAt: String,
)

@Serializable
private data class GuestListPageResponse(
    val items: List<GuestListEntryResponse>,
    val total: Long,
    val page: Int,
    val size: Int,
)

@Serializable
private data class ImportReportResponse(
    val accepted: Int,
    val rejected: List<RejectedRowResponse>,
)

@Serializable
private data class RejectedRowResponse(
    val line: Int,
    val reason: String,
)

private data class SearchContext(
    val filter: GuestListEntrySearch?,
    val page: Int,
    val size: Int,
    val empty: Boolean,
    val forbidden: Boolean,
)

private fun ApplicationCall.extractSearch(context: RbacContext): SearchContext {
    val params = request.queryParameters
    val page = params["page"]?.toIntOrNull()?.let { if (it >= 0) it else null } ?: 0
    val size = params["size"]?.toIntOrNull()?.let { if (it > 0) it else null } ?: 50
    val name = params["name"]?.takeIf { it.isNotBlank() }
    val phone = params["phone"]?.takeIf { it.isNotBlank() }
    val status =
        params["status"]?.let {
            runCatching { GuestListEntryStatus.valueOf(it.uppercase()) }
                .getOrElse { throw BadRequestException("Invalid status") }
        }
    val clubParam = params["club"]?.toLongOrNull()
    val from = parseInstant(params["from"])
    val to = parseInstant(params["to"])

    val baseFilter =
        GuestListEntrySearch(
            nameQuery = name,
            phoneQuery = phone,
            status = status,
            createdFrom = from,
            createdTo = to,
        )

    val globalRoles = setOf(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER)
    val hasGlobal = context.roles.any { it in globalRoles }

    var forbidden = false
    val clubIds: Set<Long>? =
        when {
            hasGlobal -> clubParam?.let { setOf(it) }
            Role.PROMOTER in context.roles -> clubParam?.let { setOf(it) }
            else -> {
                val allowed = context.clubIds
                when {
                    clubParam != null && clubParam !in allowed -> {
                        forbidden = true
                        emptySet()
                    }
                    clubParam != null -> setOf(clubParam)
                    else -> allowed
                }
            }
        }

    val ownerId = if (Role.PROMOTER in context.roles) context.user.id else null
    val empty = clubIds?.isEmpty() == true
    val filter =
        if (empty || forbidden) {
            null
        } else {
            baseFilter.copy(clubIds = clubIds?.takeIf { it.isNotEmpty() }, ownerUserId = ownerId)
        }

    return SearchContext(filter, page, size, empty, forbidden)
}

private fun parseInstant(value: String?): Instant? =
    if (value.isNullOrBlank()) {
        null
    } else {
        runCatching { Instant.parse(value) }.getOrElse {
            val date = runCatching { LocalDate.parse(value) }.getOrElse { throw BadRequestException("Invalid date") }
            date.atStartOfDay().toInstant(ZoneOffset.UTC)
        }
    }

private fun GuestListEntryView.toResponse(): GuestListEntryResponse =
    GuestListEntryResponse(
        id = id,
        listId = listId,
        listTitle = listTitle,
        clubId = clubId,
        ownerType = ownerType.name,
        ownerUserId = ownerUserId,
        fullName = fullName,
        phone = phone,
        guestsCount = guestsCount,
        notes = notes,
        status = status.name,
        listCreatedAt = listCreatedAt.toString(),
    )

private fun GuestListImportReport.toResponse(): ImportReportResponse =
    ImportReportResponse(
        accepted = accepted,
        rejected = this.rejected.map { RejectedRowResponse(it.line, it.reason) },
    )

private fun List<GuestListEntryView>.toExportCsv(): String {
    val b = StringBuilder()
    b.appendLine(
        listOf(
            "entry_id",
            "list_id",
            "club_id",
            "list_title",
            "owner_type",
            "owner_user_id",
            "full_name",
            "phone",
            "guests_count",
            "status",
            "notes",
            "list_created_at",
        ).joinToString(","),
    )
    for (item in this) {
        b.append(item.id).append(',')
        b.append(item.listId).append(',')
        b.append(item.clubId).append(',')
        b.append(escapeCsv(item.listTitle)).append(',')
        b.append(item.ownerType.name).append(',')
        b.append(item.ownerUserId).append(',')
        b.append(escapeCsv(item.fullName)).append(',')
        b.append(item.phone ?: "").append(',')
        b.append(item.guestsCount).append(',')
        b.append(item.status.name).append(',')
        b.append(escapeCsv(item.notes)).append(',')
        b.append(item.listCreatedAt.toString()).append('\n')
    }
    return b.toString()
}

private fun escapeCsv(value: String?): String {
    if (value.isNullOrEmpty()) return ""
    val escaped = value.replace("\"", "\"\"")
    return "\"$escaped\""
}

private fun RbacContext.canAccess(list: GuestList): Boolean {
    val globalRoles = setOf(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER)
    if (roles.any { it in globalRoles }) return true
    return when {
        Role.PROMOTER in roles ->
            list.ownerType == GuestListOwnerType.PROMOTER && list.ownerUserId == user.id
        else -> list.clubId in clubIds
    }
}

private const val PRECEDENCE_NONE = 0
private const val PRECEDENCE_ANY = 1
private const val PRECEDENCE_TYPE = 2
private const val PRECEDENCE_EXACT = 3
private val AcceptInvalidAttribute = AttributeKey<Boolean>("guestListAcceptInvalid")

private data class Best(
    var precedence: Int = PRECEDENCE_NONE,
    var quality: Double = 0.0,
) {
    fun update(candidatePrecedence: Int, candidateQuality: Double) {
        if (candidatePrecedence == PRECEDENCE_NONE) return
        when {
            candidatePrecedence > precedence -> {
                precedence = candidatePrecedence
                quality = candidateQuality
            }
            candidatePrecedence == precedence -> quality = max(quality, candidateQuality)
        }
    }
}

private fun ApplicationCall.wantsCsv(): Boolean {
    if (request.queryParameters["format"]?.equals("csv", ignoreCase = true) == true) return true

    val items = runCatching { request.acceptItems() }.getOrElse { emptyList() }
    val hasAcceptHeader = request.headers[HttpHeaders.Accept] != null

    val csvBest = Best()
    val jsonBest = Best()
    var parsedAny = false

    fun precedenceFor(baseType: ContentType, target: ContentType): Int =
        when {
            baseType == target -> PRECEDENCE_EXACT
            baseType.contentType == target.contentType && baseType.contentSubtype == "*" -> PRECEDENCE_TYPE
            baseType.contentType == "*" && baseType.contentSubtype == "*" -> PRECEDENCE_ANY
            else -> PRECEDENCE_NONE
        }

    for (item in items) {
        val type = runCatching { ContentType.parse(item.value) }.getOrNull() ?: continue
        parsedAny = true
        val baseType = type.withoutParameters()

        csvBest.update(precedenceFor(baseType, ContentType.Text.CSV), item.quality)
        jsonBest.update(precedenceFor(baseType, ContentType.Application.Json), item.quality)
    }

    if (!parsedAny) {
        csvBest.quality = 1.0
        jsonBest.quality = 1.0
        if (hasAcceptHeader) {
            attributes.put(AcceptInvalidAttribute, true)
        }
    } else {
        attributes.put(AcceptInvalidAttribute, false)
    }

    return csvBest.quality > 0.0 && csvBest.quality > jsonBest.quality
}

private fun String?.toBooleanStrictOrNull(): Boolean? =
    this?.let {
        when {
            it.equals("true", ignoreCase = true) -> true
            it.equals("false", ignoreCase = true) -> false
            else -> null
        }
    }

private val TSV_CONTENT_TYPE: ContentType = ContentType.parse("text/tab-separated-values")

private fun isWithinWindow(
    now: Instant,
    start: Instant?,
    end: Instant?,
): Boolean {
    val afterStart = start?.let { !now.isBefore(it) } ?: true
    val beforeEnd = end?.let { !now.isAfter(it) } ?: true
    return afterStart && beforeEnd
}
