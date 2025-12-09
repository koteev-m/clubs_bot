package com.example.bot.promoter.invites

import kotlinx.serialization.Serializable
import java.time.Instant

enum class PromoterInviteStatus {
    ISSUED,
    OPENED,
    CONFIRMED,
    ARRIVED,
    NO_SHOW,
    REVOKED,
}

data class PromoterInvite(
    val id: Long,
    val promoterId: Long,
    val clubId: Long,
    val eventId: Long,
    val guestName: String,
    val guestCount: Int,
    var status: PromoterInviteStatus,
    val issuedAt: Instant,
    var openedAt: Instant?,
    var confirmedAt: Instant?,
    var arrivedAt: Instant?,
    var noShowAt: Instant?,
    var revokedAt: Instant?,
) {
    fun markArrived(now: Instant): Boolean {
        return when (status) {
            PromoterInviteStatus.ARRIVED -> true
            PromoterInviteStatus.ISSUED, PromoterInviteStatus.OPENED, PromoterInviteStatus.CONFIRMED -> {
                status = PromoterInviteStatus.ARRIVED
                arrivedAt = arrivedAt ?: now
                true
            }

            PromoterInviteStatus.REVOKED, PromoterInviteStatus.NO_SHOW -> false
        }
    }

    fun revoke(now: Instant): Boolean {
        return when (status) {
            PromoterInviteStatus.ISSUED, PromoterInviteStatus.OPENED, PromoterInviteStatus.CONFIRMED -> {
                status = PromoterInviteStatus.REVOKED
                revokedAt = revokedAt ?: now
                true
            }

            PromoterInviteStatus.ARRIVED, PromoterInviteStatus.NO_SHOW, PromoterInviteStatus.REVOKED -> false
        }
    }

    fun copySnapshot(): PromoterInvite =
        PromoterInvite(
            id = id,
            promoterId = promoterId,
            clubId = clubId,
            eventId = eventId,
            guestName = guestName,
            guestCount = guestCount,
            status = status,
            issuedAt = issuedAt,
            openedAt = openedAt,
            confirmedAt = confirmedAt,
            arrivedAt = arrivedAt,
            noShowAt = noShowAt,
            revokedAt = revokedAt,
        )
}

@Serializable
data class InviteTimelineEvent(
    val type: String,
    val at: String,
)

@Serializable
data class PromoterInviteView(
    val id: Long,
    val clubId: Long,
    val eventId: Long,
    val promoterId: Long,
    val guestName: String,
    val guestCount: Int,
    val status: String,
    val issuedAt: String,
    val openedAt: String? = null,
    val confirmedAt: String? = null,
    val arrivedAt: String? = null,
    val noShowAt: String? = null,
    val revokedAt: String? = null,
    val timeline: List<InviteTimelineEvent>,
)
