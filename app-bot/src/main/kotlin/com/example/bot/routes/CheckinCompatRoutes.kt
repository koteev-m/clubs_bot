package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.guestlists.GuestListRepository
import com.example.bot.metrics.UiCheckinMetrics
import com.example.bot.security.rbac.authorize
import com.example.bot.webapp.InitDataAuthPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Duration

private val DEFAULT_QR_TTL: Duration = Duration.ofHours(12)
private val INIT_DATA_MAX_AGE: Duration = Duration.ofHours(24)

@Serializable
private data class QrPayload(
    val qr: String,
    val clubId: Long? = null,
)

@Suppress("UNUSED_PARAMETER")
fun Application.checkinCompatRoutes(
    repository: GuestListRepository,
    initDataBotTokenProvider: () -> String = {
        System.getenv("TELEGRAM_BOT_TOKEN") ?: error("TELEGRAM_BOT_TOKEN missing")
    },
    qrSecretProvider: () -> String = {
        System.getenv("QR_SECRET") ?: error("QR_SECRET missing")
    },
    clock: Clock = Clock.systemUTC(),
    qrTtl: Duration = DEFAULT_QR_TTL,
) {
    routing {
        route("/api/checkin") {
            install(InitDataAuthPlugin) {
                botTokenProvider = initDataBotTokenProvider
                maxAge = INIT_DATA_MAX_AGE
                this.clock = clock
            }

            authorize(Role.CLUB_ADMIN, Role.MANAGER, Role.ENTRY_MANAGER) {
                post("/qr") {
                    UiCheckinMetrics.incTotal()
                    UiCheckinMetrics.timeScan {
                        val payload =
                            runCatching { call.receive<QrPayload>() }
                                .getOrNull()
                                ?: run {
                                    UiCheckinMetrics.incError()
                                    call.respond(HttpStatusCode.BadRequest, "Invalid JSON")
                                    return@timeScan
                                }

                        val qr = payload.qr.trim()
                        if (qr.isEmpty()) {
                            UiCheckinMetrics.incError()
                            call.respond(HttpStatusCode.BadRequest, "Empty QR")
                            return@timeScan
                        }

                        val clubId =
                            payload.clubId
                                ?: run {
                                    UiCheckinMetrics.incError()
                                    call.respond(HttpStatusCode.BadRequest, "clubId is required")
                                    return@timeScan
                                }

                        val location = "/api/clubs/$clubId/checkin/scan"
                        call.response.headers.append(HttpHeaders.Location, location)
                        call.respond(HttpStatusCode.TemporaryRedirect)

                        // If front-end cannot follow redirects, inline processing from CheckinRoutes can
                        // be added here by copying the scan logic and performing the same checks.
                    }
                }

                get("/search") {
                    call.respond(
                        HttpStatusCode.NotImplemented,
                        mapOf("status" to "not_implemented", "feature" to "checkin_search"),
                    )
                }

                post("/plus-one") {
                    call.respond(
                        HttpStatusCode.NotImplemented,
                        mapOf("status" to "not_implemented", "feature" to "checkin_plus_one"),
                    )
                }
            }
        }
    }
}
