package com.example.bot.promoter.invites

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class InMemoryPromoterInviteRepository(
    private val isLegacyClub: (Long) -> Boolean = { true },
) : PromoterInviteRepository {
    private val storage = ConcurrentHashMap<Long, PromoterInvite>()
    private val seq = AtomicLong(0)

    override fun nextId(): Long = seq.incrementAndGet()

    override fun save(invite: PromoterInvite): PromoterInvite {
        check(isLegacyClub(invite.clubId)) { "Club unavailable" }
        val snapshot = invite.copySnapshot()
        storage[snapshot.id] = snapshot
        return snapshot
    }

    override fun findById(id: Long): PromoterInvite? = storage[id]?.takeIf { isLegacyClub(it.clubId) }?.copySnapshot()

    override fun listByPromoter(promoterId: Long): List<PromoterInvite> =
        storage.values
            .asSequence()
            .filter { it.promoterId == promoterId }
            .filter { isLegacyClub(it.clubId) }
            .map { it.copySnapshot() }
            .toList()

    override fun listByPromoterAndEvent(
        promoterId: Long,
        eventId: Long?,
    ): List<PromoterInvite> =
        storage.values
            .asSequence()
            .filter { it.promoterId == promoterId }
            .filter { eventId == null || it.eventId == eventId }
            .filter { isLegacyClub(it.clubId) }
            .map { it.copySnapshot() }
            .toList()

    override fun listByClub(clubId: Long): List<PromoterInvite> =
        storage.values
            .asSequence()
            .filter { it.clubId == clubId }
            .filter { isLegacyClub(it.clubId) }
            .map { it.copySnapshot() }
            .toList()
}
