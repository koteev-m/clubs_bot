package com.example.bot.data.booking

import com.example.bot.data.audit.AuditLogTable
import com.example.bot.data.notifications.NotificationsOutboxTable
import com.example.bot.data.notifications.OutboxStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BookingTablesSmokeTest {
    @Test
    fun `booking status enum matches schema`() {
        assertEquals(
            setOf("BOOKED", "SEATED", "NO_SHOW", "CANCELLED"),
            BookingStatus.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `booking columns use timestamptz`() {
        val expected = "org.jetbrains.exposed.sql.javatime.JavaOffsetDateTimeColumnType"
        assertEquals(expected, BookingsTable.slotStart.columnType::class.qualifiedName)
        assertEquals(expected, BookingsTable.slotEnd.columnType::class.qualifiedName)
        assertEquals(expected, BookingHoldsTable.slotStart.columnType::class.qualifiedName)
        assertEquals(expected, BookingHoldsTable.slotEnd.columnType::class.qualifiedName)
    }

    @Test
    fun `outbox uses jsonb and enum status`() {
        assertEquals(
            setOf("NEW", "SENT", "FAILED"),
            OutboxStatus.entries.map { it.name }.toSet(),
        )
        assertEquals(
            "org.jetbrains.exposed.sql.json.JsonBColumnType",
            NotificationsOutboxTable.payload.columnType::class.qualifiedName,
        )
    }

    @Test
    fun `audit log meta is jsonb`() {
        assertEquals(
            "org.jetbrains.exposed.sql.json.JsonBColumnType",
            AuditLogTable.meta.columnType::class.qualifiedName,
        )
    }
}
