package com.example.bot.owner

import com.example.bot.booking.a3.Booking
import com.example.bot.booking.a3.BookingState
import com.example.bot.booking.a3.BookingStatus
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListRepository
import com.example.bot.clubs.EventsRepository
import com.example.bot.layout.LayoutRepository
import com.example.bot.layout.Table
import com.example.bot.layout.Zone
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private const val LOW_OCCUPANCY_THRESHOLD = 0.5
private const val HIGH_NOSHOW_THRESHOLD = 0.4
private const val WEAK_PROMOTER_NOSHOW_THRESHOLD = 0.5
private const val MIN_PROMOTER_INVITED_FOR_ALERT = 5

/** Max events exposed in tables.byEvent; totals are always over all events. */
private const val MAX_EVENTS_IN_BY_EVENT = 10

class OwnerHealthServiceImpl(
    private val layoutRepository: LayoutRepository,
    private val eventsRepository: EventsRepository,
    private val bookingState: BookingState,
    private val guestListRepository: GuestListRepository,
    private val clock: Clock = Clock.systemUTC(),
) : OwnerHealthService {
    override suspend fun healthForClub(request: OwnerHealthRequest): OwnerHealthSnapshot? {
        val (fromInclusive, toExclusive) = currentWindow(request)
        val events = eventsRepository.list(clubId = request.clubId, city = null, from = fromInclusive, to = toExclusive, offset = 0, limit = 200)
        if (events.isEmpty()) {
            return emptySnapshot(request, fromInclusive, toExclusive)
        }

        val metaGeneratedAt = Instant.now(clock)
        val allEventSummaries = mutableListOf<EventTablesHealth>()
        val zoneAggregates = mutableMapOf<String, ZoneAccumulator>()

        var totalCapacity = 0
        var totalBooked = 0
        var incomplete = false

        val guestListsByEvent = loadGuestEntries(request.clubId, events.map { it.id })

        val bookingsByEvent = events.associate { event ->
            val bookings = bookingState.findBookingsForEvent(request.clubId, event.id)
            event.id to bookings
        }

        for (event in events) {
            val layout = layoutRepository.getLayout(request.clubId, event.id)
            if (layout == null) {
                incomplete = true
                continue
            }
            val capacity = layout.tables.sumOf(Table::capacity)
            val bookings = bookingsByEvent[event.id].orEmpty().filter { it.status == BookingStatus.BOOKED }
            val bookedSeats = bookings.sumOf { it.guestCount }

            totalCapacity += capacity
            totalBooked += bookedSeats

            val zoneIdByTableId = layout.tables.associate { it.id to it.zoneId }
            val bookedByZone = mutableMapOf<String, Int>()
            for (booking in bookings) {
                val zoneId = zoneIdByTableId[booking.tableId] ?: continue
                bookedByZone[zoneId] = (bookedByZone[zoneId] ?: 0) + booking.guestCount
            }
            val byZone = layout.tables.groupBy(Table::zoneId)
            byZone.forEach { (zoneId, tables) ->
                val zoneCapacity = tables.sumOf(Table::capacity)
                val bookedInZone = bookedByZone[zoneId] ?: 0
                val accumulator = zoneAggregates.getOrPut(zoneId) { ZoneAccumulator(zoneId, layout.zones) }
                accumulator.capacity += zoneCapacity
                accumulator.booked += bookedInZone
            }

            allEventSummaries +=
                EventTablesHealth(
                    eventId = event.id,
                    startUtc = event.startUtc.toString(),
                    title = event.title,
                    totalTableCapacity = capacity,
                    bookedSeats = bookedSeats,
                    occupancyRate = safeRate(bookedSeats, capacity),
                )
        }

        val zoneHealth = zoneAggregates.values
            .sortedWith(compareBy<ZoneAccumulator> { it.zoneId }.thenBy { it.zoneName ?: "" })
            .map { it.toHealth() }

        val byEvent = allEventSummaries.sortedBy { it.startUtc }.take(MAX_EVENTS_IN_BY_EVENT)

        val attendance = attendanceFor(events, bookingsByEvent, guestListsByEvent)
        val promoters = promotersFor(bookingsByEvent)
        val breakdowns = weekdayBreakdownFor(events, bookingsByEvent, guestListsByEvent, allEventSummaries)
        val alerts = alertsFor(allEventSummaries, attendance.events, promoters.byPromoter)

        val tablesHealth =
            TablesHealth(
                eventsCount = events.size,
                totalTableCapacity = totalCapacity,
                bookedSeats = totalBooked,
                occupancyRate = safeRate(totalBooked, totalCapacity),
                byZone = zoneHealth,
                byEvent = byEvent,
            )

        val trend =
            trendFor(
                request = request,
                currentTablesRate = tablesHealth.occupancyRate,
                attendance = attendance,
                promoters = promoters.totals,
            )

        val snapshot =
            OwnerHealthSnapshot(
                clubId = request.clubId,
                period =
                    OwnerHealthPeriodWindow(
                        type = request.period.name.lowercase(),
                        from = fromInclusive.toString(),
                        to = toExclusive.toString(),
                    ),
                meta =
                    OwnerHealthMeta(
                        generatedAt = metaGeneratedAt.toString(),
                        granularity = request.granularity.name.lowercase(),
                        eventsCount = events.size,
                        hasIncompleteData = incomplete,
                    ),
                tables = tablesHealth,
                attendance = attendance.channelsView,
                promoters = promoters,
                alerts = alerts,
                trend = trend,
                breakdowns = breakdowns,
            )

        return when (request.granularity) {
            OwnerHealthGranularity.SUMMARY -> snapshot.toSummary()
            OwnerHealthGranularity.FULL -> snapshot
        }
    }

    private fun currentWindow(request: OwnerHealthRequest): Pair<Instant, Instant> {
        val now = request.now
        val duration = periodDuration(request.period)
        return now.minus(duration) to now
    }

    /**
     * Logical duration for owner-health aggregation for a given period.
     *
     * New period types (e.g. DAY, CUSTOM_RANGE) should be wired here so that currentWindow,
     * trend baselines, and empty snapshots remain consistent.
     */
    private fun periodDuration(period: OwnerHealthPeriod): Duration =
        when (period) {
            OwnerHealthPeriod.WEEK -> Duration.ofDays(7)
            OwnerHealthPeriod.MONTH -> Duration.ofDays(30)
        }

    private fun weekdayBreakdownFor(
        events: List<com.example.bot.clubs.Event>,
        bookingsByEvent: Map<Long, List<Booking>>,
        guestListsByEvent: Map<Long, List<com.example.bot.club.GuestListEntry>>,
        eventSummaries: List<EventTablesHealth>,
    ): OwnerHealthBreakdowns {
        val summariesByEventId = eventSummaries.associateBy { it.eventId }
        val eventsByDay = events.groupBy { event -> event.startUtc.atOffset(ZoneOffset.UTC).dayOfWeek }

        val byWeekday =
            eventsByDay.entries
                .sortedBy { it.key }
                .map { (dayOfWeek, dayEvents) ->
                    val eventIds = dayEvents.map { it.id }.toSet()

                    val dayTablesCapacity = dayEvents.sumOf { summariesByEventId[it.id]?.totalTableCapacity ?: 0 }
                    val dayTablesBooked = dayEvents.sumOf { summariesByEventId[it.id]?.bookedSeats ?: 0 }

                    val attendanceDay =
                        attendanceFor(
                            events = dayEvents,
                            bookingsByEvent = bookingsByEvent,
                            guestListsByEvent = guestListsByEvent,
                        )

                    val promotersDay = promotersFor(bookingsByEvent.filterKeys { it in eventIds })

                    WeekdayHealth(
                        dayOfWeek = dayOfWeek.name,
                        eventsCount = dayEvents.size,
                        tables =
                            WeekdayTablesHealth(
                                totalTableCapacity = dayTablesCapacity,
                                bookedSeats = dayTablesBooked,
                                occupancyRate = safeRate(dayTablesBooked, dayTablesCapacity),
                            ),
                        attendance =
                            WeekdayAttendanceHealth(
                                bookings = attendanceDay.bookings,
                                guestLists = attendanceDay.guestLists,
                            ),
                        promoters = promotersDay.totals,
                    )
                }

        return OwnerHealthBreakdowns(byWeekday = byWeekday)
    }

    private suspend fun loadGuestEntries(clubId: Long, eventIds: List<Long>): Map<Long, List<com.example.bot.club.GuestListEntry>> {
        val result = mutableMapOf<Long, MutableList<com.example.bot.club.GuestListEntry>>()
        val lists = loadGuestLists(clubId)
        lists.filter { it.eventId in eventIds }.forEach { list ->
            val entries = loadEntriesForList(list.id)
            val acc = result.getOrPut(list.eventId) { mutableListOf() }
            acc += entries
        }
        return result.mapValues { it.value.toList() }
    }

    private suspend fun loadGuestLists(clubId: Long): List<com.example.bot.club.GuestList> {
        val pageSize = 100
        val acc = mutableListOf<com.example.bot.club.GuestList>()
        var page = 0
        while (true) {
            val batch = guestListRepository.listListsByClub(clubId, page, pageSize)
            acc += batch
            if (batch.size < pageSize) break
            page += 1
        }
        return acc
    }

    private suspend fun loadEntriesForList(listId: Long): List<com.example.bot.club.GuestListEntry> {
        val pageSize = 100
        val acc = mutableListOf<com.example.bot.club.GuestListEntry>()
        var page = 0
        while (true) {
            val batch = guestListRepository.listEntries(listId, page, pageSize)
            acc += batch
            if (batch.size < pageSize) break
            page += 1
        }
        return acc
    }

    private data class AttendanceAggregates(
        val bookings: AttendanceChannel,
        val guestLists: AttendanceChannel,
        val direct: AttendanceChannel,
        val promoterBookings: AttendanceChannel,
        val events: List<EventAttendance>,
    ) {
        val channels: AttendanceChannels
            get() =
                AttendanceChannels(
                    directBookings = direct,
                    promoterBookings = promoterBookings,
                    guestLists = guestLists,
                )

        val channelsView: AttendanceHealth
            get() =
                AttendanceHealth(
                    bookings = bookings,
                    guestLists = guestLists,
                    channels = channels,
                )
    }

    private data class EventAttendance(
        val eventId: Long,
        val planned: Int,
        val arrived: Int,
    )

    private fun attendanceFor(
        events: List<com.example.bot.clubs.Event>,
        bookingsByEvent: Map<Long, List<Booking>>,
        guestListsByEvent: Map<Long, List<com.example.bot.club.GuestListEntry>>,
    ): AttendanceAggregates {
        var bookingsPlanned = 0
        var bookingsArrived = 0
        var promoterPlanned = 0
        var promoterArrived = 0
        var directPlanned = 0
        var directArrived = 0

        var guestPlanned = 0
        var guestArrived = 0

        val perEventAttendance = mutableListOf<EventAttendance>()

        for (event in events) {
            val bookings = bookingsByEvent[event.id].orEmpty().filter { it.status == BookingStatus.BOOKED }
            val bookingsPlannedEvent = bookings.sumOf { it.guestCount }
            // TODO: Booking check-in semantics are not available; arrived guests per booking are assumed to be zero.
            val bookingsArrivedEvent = 0

            val promoterBookings = bookings.filter { it.promoterId != null }
            val promoterPlannedEvent = promoterBookings.sumOf { it.guestCount }
            val promoterArrivedEvent = 0

            val directBookings = bookings.filter { it.promoterId == null }
            val directPlannedEvent = directBookings.sumOf { it.guestCount }
            val directArrivedEvent = 0

            val guestEntries = guestListsByEvent[event.id].orEmpty()
            val plannedGuestsEvent =
                guestEntries.filterNot { it.status == GuestListEntryStatus.EXPIRED }.sumOf { it.guestsCount }
            val arrivedGuestsEvent = guestEntries.filter { it.status == GuestListEntryStatus.CHECKED_IN }.sumOf { it.guestsCount }

            bookingsPlanned += bookingsPlannedEvent
            bookingsArrived += bookingsArrivedEvent

            promoterPlanned += promoterPlannedEvent
            promoterArrived += promoterArrivedEvent

            directPlanned += directPlannedEvent
            directArrived += directArrivedEvent

            guestPlanned += plannedGuestsEvent
            guestArrived += arrivedGuestsEvent

            perEventAttendance +=
                EventAttendance(
                    eventId = event.id,
                    planned = bookingsPlannedEvent + plannedGuestsEvent,
                    arrived = bookingsArrivedEvent + arrivedGuestsEvent,
                )
        }

        val bookingsChannel = attendanceChannel(bookingsPlanned, bookingsArrived)
        val guestListChannel = attendanceChannel(guestPlanned, guestArrived)
        val directChannel = attendanceChannel(directPlanned, directArrived)
        val promoterChannel = attendanceChannel(promoterPlanned, promoterArrived)

        return AttendanceAggregates(
            bookings = bookingsChannel,
            guestLists = guestListChannel,
            direct = directChannel,
            promoterBookings = promoterChannel,
            events = perEventAttendance,
        )
    }

    private fun promotersFor(
        bookingsByEvent: Map<Long, List<Booking>>,
    ): PromotersHealth {
        val promoterMap = mutableMapOf<Long, PromoterAccumulator>()

        bookingsByEvent.values
            .flatten()
            .asSequence()
            .filter { it.status == BookingStatus.BOOKED && it.promoterId != null }
            .forEach { booking ->
                val promoterId = booking.promoterId!!
                val acc = promoterMap.getOrPut(promoterId) { PromoterAccumulator(promoterId) }
                acc.invited += booking.guestCount
                // TODO: when booking check-ins become available, also increment arrived here
            }

        val byPromoter = promoterMap.values
            .map { it.toHealth() }
            .sortedWith(compareByDescending<PromoterHealth> { it.arrivedGuests }.thenByDescending { it.invitedGuests }.thenBy { it.promoterId })

        val totalsInvited = byPromoter.sumOf { it.invitedGuests }
        val totalsArrived = byPromoter.sumOf { it.arrivedGuests }
        val totalsNoShow = (totalsInvited - totalsArrived).coerceAtLeast(0)
        val totals =
            PromoterTotals(
                invitedGuests = totalsInvited,
                arrivedGuests = totalsArrived,
                noShowGuests = totalsNoShow,
                noShowRate = safeRate(totalsNoShow, totalsInvited),
            )

        val topByArrived = byPromoter.sortedByDescending { it.arrivedGuests }.take(5).map { it.promoterId }
        val topByInvited = byPromoter.sortedByDescending { it.invitedGuests }.take(5).map { it.promoterId }

        val top = PromoterTop(byArrivedGuests = topByArrived, byInvitedGuests = topByInvited)

        return PromotersHealth(totals = totals, byPromoter = byPromoter, top = top)
    }

    private fun alertsFor(
        events: List<EventTablesHealth>,
        attendance: List<EventAttendance>,
        promoters: List<PromoterHealth>,
    ): OwnerHealthAlerts {
        val lowOccupancy =
            events
                .filter { it.occupancyRate < LOW_OCCUPANCY_THRESHOLD }
                .sortedBy { it.startUtc }
                .take(10)
                .map {
                    AlertEvent(
                        eventId = it.eventId,
                        title = it.title,
                        startUtc = it.startUtc,
                        occupancyRate = it.occupancyRate,
                    )
                }

        val attendanceByEvent = attendance.associateBy { it.eventId }
        val highNoShow =
            events
                .mapNotNull { event ->
                    val att = attendanceByEvent[event.eventId] ?: return@mapNotNull null
                    val noShow = (att.planned - att.arrived).coerceAtLeast(0)
                    val rate = safeRate(noShow, att.planned)
                    if (rate > HIGH_NOSHOW_THRESHOLD) {
                        AlertEvent(
                            eventId = event.eventId,
                            title = event.title,
                            startUtc = event.startUtc,
                            noShowRate = rate,
                        )
                    } else {
                        null
                    }
                }
                .sortedBy { it.startUtc }
                .take(10)

        val weakPromoters =
            promoters
                .filter { it.invitedGuests >= MIN_PROMOTER_INVITED_FOR_ALERT && it.noShowRate > WEAK_PROMOTER_NOSHOW_THRESHOLD }
                .sortedWith(compareByDescending<PromoterHealth> { it.noShowRate }.thenByDescending { it.invitedGuests })
                .take(10)
                .map { promoter ->
                    AlertPromoter(
                        promoterId = promoter.promoterId,
                        name = promoter.name,
                        invitedGuests = promoter.invitedGuests,
                        noShowRate = promoter.noShowRate,
                    )
                }

        return OwnerHealthAlerts(lowOccupancyEvents = lowOccupancy, highNoShowEvents = highNoShow, weakPromoters = weakPromoters)
    }

    private suspend fun trendFor(
        request: OwnerHealthRequest,
        currentTablesRate: Double,
        attendance: AttendanceAggregates,
        promoters: PromoterTotals,
    ): OwnerHealthTrend {
        val (currentFrom, currentTo) = currentWindow(request)
        val previousTo = currentFrom
        val previousFrom = previousTo.minus(periodDuration(request.period))

        val previousEvents =
            runCatching {
                eventsRepository.list(
                    clubId = request.clubId,
                    city = null,
                    from = previousFrom,
                    to = previousTo,
                    offset = 0,
                    limit = 200,
                )
            }.getOrElse { emptyList() }

        val bookingsByEvent = previousEvents.associate { event -> event.id to bookingState.findBookingsForEvent(request.clubId, event.id) }
        val guestLists = runCatching { loadGuestEntries(request.clubId, previousEvents.map { it.id }) }.getOrElse { emptyMap() }
        val promotersPrev = promotersFor(bookingsByEvent)
        val attendancePrev = attendanceFor(previousEvents, bookingsByEvent, guestLists)
        var tablesPrevCapacity = 0
        var tablesPrevBooked = 0
        for (event in previousEvents) {
            val layout = layoutRepository.getLayout(request.clubId, event.id)
            tablesPrevCapacity += layout?.tables?.sumOf(Table::capacity) ?: 0
            tablesPrevBooked += bookingsByEvent[event.id].orEmpty().filter { it.status == BookingStatus.BOOKED }.sumOf { it.guestCount }
        }

        val prevTableRate = safeRate(tablesPrevBooked, tablesPrevCapacity)
        val prevBookingsNoShow = attendancePrev.bookings.noShowGuests
        val prevBookingsPlanned = attendancePrev.bookings.plannedGuests
        val prevGuestNoShow = attendancePrev.guestLists.noShowGuests
        val prevGuestPlanned = attendancePrev.guestLists.plannedGuests
        val prevPromoterRate = promotersPrev.totals.noShowRate

        return OwnerHealthTrend(
            baselinePeriod = BaselinePeriod(from = previousFrom.toString(), to = previousTo.toString()),
            tables = TableTrend(occupancyRate = rateDelta(currentTablesRate, prevTableRate)),
            attendance =
                AttendanceTrend(
                    noShowRateBookings = rateDelta(attendance.bookings.noShowRate, safeRate(prevBookingsNoShow, prevBookingsPlanned)),
                    noShowRateGuestLists = rateDelta(attendance.guestLists.noShowRate, safeRate(prevGuestNoShow, prevGuestPlanned)),
                ),
            promoters = PromoterTrend(noShowRate = rateDelta(promoters.noShowRate, prevPromoterRate)),
        )
    }

    private fun attendanceChannel(planned: Int, arrived: Int): AttendanceChannel {
        val noShow = (planned - arrived).coerceAtLeast(0)
        return AttendanceChannel(plannedGuests = planned, arrivedGuests = arrived, noShowGuests = noShow, noShowRate = safeRate(noShow, planned))
    }

    private fun OwnerHealthSnapshot.toSummary(): OwnerHealthSnapshot =
        copy(
            tables = tables.copy(byZone = emptyList(), byEvent = emptyList()),
            promoters = promoters.copy(byPromoter = emptyList(), top = PromoterTop(byArrivedGuests = emptyList(), byInvitedGuests = emptyList())),
            alerts = alerts.copy(lowOccupancyEvents = alerts.lowOccupancyEvents.take(1), highNoShowEvents = alerts.highNoShowEvents.take(1), weakPromoters = alerts.weakPromoters.take(1)),
            breakdowns = breakdowns?.copy(byWeekday = emptyList()),
        )

    private fun emptySnapshot(request: OwnerHealthRequest, from: Instant, to: Instant): OwnerHealthSnapshot {
        val zeroChannel = AttendanceChannel(0, 0, 0, 0.0)
        val baselineFrom = from.minus(periodDuration(request.period))
        return OwnerHealthSnapshot(
            clubId = request.clubId,
            period = OwnerHealthPeriodWindow(type = request.period.name.lowercase(), from = from.toString(), to = to.toString()),
            meta = OwnerHealthMeta(generatedAt = Instant.now(clock).toString(), granularity = request.granularity.name.lowercase(), eventsCount = 0, hasIncompleteData = false),
            tables = TablesHealth(eventsCount = 0, totalTableCapacity = 0, bookedSeats = 0, occupancyRate = 0.0, byZone = emptyList(), byEvent = emptyList()),
            attendance = AttendanceHealth(bookings = zeroChannel, guestLists = zeroChannel, channels = AttendanceChannels(directBookings = zeroChannel, promoterBookings = zeroChannel, guestLists = zeroChannel)),
            promoters = PromotersHealth(totals = PromoterTotals(0, 0, 0, 0.0), byPromoter = emptyList(), top = PromoterTop(emptyList(), emptyList())),
            alerts = OwnerHealthAlerts(emptyList(), emptyList(), emptyList()),
            trend = OwnerHealthTrend(
                baselinePeriod = BaselinePeriod(from = baselineFrom.toString(), to = from.toString()),
                tables = TableTrend(occupancyRate = rateDelta(0.0, 0.0)),
                attendance = AttendanceTrend(noShowRateBookings = rateDelta(0.0, 0.0), noShowRateGuestLists = rateDelta(0.0, 0.0)),
                promoters = PromoterTrend(noShowRate = rateDelta(0.0, 0.0)),
            ),
            breakdowns = OwnerHealthBreakdowns(),
        )
    }
}

private fun safeRate(numerator: Int, denominator: Int): Double = if (denominator <= 0) 0.0 else numerator.toDouble() / denominator

private data class ZoneAccumulator(
    val zoneId: String,
    val zones: List<Zone>,
    var capacity: Int = 0,
    var booked: Int = 0,
) {
    val zoneName: String?
        get() = zones.firstOrNull { it.id == zoneId }?.name

    fun toHealth(): ZoneTablesHealth =
        ZoneTablesHealth(
            zoneId = zoneId,
            zoneName = zoneName,
            totalTableCapacity = capacity,
            bookedSeats = booked,
            occupancyRate = safeRate(booked, capacity),
        )
}

private data class PromoterAccumulator(
    val promoterId: Long,
    var invited: Int = 0,
    var arrived: Int = 0,
) {
    fun toHealth(): PromoterHealth {
        val noShow = (invited - arrived).coerceAtLeast(0)
        return PromoterHealth(
            promoterId = promoterId,
            name = null,
            invitedGuests = invited,
            arrivedGuests = arrived,
            noShowGuests = noShow,
            noShowRate = safeRate(noShow, invited),
        )
    }
}
