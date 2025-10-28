package com.example.bot.routes

import com.example.bot.observability.MetricsProvider
import com.example.bot.payments.finalize.PaymentsFinalizeService
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.promo.PromoAttributionService
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
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.micrometer.tracing.Tracer
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.koin.ktor.ext.getKoin
import org.koin.ktor.ext.inject
import java.util.UUID

private val logger = KotlinLogging.logger("PaymentsFinalizeRoute")

@Serializable
data class FinalizeRequest(
    val bookingId: String,
    val paymentToken: String? = null,
    val promoDeepLink: String? = null,
)

@Serializable
data class FinalizeResponse(
    val status: String,
    val bookingId: String,
    val paymentStatus: String,
    val promoAttached: Boolean,
)

fun Application.paymentsFinalizeRoutes(miniAppBotTokenProvider: () -> String) {
    val enabled = System.getenv("FINALIZE_ENABLED")?.toBooleanStrictOrNull() ?: true
    if (!enabled) {
        logger.info("payments.finalize route disabled via FINALIZE_ENABLED=false")
        return
    }
    logger.info("payments.finalize route enabled")

    // Основной сервис финализации
    val paymentsService by inject<PaymentsFinalizeService>()

    // Koin для ленивых/опциональных зависимостей
    val koin = getKoin()

    routing {
        // Общий корневой узел БЕЗ установки InitDataAuthPlugin
        route("/api/clubs/{clubId}/bookings") {

            // Плагин ставим на дочерний узел /finalize, чтобы не конфликтовать с другими модулями
            route("/finalize") {
                withMiniAppAuth(miniAppBotTokenProvider)

                post {
                    val callId = call.callId ?: "unknown"
                    val metricsProvider = koin.getOrNull<MetricsProvider>()
                    val tracer = koin.getOrNull<Tracer>()

                    val user = call.attributes[MiniAppUserKey]
                    val clubId = call.parameters["clubId"]?.toLongOrNull()
                    if (clubId == null) {
                        PaymentsMetrics
                            .timer(metricsProvider, Path.Finalize, Source.MiniApp)
                            .record(Result.Validation)
                        logger.warn { "[payments] finalize result=validation club=unknown booking=unknown requestId=$callId" }
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid club id"))
                        return@post
                    }

                    val idempotencyKey = call.request.headers["Idempotency-Key"]?.trim()
                    if (idempotencyKey.isNullOrEmpty()) {
                        PaymentsMetrics
                            .timer(metricsProvider, Path.Finalize, Source.MiniApp)
                            .record(Result.Validation)
                        logger.warn { "[payments] finalize result=validation club=$clubId booking=unknown requestId=$callId" }
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Idempotency-Key required"))
                        return@post
                    }

                    val request =
                        runCatching { call.receive<FinalizeRequest>() }
                            .getOrElse { throwable ->
                                PaymentsMetrics
                                    .timer(metricsProvider, Path.Finalize, Source.MiniApp)
                                    .record(Result.Validation)
                                logger.warn(throwable) {
                                    "[payments] finalize result=validation club=$clubId booking=unknown requestId=$callId"
                                }
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid payload"))
                                return@post
                            }

                    val bookingId = runCatching { UUID.fromString(request.bookingId) }.getOrNull()
                    if (bookingId == null) {
                        PaymentsMetrics
                            .timer(metricsProvider, Path.Finalize, Source.MiniApp)
                            .record(Result.Validation)
                        logger.warn { "[payments] finalize result=validation club=$clubId booking=unknown requestId=$callId" }
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid bookingId"))
                        return@post
                    }

                    val bookingLabel = maskBookingId(bookingId)
                    val timer = PaymentsMetrics.timer(metricsProvider, Path.Finalize, Source.MiniApp)
                    val metadata =
                        PaymentsTraceMetadata(
                            httpRoute = "/api/clubs/{clubId}/bookings/finalize",
                            paymentsPath = Path.Finalize.tag,
                            idempotencyKeyPresent = true,
                            bookingIdMasked = bookingLabel,
                            requestId = callId,
                        )

                    logger.info { "[payments] finalize start club=$clubId booking=$bookingLabel requestId=$callId" }

                    val promoService = koin.getOrNull<PromoAttributionService>()
                    tracer.spanSuspending("payments.finalize.handler", metadata) {
                        try {
                            val result =
                                paymentsService.finalize(
                                    clubId = clubId,
                                    bookingId = bookingId,
                                    paymentToken = request.paymentToken,
                                    idemKey = idempotencyKey,
                                    actorUserId = user.id,
                                )

                            val promoAttached =
                                if (!request.promoDeepLink.isNullOrBlank() && promoService != null) {
                                    runCatching {
                                        promoService.attachDeepLink(bookingId, request.promoDeepLink)
                                    }.onFailure { throwable ->
                                        logger.warn(throwable) {
                                            "[payments] finalize promo-attach-failed club=$clubId booking=$bookingLabel requestId=$callId"
                                        }
                                    }.getOrDefault(false)
                                } else {
                                    false
                                }

                            timer.record(Result.Ok)
                            setResult(Result.Ok)
                            logger.info {
                                "[payments] finalize result=ok club=$clubId booking=$bookingLabel requestId=$callId status=${result.paymentStatus}"
                            }
                            call.respond(
                                HttpStatusCode.OK,
                                FinalizeResponse(
                                    status = "OK",
                                    bookingId = bookingId.toString(),
                                    paymentStatus = result.paymentStatus,
                                    promoAttached = promoAttached,
                                ),
                            )
                        } catch (conflict: PaymentsFinalizeService.ConflictException) {
                            timer.record(Result.Conflict)
                            setResult(Result.Conflict)
                            logger.warn(conflict) {
                                "[payments] finalize result=conflict club=$clubId booking=$bookingLabel requestId=$callId"
                            }
                            call.respond(HttpStatusCode.Conflict, mapOf("error" to conflict.message))
                        } catch (validation: PaymentsFinalizeService.ValidationException) {
                            timer.record(Result.Validation)
                            setResult(Result.Validation)
                            logger.warn(validation) {
                                "[payments] finalize result=validation club=$clubId booking=$bookingLabel requestId=$callId"
                            }
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to validation.message))
                        } catch (unexpected: Throwable) {
                            timer.record(Result.Unexpected)
                            setResult(Result.Unexpected)
                            logger.error(unexpected) {
                                "[payments] finalize result=unexpected club=$clubId booking=$bookingLabel requestId=$callId"
                            }
                            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal"))
                        }
                    }
                }
            }
        }
    }
}
