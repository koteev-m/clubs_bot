package com.example.bot.routes

import com.example.bot.availability.AvailabilityService
import com.example.bot.availability.NightDto
import com.example.bot.availability.TableAvailabilityDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val DEFAULT_LIMIT = 8
private const val MAX_LIMIT = 50

@Serializable
data class ApiNightDto(
    val startUtc: String,
    val name: String,
)

@Serializable
data class ApiFreeTableDto(
    val id: Long,
    val number: String? = null,
    val capacity: Int,
    val status: String = "FREE",
)

fun Application.availabilityApiRoutes(service: AvailabilityService) {
    routing {
        get("/api/clubs/{clubId}/nights") {
            val clubId =
                call.parameters["clubId"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid clubId")

            val limitParam = call.request.queryParameters["limit"]
            val limit =
                when {
                    limitParam == null -> DEFAULT_LIMIT
                    else -> limitParam.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
                }
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid limit")

            val nights = withContext(Dispatchers.IO) { service.listOpenNights(clubId, limit) }
            call.respond(nights.map { it.toApiDto() })
        }

        get("/api/clubs/{clubId}/nights/{startUtc}/tables/free") {
            val clubId =
                call.parameters["clubId"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid clubId")

            val startUtcRaw =
                call.parameters["startUtc"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid startUtc")

            val startUtc =
                runCatching { Instant.parse(startUtcRaw) }
                    .getOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid startUtc")

            val tables = withContext(Dispatchers.IO) { service.listFreeTables(clubId, startUtc) }
            call.respond(tables.map { it.toApiDto() })
        }
    }
}

private fun NightDto.toApiDto(): ApiNightDto {
    val zone = timezone.toZoneId()
    val startZoned = eventStartUtc.atZone(zone)
    val endZoned = eventEndUtc.atZone(zone)
    val dayPart = NIGHT_DAY_FORMATTER.format(startZoned)
    val timePart = NIGHT_TIME_FORMATTER.format(startZoned) + "–" + NIGHT_TIME_FORMATTER.format(endZoned)
    val baseName = "$dayPart · $timePart"
    val name = if (isSpecial) "✨ $baseName" else baseName

    return ApiNightDto(
        startUtc = eventStartUtc.toString(),
        name = name,
    )
}

private fun TableAvailabilityDto.toApiDto(): ApiFreeTableDto =
    ApiFreeTableDto(
        id = tableId,
        number = tableNumber.takeIf { it.isNotBlank() },
        capacity = capacity,
        status = status.name,
    )

private fun String.toZoneId(): ZoneId = runCatching { ZoneId.of(this) }.getOrElse { ZoneOffset.UTC }

private val NIGHT_DAY_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.ENGLISH)

private val NIGHT_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
