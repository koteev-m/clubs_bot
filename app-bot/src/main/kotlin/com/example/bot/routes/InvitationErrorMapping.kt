package com.example.bot.routes

import com.example.bot.club.InvitationServiceError
import com.example.bot.http.ErrorCodes
import io.ktor.http.HttpStatusCode

internal fun InvitationServiceError.toHttpError(): Pair<HttpStatusCode, String> =
    when (this) {
        InvitationServiceError.INVITATION_INVALID -> HttpStatusCode.BadRequest to ErrorCodes.invitation_invalid
        InvitationServiceError.INVITATION_REVOKED -> HttpStatusCode.Gone to ErrorCodes.invitation_revoked
        InvitationServiceError.INVITATION_EXPIRED -> HttpStatusCode.Gone to ErrorCodes.invitation_expired
        InvitationServiceError.INVITATION_ALREADY_USED -> HttpStatusCode.Conflict to ErrorCodes.invitation_already_used
        InvitationServiceError.GUEST_LIST_ENTRY_NOT_FOUND -> HttpStatusCode.NotFound to ErrorCodes.entry_not_found
        InvitationServiceError.GUEST_LIST_NOT_ACTIVE -> HttpStatusCode.Conflict to ErrorCodes.guest_list_not_active
    }
