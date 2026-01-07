package com.example.bot.routes

import com.example.bot.club.GuestList
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListRepository
import com.example.bot.data.security.Role
import com.example.bot.guestlists.QrGuestListCodec
import com.example.bot.guestlists.StartParamGuestListCodec
import com.example.bot.plugins.miniAppBotTokenRequired
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.RbacContext
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.rbacContext
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * POST /api/guest-lists/{listId}/entries/{entryId}/invite
 * Ответ: { qr: "GL:...", start_param: "G_...", start_link?: "https://t.me/<bot>?start=G_..." }
 *
 * Доступ: OWNER, GLOBAL_ADMIN, HEAD_MANAGER, CLUB_ADMIN, MANAGER, PROMOTER (с проверкой принадлежности списка).
 */
fun Application.guestListInviteRoutes(
    repository: GuestListRepository,
    botTokenProvider: () -> String = { miniAppBotTokenRequired() },
    clock: Clock = Clock.systemUTC(),
    qrTtl: Duration = Duration.ofHours(12),
    botUsernameProvider: () -> String? = { System.getenv("TELEGRAM_BOT_USERNAME") },
    qrSecretProvider: () -> String = { System.getenv("QR_SECRET") ?: error("QR_SECRET missing") },
) {
    routing {
        route("/api/guest-lists/{listId}/entries/{entryId}") {
            withMiniAppAuth { botTokenProvider() }

            authorize(
                Role.OWNER,
                Role.GLOBAL_ADMIN,
                Role.HEAD_MANAGER,
                Role.CLUB_ADMIN,
                Role.MANAGER,
                Role.PROMOTER,
            ) {
                post("/invite") {
                    val listId =
                        call.parameters["listId"]?.toLongOrNull()
                            ?: return@post call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "invalid_list_id"),
                            )
                    val entryId =
                        call.parameters["entryId"]?.toLongOrNull()
                            ?: return@post call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "invalid_entry_id"),
                            )

                    val list =
                        repository.getList(listId)
                            ?: return@post call.respond(
                                HttpStatusCode.NotFound,
                                mapOf("error" to "list_not_found"),
                            )
                    val entry =
                        repository.findEntry(entryId)
                            ?: return@post call.respond(
                                HttpStatusCode.NotFound,
                                mapOf("error" to "entry_not_found"),
                            )
                    if (entry.listId != list.id) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "entry_list_mismatch"),
                        )
                    }

                    // RBAC: можно ли выпускать для этого списка
                    val context = call.rbacContext()
                    if (!canIssueForList(context, list)) {
                        return@post call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf("error" to "forbidden"),
                        )
                    }

                    val now = Instant.now(clock)
                    val secret = qrSecretProvider()

                    val qrToken = QrGuestListCodec.encode(list.id, entry.id, now, secret)
                    val startParam = StartParamGuestListCodec.encode(list.id, entry.id, now, secret)

                    val botUsername = botUsernameProvider()?.takeIf { it.isNotBlank() }
                    val startLink =
                        botUsername?.let {
                            val payload = URLEncoder.encode(startParam, StandardCharsets.UTF_8)
                            "https://t.me/$it?start=$payload"
                        }

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "qr" to qrToken,
                            "start_param" to startParam,
                            "start_link" to startLink,
                            "ttl_seconds" to qrTtl.seconds,
                        ),
                    )
                }

                // health/ping
                post("/invite/ping") {
                    call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.OK)
                }
            }
        }
    }
}

private val GLOBAL_ROLES: Set<Role> =
    setOf(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER)

private fun canIssueForList(
    context: RbacContext,
    list: GuestList,
): Boolean {
    val isGlobal = context.roles.any { it in GLOBAL_ROLES }

    // Админ/менеджер клуба — если список относится к его клубу
    val isClubScoped =
        (Role.CLUB_ADMIN in context.roles || Role.MANAGER in context.roles) &&
            (list.clubId in context.clubIds)

    // Промоутер — только свои списки
    val isPromoterOwnList =
        (Role.PROMOTER in context.roles) &&
            list.ownerType == GuestListOwnerType.PROMOTER &&
            list.ownerUserId == context.user.id

    return isGlobal || isClubScoped || isPromoterOwnList
}
