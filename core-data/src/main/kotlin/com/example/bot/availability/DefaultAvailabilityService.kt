package com.example.bot.availability

import com.example.bot.data.booking.BookingHoldsTable
import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.BookingsTable
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.booking.TablesTable
import com.example.bot.data.db.withTxRetry
import com.example.bot.policy.CutoffPolicy
import com.example.bot.time.Club
import com.example.bot.time.ClubException
import com.example.bot.time.ClubHoliday
import com.example.bot.time.ClubHour
import com.example.bot.time.Event
import com.example.bot.time.OperatingRulesResolver
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.not
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class DefaultAvailabilityService(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) :
    AvailabilityService(
            UnsupportedAvailabilityRepository,
            OperatingRulesResolver(UnsupportedAvailabilityRepository, clock),
            CutoffPolicy(),
            clock,
        ) {
    override suspend fun listOpenNights(
        clubId: Long,
        limit: Int,
    ): List<NightDto> {
        val now = Instant.now(clock).atOffset(ZoneOffset.UTC)

        return withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                EventsTable
                    .selectAll()
                    .where { (EventsTable.clubId eq clubId) and (EventsTable.endAt greater now) }
                    .orderBy(EventsTable.startAt, SortOrder.ASC)
                    .limit(limit)
                    .map { row ->
                        val start = row[EventsTable.startAt].toInstant()
                        val end = row[EventsTable.endAt].toInstant()
                        val isSpecial = row[EventsTable.isSpecial]

                        val tz = "UTC"
                        val zone: ZoneId =
                            runCatching { ZoneId.of(tz) }.getOrDefault(ZoneOffset.UTC)

                        NightDto(
                            eventStartUtc = start,
                            eventEndUtc = end,
                            isSpecial = isSpecial,
                            arrivalByUtc = start,
                            openLocal = start.atZone(zone).toLocalDateTime(),
                            closeLocal = end.atZone(zone).toLocalDateTime(),
                            timezone = tz,
                        )
                    }
            }
        }
    }

    override suspend fun listFreeTables(
        clubId: Long,
        eventStartUtc: Instant,
    ): List<TableAvailabilityDto> {
        val start = eventStartUtc.atOffset(ZoneOffset.UTC)
        val now = Instant.now(clock).atOffset(ZoneOffset.UTC)

        return withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val eventRow =
                    EventsTable
                        .selectAll()
                        .where {
                            (EventsTable.clubId eq clubId) and (EventsTable.startAt eq start)
                        }
                        .limit(1)
                        .firstOrNull()
                        ?: return@newSuspendedTransaction emptyList()

                val eventId = eventRow[EventsTable.id]

                // EXISTS-подзапрос: забронированные столы
                val bookedExists =
                    exists(
                        BookingsTable
                            .selectAll()
                            .where {
                                (BookingsTable.eventId eq eventId) and
                                    (BookingsTable.tableId eq TablesTable.id) and
                                    (
                                        BookingsTable.status inList
                                            listOf(
                                                BookingStatus.BOOKED.name,
                                                BookingStatus.SEATED.name,
                                            )
                                    )
                            },
                    )

                // EXISTS-подзапрос: столы с активными холдами
                val heldExists =
                    exists(
                        BookingHoldsTable
                            .selectAll()
                            .where {
                                (BookingHoldsTable.eventId eq eventId) and
                                    (BookingHoldsTable.tableId eq TablesTable.id) and
                                    (BookingHoldsTable.expiresAt greater now)
                            },
                    )

                TablesTable
                    .selectAll()
                    .where {
                        (TablesTable.clubId eq clubId) and
                            (TablesTable.active eq true) and
                            not(bookedExists) and
                            not(heldExists)
                    }
                    .orderBy(TablesTable.tableNumber, SortOrder.ASC)
                    .map { row ->
                        val tableId = row[TablesTable.id]
                        val number = row[TablesTable.tableNumber]
                        val capacity = row[TablesTable.capacity]
                        val minDeposit = row[TablesTable.minDeposit].toMajorInt()
                        val zoneLabel = row[TablesTable.zoneId]?.let { "Zone $it" } ?: "Hall"

                        TableAvailabilityDto(
                            tableId = tableId,
                            tableNumber = number.toString(),
                            zone = zoneLabel,
                            capacity = capacity,
                            minDeposit = minDeposit,
                            status = TableStatus.FREE,
                        )
                    }
            }
        }
    }
}

private fun BigDecimal.toMajorInt(): Int {
    val normalized = stripTrailingZeros()
    if (normalized.scale() > 0) {
        error(
            "Table min_deposit has fractional part: $this",
        )
    }
    return normalized.toBigIntegerExact().intValueExact()
}

private object UnsupportedAvailabilityRepository : AvailabilityRepository {
    private fun unsupported(): Nothing =
        error(
            "DefaultAvailabilityService overrides AvailabilityService methods; " +
                "repository access is unsupported",
        )

    override suspend fun findClub(clubId: Long): Club? = unsupported()

    override suspend fun listClubHours(clubId: Long): List<ClubHour> = unsupported()

    override suspend fun listHolidays(
        clubId: Long,
        from: java.time.LocalDate,
        to: java.time.LocalDate,
    ): List<ClubHoliday> = unsupported()

    override suspend fun listExceptions(
        clubId: Long,
        from: java.time.LocalDate,
        to: java.time.LocalDate,
    ): List<ClubException> = unsupported()

    override suspend fun listEvents(
        clubId: Long,
        from: Instant,
        to: Instant,
    ): List<Event> = unsupported()

    override suspend fun findEvent(
        clubId: Long,
        startUtc: Instant,
    ): Event? = unsupported()

    override suspend fun listTables(clubId: Long): List<Table> = unsupported()

    override suspend fun listActiveHoldTableIds(
        eventId: Long,
        now: Instant,
    ): Set<Long> = unsupported()

    override suspend fun listActiveBookingTableIds(eventId: Long): Set<Long> = unsupported()
}
