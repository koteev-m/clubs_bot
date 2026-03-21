package com.example.bot.data.privacy

import com.example.bot.audit.AuditLogEvent
import com.example.bot.audit.AuditLogRecord
import com.example.bot.audit.AuditLogRepository
import com.example.bot.data.booking.BookingsTable
import com.example.bot.data.club.GuestListEntriesTable
import com.example.bot.data.club.GuestListsTable
import com.example.bot.data.security.UsersTable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class PrivacyServiceTest {
    private val db = Database.connect("jdbc:h2:mem:privacy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", driver = "org.h2.Driver")
    private val fixedClock = Clock.fixed(Instant.parse("2026-03-21T10:15:30Z"), ZoneOffset.UTC)
    private val phoneCipher = PhoneCipher("12345678901234567890123456789012")

    init {
        transaction(db) {
            SchemaUtils.drop(BookingsTable, GuestListEntriesTable, GuestListsTable, UsersTable)
            SchemaUtils.create(UsersTable, GuestListsTable, GuestListEntriesTable, BookingsTable)
        }
    }

    @Test
    fun `phone cipher encrypts and decrypts without plaintext reuse`() {
        val protected = phoneCipher.protect("+15551234567")

        assertNotEquals("+15551234567", protected.encrypted)
        assertEquals(64, protected.hash.length)
        assertEquals("+15551234567", phoneCipher.decrypt(protected.encrypted))
    }

    @Test
    fun `anonymize user scrubs phones and writes audit`() = runBlocking {
        val audit = RecordingAuditLogRepository()
        val service = PrivacyService(db, phoneCipher, PrivacyRetentionConfig(Duration.ofDays(30)), audit, fixedClock)
        transaction(db) {
            val protected = phoneCipher.protect("+15551234567")
            UsersTable.insert {
                it[id] = 1L
                it[telegramUserId] = 100L
                it[username] = "guest"
                it[displayName] = "Guest"
                it[phoneE164] = null
                it[encryptedPhone] = protected.encrypted
                it[phoneHash] = protected.hash
                it[anonymizedAt] = null
            }
            GuestListsTable.insert {
                it[id] = 10L
                it[clubId] = 1L
                it[eventId] = 1L
                it[promoterId] = null
                it[ownerType] = "PROMOTER"
                it[ownerUserId] = 1L
                it[title] = "VIP"
                it[capacity] = 10
                it[arrivalWindowStart] = null
                it[arrivalWindowEnd] = null
                it[status] = "ACTIVE"
                it[createdAt] = fixedClock.instant().atOffset(ZoneOffset.UTC)
                it[updatedAt] = fixedClock.instant().atOffset(ZoneOffset.UTC)
            }
            GuestListEntriesTable.insert {
                it[id] = 20L
                it[guestListId] = 10L
                it[displayName] = "Guest"
                it[fullName] = "Guest"
                it[tgUsername] = null
                it[phone] = null
                it[encryptedPhone] = protected.encrypted
                it[phoneHash] = protected.hash
                it[anonymizedAt] = null
                it[telegramUserId] = 1L
                it[plusOnesAllowed] = 0
                it[plusOnesUsed] = 0
                it[category] = "DEFAULT"
                it[comment] = null
                it[status] = "PLANNED"
                it[checkedInAt] = null
                it[checkedInBy] = null
                it[createdAt] = fixedClock.instant().atOffset(ZoneOffset.UTC)
                it[updatedAt] = fixedClock.instant().atOffset(ZoneOffset.UTC)
            }
        }

        val result = service.anonymizeUser(1L, PrivacyAdminActor(99L, "OWNER"), "dsr-request")

        assertEquals(1, result.usersUpdated)
        assertEquals(1, result.guestListEntriesUpdated)
        transaction(db) {
            val user = UsersTable.selectAll().where { UsersTable.id eq 1L }.single()
            assertNull(user[UsersTable.phoneE164])
            assertNull(user[UsersTable.encryptedPhone])
            assertNull(user[UsersTable.phoneHash])
            assertTrue(user[UsersTable.anonymizedAt] != null)
        }
        assertEquals(1, audit.events.size)
        assertEquals("ANONYMIZE", audit.events.single().action.value)
    }
}

private class RecordingAuditLogRepository : AuditLogRepository {
    val events = mutableListOf<AuditLogEvent>()

    override suspend fun append(event: AuditLogEvent): Long {
        events += event
        return events.size.toLong()
    }

    override suspend fun listForClub(clubId: Long, limit: Int, offset: Int): List<AuditLogRecord> = emptyList()

    override suspend fun listForUser(userId: Long, limit: Int, offset: Int): List<AuditLogRecord> = emptyList()
}
