package com.example.bot.telemetry

import io.opentelemetry.api.trace.Span

enum class CheckinScanResult {
    SUCCESS,
    INVALID_JSON,
    INVALID_FORMAT,
    INVALID,
    EXPIRED,
    SCOPE_MISMATCH,
    LIST_NOT_FOUND,
    ENTRY_NOT_FOUND,
    ENTRY_LIST_MISMATCH,
    OUTSIDE_ARRIVAL_WINDOW,
    UNABLE_TO_MARK,
}

enum class BookingHoldResult {
    SUCCESS,
    OVERBOOKED,
    INVALID_STATE,
    CAPACITY_EXCEEDED,
    QUOTA_EXHAUSTED,
    RATE_LIMITED,
    REJECTED,
}

enum class BookingConfirmResult {
    SUCCESS,
    INVALID_STATE,
    EXPIRED,
    SCOPE_MISMATCH,
    NOT_FOUND,
    RATE_LIMITED,
    REJECTED,
}

fun Span.setCheckinResult(result: CheckinScanResult) {
    setAttribute("checkin.scan.result", result.name.lowercase())
}

fun Span.setBookingHoldResult(result: BookingHoldResult) {
    setAttribute("booking.hold.result", result.name.lowercase())
}

fun Span.setBookingConfirmResult(result: BookingConfirmResult) {
    setAttribute("booking.confirm.result", result.name.lowercase())
}
