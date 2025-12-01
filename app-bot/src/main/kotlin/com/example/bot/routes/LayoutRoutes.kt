package com.example.bot.routes

import com.example.bot.http.etagFor
import com.example.bot.http.matchesEtag
import com.example.bot.layout.ClubLayout
import com.example.bot.layout.LayoutAssets
import com.example.bot.layout.LayoutRepository
import com.example.bot.layout.Table
import com.example.bot.layout.TableStatus
import com.example.bot.layout.Zone
import com.example.bot.metrics.RouteCacheMetrics
import com.example.bot.plugins.withMiniAppAuth
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.header
import io.ktor.server.routing.get
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.text.Charsets

private const val CACHE_CONTROL = "max-age=60, must-revalidate"
private const val VARY_HEADER = "X-Telegram-Init-Data"
private const val ASSETS_CACHE_CONTROL = "public, max-age=31536000, immutable"
private val JSON_CONTENT_TYPE = ContentType.Application.Json.withCharset(Charsets.UTF_8).toString()
private val CLUB_ID_RE = Regex("^[0-9]+$")
private val FP_RE = Regex("^[A-Za-z0-9_-]{43}$")

@Serializable
private data class ZoneDto(
    val id: String,
    val name: String,
    val tags: List<String>,
    val order: Int,
)

@Serializable
private data class TableDto(
    val id: Long,
    val zoneId: String,
    val label: String,
    val capacity: Int,
    val minimumTier: String,
    val status: TableStatusDto,
)

@Serializable
private data class LayoutAssetsDto(
    val geometryUrl: String,
    val fingerprint: String,
)

@Serializable
private data class ClubLayoutDto(
    val clubId: Long,
    val eventId: Long?,
    val zones: List<ZoneDto>,
    val tables: List<TableDto>,
    val assets: LayoutAssetsDto,
)

@Serializable
private enum class TableStatusDto {
    @SerialName("free")
    FREE,

    @SerialName("hold")
    HOLD,

    @SerialName("booked")
    BOOKED,
}

fun Application.layoutRoutes(layoutRepository: LayoutRepository) {
    val logger = LoggerFactory.getLogger("LayoutRoutes")

    routing {
        route("/api") {
            withMiniAppAuth { System.getenv("TELEGRAM_BOT_TOKEN")!! }

            get("/clubs/{id}/layout") {
                val clubId = call.parameters["id"]?.toLongOrNull()
                if (clubId == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }

                val eventId = call.request.queryParameters["eventId"]?.toLongOrNull()
                val layout =
                    withContext(Dispatchers.IO + MDCContext()) {
                        layoutRepository.getLayout(clubId, eventId)
                    }
                        ?: run {
                            call.respond(HttpStatusCode.NotFound)
                            return@get
                        }

                val etag =
                    etagFor(
                        layoutRepository.lastUpdatedAt(clubId, eventId),
                        layout.zones.size + layout.tables.size,
                        "layout|$clubId|$eventId|${layout.assets.fingerprint}|zones=${layout.zones.size}|tables=${layout.tables.size}",
                    )

                val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                if (matchesEtag(ifNoneMatch, etag)) {
                    logger.debug("layout_api.not_modified clubId={} eventId={} etag={}", clubId, eventId, etag)
                    call.respondLayoutNotModified(etag)
                    return@get
                }

                logger.debug(
                    "layout_api.ok clubId={} eventId={} zones={} tables={} fingerprint={} etag={}",
                    clubId,
                    eventId,
                    layout.zones.size,
                    layout.tables.size,
                    layout.assets.fingerprint,
                    etag,
                )

                call.respondLayout(etag, layout.toDto())
            }
        }

        layoutAssetsRoutes(logger)
    }
}

private fun ClubLayout.toDto(): ClubLayoutDto =
    ClubLayoutDto(
        clubId = clubId,
        eventId = eventId,
        zones = zones.sortedBy { it.order }.map { it.toDto() },
        tables = tables.sortedWith(compareBy<Table> { it.zoneId }.thenBy { it.id }).map { it.toDto() },
        assets = assets.toDto(),
    )

private fun Zone.toDto(): ZoneDto = ZoneDto(id = id, name = name, tags = tags, order = order)

private fun Table.toDto(): TableDto =
    TableDto(
        id = id,
        zoneId = zoneId,
        label = label,
        capacity = capacity,
        minimumTier = minimumTier,
        status = status.toDto(),
    )

private fun TableStatus.toDto(): TableStatusDto =
    when (this) {
        TableStatus.FREE -> TableStatusDto.FREE
        TableStatus.HOLD -> TableStatusDto.HOLD
        TableStatus.BOOKED -> TableStatusDto.BOOKED
    }

private fun LayoutAssets.toDto(): LayoutAssetsDto =
    LayoutAssetsDto(geometryUrl = geometryUrl, fingerprint = fingerprint)

private suspend fun ApplicationCall.respondLayout(etag: String, payload: Any) {
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.CacheControl, CACHE_CONTROL)
    response.header(HttpHeaders.Vary, VARY_HEADER)
    response.header(HttpHeaders.ContentType, JSON_CONTENT_TYPE)
    RouteCacheMetrics.recordOk("layout_api")
    respond(status = HttpStatusCode.OK, message = payload)
}

private suspend fun ApplicationCall.respondLayoutNotModified(etag: String) {
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.CacheControl, CACHE_CONTROL)
    response.header(HttpHeaders.Vary, VARY_HEADER)
    response.header(HttpHeaders.ContentType, JSON_CONTENT_TYPE)
    RouteCacheMetrics.recordNotModified("layout_api")
    respond(HttpStatusCode.NotModified)
}

private fun resourcePath(clubId: String, fingerprint: String): String = "layouts/$clubId/$fingerprint.json"

private fun Route.layoutAssetsRoutes(logger: Logger) {
    get("/assets/layouts/{clubId}/{fingerprint}.json") {
        val clubId = call.parameters["clubId"]?.takeIf { CLUB_ID_RE.matches(it) }
        val fingerprint = call.parameters["fingerprint"]?.takeIf { FP_RE.matches(it) }

        if (clubId == null || fingerprint == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        val assetResourcePath = resourcePath(clubId, fingerprint)
        val resource =
            Thread.currentThread().contextClassLoader
                .getResourceAsStream(assetResourcePath)
        if (resource == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        val etag = fingerprint
        val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
        if (matchesEtag(ifNoneMatch, etag)) {
            logger.debug("layout_asset.not_modified clubId={} fingerprint={}", clubId, fingerprint)
            call.respondLayoutAssetNotModified(etag)
            return@get
        }

        val bytes = resource.use { it.readBytes() }
        call.respondLayoutAsset(etag, bytes)
    }
}

private suspend fun ApplicationCall.respondLayoutAsset(etag: String, bytes: ByteArray) {
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.CacheControl, ASSETS_CACHE_CONTROL)
    RouteCacheMetrics.recordOk("layout_asset")
    respondBytes(bytes, ContentType.Application.Json.withCharset(Charsets.UTF_8))
}

private suspend fun ApplicationCall.respondLayoutAssetNotModified(etag: String) {
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.CacheControl, ASSETS_CACHE_CONTROL)
    response.header(HttpHeaders.ContentType, JSON_CONTENT_TYPE)
    RouteCacheMetrics.recordNotModified("layout_asset")
    respond(HttpStatusCode.NotModified)
}
