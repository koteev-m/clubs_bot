package com.example.bot.opschat

import java.time.Instant

/**
 * High-level categories for operational notifications.
 */
enum class OpsNotificationCategory {
    BOOKINGS,
    CHECKIN,
    SUPPORT,
}

/**
 * Domain events that may be routed to an operational chat topic.
 */
enum class OpsNotificationEvent(val category: OpsNotificationCategory) {
    BOOKING_CREATED(OpsNotificationCategory.BOOKINGS),
    BOOKING_CANCELLED(OpsNotificationCategory.BOOKINGS),
    BOOKING_SEATED(OpsNotificationCategory.BOOKINGS),
    GUEST_ARRIVED(OpsNotificationCategory.CHECKIN),
    GUEST_DENIED(OpsNotificationCategory.CHECKIN),
    GUEST_LATE(OpsNotificationCategory.CHECKIN),
    QUESTION_FROM_USER(OpsNotificationCategory.SUPPORT),
}

/**
 * Notification payload in the operational domain.
 * Routing/sending is handled in other layers.
 */
data class OpsDomainNotification(
    val clubId: Long,
    val event: OpsNotificationEvent,
    val subjectId: String?,
    val occurredAt: Instant,
) {
    init {
        validateClubId(clubId)
    }
}

/**
 * Per-club routing configuration for operational chats/topics.
 */
data class ClubOpsChatConfig(
    val clubId: Long,
    val chatId: Long,
    val bookingsThreadId: Int?,
    val checkinThreadId: Int?,
    val supportThreadId: Int?,
    val updatedAt: Instant,
) {
    init {
        validateClubId(clubId)
        validateChatId(chatId)
        validateThreadId("bookingsThreadId", bookingsThreadId)
        validateThreadId("checkinThreadId", checkinThreadId)
        validateThreadId("supportThreadId", supportThreadId)
    }
}

/**
 * Command DTO for creating/updating operational chat config.
 */
data class ClubOpsChatConfigUpsert(
    val clubId: Long,
    val chatId: Long,
    val bookingsThreadId: Int?,
    val checkinThreadId: Int?,
    val supportThreadId: Int?,
) {
    init {
        validateClubId(clubId)
        validateChatId(chatId)
        validateThreadId("bookingsThreadId", bookingsThreadId)
        validateThreadId("checkinThreadId", checkinThreadId)
        validateThreadId("supportThreadId", supportThreadId)
    }
}

interface ClubOpsChatConfigRepository {
    suspend fun getByClubId(clubId: Long): ClubOpsChatConfig?

    suspend fun upsert(config: ClubOpsChatConfigUpsert): ClubOpsChatConfig
}

private fun validateClubId(clubId: Long) {
    require(clubId > 0) { "clubId must be positive" }
}

private fun validateChatId(chatId: Long) {
    require(chatId != 0L) { "chatId must not be zero" }
}

private fun validateThreadId(name: String, threadId: Int?) {
    if (threadId == null) return
    require(threadId > 0) { "$name must be positive when provided" }
}
