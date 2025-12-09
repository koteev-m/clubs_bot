package com.example.bot.promoter.invites

interface PromoterInviteRepository {
    fun nextId(): Long
    fun save(invite: PromoterInvite): PromoterInvite
    fun findById(id: Long): PromoterInvite?
    fun listByPromoter(promoterId: Long): List<PromoterInvite>
    fun listByPromoterAndEvent(promoterId: Long, eventId: Long?): List<PromoterInvite>
    fun listByClub(clubId: Long): List<PromoterInvite>
}
