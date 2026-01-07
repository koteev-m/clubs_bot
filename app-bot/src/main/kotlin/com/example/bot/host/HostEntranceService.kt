package com.example.bot.host

import com.example.bot.booking.a3.Booking
import com.example.bot.booking.a3.BookingStatus
import com.example.bot.club.GuestList
import com.example.bot.club.GuestListEntry
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListRepository
import com.example.bot.club.WaitlistRepository
import com.example.bot.club.WaitlistStatus
import com.example.bot.clubs.EventsRepository
import java.time.Clock
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * Builds a read-only entrance snapshot for a specific (club, event) pair.
 *
 * Metrics semantics:
 * - Guest list: expectedGuests sums guestsCount for entries not EXPIRED; arrivedGuests sums CHECKED_IN; noShowGuests sums
 *   NO_SHOW; notArrivedGuests is max(expected - arrived - noShow, 0).
 * - Bookings: expectedGuests sums guestCount for non-CANCELED bookings; arrivedGuests and noShowGuests are intentionally 0
 *   until booking check-in semantics are wired; notArrivedGuests equals expectedGuests.
 * - Counts are summed across channels without deduplication between them.
 * - Waitlist entries mirror repository output (typically non-cancelled queue entries); activeCount equals entries.size.
 *
 * Returns null when the event is missing or not associated with the provided club, allowing the caller to map it to 404.
 */
class HostEntranceService(
    private val guestListRepository: GuestListRepository,
    private val waitlistRepository: WaitlistRepository,
    private val bookingProvider: BookingProvider,
    private val eventsRepository: EventsRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Suppress("LongMethod")
    suspend fun buildEntranceSnapshot(clubId: Long, eventId: Long): HostEntranceResponse? {
        val event = eventsRepository.findById(clubId, eventId) ?: return null
        val now = Instant.now(clock)

        val guestLists = loadGuestLists(clubId).filter { it.eventId == event.id }
        val guestEntries = guestLists.flatMap { loadEntriesForList(it.id) }
        val sortedGuestEntries = guestEntries.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, GuestListEntry::fullName).thenBy(GuestListEntry::id)
        )
        val guestMetrics = sortedGuestEntries.toGuestListMetrics()

        val guestViews = sortedGuestEntries.map {
            ExpectedGuest(
                id = it.id,
                guestName = it.fullName,
                guestCount = it.guestsCount,
                status = it.status.toWireStatus(),
                hasQr = false,
            )
        }

        val bookings = bookingProvider.findBookingsForEvent(clubId, eventId).sortedBy(Booking::id)
        val bookingMetrics = bookings.toBookingMetrics()

        val bookingViews = bookings.map {
            ExpectedBooking(
                bookingId = it.id,
                guestName = null,
                tableName = null,
                guestCount = it.guestCount,
                status = it.status.toWireStatus(),
            )
        }

        val otherMetrics = ChannelMetrics(expected = 0, arrived = 0, noShow = 0)

        val waitlistEntries = waitlistRepository.listQueue(clubId, eventId)
        val waitlistViews = waitlistEntries
            .sortedBy { it.createdAt }
            .map {
                WaitlistEntryView(
                    id = it.id,
                    clubId = it.clubId,
                    eventId = it.eventId,
                    userId = it.userId,
                    partySize = it.partySize,
                    status = it.status.toWireStatus(),
                    calledAt = it.calledAt?.toString(),
                    expiresAt = it.expiresAt?.toString(),
                    createdAt = it.createdAt.toString(),
                )
            }

        val counts = CountsSection(
            expectedTotalGuests = guestMetrics.expected + bookingMetrics.expected + otherMetrics.expected,
            arrivedTotalGuests = guestMetrics.arrived + bookingMetrics.arrived + otherMetrics.arrived,
            noShowTotalGuests = guestMetrics.noShow + bookingMetrics.noShow + otherMetrics.noShow,
        )

        val status = StatusSection(
            guestList = ChannelStatus(
                expectedGuests = guestMetrics.expected,
                arrivedGuests = guestMetrics.arrived,
                notArrivedGuests = guestMetrics.notArrived,
                noShowGuests = guestMetrics.noShow,
            ),
            bookings = ChannelStatus(
                expectedGuests = bookingMetrics.expected,
                arrivedGuests = bookingMetrics.arrived,
                notArrivedGuests = bookingMetrics.notArrived,
                noShowGuests = bookingMetrics.noShow,
            ),
            other = ChannelStatus(
                expectedGuests = otherMetrics.expected,
                arrivedGuests = otherMetrics.arrived,
                notArrivedGuests = otherMetrics.notArrived,
                noShowGuests = otherMetrics.noShow,
            ),
        )

        val expected = ExpectedSection(
            guestList = guestViews,
            bookings = bookingViews,
            other = emptyList(),
        )

        val waitlist = WaitlistSection(entries = waitlistViews, activeCount = waitlistViews.size)

        return HostEntranceResponse(
            clubId = clubId,
            eventId = eventId,
            now = now.toString(),
            expected = expected,
            status = status,
            waitlist = waitlist,
            counts = counts,
        )
    }

    private suspend fun loadGuestLists(clubId: Long): List<GuestList> {
        val pageSize = 100
        val result = mutableListOf<GuestList>()
        var page = 0
        while (true) {
            val batch = guestListRepository.listListsByClub(clubId, page, pageSize)
            result += batch
            if (batch.size < pageSize) break
            page += 1
        }
        return result
    }

    private suspend fun loadEntriesForList(listId: Long): List<GuestListEntry> {
        val pageSize = 100
        val result = mutableListOf<GuestListEntry>()
        var page = 0
        while (true) {
            val batch = guestListRepository.listEntries(listId, page, pageSize)
            result += batch
            if (batch.size < pageSize) break
            page += 1
        }
        return result
    }

    private fun List<GuestListEntry>.toGuestListMetrics(): ChannelMetrics {
        val expected = filter { it.status != GuestListEntryStatus.EXPIRED }.sumOf { it.guestsCount }
        val arrivedStatuses = setOf(
            GuestListEntryStatus.ARRIVED,
            GuestListEntryStatus.LATE,
            GuestListEntryStatus.CHECKED_IN,
        )
        val noShowStatuses = setOf(
            GuestListEntryStatus.NO_SHOW,
            GuestListEntryStatus.DENIED,
        )
        val arrived = filter { it.status in arrivedStatuses }.sumOf { it.guestsCount }
        val noShow = filter { it.status in noShowStatuses }.sumOf { it.guestsCount }
        return ChannelMetrics(expected = expected, arrived = arrived, noShow = noShow)
    }

    private fun List<Booking>.toBookingMetrics(): ChannelMetrics {
        val active = filter { it.status != BookingStatus.CANCELED }
        val expected = active.sumOf { it.guestCount }
        return ChannelMetrics(expected = expected, arrived = 0, noShow = 0)
    }
}

