package com.example.bot.routes

import com.example.bot.booking.legacy.BookingError
import com.example.bot.booking.legacy.BookingService
import com.example.bot.booking.legacy.ConfirmRequest
import com.example.bot.booking.legacy.Either
import com.example.bot.booking.legacy.HoldRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

/**
 * Defines HTTP routes for booking operations.
 */
@Deprecated(message = "Replaced by /api/clubs/{clubId}/bookings/* under RBAC")
fun Route.bookingRoutes(service: BookingService) {
    route("bookings") {
        post("/hold") {
            val req = call.receive<HoldRequest>()
            val key = call.request.headers["Idempotency-Key"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            when (val res = service.hold(req, key)) {
                is Either.Left -> call.respond(mapError(res.value))
                is Either.Right -> call.respond(res.value)
            }
        }
        post("/confirm") {
            val req = call.receive<ConfirmRequest>()
            val key = call.request.headers["Idempotency-Key"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            when (val res = service.confirm(req, key)) {
                is Either.Left -> call.respond(mapError(res.value))
                is Either.Right -> call.respond(res.value)
            }
        }
        post("/{id}/cancel") {
            val bookingId = UUID.fromString(call.parameters["id"]!!)
            val key = call.request.headers["Idempotency-Key"] ?: "" // unused
            when (val res = service.cancel(bookingId, 0, null, key)) {
                is Either.Left -> call.respond(mapError(res.value))
                is Either.Right -> call.respond(res.value)
            }
        }
        post("/seat/qr") {
            val payload = call.receive<Map<String, String>>()
            val qr = payload["qrSecret"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val key = call.request.headers["Idempotency-Key"] ?: "" // unused
            when (val res = service.seatByQr(qr, 0, key)) {
                is Either.Left -> call.respond(mapError(res.value))
                is Either.Right -> call.respond(res.value)
            }
        }
    }
}

private fun mapError(error: BookingError): Pair<HttpStatusCode, Map<String, String>> =
    when (error) {
        is BookingError.Conflict -> HttpStatusCode.Conflict to mapOf("error" to error.message)
        is BookingError.Validation ->
            HttpStatusCode.UnprocessableEntity to
                mapOf("error" to error.message)
        is BookingError.NotFound -> HttpStatusCode.NotFound to mapOf("error" to error.message)
        is BookingError.Forbidden -> HttpStatusCode.Forbidden to mapOf("error" to error.message)
        is BookingError.Gone -> HttpStatusCode.Gone to mapOf("error" to error.message)
        is BookingError.Internal ->
            HttpStatusCode.InternalServerError to
                mapOf("error" to error.message)
    }
