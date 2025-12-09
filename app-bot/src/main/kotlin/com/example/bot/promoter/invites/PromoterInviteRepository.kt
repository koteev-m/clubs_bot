package com.example.bot.promoter.invites

interface PromoterInviteRepository {
    fun nextId(): Long
    fun save(invite: PromoterInvite): PromoterInvite
    fun findById(id: Long): PromoterInvite?
    fun listByPromoterAndEvent(promoterId: Long, eventId: Long?): List<PromoterInvite>
}
