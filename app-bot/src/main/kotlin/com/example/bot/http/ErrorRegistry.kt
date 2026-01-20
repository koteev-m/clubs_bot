package com.example.bot.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

@Serializable
data class ErrorCodeInfo(
    val code: String,
    val http: Int,
    val stable: Boolean = true,
    val deprecated: Boolean = false,
)

@Serializable
data class ErrorCodesPayload(
    val version: Int = 2,
    val codes: List<ErrorCodeInfo>,
)

object ErrorRegistry {
    const val version: Int = 8
    val etag: String = "\"error-codes-v$version\""
    const val cacheControl: String = "public, max-age=300, stale-while-revalidate=30, stale-if-error=86400"

    val common: List<ErrorCodeInfo> = listOf(
        ErrorCodeInfo(ErrorCodes.unauthorized, HttpStatusCode.Unauthorized.value),
        ErrorCodeInfo(ErrorCodes.forbidden, HttpStatusCode.Forbidden.value),
        ErrorCodeInfo(ErrorCodes.not_found, HttpStatusCode.NotFound.value),
        ErrorCodeInfo(ErrorCodes.unsupported_media_type, HttpStatusCode.UnsupportedMediaType.value),
        ErrorCodeInfo(ErrorCodes.rate_limited, HttpStatusCode.TooManyRequests.value),
        ErrorCodeInfo(ErrorCodes.request_timeout, HttpStatusCode.RequestTimeout.value),
        ErrorCodeInfo(ErrorCodes.payload_too_large, HttpStatusCode.PayloadTooLarge.value),
        ErrorCodeInfo(ErrorCodes.internal_error, HttpStatusCode.InternalServerError.value),
    ).sortedBy { it.code }

    val checkin: List<ErrorCodeInfo> = listOf(
        ErrorCodeInfo(ErrorCodes.invalid_json, HttpStatusCode.BadRequest.value),
        ErrorCodeInfo(ErrorCodes.invalid_qr_length, HttpStatusCode.BadRequest.value),
        ErrorCodeInfo(ErrorCodes.invalid_qr_format, HttpStatusCode.BadRequest.value),
        ErrorCodeInfo(ErrorCodes.empty_qr, HttpStatusCode.BadRequest.value),
        ErrorCodeInfo(ErrorCodes.invalid_or_expired_qr, HttpStatusCode.BadRequest.value),
        ErrorCodeInfo(ErrorCodes.invalid_club_id, HttpStatusCode.BadRequest.value),
        ErrorCodeInfo(ErrorCodes.checkin_invalid_payload, HttpStatusCode.BadRequest.value),
        ErrorCodeInfo(ErrorCodes.checkin_deny_reason_required, HttpStatusCode.BadRequest.value),
        ErrorCodeInfo(ErrorCodes.checkin_subject_not_found, HttpStatusCode.NotFound.value),
        ErrorCodeInfo(ErrorCodes.checkin_forbidden, HttpStatusCode.Forbidden.value),
        ErrorCodeInfo(ErrorCodes.entry_list_mismatch, HttpStatusCode.BadRequest.value),
        ErrorCodeInfo(ErrorCodes.list_not_found, HttpStatusCode.NotFound.value),
        ErrorCodeInfo(ErrorCodes.entry_not_found, HttpStatusCode.NotFound.value),
        ErrorCodeInfo(ErrorCodes.club_scope_mismatch, HttpStatusCode.Forbidden.value),
        ErrorCodeInfo(ErrorCodes.outside_arrival_window, HttpStatusCode.Conflict.value),
        ErrorCodeInfo(ErrorCodes.unable_to_mark, HttpStatusCode.Conflict.value),
    ).sortedBy { it.code }

