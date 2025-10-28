package com.example.bot.routes

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.pingRoute() {
    routing {
        get("/ping") {
            call.respondText("OK", ContentType.Text.Plain)
        }
    }
}
