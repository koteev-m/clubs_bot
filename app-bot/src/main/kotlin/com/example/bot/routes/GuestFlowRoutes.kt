package com.example.bot.routes

import com.example.bot.availability.AvailabilityService
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.util.getOrFail

/**
 * Routes serving open nights for guest flow.
*/
fun Route.guestFlowRoutes(availability: AvailabilityService) {
    route("/clubs/{clubId}") {
        get("/nights") {
            val clubId = call.parameters.getOrFail("clubId").toLong()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_NIGHTS_LIMIT
            val nights = availability.listOpenNights(clubId, limit)
            call.respond(nights)
        }
    }
}

private const val DEFAULT_NIGHTS_LIMIT = 8
