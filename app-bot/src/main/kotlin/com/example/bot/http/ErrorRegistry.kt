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
    val version: Int = 1,
    val codes: List<ErrorCodeInfo>,
)

object ErrorRegistry {
    const val version: Int = 1
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
        ErrorCodeInfo(ErrorCodes.entry_list_mismatch, HttpStatusCode.BadRequest.value),
        ErrorCodeInfo(ErrorCodes.list_not_found, HttpStatusCode.NotFound.value),
        ErrorCodeInfo(ErrorCodes.entry_not_found, HttpStatusCode.NotFound.value),
        ErrorCodeInfo(ErrorCodes.club_scope_mismatch, HttpStatusCode.Forbidden.value),
        ErrorCodeInfo(ErrorCodes.outside_arrival_window, HttpStatusCode.Conflict.value),
        ErrorCodeInfo(ErrorCodes.unable_to_mark, HttpStatusCode.Conflict.value),
    ).sortedBy { it.code }

    val codes: List<ErrorCodeInfo> = common + checkin

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
