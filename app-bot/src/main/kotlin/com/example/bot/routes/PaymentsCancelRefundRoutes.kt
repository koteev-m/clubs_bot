package com.example.bot.routes

import com.example.bot.di.PaymentsService
import com.example.bot.observability.MetricsProvider
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.envBool
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.ClubScope
import com.example.bot.data.security.Role
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.clubScoped
import com.example.bot.telemetry.PaymentsMetrics
import com.example.bot.telemetry.PaymentsMetrics.Path
import com.example.bot.telemetry.PaymentsMetrics.Result
import com.example.bot.telemetry.PaymentsMetrics.Source
import com.example.bot.telemetry.PaymentsTraceMetadata
import com.example.bot.telemetry.maskBookingId
import com.example.bot.telemetry.setResult
import com.example.bot.telemetry.spanSuspending
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.koin.core.Koin
import org.koin.ktor.ext.getKoin
import io.micrometer.tracing.Tracer
import java.util.UUID

private val logger = KotlinLogging.logger("PaymentsCancelRefundRoutes")

private typealias RbacRouteWrapper = io.ktor.server.routing.Route.() -> Unit

@Serializable
private data class CancelRequest(val reason: String? = null)

@Serializable
data class CancelResponse(
    val status: String,
    val bookingId: String,
    val idempotent: Boolean,
    val alreadyCancelled: Boolean = false,
)

@Serializable
private data class RefundRequest(val amountMinor: Long? = null)

@Serializable
data class RefundResponse(
    val status: String,
    val bookingId: String,
    val refundAmountMinor: Long,
    val idempotent: Boolean,
)

fun Application.paymentsCancelRefundRoutes(miniAppBotTokenProvider: () -> String) {
    val cancelEnabled = envBool("CANCEL_ENABLED", default = true)
    val refundEnabled = envBool("REFUND_ENABLED", default = true)

    if (!cancelEnabled && !refundEnabled) {
        logger.info { "[payments] cancel/refund routes disabled" }
        return
    }

    val rbacEnabled = envBool("RBAC_ENABLED", default = false)
    val rbacAvailable = rbacEnabled && pluginOrNull(RbacPlugin) != null
    val koin = getKoin()

    routing {
        // Корневой узел оставляем без InitDataAuthPlugin,
        // чтобы избежать DuplicatePluginException
        route("/api/clubs/{clubId}/bookings") {

            val registerHandlers: RbacRouteWrapper =
                if (rbacAvailable) {
                    {
                        authorize(
                            Role.PROMOTER,
                            Role.CLUB_ADMIN,
                            Role.MANAGER,
                            Role.GUEST,
                        ) {
                            clubScoped(ClubScope.Own) {
                                registerCancelRefundHandlers(
                                    cancelEnabled = cancelEnabled,
                                    refundEnabled = refundEnabled,
                                    koin = koin,
                                    miniAppBotTokenProvider = miniAppBotTokenProvider,
                                )
                            }
                        }
                    }
                } else {
                    {
                        registerCancelRefundHandlers(
                            cancelEnabled = cancelEnabled,
                            refundEnabled = refundEnabled,
                            koin = koin,
                            miniAppBotTokenProvider = miniAppBotTokenProvider,
                        )
                    }
                }

            registerHandlers.invoke(this)
        }
    }
}

