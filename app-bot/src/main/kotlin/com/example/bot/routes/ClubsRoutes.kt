package com.example.bot.routes

import com.example.bot.clubs.Club
import com.example.bot.clubs.ClubsRepository
import com.example.bot.clubs.Event
import com.example.bot.clubs.EventsRepository
import com.example.bot.http.etagFor
import com.example.bot.http.matchesEtag
import com.example.bot.plugins.withMiniAppAuth
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.text.Charsets

private const val DEFAULT_PAGE_SIZE = 20
private const val MAX_PAGE_SIZE = 100
private const val CACHE_CONTROL = "max-age=60, must-revalidate"
private const val VARY_HEADER = "X-Telegram-Init-Data"
private val JSON_CONTENT_TYPE = ContentType.Application.Json.withCharset(Charsets.UTF_8).toString()

@Serializable
data class ClubDto(
    val id: Long,
    val city: String,
    val name: String,
    val genres: List<String>,
    val tags: List<String>,
    val logoUrl: String?,
)

@Serializable
data class EventDto(
    val id: Long,
    val clubId: Long,
    val startUtc: String,
    val endUtc: String,
    val title: String?,
    val isSpecial: Boolean,
)

private fun Club.toDto(): ClubDto =
    ClubDto(
        id = id,
        city = city,
        name = name,
        genres = genres,
        tags = tags,
        logoUrl = logoUrl,
    )

private fun Event.toDto(): EventDto =
    EventDto(
        id = id,
        clubId = clubId,
        startUtc = startUtc.toString(),
        endUtc = endUtc.toString(),
        title = title,
        isSpecial = isSpecial,
    )

fun Application.clubsRoutes(
    clubsRepository: ClubsRepository,
    eventsRepository: EventsRepository,
) {
    val logger = LoggerFactory.getLogger("ClubsRoutes")

    routing {
        route("/api") {
            withMiniAppAuth {
                System.getenv("TELEGRAM_BOT_TOKEN")!!
            }

            get("/clubs") {
                val params = call.request.queryParameters
                val city = params["city"]?.takeIf { it.isNotBlank() }
                val tag = params["tag"]?.takeIf { it.isNotBlank() }
                val query = params["q"]?.takeIf { it.isNotBlank() }
                val page = params["page"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val size = params["size"]?.toIntOrNull()?.coerceIn(1, MAX_PAGE_SIZE) ?: DEFAULT_PAGE_SIZE
                val offset = page * size

                val hasFilters = city != null || query != null || tag != null
                val clubs =
                    if (!hasFilters) {
                        emptyList()
                    } else {
                        withContext(Dispatchers.IO + MDCContext()) {
                            clubsRepository.list(city, query, tag, offset, size)
                        }
                    }

                logger.debug(
                    "clubs_api.ok city={} tag={} q={} page={} size={} count={}",
                    city,
                    tag,
                    query,
                    page,
                    size,
                    clubs.size,
                )

                val etag = etagFor(clubsRepository.lastUpdatedAt(), clubs.size, "clubs|$city|$tag|$query|$page|$size")

                call.respondWithCache(
                    etag = etag,
                    logger = logger,
                ) {
                    clubs.map { it.toDto() }
                }
            }

            get("/events") {
                val params = call.request.queryParameters
                val clubId = params["clubId"]?.toLongOrNull()
                val city = params["city"]?.takeIf { it.isNotBlank() }
                var from = parseInstant(params["from"])
                var to = parseInstant(params["to"])
                val page = params["page"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val size = params["size"]?.toIntOrNull()?.coerceIn(1, MAX_PAGE_SIZE) ?: DEFAULT_PAGE_SIZE
                val offset = page * size

                if (from != null && to != null && from.isAfter(to)) {
                    val tmp = from
                    from = to
                    to = tmp
                }

                val hasFilters = clubId != null || !city.isNullOrBlank()
                val events =
                    if (!hasFilters) {
                        emptyList()
                    } else {
                        withContext(Dispatchers.IO + MDCContext()) {
                            eventsRepository.list(
                                clubId = clubId,
                                city = city,
                                from = from,
                                to = to,
                                offset = offset,
                                limit = size,
                            )
                        }
                    }

                logger.debug(
                    "clubs_api.ok_events clubId={} city={} from={} to={} page={} size={} count={}",
                    clubId,
                    city,
                    from,
                    to,
                    page,
                    size,
                    events.size,
                )

                val etag = etagFor(eventsRepository.lastUpdatedAt(), events.size, "events|$clubId|$city|$from|$to|$page|$size")

                call.respondWithCache(
                    etag = etag,
                    logger = logger,
                ) {
                    events.map { it.toDto() }
                }
            }
        }
    }
}

private fun parseInstant(value: String?): Instant? {
    if (value == null) return null

    return runCatching { Instant.parse(value) }
        .getOrElse {
            runCatching { LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()
        }
}

private suspend fun ApplicationCall.callRespond(etag: String, payload: Any) {
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.CacheControl, CACHE_CONTROL)
    response.header(HttpHeaders.Vary, VARY_HEADER)
    response.header(HttpHeaders.ContentType, JSON_CONTENT_TYPE)
    respond(status = HttpStatusCode.OK, message = payload)
}

private suspend fun ApplicationCall.respondWithCache(
    etag: String,
    logger: org.slf4j.Logger,
    payloadProvider: suspend () -> Any,
) {
    val ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]
    if (matchesEtag(ifNoneMatch, etag)) {
        logger.debug("clubs_api.not_modified etag={}", etag)
        response.header(HttpHeaders.ETag, etag)
        response.header(HttpHeaders.CacheControl, CACHE_CONTROL)
        response.header(HttpHeaders.Vary, VARY_HEADER)
        response.header(HttpHeaders.ContentType, JSON_CONTENT_TYPE)
        respond(HttpStatusCode.NotModified)
        return
    }

    callRespond(etag, payloadProvider())
}
