package com.example.bot.data.booking.core

import com.example.bot.audit.AuditLogEvent
import com.example.bot.audit.CustomAuditAction
import com.example.bot.audit.StandardAuditEntityType
import com.example.bot.data.audit.AuditLogRepositoryImpl
import com.example.bot.data.audit.AuditLogTable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import testing.RequiresDocker
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@RequiresDocker
@Tag("it")
class AuditLogRepositoryIT : PostgresIntegrationTest() {
    @Test
    fun `log writes audit record`() =
        runBlocking {
            val clock = Clock.fixed(Instant.parse("2025-04-01T08:15:00Z"), ZoneOffset.UTC)
            val repo = AuditLogRepositoryImpl(database, clock)
            val meta = buildJsonObject { put("bookingId", "abc") }
            val fingerprint = UUID.randomUUID().toString()
            val id =
                repo.append(
                    AuditLogEvent(
                        clubId = 7L,
                        nightId = null,
                        actorUserId = 42L,
                        actorRole = null,
                        subjectUserId = null,
                        entityType = StandardAuditEntityType.BOOKING,
                        entityId = null,
                        action = CustomAuditAction("BOOKING_CREATED"),
                        fingerprint = fingerprint,
                        metadata = meta,
                    ),
                )
            val stored =
                transaction(database) {
                    AuditLogTable
                        .selectAll()
                        .where { AuditLogTable.id eq id }
                        .firstOrNull()
                } ?: fail("audit record not found")
            assertEquals("BOOKING_CREATED", stored[AuditLogTable.action])
            assertEquals("BOOKING", stored[AuditLogTable.entityType])
            assertEquals(fingerprint, stored[AuditLogTable.fingerprint])
            assertEquals(meta.toString(), stored[AuditLogTable.metadataJson])
        }

    @Test
    fun `append is idempotent for fingerprint`() =
        runBlocking {
            val repo = AuditLogRepositoryImpl(database)
            val fingerprint = "fingerprint-1"
            val id =
                repo.append(
                    AuditLogEvent(
                        clubId = 1L,
                        nightId = null,
                        actorUserId = 10L,
                        actorRole = null,
                        subjectUserId = null,
                        entityType = StandardAuditEntityType.BOOKING,
                        entityId = null,
                        action = CustomAuditAction("BOOKING_CREATED"),
                        fingerprint = fingerprint,
                        metadata = buildJsonObject { put("bookingId", "abc") },
                    ),
                )
            val secondId =
                repo.append(
                    AuditLogEvent(
                        clubId = 1L,
                        nightId = null,
                        actorUserId = 10L,
                        actorRole = null,
                        subjectUserId = null,
                        entityType = StandardAuditEntityType.BOOKING,
                        entityId = null,
                        action = CustomAuditAction("BOOKING_CREATED"),
                        fingerprint = fingerprint,
                        metadata = buildJsonObject { put("bookingId", "abc") },
                    ),
                )
            assertEquals(id, secondId)
            val total =
                transaction(database) {
                    AuditLogTable
                        .selectAll()
                        .where { AuditLogTable.fingerprint eq fingerprint }
                        .count()
                }
            assertEquals(1, total)
        }

    @Test
    fun `append sanitizes metadata`() =
        runBlocking {
            val repo = AuditLogRepositoryImpl(database)
            val id =
                repo.append(
                    AuditLogEvent(
                        clubId = 3L,
                        nightId = null,
                        actorUserId = 99L,
                        actorRole = null,
                        subjectUserId = null,
                        entityType = StandardAuditEntityType.BOOKING,
                        entityId = null,
                        action = CustomAuditAction("BOOKING_UPDATED"),
                        fingerprint = UUID.randomUUID().toString(),
                        metadata =
                            buildJsonObject {
                                put("initData", "secret")
                                put("qrToken", "qr-secret")
                                put("phone", "+79991234567")
                                put("access_token", "access")
                                put("contact", "+79991234567")
                                put("notes", "8 (999) 123-45-67")
                                put("safe", "ok")
                            },
                    ),
                )

            val stored =
                transaction(database) {
                    AuditLogTable
                        .selectAll()
                        .where { AuditLogTable.id eq id }
                        .firstOrNull()
                } ?: fail("audit record not found")

            val parsed = Json.parseToJsonElement(stored[AuditLogTable.metadataJson]) as JsonObject
            assertFalse(parsed.containsKey("initData"))
            assertFalse(parsed.containsKey("qrToken"))
            assertFalse(parsed.containsKey("phone"))
            assertFalse(parsed.containsKey("access_token"))
            assertEquals("[REDACTED]", parsed["contact"]?.jsonPrimitive?.content)
            assertEquals("[REDACTED]", parsed["notes"]?.jsonPrimitive?.content)
            assertEquals("ok", parsed["safe"]?.jsonPrimitive?.content)
        }
}
