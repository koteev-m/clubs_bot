package com.example.bot.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.time.Instant

fun Application.installDiagTime() {
    routing {
        get("/diag/time") {
            val now = Instant.now()
            call.respond(
                mapOf(
                    "now" to now.toString(),
                    "epochSeconds" to now.epochSecond,
                ),
            )
        }
    }
}
