package com.example.bot.owner

import java.time.Instant
import kotlinx.serialization.Serializable

enum class OwnerHealthPeriod { WEEK, MONTH }

enum class OwnerHealthGranularity { SUMMARY, FULL }

data class OwnerHealthRequest(
    val clubId: Long,
    val period: OwnerHealthPeriod,
    val granularity: OwnerHealthGranularity,
    val now: Instant,
)

@Serializable
data class OwnerHealthSnapshot(
    val clubId: Long,
    val period: OwnerHealthPeriodWindow,
    val meta: OwnerHealthMeta,
    val tables: TablesHealth,
    val attendance: AttendanceHealth,
    val promoters: PromotersHealth,
    val alerts: OwnerHealthAlerts,
    val trend: OwnerHealthTrend,
)

@Serializable
data class OwnerHealthPeriodWindow(
    val type: String,
    val from: String,
    val to: String,
)

@Serializable
data class OwnerHealthMeta(
    val generatedAt: String,
    val granularity: String,
    val eventsCount: Int,
    val hasIncompleteData: Boolean,
)

@Serializable
data class TablesHealth(
    /** Number of events in the requested period, not only those with layout. */
    val eventsCount: Int,
    val totalTableCapacity: Int,
    val bookedSeats: Int,
    val occupancyRate: Double,
    val byZone: List<ZoneTablesHealth>,
    val byEvent: List<EventTablesHealth>,
)

@Serializable
data class ZoneTablesHealth(
    val zoneId: String,
    val zoneName: String?,
    val totalTableCapacity: Int,
    val bookedSeats: Int,
    val occupancyRate: Double,
)

@Serializable
data class EventTablesHealth(
    val eventId: Long,
    val startUtc: String?,
    val title: String?,
    val totalTableCapacity: Int,
    val bookedSeats: Int,
    val occupancyRate: Double,
)

@Serializable
data class AttendanceHealth(
    val bookings: AttendanceChannel,
    val guestLists: AttendanceChannel,
    val channels: AttendanceChannels,
)

@Serializable
data class AttendanceChannels(
    val directBookings: AttendanceChannel,
    val promoterBookings: AttendanceChannel,
    val guestLists: AttendanceChannel,
)

@Serializable
data class AttendanceChannel(
    val plannedGuests: Int,
    val arrivedGuests: Int,
    val noShowGuests: Int,
    val noShowRate: Double,
)

@Serializable
data class PromotersHealth(
    val totals: PromoterTotals,
    val byPromoter: List<PromoterHealth>,
    val top: PromoterTop,
)

@Serializable
data class PromoterTotals(
    val invitedGuests: Int,
    val arrivedGuests: Int,
    val noShowGuests: Int,
    val noShowRate: Double,
)

@Serializable
data class PromoterHealth(
    val promoterId: Long,
    val name: String?,
    val invitedGuests: Int,
    val arrivedGuests: Int,
    val noShowGuests: Int,
    val noShowRate: Double,
)

@Serializable
data class PromoterTop(
    val byArrivedGuests: List<Long>,
    val byInvitedGuests: List<Long>,
)

@Serializable
data class OwnerHealthAlerts(
    val lowOccupancyEvents: List<AlertEvent>,
    val highNoShowEvents: List<AlertEvent>,
    val weakPromoters: List<AlertPromoter>,
)

@Serializable
data class AlertEvent(
    val eventId: Long,
    val title: String?,
    val startUtc: String?,
    val occupancyRate: Double? = null,
    val noShowRate: Double? = null,
)

@Serializable
data class AlertPromoter(
    val promoterId: Long,
    val name: String?,
    val invitedGuests: Int,
    val noShowRate: Double,
)

@Serializable
data class OwnerHealthTrend(
    val baselinePeriod: BaselinePeriod,
    val tables: TableTrend,
    val attendance: AttendanceTrend,
    val promoters: PromoterTrend,
)

@Serializable
data class BaselinePeriod(
    val from: String,
    val to: String,
)

@Serializable
data class TableTrend(
    val occupancyRate: RateDelta,
)

@Serializable
data class AttendanceTrend(
    val noShowRateBookings: RateDelta,
    val noShowRateGuestLists: RateDelta,
)

@Serializable
data class PromoterTrend(
    val noShowRate: RateDelta,
)

@Serializable
data class RateDelta(
    val current: Double,
    val previous: Double,
    val deltaAbs: Double,
    val deltaRel: Double?,
)

data class OwnerHealthWatermarks(
    val currentPeriodUpdatedAt: Instant?,
    val previousPeriodUpdatedAt: Instant?,
)

interface OwnerHealthService {
    suspend fun healthForClub(request: OwnerHealthRequest): OwnerHealthSnapshot?
}

fun rateDelta(current: Double, previous: Double): RateDelta =
    RateDelta(
        current = current,
        previous = previous,
        deltaAbs = current - previous,
        deltaRel = if (previous == 0.0) null else (current - previous) / previous,
    )

fun ownerHealthEtagSeed(
    request: OwnerHealthRequest,
    periodFrom: Instant,
    periodTo: Instant,
    watermarks: OwnerHealthWatermarks,
): String =
    listOf(
        "owner_health",
        request.clubId.toString(),
        request.period.name,
        request.granularity.name,
        periodFrom.toString(),
        periodTo.toString(),
        watermarks.currentPeriodUpdatedAt?.toString().orEmpty(),
        watermarks.previousPeriodUpdatedAt?.toString().orEmpty(),
    ).joinToString("|")
