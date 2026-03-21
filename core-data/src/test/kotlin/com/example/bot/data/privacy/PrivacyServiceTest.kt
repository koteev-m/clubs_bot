package com.example.bot.data.privacy

import com.example.bot.audit.AuditLogEvent
import com.example.bot.audit.AuditLogRecord
import com.example.bot.audit.AuditLogRepository
import com.example.bot.data.booking.BookingsTable
import com.example.bot.data.club.GuestListEntriesTable
import com.example.bot.data.club.GuestListsTable
import com.example.bot.data.security.UsersTable
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class PrivacyServiceTest {
    private val db = Database.connect("jdbc:h2:mem:privacy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", driver = "org.h2.Driver")
    private val fixedClock = Clock.fixed(Instant.parse("2026-03-21T10:15:30Z"), ZoneOffset.UTC)
    private val secret = "12345678901234567890123456789012"
    private val phoneCipher = PhoneCipher(secret)

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
    fun `phone hash is deterministic keyed hmac`() {
        val hash = phoneCipher.hash("+15551234567")

        assertEquals(hash, phoneCipher.hash("+15551234567"))
        assertEquals(expectedHmac(secret, "+15551234567"), hash)
        assertNotEquals(plainSha256("+15551234567"), hash)
        assertNotEquals(hash, PhoneCipher("abcdefghijklmnopqrstuvwxyz123456").hash("+15551234567"))
    }

    @Test
    fun `privacy config fails closed for prod like envs without key`() {
        listOf("prod", "production", "stage", "staging").forEach { profile ->
            val error =
                assertThrows(IllegalStateException::class.java) {
                    PrivacyConfig.fromEnv(mapOf("APP_PROFILE" to profile))
                }
            assertTrue(error.message!!.contains("PHONE_ENCRYPTION_KEY"))
        }
    }

    @Test
    fun `privacy config uses app env aliases and rejects dev default in prod like`() {
        listOf("prod", "production", "stage", "staging").forEach { envName ->
            val missingKey =
                assertThrows(IllegalStateException::class.java) {
                    PrivacyConfig.fromEnv(mapOf("APP_ENV" to envName))
                }
            assertTrue(missingKey.message!!.contains("PHONE_ENCRYPTION_KEY"))
        }

        val devConfig = PrivacyConfig.fromEnv(emptyMap())
        assertEquals(
            devConfig.phoneCipher.hash("+15551234567"),
            PrivacyConfig.fromEnv(mapOf("APP_PROFILE" to "dev")).phoneCipher.hash("+15551234567"),
        )
    }

    @Test
    fun `anonymize user scrubs linked rows by internal and telegram identifiers and writes audit`() = runBlocking {
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
            BookingsTable.insert {
                it[id] = java.util.UUID.randomUUID()
                it[eventId] = 1L
                it[clubId] = 1L
                it[tableId] = 1L
                it[tableNumber] = 10
                it[guestUserId] = 1L
                it[guestName] = "Guest"
                it[phoneE164] = null
                it[encryptedPhone] = protected.encrypted
                it[phoneHash] = protected.hash
                it[anonymizedAt] = null
                it[promoterUserId] = null
                it[guestsCount] = 2
                it[minDeposit] = java.math.BigDecimal.TEN
                it[totalDeposit] = java.math.BigDecimal.TEN
                it[slotStart] = fixedClock.instant().atOffset(ZoneOffset.UTC)
                it[slotEnd] = fixedClock.instant().plusSeconds(3600).atOffset(ZoneOffset.UTC)
                it[arrivalBy] = null
                it[status] = "CONFIRMED"
                it[qrSecret] = "secret"
                it[idempotencyKey] = "idem"
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
                it[telegramUserId] = 100L
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
        assertEquals(1, result.bookingsUpdated)
        assertEquals(1, result.guestListEntriesUpdated)
        transaction(db) {
            val user = UsersTable.selectAll().where { UsersTable.id eq 1L }.single()
            assertNull(user[UsersTable.phoneE164])
            assertNull(user[UsersTable.encryptedPhone])
            assertNull(user[UsersTable.phoneHash])
            assertTrue(user[UsersTable.anonymizedAt] != null)

            val booking = BookingsTable.selectAll().single()
            assertNull(booking[BookingsTable.encryptedPhone])
            assertNull(booking[BookingsTable.phoneHash])

            val guest = GuestListEntriesTable.selectAll().single()
            assertNull(guest[GuestListEntriesTable.phone])
            assertNull(guest[GuestListEntriesTable.encryptedPhone])
            assertNull(guest[GuestListEntriesTable.phoneHash])
        }
        assertEquals(1, audit.events.size)
        assertEquals("ANONYMIZE", audit.events.single().action.value)
    }

    private fun expectedHmac(secret: String, phone: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(("phone:" + phone).toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun plainSha256(phone: String): String =
        MessageDigest.getInstance("SHA-256").digest(("phone:" + phone).toByteArray()).joinToString("") { "%02x".format(it) }
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
