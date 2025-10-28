package com.example.bot.routes

import com.example.bot.observability.DefaultHealthService
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.healthRoutes(service: DefaultHealthService) {
    routing {
        get("/health") { call.respond(service.health()) }
        get("/ready") { call.respond(service.readiness()) }
    }
}
