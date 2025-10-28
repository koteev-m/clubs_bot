package com.example.bot.booking

import com.example.bot.booking.legacy.BookingError as LegacyBookingError
import com.example.bot.booking.legacy.BookingService as LegacyBookingService
import com.example.bot.booking.legacy.BookingSummary as LegacyBookingSummary
import com.example.bot.booking.legacy.ConfirmInput as LegacyConfirmInput
import com.example.bot.booking.legacy.ConfirmRequest as LegacyConfirmRequest
import com.example.bot.booking.legacy.ConfirmResult as LegacyConfirmResult
import com.example.bot.booking.legacy.ContactInfo as LegacyContactInfo
import com.example.bot.booking.legacy.Either as LegacyEither
import com.example.bot.booking.legacy.HoldRequest as LegacyHoldRequest
import com.example.bot.booking.legacy.HoldResponse as LegacyHoldResponse
import com.example.bot.booking.legacy.InvoiceInfo as LegacyInvoiceInfo

/**
 * Type aliases that preserve the original booking API for legacy tests.
 */
@Suppress("EXPERIMENTAL_FEATURE_WARNING")
typealias BookingService = LegacyBookingService

@Suppress("EXPERIMENTAL_FEATURE_WARNING")
typealias HoldRequest = LegacyHoldRequest

@Suppress("EXPERIMENTAL_FEATURE_WARNING")
typealias HoldResponse = LegacyHoldResponse

@Suppress("EXPERIMENTAL_FEATURE_WARNING")
typealias ConfirmRequest = LegacyConfirmRequest

@Suppress("EXPERIMENTAL_FEATURE_WARNING")
typealias BookingSummary = LegacyBookingSummary

@Suppress("EXPERIMENTAL_FEATURE_WARNING")
typealias BookingError = LegacyBookingError

@Suppress("EXPERIMENTAL_FEATURE_WARNING")
typealias Either<L, R> = LegacyEither<L, R>

@Suppress("EXPERIMENTAL_FEATURE_WARNING")
typealias ConfirmInput = LegacyConfirmInput

@Suppress("EXPERIMENTAL_FEATURE_WARNING")
typealias ContactInfo = LegacyContactInfo

@Suppress("EXPERIMENTAL_FEATURE_WARNING")
typealias ConfirmResult = LegacyConfirmResult

@Suppress("EXPERIMENTAL_FEATURE_WARNING")
typealias InvoiceInfo = LegacyInvoiceInfo
