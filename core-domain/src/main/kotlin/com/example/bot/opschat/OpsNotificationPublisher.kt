package com.example.bot.opschat

import java.time.Instant

fun interface OpsNotificationPublisher {
    fun enqueue(notification: OpsDomainNotification)
}

object NoopOpsNotificationPublisher : OpsNotificationPublisher {
    override fun enqueue(notification: OpsDomainNotification) = Unit
}

fun OpsNotificationPublisher.enqueueSystemAlert(
    clubId: Long,
    subjectId: String? = null,
    occurredAt: Instant = Instant.now(),
) {
    enqueue(
        OpsDomainNotification(
            clubId = clubId,
            event = OpsNotificationEvent.SYSTEM_ALERT,
            subjectId = subjectId,
            occurredAt = occurredAt,
        ),
    )
}
