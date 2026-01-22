package com.example.bot.routes

import com.example.bot.http.ErrorCodes
import com.example.bot.http.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.checkinCompatRoutes() {
    routing {
        route("/api/checkin") {
            post("/qr") {
                call.respondError(HttpStatusCode.Forbidden, ErrorCodes.checkin_forbidden)
            }
        }
    }
}
