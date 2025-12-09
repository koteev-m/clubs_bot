package com.example.bot.promoter.invites

import java.time.Clock
import java.time.Instant

class PromoterInviteService(
    private val repository: PromoterInviteRepository,
    private val clock: Clock,
) {
    fun issueInvite(
        promoterId: Long,
        clubId: Long,
        eventId: Long,
        guestName: String,
        guestCount: Int,
        now: Instant = Instant.now(clock),
    ): PromoterInviteView {
        val invite =
            PromoterInvite(
                id = repository.nextId(),
                promoterId = promoterId,
                clubId = clubId,
                eventId = eventId,
                guestName = guestName,
                guestCount = guestCount,
                status = PromoterInviteStatus.ISSUED,
                issuedAt = now,
                openedAt = null,
                confirmedAt = null,
                arrivedAt = null,
                noShowAt = null,
                revokedAt = null,
            )
        val saved = repository.save(invite)
        return toView(saved)
    }

    fun listInvites(
        promoterId: Long,
        eventId: Long?,
    ): List<PromoterInviteView> {
        return repository.listByPromoterAndEvent(promoterId, eventId)
            .sortedByDescending { it.issuedAt }
            .map { toView(it) }
    }

    fun revokeInvite(
        promoterId: Long,
        inviteId: Long,
        now: Instant = Instant.now(clock),
    ): RevokeResult {
        val invite = repository.findById(inviteId) ?: return RevokeResult.NotFound
        if (invite.promoterId != promoterId) return RevokeResult.Forbidden
        val revoked = invite.revoke(now)
        if (!revoked) return RevokeResult.InvalidState
        val saved = repository.save(invite)
        return RevokeResult.Success(toView(saved))
    }

    fun markArrivedById(inviteId: Long, now: Instant = Instant.now(clock)): Boolean {
        val invite = repository.findById(inviteId) ?: return false
        val changed = invite.markArrived(now)
        if (!changed) return false
        repository.save(invite)
        return true
    }

    fun exportCsv(
        promoterId: Long,
        eventId: Long,
    ): String {
        val rows = repository.listByPromoterAndEvent(promoterId, eventId)
            .sortedByDescending { it.issuedAt }
        val header =
            listOf(
                "inviteId",
                "promoterId",
                "clubId",
                "eventId",
                "guestName",
                "guestCount",
                "status",
                "issuedAt",
                "openedAt",
                "confirmedAt",
                "arrivedAt",
                "noShowAt",
                "revokedAt",
            )
        val body =
            rows.joinToString("\n") { invite ->
                listOf(
                    invite.id.toString(),
                    invite.promoterId.toString(),
                    invite.clubId.toString(),
                    invite.eventId.toString(),
                    invite.guestName,
                    invite.guestCount.toString(),
                    invite.status.name.lowercase(),
                    invite.issuedAt.toString(),
                    invite.openedAt.toStringOrEmpty(),
                    invite.confirmedAt.toStringOrEmpty(),
                    invite.arrivedAt.toStringOrEmpty(),
                    invite.noShowAt.toStringOrEmpty(),
                    invite.revokedAt.toStringOrEmpty(),
                ).joinToString(",") { escapeCsv(it) }
            }
        return buildString {
            append(header.joinToString(","))
            if (rows.isNotEmpty()) {
                append('\n')
                append(body)
            }
        }
    }

    private fun Instant?.toStringOrEmpty(): String = this?.toString() ?: ""

    private fun escapeCsv(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private fun toView(invite: PromoterInvite): PromoterInviteView {
        val timeline = buildTimeline(invite)
        return PromoterInviteView(
            id = invite.id,
            clubId = invite.clubId,
            eventId = invite.eventId,
            promoterId = invite.promoterId,
            guestName = invite.guestName,
            guestCount = invite.guestCount,
            status = invite.status.name.lowercase(),
            issuedAt = invite.issuedAt.toString(),
            openedAt = invite.openedAt?.toString(),
            confirmedAt = invite.confirmedAt?.toString(),
            arrivedAt = invite.arrivedAt?.toString(),
            noShowAt = invite.noShowAt?.toString(),
            revokedAt = invite.revokedAt?.toString(),
            timeline = timeline,
        )
    }

    private fun buildTimeline(invite: PromoterInvite): List<InviteTimelineEvent> {
        return buildList {
            add("issued" to invite.issuedAt)
            invite.openedAt?.let { add("opened" to it) }
            invite.confirmedAt?.let { add("confirmed" to it) }
            invite.arrivedAt?.let { add("arrived" to it) }
            invite.noShowAt?.let { add("no_show" to it) }
            invite.revokedAt?.let { add("revoked" to it) }
        }.sortedBy { it.second }
            .map { (type, at) -> InviteTimelineEvent(type = type, at = at.toString()) }
    }

    sealed class RevokeResult {
        data class Success(val invite: PromoterInviteView) : RevokeResult()
        data object NotFound : RevokeResult()
        data object Forbidden : RevokeResult()
        data object InvalidState : RevokeResult()
    }
}