interface BookingProvider {
    /**
     * Returns a snapshot of all bookings for the given club and event.
     *
     * Implementations are expected to filter out any expired/evicted state internally,
     * so callers can safely aggregate on the returned immutable copies.
     */
    fun findBookingsForEvent(clubId: Long, eventId: Long): List<com.example.bot.booking.a3.Booking>
}

private data class ChannelMetrics(
    val expected: Int,
    val arrived: Int,
    val noShow: Int,
) {
    val notArrived: Int
        get() = (expected - arrived - noShow).coerceAtLeast(0)
}

private fun GuestListEntryStatus.toWireStatus(): String = name.lowercase(Locale.ROOT)

private fun BookingStatus.toWireStatus(): String = name.lowercase(Locale.ROOT)

private fun WaitlistStatus.toWireStatus(): String = name.lowercase(Locale.ROOT)

@Serializable
/** Snapshot of the “entrance today” aggregate for a single event. */
data class HostEntranceResponse(
    val clubId: Long,
    val eventId: Long,
    val now: String,
    val expected: ExpectedSection,
    val status: StatusSection,
    val waitlist: WaitlistSection,
    val counts: CountsSection,
)

@Serializable
/** Expected guests grouped by channel. */
data class ExpectedSection(
    val guestList: List<ExpectedGuest>,
    val bookings: List<ExpectedBooking>,
    val other: List<ExpectedOther>,
)

@Serializable
data class ExpectedGuest(
    val id: Long,
    val guestName: String,
    val guestCount: Int,
    val status: String,
    /**
     * Whether this guest list entry has a dedicated QR code linked to it.
     *
     * Currently always false until QR linkage is added to the guest list domain.
     */
    val hasQr: Boolean,
)

@Serializable
data class ExpectedBooking(
    val bookingId: Long,
    val guestName: String?,
    val tableName: String?,
    val guestCount: Int,
    val status: String,
)

@Serializable
data class ExpectedOther(
    val id: Long,
    val label: String,
    val guestCount: Int,
    val status: String,
)

@Serializable
/** Aggregated status metrics per channel. */
data class StatusSection(
    val guestList: ChannelStatus,
    val bookings: ChannelStatus,
    val other: ChannelStatus,
)

@Serializable
/**
 * expectedGuests: total expected for the channel; arrivedGuests: already checked in;
 * notArrivedGuests: max(expected - arrived - noShow, 0); noShowGuests: marked as no-show.
 */
data class ChannelStatus(
    val expectedGuests: Int,
    val arrivedGuests: Int,
    val notArrivedGuests: Int,
    val noShowGuests: Int,
)

@Serializable
data class WaitlistSection(
    val entries: List<WaitlistEntryView>,
    val activeCount: Int,
)

@Serializable
data class WaitlistEntryView(
    val id: Long,
    val clubId: Long,
    val eventId: Long,
    val userId: Long,
    val partySize: Int,
    val status: String,
    val calledAt: String?,
    val expiresAt: String?,
    val createdAt: String,
)

@Serializable
data class CountsSection(
    val expectedTotalGuests: Int,
    val arrivedTotalGuests: Int,
    val noShowTotalGuests: Int,
)
