package com.example.bot.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable private data class QrPayload(val qr: String, val clubId: Long?)

fun Application.checkinCompatRoutes() {
    routing {
        route("/api/checkin") {
            post("/qr") {
                val payload =
                    runCatching { call.receive<QrPayload>() }.getOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            "Invalid JSON",
                        )
                val clubId =
                    payload.clubId
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            "clubId is required",
                        )

                // На новый защищённый endpoint (там и произойдёт единственная проверка initData)
                call.response.headers.append(HttpHeaders.Location, "/api/clubs/$clubId/checkin/scan")
                call.respond(HttpStatusCode.TemporaryRedirect)
            }
        }
    }
}
