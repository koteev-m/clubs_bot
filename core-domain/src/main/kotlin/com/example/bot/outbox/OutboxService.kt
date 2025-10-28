package com.example.bot.outbox

import java.time.Instant
import java.util.UUID

/**
 * Outbox service is used to enqueue notification events for asynchronous
 * processing. The implementation is expected to persist events in a durable
 * store so that an external worker can dispatch them later.
 */
interface OutboxService {
    /** Enqueues a notification. */
    suspend fun enqueue(
        kind: String,
        chatId: Long,
        threadId: Long?,
        payload: String,
    )
}

/** Simple DTO representing an outbox record. */
data class OutboxRecord(
    val id: UUID,
    val kind: String,
    val chatId: Long,
    val threadId: Long?,
    val payload: String,
    val status: String = "NEW",
    val nextAttemptAt: Instant = Instant.now(),
)
