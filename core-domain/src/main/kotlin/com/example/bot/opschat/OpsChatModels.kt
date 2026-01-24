package com.example.bot.opschat

import java.time.Instant

/**
 * High-level categories for operational notifications.
 */
enum class OpsNotificationCategory {
    BOOKINGS,
    CHECKIN,
    GUEST_LISTS,
    SUPPORT,
    ALERTS,
}

/**
 * Domain events that may be routed to an operational chat topic.
 */
enum class OpsNotificationEvent(val category: OpsNotificationCategory) {
    NEW_BOOKING(OpsNotificationCategory.BOOKINGS),
    BOOKING_CREATED(OpsNotificationCategory.BOOKINGS),
    BOOKING_UPDATED(OpsNotificationCategory.BOOKINGS),
    BOOKING_CANCELLED(OpsNotificationCategory.BOOKINGS),
    BOOKING_SEATED(OpsNotificationCategory.BOOKINGS),
    GUEST_ARRIVED(OpsNotificationCategory.CHECKIN),
    GUEST_DENIED(OpsNotificationCategory.CHECKIN),
    GUEST_LATE(OpsNotificationCategory.CHECKIN),
    GUEST_LIST_CREATED(OpsNotificationCategory.GUEST_LISTS),
    QUESTION_FROM_USER(OpsNotificationCategory.SUPPORT),
    SUPPORT_QUESTION_CREATED(OpsNotificationCategory.SUPPORT),
    SYSTEM_ALERT(OpsNotificationCategory.ALERTS),
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
    val guestListsThreadId: Int?,
    val supportThreadId: Int?,
    val alertsThreadId: Int?,
    val updatedAt: Instant,
) {
    init {
        validateClubId(clubId)
        validateChatId(chatId)
        validateThreadId("bookingsThreadId", bookingsThreadId)
        validateThreadId("checkinThreadId", checkinThreadId)
        validateThreadId("guestListsThreadId", guestListsThreadId)
        validateThreadId("supportThreadId", supportThreadId)
        validateThreadId("alertsThreadId", alertsThreadId)
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
    val guestListsThreadId: Int?,
    val supportThreadId: Int?,
    val alertsThreadId: Int?,
) {
    init {
        validateClubId(clubId)
        validateChatId(chatId)
        validateThreadId("bookingsThreadId", bookingsThreadId)
        validateThreadId("checkinThreadId", checkinThreadId)
        validateThreadId("guestListsThreadId", guestListsThreadId)
        validateThreadId("supportThreadId", supportThreadId)
        validateThreadId("alertsThreadId", alertsThreadId)
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
