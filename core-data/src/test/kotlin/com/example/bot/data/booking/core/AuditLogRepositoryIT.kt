package com.example.bot.data.booking.core

import com.example.bot.data.audit.AuditLogTable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import testing.RequiresDocker
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RequiresDocker
@Tag("it")
class AuditLogRepositoryIT : PostgresIntegrationTest() {
    @Test
    fun `log writes audit record`() =
        runBlocking {
            val clock = Clock.fixed(Instant.parse("2025-04-01T08:15:00Z"), ZoneOffset.UTC)
            val repo = AuditLogRepository(database, clock)
            val meta = buildJsonObject { put("bookingId", "abc") }
            val id = repo.log(42L, "BOOKING_CREATED", "booking", 7L, "OK", "127.0.0.1", meta)
            val stored =
                transaction(database) {
                    AuditLogTable
                        .selectAll()
                        .where { AuditLogTable.id eq id }
                        .firstOrNull()
                } ?: fail("audit record not found")
            assertEquals("BOOKING_CREATED", stored[AuditLogTable.action])
            assertEquals("booking", stored[AuditLogTable.resource])
            assertEquals("OK", stored[AuditLogTable.result])
            assertEquals("127.0.0.1", stored[AuditLogTable.ip])
            assertEquals(meta, stored[AuditLogTable.meta])
        }
}
