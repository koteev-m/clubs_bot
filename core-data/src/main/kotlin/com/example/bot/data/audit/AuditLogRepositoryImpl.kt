package com.example.bot.data.audit

import com.example.bot.audit.AuditAction
import com.example.bot.audit.AuditEntityType
import com.example.bot.audit.AuditLogEvent
import com.example.bot.audit.AuditLogRecord
import com.example.bot.audit.AuditLogRepository
import com.example.bot.data.db.withTxRetry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

class AuditLogRepositoryImpl(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) : AuditLogRepository {
    private val canonJson = Json { encodeDefaults = true; explicitNulls = false }

    override suspend fun append(event: AuditLogEvent): Long {
        val now = Instant.now(clock)
        val sanitized = sanitizeMetadata(event.metadata)
        val metadataJson = canonJson.encodeToString(JsonElement.serializer(), sanitized)
        return withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val insertStatement =
                    AuditLogTable.insertIgnore {
                        it[createdAt] = now.atOffset(ZoneOffset.UTC)
                        it[clubId] = event.clubId
                        it[nightId] = event.nightId
                        it[actorUserId] = event.actorUserId
                        it[actorRole] = event.actorRole
                        it[subjectUserId] = event.subjectUserId
                        it[entityType] = event.entityType.value
                        it[entityId] = event.entityId
                        it[action] = event.action.value
                        it[fingerprint] = event.fingerprint
                        it[this.metadataJson] = metadataJson
                    }
                if (insertStatement.insertedCount == 0) {
                    AuditLogTable
                        .select { AuditLogTable.fingerprint eq event.fingerprint }
                        .limit(1)
                        .first()[AuditLogTable.id]
                } else {
                    insertStatement[AuditLogTable.id]
                }
            }
        }
    }

    override suspend fun listForClub(
        clubId: Long,
        limit: Int,
        offset: Int,
    ): List<AuditLogRecord> =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                AuditLogTable
                    .select { AuditLogTable.clubId eq clubId }
                    .orderBy(AuditLogTable.createdAt to SortOrder.DESC, AuditLogTable.id to SortOrder.DESC)
                    .limit(limit, offset.toLong())
                    .map { it.toAuditLogRecord(canonJson) }
            }
        }

    override suspend fun listForUser(
        userId: Long,
        limit: Int,
        offset: Int,
    ): List<AuditLogRecord> =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                AuditLogTable
                    .select {
                        (AuditLogTable.actorUserId eq userId) or
                            (AuditLogTable.subjectUserId eq userId)
                    }
                    .orderBy(AuditLogTable.createdAt to SortOrder.DESC, AuditLogTable.id to SortOrder.DESC)
                    .limit(limit, offset.toLong())
                    .map { it.toAuditLogRecord(canonJson) }
            }
        }

    private fun sanitizeMetadata(metadata: JsonElement?): JsonElement {
        if (metadata == null || metadata is JsonNull) {
            return JsonObject(emptyMap())
        }
        return sanitizeElement(metadata)
    }

    private fun sanitizeElement(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> {
                val filtered =
                    element.entries
                        .filterNot { (key, _) -> isSensitiveKey(key) }
                        .associate { (key, value) -> key to sanitizeElement(value) }
                JsonObject(filtered)
            }

            is JsonArray -> JsonArray(element.map { sanitizeElement(it) })
            is JsonPrimitive -> sanitizePrimitive(element)
            else -> element
        }

    private fun sanitizePrimitive(value: JsonPrimitive): JsonElement {
        if (!value.isString) return value
        val content = value.content
        return if (looksLikePhone(content)) {
            JsonPrimitive("[REDACTED]")
        } else {
            value
        }
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase()
        return sensitiveKeyMarkers.any { marker -> normalized.contains(marker) }
    }

    private fun looksLikePhone(value: String): Boolean = phoneRegex.matches(value)

    private companion object {
        val sensitiveKeyMarkers = setOf("initdata", "init_data", "qr", "token", "phone")
        val phoneRegex = Regex("^\\+?[0-9][0-9\\s()\\-]{6,}$")
    }
}

private fun org.jetbrains.exposed.sql.ResultRow.toAuditLogRecord(
    canonJson: Json,
): AuditLogRecord {
    val metadata =
        runCatching {
            canonJson.parseToJsonElement(this[AuditLogTable.metadataJson])
        }.getOrElse { JsonObject(emptyMap()) }
    return AuditLogRecord(
        id = this[AuditLogTable.id],
        createdAt = this[AuditLogTable.createdAt].toInstant(),
        clubId = this[AuditLogTable.clubId],
        nightId = this[AuditLogTable.nightId],
        actorUserId = this[AuditLogTable.actorUserId],
        actorRole = this[AuditLogTable.actorRole],
        subjectUserId = this[AuditLogTable.subjectUserId],
        entityType = AuditEntityType.from(this[AuditLogTable.entityType]),
        entityId = this[AuditLogTable.entityId],
        action = AuditAction.from(this[AuditLogTable.action]),
        fingerprint = this[AuditLogTable.fingerprint],
        metadata = metadata,
    )
}
