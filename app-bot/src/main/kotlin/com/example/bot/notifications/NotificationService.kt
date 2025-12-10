package com.example.bot.notifications

import com.example.bot.club.WaitlistEntry
import org.slf4j.LoggerFactory

/**
 * Abstraction for external notifications (push, Telegram, etc.) triggered by waitlist events.
 *
 * The contract calls [notifyEnqueuedGuest] only after a successful enqueue via /api/clubs/{clubId}/waitlist. Implementations
 * may be real (deliver messages) or lightweight no-op/logging variants for local/testing environments.
 */
interface NotificationService {
    suspend fun notifyEnqueuedGuest(entry: WaitlistEntry)
}

/**
 * Default logging-only notification service; it records enqueue events but does not send real pushes.
 */
class LoggingNotificationService : NotificationService {
    private val logger = LoggerFactory.getLogger(LoggingNotificationService::class.java)

    override suspend fun notifyEnqueuedGuest(entry: WaitlistEntry) {
        logger.info(
            "notification.enqueue waitlist_id={} user_id={} club_id={} event_id={}",
            entry.id,
            entry.userId,
            entry.clubId,
            entry.eventId,
        )
    }
}
