package com.example.bot.http

object ErrorCodes {
    const val unauthorized: String = "unauthorized"
    const val forbidden: String = "forbidden"
    const val not_found: String = "not_found"
    const val unsupported_media_type: String = "unsupported_media_type"
    const val rate_limited: String = "rate_limited"
    const val request_timeout: String = "request_timeout"
    const val payload_too_large: String = "payload_too_large"
    const val internal_error: String = "internal_error"

    const val invalid_json: String = "invalid_json"
    const val invalid_qr_length: String = "invalid_qr_length"
    const val invalid_qr_format: String = "invalid_qr_format"
    const val empty_qr: String = "empty_qr"
    const val invalid_or_expired_qr: String = "invalid_or_expired_qr"
    const val invalid_club_id: String = "invalid_club_id"
    const val list_not_found: String = "list_not_found"
    const val entry_not_found: String = "entry_not_found"
    const val entry_list_mismatch: String = "entry_list_mismatch"
    const val club_scope_mismatch: String = "club_scope_mismatch"
    const val outside_arrival_window: String = "outside_arrival_window"
    const val unable_to_mark: String = "unable_to_mark"
}
