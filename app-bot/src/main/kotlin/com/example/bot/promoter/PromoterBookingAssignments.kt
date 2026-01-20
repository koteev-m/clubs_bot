package com.example.bot.promoter

import java.util.concurrent.ConcurrentHashMap

class PromoterBookingAssignments {
    private val byEntryId = ConcurrentHashMap<Long, Long>()

    fun assignEntry(entryId: Long, bookingId: Long): Boolean {
        return byEntryId.putIfAbsent(entryId, bookingId) == null
    }

    fun findBookingForEntry(entryId: Long): Long? = byEntryId[entryId]
}
