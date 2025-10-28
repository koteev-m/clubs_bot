package com.example.bot.promo

import java.time.Instant
import java.util.UUID

/** Representation of a promo link that can be distributed by promoters. */
data class PromoLink(
    val id: Long,
    val promoterUserId: Long,
    val clubId: Long?,
    val utmSource: String,
    val utmMedium: String,
    val utmCampaign: String,
    val utmContent: String?,
    val createdAt: Instant,
)

sealed interface PromoLinkError {
    data object NotFound : PromoLinkError
}

sealed interface PromoLinkResult<out T> {
    data class Success<T>(val value: T) : PromoLinkResult<T>

    data class Failure(val error: PromoLinkError) : PromoLinkResult<Nothing>
}

interface PromoLinkRepository {
    suspend fun issueLink(
        promoterUserId: Long,
        clubId: Long?,
        utmSource: String,
        utmMedium: String,
        utmCampaign: String,
        utmContent: String?,
    ): PromoLink

    suspend fun get(id: Long): PromoLink?

    suspend fun listByPromoter(
        promoterUserId: Long,
        clubId: Long? = null,
    ): List<PromoLink>

    suspend fun deactivate(id: Long): PromoLinkResult<Unit>
}

/** Representation of attribution between bookings and promo links. */
data class PromoAttribution(
    val id: Long,
    val bookingId: UUID,
    val promoLinkId: Long,
    val promoterUserId: Long,
    val utmSource: String,
    val utmMedium: String,
    val utmCampaign: String,
    val utmContent: String?,
    val createdAt: Instant,
)

sealed interface PromoAttributionError {
    data object AlreadyAttributed : PromoAttributionError
}

sealed interface PromoAttributionResult<out T> {
    data class Success<T>(val value: T) : PromoAttributionResult<T>

    data class Failure(val error: PromoAttributionError) : PromoAttributionResult<Nothing>
}

interface PromoAttributionRepository {
    suspend fun attachUnique(
        bookingId: UUID,
        promoLinkId: Long,
        promoterUserId: Long,
        utmSource: String,
        utmMedium: String,
        utmCampaign: String,
        utmContent: String?,
    ): PromoAttributionResult<PromoAttribution>

    suspend fun findByBooking(bookingId: UUID): PromoAttribution?
}

/** Template for booking creation shortcuts. */
data class BookingTemplate(
    val id: Long,
    val promoterUserId: Long,
    val clubId: Long,
    val tableCapacityMin: Int,
    val notes: String?,
    val isActive: Boolean,
    val createdAt: Instant,
)

data class BookingTemplateSignature(val templateId: Long, val value: String)

sealed interface BookingTemplateError {
    data object NotFound : BookingTemplateError
}

sealed interface BookingTemplateResult<out T> {
    data class Success<T>(val value: T) : BookingTemplateResult<T>

    data class Failure(val error: BookingTemplateError) : BookingTemplateResult<Nothing>
}

interface BookingTemplateRepository {
    suspend fun create(
        promoterUserId: Long,
        clubId: Long,
        tableCapacityMin: Int,
        notes: String?,
    ): BookingTemplate

    suspend fun update(
        id: Long,
        tableCapacityMin: Int,
        notes: String?,
        isActive: Boolean,
    ): BookingTemplateResult<BookingTemplate>

    suspend fun deactivate(id: Long): BookingTemplateResult<Unit>

    suspend fun get(id: Long): BookingTemplate?

    suspend fun listByOwner(promoterUserId: Long): List<BookingTemplate>

    suspend fun listByClub(
        clubId: Long,
        onlyActive: Boolean = true,
    ): List<BookingTemplate>

    suspend fun applyTemplateSignature(id: Long): BookingTemplateResult<BookingTemplateSignature>
}
