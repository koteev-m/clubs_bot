package com.example.bot.routes

import com.example.bot.booking.BookingCmdResult
import com.example.bot.booking.BookingService
import com.example.bot.webapp.InitDataAuthPlugin
import com.example.bot.webapp.InitDataPrincipalKey
import com.example.bot.webapp.TelegramPrincipal
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

fun Application.bookingFinalizeRoutes(
    bookingService: BookingService,
    initDataBotTokenProvider: () -> String = {
        System.getenv("TELEGRAM_BOT_TOKEN") ?: error("TELEGRAM_BOT_TOKEN missing")
    },
) {
    routing {
        route("/api/clubs/{clubId}/bookings") {
            install(InitDataAuthPlugin) {
                botTokenProvider = initDataBotTokenProvider
            }

            post("/finalize") {
                call.parameters["clubId"]?.toLongOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid clubId")

                val principal: TelegramPrincipal = call.attributes[InitDataPrincipalKey]
                val telegramUserId = principal.userId

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
                    val result = bookingService.finalize(bookingId, telegramUserId)

                    when (result) {
                        is BookingCmdResult.Booked -> {
                            call.respond(
                                HttpStatusCode.OK,
                                mapOf(
                                    "status" to "booked",
                                    "bookingId" to result.bookingId.toString(),
                                ),
                            )
                        }

                        else -> {
                            call.respond(
                                HttpStatusCode.Conflict,
                                mapOf(
                                    "status" to "failed",
                                    "code" to (result::class.simpleName ?: "Unknown"),
                                    "bookingId" to bookingId.toString(),
                                ),
                            )
                        }
                    }
                } finally {
                    MDC.remove("Idempotency-Key")
                }
            }
        }
    }
}

@Serializable
private data class FinalizePayload(val bookingId: String)
