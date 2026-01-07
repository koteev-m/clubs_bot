package com.example.bot.routes

import com.example.bot.club.InvitationCard
import com.example.bot.club.InvitationResponse
import com.example.bot.club.InvitationService
import com.example.bot.club.InvitationServiceResult
import com.example.bot.http.ErrorCodes
import com.example.bot.http.respondError
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
private data class ResolveInvitationRequest(
    val token: String,
)

@Serializable
private data class RespondInvitationRequest(
    val token: String,
    val response: InvitationResponseDto,
)

@Serializable
private data class InvitationCardDto(
    val invitationId: Long,
    val entryId: Long,
    val guestListId: Long,
    val clubId: Long,
    val clubName: String?,
    val eventId: Long,
    val arrivalWindowStart: String?,
    val arrivalWindowEnd: String?,
    val displayName: String,
    val entryStatus: String,
    val expiresAt: String,
    val revokedAt: String?,
    val usedAt: String?,
)

@Serializable
private enum class InvitationResponseDto {
    CONFIRM,
    DECLINE,
}

fun Application.invitationRoutes(
    invitationService: InvitationService,
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    routing {
        route("/api/invitations") {
            post("/resolve") {
                val payload = runCatching { call.receive<ResolveInvitationRequest>() }.getOrNull()
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                when (val result = invitationService.resolveInvitation(payload.token)) {
                    is InvitationServiceResult.Failure -> {
                        val (status, code) = result.error.toHttpError()
                        call.respondError(status, code)
                    }
                    is InvitationServiceResult.Success -> call.respond(result.value.toDto())
                }
            }

            route("/respond") {
                withMiniAppAuth(
                    botTokenProvider = botTokenProvider,
                    allowMissingInitData = true,
                )

                post {
                    val payload = runCatching { call.receive<RespondInvitationRequest>() }.getOrNull()
                        ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                    val telegramUserId = call.telegramUserIdOrNull()
                        ?: return@post call.respondError(HttpStatusCode.Forbidden, ErrorCodes.invitation_forbidden)

                    val response = payload.response.toDomain()
                    when (val result = invitationService.respondToInvitation(payload.token, telegramUserId, response)) {
                        is InvitationServiceResult.Failure -> {
                            val (status, code) = result.error.toHttpError()
                            call.respondError(status, code)
                        }
                        is InvitationServiceResult.Success -> call.respond(result.value.toDto())
                    }
                }
            }
        }
    }
}

private fun InvitationResponseDto.toDomain(): InvitationResponse =
    when (this) {
        InvitationResponseDto.CONFIRM -> InvitationResponse.CONFIRM
        InvitationResponseDto.DECLINE -> InvitationResponse.DECLINE
    }

private fun ApplicationCall.telegramUserIdOrNull(): Long? {
    if (attributes.contains(MiniAppUserKey)) {
        return attributes[MiniAppUserKey].id
    }
    return null
}

private fun InvitationCard.toDto(): InvitationCardDto =
    InvitationCardDto(
        invitationId = invitationId,
        entryId = entryId,
        guestListId = guestListId,
        clubId = clubId,
        clubName = clubName,
        eventId = eventId,
        arrivalWindowStart = arrivalWindowStart?.toString(),
        arrivalWindowEnd = arrivalWindowEnd?.toString(),
        displayName = displayName,
        entryStatus = entryStatus.name,
        expiresAt = expiresAt.toString(),
        revokedAt = revokedAt?.toString(),
        usedAt = usedAt?.toString(),
    )