private fun io.ktor.server.routing.Route.registerCancelRefundHandlers(
    cancelEnabled: Boolean,
    refundEnabled: Boolean,
    koin: Koin,
    miniAppBotTokenProvider: () -> String,
) {
    val metricsProvider = koin.getOrNull<MetricsProvider>()
    val tracer = koin.getOrNull<Tracer>()
    val paymentsService = koin.get<PaymentsService>()

    if (cancelEnabled) {
        // Плагин ставится на дочерний узел /{bookingId}/cancel
        route("/{bookingId}/cancel") {
            withMiniAppAuth(miniAppBotTokenProvider)

            post {
                val callId = call.callId ?: "unknown"
                val user = call.attributes[MiniAppUserKey]
                val clubId = call.parameters["clubId"]?.toLongOrNull()
                if (clubId == null) {
                    PaymentsMetrics
                        .timer(metricsProvider, Path.Cancel, Source.MiniApp)
                        .record(Result.Validation)
                    logger.warn { "[payments] cancel result=validation club=unknown booking=unknown requestId=$callId" }
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_club"))
                    return@post
                }

                val bookingId =
                    call.parameters["bookingId"]?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
                if (bookingId == null) {
                    PaymentsMetrics
                        .timer(metricsProvider, Path.Cancel, Source.MiniApp)
                        .record(Result.Validation)
                    logger.warn { "[payments] cancel result=validation club=$clubId booking=unknown requestId=$callId" }
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_booking"))
                    return@post
                }

                val idempotencyKey = call.request.headers["Idempotency-Key"]?.trim()
                if (idempotencyKey.isNullOrEmpty()) {
                    PaymentsMetrics
                        .timer(metricsProvider, Path.Cancel, Source.MiniApp)
                        .record(Result.Validation)
                    logger.warn { "[payments] cancel result=validation club=$clubId booking=${maskBookingId(bookingId)} requestId=$callId" }
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Idempotency-Key required"))
                    return@post
                }

                val bookingLabel = maskBookingId(bookingId)
                val timer = PaymentsMetrics.timer(metricsProvider, Path.Cancel, Source.MiniApp)

                val payload =
                    runCatching { call.receive<CancelRequest>() }
                        .getOrElse { throwable ->
                            timer.record(Result.Validation)
                            logger.warn(throwable) {
                                "[payments] cancel result=validation club=$clubId booking=$bookingLabel requestId=$callId"
                            }
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_payload"))
                            return@post
                        }

                val metadata =
                    PaymentsTraceMetadata(
                        httpRoute = "/api/clubs/{clubId}/bookings/{bookingId}/cancel",
                        paymentsPath = Path.Cancel.tag,
                        idempotencyKeyPresent = true,
                        bookingIdMasked = bookingLabel,
                        requestId = callId,
                    )

                tracer.spanSuspending("payments.cancel.handler", metadata) {
                    try {
                        val result =
                            paymentsService.cancel(
                                clubId = clubId,
                                bookingId = bookingId,
                                reason = payload.reason,
                                idemKey = idempotencyKey,
                                actorUserId = user.id,
                            )
                        timer.record(Result.Ok)
                        setResult(Result.Ok)
                        logger.info {
                            "[payments] cancel result=ok club=$clubId booking=$bookingLabel requestId=$callId idempotent=${result.idempotent}"
                        }
                        call.respond(
                            CancelResponse(
                                status = "CANCELLED",
                                bookingId = bookingId.toString(),
                                idempotent = result.idempotent,
                                alreadyCancelled = result.alreadyCancelled,
                            ),
                        )
                    } catch (validation: PaymentsService.ValidationException) {
                        timer.record(Result.Validation)
                        setResult(Result.Validation)
                        logger.warn(validation) {
                            "[payments] cancel result=validation club=$clubId booking=$bookingLabel requestId=$callId"
                        }
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to validation.message))
                    } catch (conflict: PaymentsService.ConflictException) {
                        timer.record(Result.Conflict)
                        setResult(Result.Conflict)
                        logger.warn(conflict) {
                            "[payments] cancel result=conflict club=$clubId booking=$bookingLabel requestId=$callId"
                        }
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to conflict.message))
                    } catch (unexpected: Throwable) {
                        timer.record(Result.Unexpected)
                        setResult(Result.Unexpected)
                        logger.error(unexpected) {
                            "[payments] cancel result=unexpected club=$clubId booking=$bookingLabel requestId=$callId"
                        }
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal"))
                    }
                }
            }
        }
    }

    if (refundEnabled) {
        // Плагин ставится на дочерний узел /{bookingId}/refund
        route("/{bookingId}/refund") {
            withMiniAppAuth(miniAppBotTokenProvider)

            post {
                val callId = call.callId ?: "unknown"
                val user = call.attributes[MiniAppUserKey]
                val clubId = call.parameters["clubId"]?.toLongOrNull()
                if (clubId == null) {
                    PaymentsMetrics
                        .timer(metricsProvider, Path.Refund, Source.MiniApp)
                        .record(Result.Validation)
                    logger.warn { "[payments] refund result=validation club=unknown booking=unknown requestId=$callId" }
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_club"))
                    return@post
                }

                val bookingId =
                    call.parameters["bookingId"]?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
                if (bookingId == null) {
                    PaymentsMetrics
                        .timer(metricsProvider, Path.Refund, Source.MiniApp)
                        .record(Result.Validation)
                    logger.warn { "[payments] refund result=validation club=$clubId booking=unknown requestId=$callId" }
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_booking"))
                    return@post
                }

                val idempotencyKey = call.request.headers["Idempotency-Key"]?.trim()
                if (idempotencyKey.isNullOrEmpty()) {
                    PaymentsMetrics
                        .timer(metricsProvider, Path.Refund, Source.MiniApp)
                        .record(Result.Validation)
                    logger.warn { "[payments] refund result=validation club=$clubId booking=${maskBookingId(bookingId)} requestId=$callId" }
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Idempotency-Key required"))
                    return@post
                }

                val bookingLabel = maskBookingId(bookingId)
                val timer = PaymentsMetrics.timer(metricsProvider, Path.Refund, Source.MiniApp)

                val payload =
                    runCatching { call.receive<RefundRequest>() }
                        .getOrElse { throwable ->
                            timer.record(Result.Validation)
                            logger.warn(throwable) {
                                "[payments] refund result=validation club=$clubId booking=$bookingLabel requestId=$callId"
                            }
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_payload"))
                            return@post
                        }

                val metadata =
                    PaymentsTraceMetadata(
                        httpRoute = "/api/clubs/{clubId}/bookings/{bookingId}/refund",
                        paymentsPath = Path.Refund.tag,
                        idempotencyKeyPresent = true,
                        bookingIdMasked = bookingLabel,
                        requestId = callId,
                    )

                tracer.spanSuspending("payments.refund.handler", metadata) {
                    try {
                        val result =
                            paymentsService.refund(
                                clubId = clubId,
                                bookingId = bookingId,
                                amountMinor = payload.amountMinor,
                                idemKey = idempotencyKey,
                                actorUserId = user.id,
                            )
                        timer.record(Result.Ok)
                        setResult(Result.Ok)
                        logger.info {
                            "[payments] refund result=ok club=$clubId booking=$bookingLabel requestId=$callId idempotent=${result.idempotent}"
                        }
                        call.respond(
                            RefundResponse(
                                status = "REFUNDED",
                                bookingId = bookingId.toString(),
                                refundAmountMinor = result.refundAmountMinor,
                                idempotent = result.idempotent,
                            ),
                        )
                    } catch (validation: PaymentsService.ValidationException) {
                        timer.record(Result.Validation)
                        setResult(Result.Validation)
                        logger.warn(validation) {
                            "[payments] refund result=validation club=$clubId booking=$bookingLabel requestId=$callId"
                        }
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to validation.message))
                    } catch (conflict: PaymentsService.ConflictException) {
                        timer.record(Result.Conflict)
                        setResult(Result.Conflict)
                        logger.warn(conflict) {
                            "[payments] refund result=conflict club=$clubId booking=$bookingLabel requestId=$callId"
                        }
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to conflict.message))
                    } catch (unprocessable: PaymentsService.UnprocessableException) {
                        timer.record(Result.Unprocessable)
                        setResult(Result.Unprocessable)
                        logger.warn(unprocessable) {
                            "[payments] refund result=unprocessable club=$clubId booking=$bookingLabel requestId=$callId"
                        }
                        call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to unprocessable.message))
                    } catch (unexpected: Throwable) {
                        timer.record(Result.Unexpected)
                        setResult(Result.Unexpected)
                        logger.error(unexpected) {
                            "[payments] refund result=unexpected club=$clubId booking=$bookingLabel requestId=$callId"
                        }
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal"))
                    }
                }
            }
        }
    }
}
