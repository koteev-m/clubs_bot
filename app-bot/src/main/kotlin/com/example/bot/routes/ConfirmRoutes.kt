package com.example.bot.routes

import com.example.bot.booking.PaymentPolicy
import com.example.bot.booking.legacy.Either
import com.example.bot.booking.payments.ConfirmInput
import com.example.bot.booking.payments.PaymentMode
import com.example.bot.booking.payments.PaymentsService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.UUID

/**
 * HTTP endpoints for booking confirmation and payments.
 */
fun Route.confirmRoutes(payments: PaymentsService) {
    post("/api/confirm") {
        val input = call.receive<ConfirmInput>()
        val idem = call.request.headers["Idempotency-Key"] ?: UUID.randomUUID().toString()
        val policy = PaymentPolicy(mode = PaymentMode.PROVIDER_DEPOSIT)
        when (val res = payments.startConfirmation(input, null, policy, idem)) {
            is Either.Left -> call.respond(HttpStatusCode.InternalServerError, res.value)
            is Either.Right -> call.respond(res.value)
        }
    }
}
