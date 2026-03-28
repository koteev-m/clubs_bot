package com.example.bot.availability

import com.example.bot.data.booking.BookingHoldsTable
import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.BookingsTable
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.booking.TablesTable
import com.example.bot.data.db.Clubs
import com.example.bot.data.db.withRetriedTx
import com.example.bot.policy.CutoffPolicy
import com.example.bot.time.Club
import com.example.bot.time.ClubException
import com.example.bot.time.ClubHoliday
import com.example.bot.time.ClubHour
import com.example.bot.time.Event
import com.example.bot.time.OperatingRulesResolver
import io.micrometer.core.instrument.MeterRegistry
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DefaultAvailabilityService(
    db: Database,
    clock: Clock = Clock.systemUTC(),
    nightsTtl: Duration = Duration.ofSeconds(60),
    tablesTtl: Duration = Duration.ofSeconds(15),
    meterRegistry: MeterRegistry? = null,
) : AvailabilityService(
        repository = DatabaseAvailabilityRepository(db),
        rulesResolver = OperatingRulesResolver(DatabaseAvailabilityRepository(db), clock),
        cutoffPolicy = CutoffPolicy(),
        clock = clock,
        nightsTtl = nightsTtl,
        tablesTtl = tablesTtl,
        meterRegistry = meterRegistry,
    ), AvailabilityCacheInvalidator

private class DatabaseAvailabilityRepository(
    private val db: Database,
) : AvailabilityRepository {
    override suspend fun findClub(clubId: Long): Club? =
        withRetriedTx(name = "availability.findClub", readOnly = true, database = db) {
            Clubs
                .selectAll()
                .where { Clubs.id eq clubId.toInt() }
                .limit(1)
                .firstOrNull()
                ?.let { Club(clubId, it[Clubs.timezone]) }
        }

    override suspend fun listClubHours(clubId: Long): List<ClubHour> = emptyList()

    override suspend fun listHolidays(
        clubId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<ClubHoliday> = emptyList()

    override suspend fun listExceptions(
        clubId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<ClubException> = emptyList()

    override suspend fun listEvents(
        clubId: Long,
        from: Instant,
        to: Instant,
    ): List<Event> {
        val fromOffset = from.atOffset(ZoneOffset.UTC)
        val toOffset = to.atOffset(ZoneOffset.UTC)
        return withRetriedTx(name = "availability.listEvents", readOnly = true, database = db) {
            EventsTable
                .selectAll()
                .where {
                    (EventsTable.clubId eq clubId) and
                        (EventsTable.endAt greater fromOffset) and
                        (EventsTable.startAt lessEq toOffset)
                }.orderBy(EventsTable.startAt, SortOrder.ASC)
                .map { row ->
                    Event(
                        id = row[EventsTable.id],
                        clubId = row[EventsTable.clubId],
                        startUtc = row[EventsTable.startAt].toInstant(),
                        endUtc = row[EventsTable.endAt].toInstant(),
                        isSpecial = row[EventsTable.isSpecial],
                    )
                }
        }
    }

    override suspend fun findEvent(
        clubId: Long,
        startUtc: Instant,
    ): Event? {
        val start = startUtc.atOffset(ZoneOffset.UTC)
        return withRetriedTx(name = "availability.findEvent", readOnly = true, database = db) {
            EventsTable
                .selectAll()
                .where { (EventsTable.clubId eq clubId) and (EventsTable.startAt eq start) }
                .limit(1)
                .firstOrNull()
                ?.let { row ->
                    Event(
                        id = row[EventsTable.id],
                        clubId = row[EventsTable.clubId],
                        startUtc = row[EventsTable.startAt].toInstant(),
                        endUtc = row[EventsTable.endAt].toInstant(),
                        isSpecial = row[EventsTable.isSpecial],
                    )
                }
        }
    }

    override suspend fun listTables(clubId: Long): List<Table> =
        withRetriedTx(name = "availability.listTables", readOnly = true, database = db) {
            TablesTable
                .selectAll()
                .where { TablesTable.clubId eq clubId }
                .orderBy(TablesTable.tableNumber, SortOrder.ASC)
                .map { row ->
                    Table(
                        id = row[TablesTable.id],
                        number = row[TablesTable.tableNumber].toString(),
                        zone = row[TablesTable.zoneId]?.let { "Zone $it" } ?: "Hall",
                        capacity = row[TablesTable.capacity],
                        minDeposit = row[TablesTable.minDeposit].toMajorInt(),
                        active = row[TablesTable.active],
                    )
                }
        }

    override suspend fun listActiveHoldTableIds(
        eventId: Long,
        now: Instant,
    ): Set<Long> {
        val current = now.atOffset(ZoneOffset.UTC)
        return withRetriedTx(name = "availability.listActiveHoldTableIds", readOnly = true, database = db) {
            BookingHoldsTable
                .selectAll()
                .where { (BookingHoldsTable.eventId eq eventId) and (BookingHoldsTable.expiresAt greater current) }
                .map { it[BookingHoldsTable.tableId] }
                .toSet()
        }
    }

    override suspend fun listActiveBookingTableIds(eventId: Long): Set<Long> =
        withRetriedTx(name = "availability.listActiveBookingTableIds", readOnly = true, database = db) {
            BookingsTable
                .selectAll()
                .where {
                    (BookingsTable.eventId eq eventId) and
                        (BookingsTable.status inList listOf(BookingStatus.BOOKED.name, BookingStatus.SEATED.name))
                }
                .map { it[BookingsTable.tableId] }
                .toSet()
        }
}

private fun BigDecimal.toMajorInt(): Int {
    val normalized = stripTrailingZeros()
    if (normalized.scale() > 0) {
        error("Table min_deposit has fractional part: $this")
    }
    return normalized.toBigIntegerExact().intValueExact()
}
