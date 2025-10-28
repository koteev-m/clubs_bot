package com.example.bot.routes

import com.example.bot.availability.AvailabilityService
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.util.getOrFail
import java.time.Instant

/**
 * Routes exposing availability information for the mini app.
 */
fun Route.availabilityRoutes(service: AvailabilityService) {
    route("/clubs/{clubId}") {
        get("/nights") {
            val clubId = call.parameters.getOrFail("clubId").toLong()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_NIGHTS_LIMIT
            val nights = service.listOpenNights(clubId, limit)
            call.respond(nights)
        }

        get("/nights/{startUtc}/tables/free") {
            val clubId = call.parameters.getOrFail("clubId").toLong()
            val startUtc = call.parameters.getOrFail("startUtc")
            val instant = Instant.parse(startUtc)
            val tables = service.listFreeTables(clubId, instant)
            call.respond(tables)
        }
    }
}

private const val DEFAULT_NIGHTS_LIMIT = 8
