package com.example.bot.audit

import java.time.Instant
import kotlinx.serialization.json.JsonElement

interface AuditLogRepository {
    suspend fun append(event: AuditLogEvent): Long

    suspend fun listForClub(
        clubId: Long,
        limit: Int,
        offset: Int,
    ): List<AuditLogRecord>

    suspend fun listForUser(
        userId: Long,
        limit: Int,
        offset: Int,
    ): List<AuditLogRecord>
}

data class AuditLogEvent(
    val clubId: Long?,
    val nightId: Long?,
    val actorUserId: Long?,
    val actorRole: String?,
    val subjectUserId: Long?,
    val entityType: AuditEntityType,
    val entityId: Long?,
    val action: AuditAction,
    val fingerprint: String,
    val metadata: JsonElement? = null,
)

data class AuditLogRecord(
    val id: Long,
    val createdAt: Instant,
    val clubId: Long?,
    val nightId: Long?,
    val actorUserId: Long?,
    val actorRole: String?,
    val subjectUserId: Long?,
    val entityType: AuditEntityType,
    val entityId: Long?,
    val action: AuditAction,
    val fingerprint: String,
    val metadata: JsonElement,
)

sealed interface AuditEntityType {
    val value: String

    companion object {
        fun from(value: String): AuditEntityType =
            StandardAuditEntityType.from(value) ?: CustomAuditEntityType(value)
    }
}

enum class StandardAuditEntityType(override val value: String) : AuditEntityType {
    BOOKING("BOOKING"),
    VISIT("VISIT"),
    TABLE_DEPOSIT("TABLE_DEPOSIT"),
    SHIFT_REPORT("SHIFT_REPORT"),
    ROLE_ASSIGNMENT("ROLE_ASSIGNMENT"),
    HTTP_ACCESS("HTTP_ACCESS"),
    LEGACY("LEGACY"),
    ;

    companion object {
        fun from(value: String): StandardAuditEntityType? =
            entries.firstOrNull { it.value == value }
    }
}

data class CustomAuditEntityType(override val value: String) : AuditEntityType

sealed interface AuditAction {
    val value: String

    companion object {
        fun from(value: String): AuditAction =
            StandardAuditAction.from(value) ?: CustomAuditAction(value)
    }
}

enum class StandardAuditAction(override val value: String) : AuditAction {
    CREATE("CREATE"),
    UPDATE("UPDATE"),
    CANCEL("CANCEL"),
    CHECKIN("CHECKIN"),
    REDEEM("REDEEM"),
    ACCESS_GRANTED("ACCESS_GRANTED"),
    ACCESS_DENIED("ACCESS_DENIED"),
    LEGACY("LEGACY"),
    ;

    companion object {
        fun from(value: String): StandardAuditAction? =
            entries.firstOrNull { it.value == value }
    }
}

data class CustomAuditAction(override val value: String) : AuditAction
