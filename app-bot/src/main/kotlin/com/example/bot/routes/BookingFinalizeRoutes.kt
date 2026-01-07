package com.example.bot.routes

import com.example.bot.booking.BookingCmdResult
import com.example.bot.booking.BookingService
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.miniAppBotTokenRequired
import com.example.bot.plugins.withMiniAppAuth
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.MDC
import java.util.UUID

@Serializable
private data class FinalizePayload(val bookingId: String)

fun Application.bookingFinalizeRoutes(
    bookingService: BookingService,
    botTokenProvider: () -> String = { miniAppBotTokenRequired() },
) {
    routing {
        route("/api/clubs/{clubId}/bookings") {
            withMiniAppAuth { botTokenProvider() }

            post("/finalize") {
                call.parameters["clubId"]?.toLongOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid clubId")

                val principal = call.attributes[MiniAppUserKey]
                val telegramUserId = principal.id

                val payload =
                    runCatching { call.receive<FinalizePayload>() }
                        .getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid JSON")

                val bookingId =
                    runCatching { UUID.fromString(payload.bookingId) }
                        .getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid bookingId")

                val idem = call.request.headers["Idempotency-Key"] ?: "finalize:$bookingId"
                MDC.put("Idempotency-Key", idem)

                try {
                    when (val result = bookingService.finalize(bookingId, telegramUserId)) {
                        is BookingCmdResult.Booked ->
                            call.respond(
                                HttpStatusCode.OK,
                                mapOf("status" to "booked", "bookingId" to result.bookingId.toString()),
                            )
                        BookingCmdResult.NotFound ->
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found"))
                        else ->
                            call.respond(
                                HttpStatusCode.Conflict,
                                mapOf(
                                    "error" to "conflict",
                                    "code" to (result::class.simpleName ?: "Unknown"),
                                ),
                            )
                    }
                } finally {
                    MDC.remove("Idempotency-Key")
                }
            }
        }
    }
}
