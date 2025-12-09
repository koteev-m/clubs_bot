package com.example.bot.promoter.invites

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class InMemoryPromoterInviteRepository : PromoterInviteRepository {
    private val storage = ConcurrentHashMap<Long, PromoterInvite>()
    private val seq = AtomicLong(0)

    override fun nextId(): Long = seq.incrementAndGet()

    override fun save(invite: PromoterInvite): PromoterInvite {
        val snapshot = invite.copySnapshot()
        storage[snapshot.id] = snapshot
        return snapshot
    }

    override fun findById(id: Long): PromoterInvite? = storage[id]?.copySnapshot()

    override fun listByPromoterAndEvent(promoterId: Long, eventId: Long?): List<PromoterInvite> {
        return storage.values
            .asSequence()
            .filter { it.promoterId == promoterId }
            .filter { eventId == null || it.eventId == eventId }
            .map { it.copySnapshot() }
            .toList()
    }
}
