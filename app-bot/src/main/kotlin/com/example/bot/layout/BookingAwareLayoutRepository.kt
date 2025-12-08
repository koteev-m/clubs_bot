package com.example.bot.layout

import com.example.bot.booking.a3.BookingState
import java.time.Instant

/**
 * Layout repository decorator that overlays table statuses from booking state.
 */
class BookingAwareLayoutRepository(
    private val delegate: LayoutRepository,
    private val bookingState: BookingState,
) : LayoutRepository {
    override suspend fun getLayout(
        clubId: Long,
        eventId: Long?,
    ): ClubLayout? {
        val base = delegate.getLayout(clubId, eventId) ?: return null
        val updatedTables =
            eventId?.let {
                base.tables.map { table ->
                    val status = bookingState.tableStatus(eventId, table.id)
                    table.copy(status = status)
                }
            } ?: base.tables

        return base.copy(tables = updatedTables)
    }

    override suspend fun lastUpdatedAt(
        clubId: Long,
        eventId: Long?,
    ): Instant? {
        val base = delegate.lastUpdatedAt(clubId, eventId)
        val bookingUpdated = eventId?.let { bookingState.lastUpdatedAt(it) }
        return listOfNotNull(base, bookingUpdated).maxOrNull()
    }
}
