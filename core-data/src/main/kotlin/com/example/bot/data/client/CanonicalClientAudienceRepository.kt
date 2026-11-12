package com.example.bot.data.client

import java.time.Instant
import java.util.Collections

/** Canonical resource metadata only; never user authority or a persisted bot binding. */
class CanonicalClientAudienceSnapshot(
    val clientId: Long,
    val lifecycleVersion: Long,
    mappingCreatedAt: Map<Long, Instant>,
) {
    val mappingCreatedAt: Map<Long, Instant> = Collections.unmodifiableMap(HashMap(mappingCreatedAt))

    init {
        require(clientId > 0 && lifecycleVersion > 0)
        require(this.mappingCreatedAt.isNotEmpty() && this.mappingCreatedAt.keys.all { it > 0 })
    }

    /** Timestamps are comparison metadata, not durable mapping generations or revoke tombstones. */
    fun sameSnapshot(other: CanonicalClientAudienceSnapshot): Boolean =
        clientId == other.clientId &&
            lifecycleVersion == other.lifecycleVersion &&
            mappingCreatedAt == other.mappingCreatedAt
}

/** Blocking read: use an IO dispatcher. Does not read membership, grants, roles or bot credentials. */
fun interface CanonicalClientAudienceRepository {
    fun read(audience: ClientAudience.Client): CanonicalClientAudienceSnapshot?
}
