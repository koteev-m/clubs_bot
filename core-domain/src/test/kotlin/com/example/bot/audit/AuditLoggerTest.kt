package com.example.bot.audit

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class AuditLoggerTest {
    @Test
    fun `role granted includes club id in fingerprint`() = runBlocking {
        val auditRepo = FakeAuditLogRepository()
        val auditLogger = AuditLogger(auditRepo)

        auditLogger.roleGranted(
            clubId = 1,
            actorUserId = 10,
            subjectUserId = 20,
            role = "MANAGER",
            scope = "club",
        )
        auditLogger.roleGranted(
            clubId = 2,
            actorUserId = 10,
            subjectUserId = 20,
            role = "MANAGER",
            scope = "club",
        )

        assertEquals(2, auditRepo.events.size)
        val first = auditRepo.events[0]
        val second = auditRepo.events[1]
        assertNotEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `booking action includes outcome in fingerprint`() = runBlocking {
        val auditRepo = FakeAuditLogRepository()
        val auditLogger = AuditLogger(auditRepo)

        auditLogger.bookingAction(
            action = "booking.confirm",
            clubId = 1,
            entityId = "hold-1",
            metadata = buildJsonObject { put("result", "ok") },
        )
        auditLogger.bookingAction(
            action = "booking.confirm",
            clubId = 1,
            entityId = "hold-1",
            metadata = buildJsonObject { put("result", "conflict") },
        )

        assertEquals(2, auditRepo.events.size)
        val first = auditRepo.events[0]
        val second = auditRepo.events[1]
        assertNotEquals(first.fingerprint, second.fingerprint)
    }
}

private class FakeAuditLogRepository : AuditLogRepository {
    val events = mutableListOf<AuditLogEvent>()

    override suspend fun append(event: AuditLogEvent): Long {
        events.add(event)
        return events.size.toLong()
    }

    override suspend fun listForClub(
        clubId: Long,
        limit: Int,
        offset: Int,
    ): List<AuditLogRecord> = emptyList()

    override suspend fun listForUser(
        userId: Long,
        limit: Int,
        offset: Int,
    ): List<AuditLogRecord> = emptyList()
}