    val booking: List<ErrorCodeInfo> = listOf(
        ErrorCodeInfo(ErrorCodes.table_not_available, HttpStatusCode.Conflict.value),
        ErrorCodeInfo(ErrorCodes.validation_error, HttpStatusCode.BadRequest.value),
        ErrorCodeInfo(ErrorCodes.idempotency_conflict, HttpStatusCode.Conflict.value),
        ErrorCodeInfo(ErrorCodes.missing_idempotency_key, HttpStatusCode.BadRequest.value),
        ErrorCodeInfo(ErrorCodes.hold_expired, HttpStatusCode.Gone.value),
        ErrorCodeInfo(ErrorCodes.invalid_state, HttpStatusCode.Conflict.value),
        ErrorCodeInfo(ErrorCodes.late_plus_one_expired, HttpStatusCode.Gone.value),
        ErrorCodeInfo(ErrorCodes.plus_one_already_used, HttpStatusCode.Conflict.value),
        ErrorCodeInfo(ErrorCodes.capacity_exceeded, HttpStatusCode.Conflict.value),
        ErrorCodeInfo(ErrorCodes.promoter_quota_exhausted, HttpStatusCode.Conflict.value),
    ).sortedBy { it.code }

    val guestLists: List<ErrorCodeInfo> = listOf(
        ErrorCodeInfo(ErrorCodes.guest_list_not_found, HttpStatusCode.NotFound.value),
        ErrorCodeInfo(ErrorCodes.guest_list_not_active, HttpStatusCode.Conflict.value),
        ErrorCodeInfo(ErrorCodes.guest_list_limit_exceeded, HttpStatusCode.Conflict.value),
        ErrorCodeInfo(ErrorCodes.bulk_parse_too_large, HttpStatusCode.PayloadTooLarge.value),
    ).sortedBy { it.code }

    val invitations: List<ErrorCodeInfo> = listOf(
        ErrorCodeInfo(ErrorCodes.invitation_invalid, HttpStatusCode.BadRequest.value),
        ErrorCodeInfo(ErrorCodes.invitation_revoked, HttpStatusCode.Gone.value),
        ErrorCodeInfo(ErrorCodes.invitation_expired, HttpStatusCode.Gone.value),
        ErrorCodeInfo(ErrorCodes.invitation_already_used, HttpStatusCode.Conflict.value),
        ErrorCodeInfo(ErrorCodes.invitation_forbidden, HttpStatusCode.Forbidden.value),
    ).sortedBy { it.code }

    val support: List<ErrorCodeInfo> = listOf(
        ErrorCodeInfo(ErrorCodes.support_ticket_not_found, HttpStatusCode.NotFound.value),
        ErrorCodeInfo(ErrorCodes.support_ticket_forbidden, HttpStatusCode.Forbidden.value),
        ErrorCodeInfo(ErrorCodes.support_ticket_closed, HttpStatusCode.Conflict.value),
    ).sortedBy { it.code }

    val admin: List<ErrorCodeInfo> = listOf(
        ErrorCodeInfo(ErrorCodes.club_not_found, HttpStatusCode.NotFound.value),
        ErrorCodeInfo(ErrorCodes.hall_not_found, HttpStatusCode.NotFound.value),
        ErrorCodeInfo(ErrorCodes.table_not_found, HttpStatusCode.NotFound.value),
        ErrorCodeInfo(ErrorCodes.hall_name_conflict, HttpStatusCode.Conflict.value),
        ErrorCodeInfo(ErrorCodes.table_number_conflict, HttpStatusCode.Conflict.value),
        ErrorCodeInfo(ErrorCodes.invalid_table_coords, HttpStatusCode.BadRequest.value),
        ErrorCodeInfo(ErrorCodes.invalid_capacity, HttpStatusCode.BadRequest.value),
    ).sortedBy { it.code }

    val codes: List<ErrorCodeInfo> = common + checkin + booking + guestLists + invitations + support + admin

    init {
        val duplicateCodes = codes
            .groupBy { it.code }
            .filterValues { it.size > 1 }
            .keys
        require(duplicateCodes.isEmpty()) {
            "Duplicate error codes in ErrorRegistry: ${duplicateCodes.sorted().joinToString(", ")}"
        }
    }
}
