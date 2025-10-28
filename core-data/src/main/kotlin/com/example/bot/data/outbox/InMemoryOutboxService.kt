package com.example.bot.data.outbox

import com.example.bot.outbox.OutboxRecord
import com.example.bot.outbox.OutboxService
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * In-memory outbox service. In real application this would persist records in
 * a database table for asynchronous processing by a worker.
 */
class InMemoryOutboxService : OutboxService {
    val items: ConcurrentLinkedQueue<OutboxRecord> = ConcurrentLinkedQueue()

    override suspend fun enqueue(
        kind: String,
        chatId: Long,
        threadId: Long?,
        payload: String,
    ) {
        val record = OutboxRecord(UUID.randomUUID(), kind, chatId, threadId, payload)
        items.add(record)
    }
}
