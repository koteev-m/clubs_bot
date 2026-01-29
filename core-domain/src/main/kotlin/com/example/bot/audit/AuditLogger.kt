package com.example.bot.audit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

class AuditLogger(
    private val repository: AuditLogRepository,
) {
    suspend fun bookingCreated(
        bookingId: UUID,
        clubId: Long,
        tableId: Long,
        eventId: Long?,
        guests: Int,
    ) {
        append(
            clubId = clubId,
            entityType = StandardAuditEntityType.BOOKING,
            action = StandardAuditAction.CREATE,
            fingerprint = fingerprint(StandardAuditEntityType.BOOKING.value, StandardAuditAction.CREATE.value, bookingId.toString()),
            entityId = null,
            metadata =
                buildJsonObject {
                    put("bookingId", bookingId.toString())
                    put("tableId", tableId)
                    eventId?.let { put("eventId", it) }
                    put("guests", guests)
                },
        )
    }

    suspend fun bookingCancelled(
        bookingId: UUID,
        clubId: Long,
        actorUserId: Long?,
        subjectUserId: Long?,
        source: String?,
    ) {
        append(
            clubId = clubId,
            actorUserId = actorUserId,
            subjectUserId = subjectUserId,
            entityType = StandardAuditEntityType.BOOKING,
            action = StandardAuditAction.CANCEL,
            fingerprint = fingerprint(StandardAuditEntityType.BOOKING.value, StandardAuditAction.CANCEL.value, bookingId.toString()),
            entityId = null,
            metadata =
                buildJsonObject {
                    put("bookingId", bookingId.toString())
                    source?.let { put("source", it) }
                },
        )
    }

    suspend fun visitCheckedIn(
        clubId: Long,
        nightId: Long,
        checkinId: Long,
        actorUserId: Long?,
        subjectUserId: Long?,
        method: String,
        subjectType: String,
        subjectId: String,
        resultStatus: String,
    ) {
        append(
            clubId = clubId,
            nightId = nightId,
            actorUserId = actorUserId,
            subjectUserId = subjectUserId,
            entityType = StandardAuditEntityType.VISIT,
            action = StandardAuditAction.CHECKIN,
            fingerprint =
                fingerprint(
                    StandardAuditEntityType.VISIT.value,
                    StandardAuditAction.CHECKIN.value,
                    "club:$clubId:night:$nightId:checkin:$checkinId",
                ),
            entityId = checkinId,
            metadata =
                buildJsonObject {
                    put("subjectType", subjectType)
                    put("subjectId", subjectId)
                    put("method", method)
                    put("status", resultStatus)
                },
        )
    }

    suspend fun tableDepositCreated(
        depositId: UUID,
        clubId: Long,
        tableId: Long,
        tableNumber: Int,
        guests: Int,
        amountMinor: Long,
        currency: String,
        provider: String,
    ) {
        append(
            clubId = clubId,
            entityType = StandardAuditEntityType.TABLE_DEPOSIT,
            action = StandardAuditAction.CREATE,
            fingerprint = fingerprint(StandardAuditEntityType.TABLE_DEPOSIT.value, StandardAuditAction.CREATE.value, depositId.toString()),
            entityId = null,
            metadata =
                buildJsonObject {
                    put("depositId", depositId.toString())
                    put("tableId", tableId)
                    put("tableNumber", tableNumber)
                    put("guests", guests)
                    put("amountMinor", amountMinor)
                    put("currency", currency)
                    put("provider", provider)
                },
        )
    }

    suspend fun badgeEarned(
        clubId: Long,
        userId: Long,
        badgeId: Long,
        fingerprint: String,
        conditionType: String,
        threshold: Int,
        windowDays: Int?,
        earnedAt: Instant,
    ) {
        append(
            clubId = clubId,
            subjectUserId = userId,
            entityType = StandardAuditEntityType.BADGE,
            action = CustomAuditAction("EARN"),
            fingerprint = "BADGE:EARN:$fingerprint:v1",
            entityId = badgeId,
            metadata =
                buildJsonObject {
                    put("badgeId", badgeId)
                    put("conditionType", conditionType)
                    put("threshold", threshold)
                    windowDays?.let { put("windowDays", it) }
                    put("earnedAt", earnedAt.toString())
                },
        )
    }

    suspend fun couponIssued(
        clubId: Long,
        userId: Long,
        couponId: Long,
        prizeId: Long,
        fingerprint: String,
        metricType: String,
        threshold: Int,
        windowDays: Int?,
        issuedAt: Instant,
        expiresAt: Instant?,
    ) {
        append(
            clubId = clubId,
            subjectUserId = userId,
            entityType = StandardAuditEntityType.COUPON,
            action = CustomAuditAction("ISSUE"),
            fingerprint = "COUPON:ISSUE:$fingerprint:v1",
            entityId = couponId,
            metadata =
                buildJsonObject {
                    put("couponId", couponId)
                    put("prizeId", prizeId)
                    put("metricType", metricType)
                    put("threshold", threshold)
                    windowDays?.let { put("windowDays", it) }
                    put("issuedAt", issuedAt.toString())
                    expiresAt?.let { put("expiresAt", it.toString()) }
                },
        )
    }

    suspend fun shiftClosed(
        clubId: Long,
        nightId: Long,
        actorUserId: Long?,
        shiftId: String,
    ) {
        append(
            clubId = clubId,
            nightId = nightId,
            actorUserId = actorUserId,
            entityType = StandardAuditEntityType.SHIFT_REPORT,
            action = CustomAuditAction("CLOSE"),
            fingerprint =
                fingerprint(
                    StandardAuditEntityType.SHIFT_REPORT.value,
                    "CLOSE",
                    "club:$clubId:night:$nightId:shift:$shiftId",
                ),
            entityId = null,
            metadata =
                buildJsonObject {
                    put("shiftId", shiftId)
                },
        )
    }

    suspend fun roleGranted(
        clubId: Long?,
        actorUserId: Long?,
        subjectUserId: Long,
        role: String,
        scope: String?,
    ) {
        append(
            clubId = clubId,
            actorUserId = actorUserId,
            subjectUserId = subjectUserId,
            entityType = StandardAuditEntityType.ROLE_ASSIGNMENT,
            action = StandardAuditAction.CREATE,
            fingerprint =
                fingerprint(
                    StandardAuditEntityType.ROLE_ASSIGNMENT.value,
                    StandardAuditAction.CREATE.value,
                    "club:${clubId ?: "GLOBAL"}:user:$subjectUserId:role:$role:scope:${scope ?: "GLOBAL"}",
                ),
            entityId = null,
            metadata =
                buildJsonObject {
                    put("role", role)
                    scope?.let { put("scope", it) }
                },
        )
    }

    suspend fun bookingAction(
        action: String,
        clubId: Long?,
        entityId: String?,
        metadata: JsonObject?,
    ) {
        val outcome = metadata?.get("result")?.jsonPrimitive?.contentOrNull ?: "unknown"
        val entityIdOrRandom = entityId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        append(
            clubId = clubId,
            entityType = StandardAuditEntityType.BOOKING,
            action = CustomAuditAction(action),
            fingerprint =
                fingerprint(
                    StandardAuditEntityType.BOOKING.value,
                    action.uppercase(),
                    "$entityIdOrRandom:$outcome",
                ),
            entityId = null,
            metadata = metadata,
        )
    }

    private suspend fun append(
        clubId: Long?,
        nightId: Long? = null,
        actorUserId: Long? = null,
        actorRole: String? = null,
        subjectUserId: Long? = null,
        entityType: AuditEntityType,
        entityId: Long?,
        action: AuditAction,
        fingerprint: String,
        metadata: JsonObject? = null,
    ) {
        repository.append(
            AuditLogEvent(
                clubId = clubId,
                nightId = nightId,
                actorUserId = actorUserId,
                actorRole = actorRole,
                subjectUserId = subjectUserId,
                entityType = entityType,
                entityId = entityId,
                action = action,
                fingerprint = fingerprint,
                metadata = metadata,
            ),
        )
    }

    private fun fingerprint(
        entity: String,
        action: String,
        id: String,
    ): String = "$entity:$action:$id:v1"
}
