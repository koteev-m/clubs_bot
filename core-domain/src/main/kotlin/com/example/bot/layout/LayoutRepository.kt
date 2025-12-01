package com.example.bot.layout

import java.time.Instant

interface LayoutRepository {
    suspend fun getLayout(clubId: Long, eventId: Long?): ClubLayout?

    /**
     * Дата последнего значимого обновления для схемы зала/статусов (должна двигаться при изменении
     * геометрии, переименованиях, а также при смене статусов FREE/HOLD/BOOKED для данного eventId).
     */
    suspend fun lastUpdatedAt(clubId: Long, eventId: Long?): Instant?
}
