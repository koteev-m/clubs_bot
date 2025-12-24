package com.example.bot.data.club

import com.example.bot.club.Event
import com.example.bot.club.EventRepository
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.db.withRetriedTx
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.between
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import java.time.Instant
import java.time.ZoneOffset
import kotlin.ranges.ClosedRange

class EventRepositoryImpl(
    private val database: Database,
) : EventRepository {
    override suspend fun listByClub(
        clubId: Long,
        dateRange: ClosedRange<Instant>,
    ): List<Event> {
        val start = dateRange.start.atOffset(ZoneOffset.UTC)
        val end = dateRange.endInclusive.atOffset(ZoneOffset.UTC)
        return withRetriedTx(name = "events.list", readOnly = true, database = database) {
            EventsTable
                .selectAll()
                .where { (EventsTable.clubId eq clubId) and EventsTable.startAt.between(start, end) }
                .orderBy(EventsTable.startAt, SortOrder.ASC)
                .map { it.toEvent() }
        }
    }

    override suspend fun get(id: Long): Event? =
        withRetriedTx(name = "events.get", readOnly = true, database = database) {
            EventsTable
                .selectAll()
                .where { EventsTable.id eq id }
                .firstOrNull()
                ?.toEvent()
        }

    private fun ResultRow.toEvent(): Event =
        Event(
            id = this[EventsTable.id],
            clubId = this[EventsTable.clubId],
            title = this[EventsTable.title],
            startAt = this[EventsTable.startAt].toInstant(),
            endAt = this[EventsTable.endAt].toInstant(),
            isSpecial = this[EventsTable.isSpecial],
            posterUrl = this[EventsTable.posterUrl],
        )
}
