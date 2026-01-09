package com.example.bot.data.club

import com.example.bot.club.GuestListConfig
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListStatus
import com.example.bot.club.InvitationChannel
import com.example.bot.club.InvitationConfig
import com.example.bot.club.InvitationServiceResult
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InvitationServiceIT : PostgresClubIntegrationTest() {
    private val fixedInstant: Instant = Instant.parse("2024-06-02T12:00:00Z")
    private val fixedClock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    private lateinit var guestListRepo: GuestListDbRepository
    private lateinit var entryRepo: GuestListEntryDbRepository
    private lateinit var invitationRepo: InvitationDbRepository

    @BeforeEach
    fun initRepositories() {
        guestListRepo = GuestListDbRepository(database, fixedClock)
        entryRepo = GuestListEntryDbRepository(database, fixedClock)
        invitationRepo = InvitationDbRepository(database, fixedClock)
    }

    @Test
    fun `createInvitation stores only token hash`() = runBlocking {
        val clubId = insertClub(name = "Aurora")
        val eventId =
            insertEvent(
                clubId = clubId,
                title = "Showcase",
                startAt = fixedInstant,
                endAt = fixedInstant.plusSeconds(3600),
            )
        val ownerId = insertUser(username = "owner", displayName = "Owner")

        val guestList =
            guestListRepo.create(
                NewGuestList(
                    clubId = clubId,
                    eventId = eventId,
                    promoterId = ownerId,
                    ownerType = GuestListOwnerType.PROMOTER,
                    ownerUserId = ownerId,
                    title = "VIP",
                    capacity = 50,
                    arrivalWindowStart = fixedInstant,
                    arrivalWindowEnd = fixedInstant.plusSeconds(1800),
                    status = GuestListStatus.ACTIVE,
                ),
            )
        val entry =
            entryRepo
                .insertMany(
                    guestList.id,
                    listOf(NewGuestListEntry(displayName = "Alice", telegramUserId = null)),
                ).single()

        val tokenBytes = ByteArray(32) { it.toByte() }
        val expectedToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val service =
            InvitationServiceImpl(
                invitationRepo,
                guestListRepo,
                entryRepo,
                GuestListConfig(bulkMaxChars = 20_000, noShowGraceMinutes = 30),
                InvitationConfig(ttlHours = 72, botUsername = "clubbot"),
                fixedClock,
                FixedSecureRandom(tokenBytes),
            )

        val result = service.createInvitation(entry.id, InvitationChannel.TELEGRAM, createdBy = ownerId)

        check(result is InvitationServiceResult.Success)
        assertEquals(expectedToken, result.value.token)

        val stored =
            transaction(database) {
                InvitationsTable.selectAll()
                    .where { InvitationsTable.guestListEntryId eq entry.id }
                    .single()
            }
        val storedHash = stored[InvitationsTable.tokenHash]
        assertEquals(expectedToken.sha256Hex(), storedHash)
        assertNotEquals(expectedToken, storedHash)
        assertTrue(storedHash.matches(Regex("^[0-9a-f]{64}$")))
    }

    private class FixedSecureRandom(private val bytes: ByteArray) : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.indices.forEach { index -> bytes[index] = this.bytes[index % this.bytes.size] }
        }
    }

    private fun String.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
