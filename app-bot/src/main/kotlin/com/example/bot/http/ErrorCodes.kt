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

    const val guest_list_not_found: String = "guest_list_not_found"
    const val guest_list_not_active: String = "guest_list_not_active"
    const val guest_list_limit_exceeded: String = "guest_list_limit_exceeded"
    const val bulk_parse_too_large: String = "bulk_parse_too_large"

    const val invitation_invalid: String = "invitation_invalid"
    const val invitation_revoked: String = "invitation_revoked"
    const val invitation_expired: String = "invitation_expired"
    const val invitation_already_used: String = "invitation_already_used"
    const val invitation_forbidden: String = "invitation_forbidden"

    const val support_ticket_not_found: String = "support_ticket_not_found"
    const val support_ticket_forbidden: String = "support_ticket_forbidden"
    const val support_ticket_closed: String = "support_ticket_closed"

    const val checkin_forbidden: String = "checkin_forbidden"
    const val checkin_invalid_payload: String = "checkin_invalid_payload"
    const val checkin_subject_not_found: String = "checkin_subject_not_found"
    const val checkin_deny_reason_required: String = "checkin_deny_reason_required"

    // Booking (A3)
    const val table_not_available: String = "table_not_available"
    const val validation_error: String = "validation_error"
    const val idempotency_conflict: String = "idempotency_conflict"
    const val missing_idempotency_key: String = "missing_idempotency_key"
    const val hold_expired: String = "hold_expired"
    const val invalid_state: String = "invalid_state"
    const val late_plus_one_expired: String = "late_plus_one_expired"
    const val plus_one_already_used: String = "plus_one_already_used"
    const val capacity_exceeded: String = "capacity_exceeded"
    const val promoter_quota_exhausted: String = "promoter_quota_exhausted"
}
