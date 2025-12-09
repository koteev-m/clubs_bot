package com.example.bot.promoter.rating

import com.example.bot.clubs.Event
import com.example.bot.clubs.EventsRepository
import com.example.bot.promoter.invites.PromoterInvite
import com.example.bot.promoter.invites.PromoterInviteRepository
import com.example.bot.promoter.invites.PromoterInviteStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
/**
 * Sliding time windows supported by promoter analytics.
 *
 * WEEK is `[now - 7 days, now)`, MONTH is `[now - 30 days, now)` in UTC.
 */
enum class PromoterPeriod(val value: String, private val lookBack: Duration) {
    WEEK("week", Duration.ofDays(7)),
    MONTH("month", Duration.ofDays(30));

    fun bounds(now: Instant): Pair<Instant, Instant> = now.minus(lookBack) to now

    companion object {
        fun parse(raw: String?): PromoterPeriod? =
            values().firstOrNull { it.value.equals(raw, ignoreCase = true) }
    }
}

/** Personal scorecard metrics for a promoter. */
data class PromoterScorecard(
    val period: String,
    val from: Instant,
    val to: Instant,
    val invited: Int,
    val arrivals: Int,
    val noShows: Int,
    val conversionScore: Double,
)

/** Aggregated metrics for a single promoter row in the rating. */
data class PromoterRatingRow(
    val promoterId: Long,
    val invited: Int,
    val arrivals: Int,
    val noShows: Int,
    val conversionScore: Double,
)

/** Page of the club-level promoter rating. */
data class PromoterRatingPage(
    val clubId: Long,
    val period: String,
    val from: Instant,
    val to: Instant,
    val page: Int,
    val size: Int,
    val total: Int,
    val items: List<PromoterRatingRow>,
)

private data class InviteWithEvent(val invite: PromoterInvite, val event: Event)
private data class InviteEventKey(val clubId: Long, val eventId: Long)

/**
 * Aggregates promoter invite outcomes into personal scorecards and club-level ratings.
 *
 * WEEK/MONTH windows are derived from `[now - lookBack, now)` using the injected [Clock].
 * The service is analytics-only: it reads `PromoterInvite` and `Event` data without mutating
 * any domain state.
 *
 * Metrics are derived as follows (also described in README):
 * - invited: sum of `guestCount` for invites not in `REVOKED`.
 * - arrivals: sum of `guestCount` for invites with status `ARRIVED` or non-null `arrivedAt`.
 * - noShows: for events with `endUtc < now`, sum `guestCount` for invites that are neither
 *   `ARRIVED` nor `REVOKED` (future/ongoing events are excluded from no-shows).
 * - conversionScore: `0.0` when `arrivals + noShows == 0`, otherwise `arrivals / (arrivals + noShows)`.
 */
class PromoterRatingService(
    private val inviteRepository: PromoterInviteRepository,
    private val eventsRepository: EventsRepository,
    private val clock: Clock,
) {
    fun scorecardForPromoter(
        promoterId: Long,
        period: PromoterPeriod,
        now: Instant = Instant.now(clock),
    ): PromoterScorecard {
        val (from, to) = period.bounds(now)
        val invites = inviteRepository.listByPromoter(promoterId)
        val invitesInPeriod = invitesWithEvents(invites, from, to)
        val metrics = aggregate(invitesInPeriod, now)

        return PromoterScorecard(
            period = period.value,
            from = from,
            to = to,
            invited = metrics.invited,
            arrivals = metrics.arrivals,
            noShows = metrics.noShows,
            conversionScore = metrics.conversionScore,
        )
    }

    fun ratingForClub(
        clubId: Long,
        period: PromoterPeriod,
        page: Int,
        size: Int,
        now: Instant = Instant.now(clock),
    ): PromoterRatingPage {
        val (from, to) = period.bounds(now)
        val invites = inviteRepository.listByClub(clubId)
        val invitesInPeriod = invitesWithEvents(invites, from, to)
        val grouped = invitesInPeriod.groupBy { it.invite.promoterId }

        val rows = grouped.map { (promoterId, entries) ->
            val metrics = aggregate(entries, now)
            PromoterRatingRow(
                promoterId = promoterId,
                invited = metrics.invited,
                arrivals = metrics.arrivals,
                noShows = metrics.noShows,
                conversionScore = metrics.conversionScore,
            )
        }
            .sortedWith(
                compareByDescending<PromoterRatingRow> { it.conversionScore }
                    .thenByDescending { it.arrivals }
                    .thenBy { it.promoterId },
            )

        val total = rows.size
        val paged = rows.drop((page - 1) * size).take(size)

        return PromoterRatingPage(
            clubId = clubId,
            period = period.value,
            from = from,
            to = to,
            page = page,
            size = size,
            total = total,
            items = paged,
        )
    }

    private fun invitesWithEvents(
        invites: List<PromoterInvite>,
        from: Instant,
        to: Instant,
    ): List<InviteWithEvent> {
        val eventsByKey: Map<InviteEventKey, Event?> = invites
            .asSequence()
            .map { InviteEventKey(it.clubId, it.eventId) }
            .distinct()
            .associateWith { key -> eventsRepository.findById(key.clubId, key.eventId) }

        return invites
            .mapNotNull { invite ->
                val key = InviteEventKey(invite.clubId, invite.eventId)
                val event = eventsByKey[key] ?: return@mapNotNull null
                if (event.startUtc.isBefore(from) || !event.startUtc.isBefore(to)) return@mapNotNull null
                InviteWithEvent(invite, event)
            }
    }

    private data class Metrics(
        val invited: Int,
        val arrivals: Int,
        val noShows: Int,
        val conversionScore: Double,
    )

    /**
     * Computes metrics for a pre-filtered set of invites whose events start within `[from, to)`.
     * No-shows are counted only for events with `endUtc < now`. Conversion is `arrivals / (arrivals + noShows)`,
     * falling back to `0.0` when there is no denominator.
     */
    private fun aggregate(invites: List<InviteWithEvent>, now: Instant): Metrics {
        val invited = invites
            .filter { it.invite.status != PromoterInviteStatus.REVOKED }
            .sumOf { it.invite.guestCount }

        val arrivals = invites
            .filter { it.invite.status == PromoterInviteStatus.ARRIVED || it.invite.arrivedAt != null }
            .sumOf { it.invite.guestCount }

        val noShows = invites
            .filter { it.event.endUtc.isBefore(now) }
            .filter { it.invite.status != PromoterInviteStatus.ARRIVED && it.invite.status != PromoterInviteStatus.REVOKED }
            .sumOf { it.invite.guestCount }

        val invitedForScore = arrivals + noShows
        val conversionScore = if (invitedForScore == 0) 0.0 else arrivals.toDouble() / invitedForScore

        return Metrics(
            invited = invited,
            arrivals = arrivals,
            noShows = noShows,
            conversionScore = conversionScore,
        )
    }
}
