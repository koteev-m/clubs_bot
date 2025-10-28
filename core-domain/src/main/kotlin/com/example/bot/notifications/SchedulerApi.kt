package com.example.bot.notifications

import java.time.OffsetDateTime

/** API used by campaign scheduler worker. */
interface SchedulerApi {
    /** Campaign status enumeration. */
    enum class Status { SCHEDULED, SENDING, PAUSED, DONE, FAILED }

    /** Campaign definition returned by the API. */
    data class Campaign(val id: Long, val status: Status, val scheduleCron: String?, val startsAt: OffsetDateTime?)

    /** Progress information for metrics. */
    data class Progress(val enqueued: Long, val total: Long)

    /** Lists campaigns that should be considered for processing. */
    suspend fun listActive(): List<Campaign>

    /** Switches campaign state to [Status.SENDING]. */
    suspend fun markSending(id: Long)

    /** Enqueues up to [limit] recipients into outbox for campaign. */
    suspend fun enqueueBatch(
        campaignId: Long,
        limit: Int,
    ): Int

    /** Returns progress for campaign. */
    suspend fun progress(campaignId: Long): Progress

    /** Marks campaign as fully processed. */
    suspend fun markDone(id: Long)
}
