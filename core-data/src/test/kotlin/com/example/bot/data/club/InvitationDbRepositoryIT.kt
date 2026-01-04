package com.example.bot.data.club

import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListStatus
import com.example.bot.club.InvitationChannel
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.security.MessageDigest

class InvitationDbRepositoryIT : PostgresClubIntegrationTest() {
    private val fixedInstant: Instant = Instant.parse("2024-06-01T12:00:00Z")
    private val fixedClock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    private lateinit var guestListRepo: GuestListDbRepository
    private lateinit var guestListEntryRepo: GuestListEntryDbRepository
    private lateinit var invitationRepo: InvitationDbRepository

    @BeforeEach
    fun initRepositories() {
        guestListRepo = GuestListDbRepository(database, fixedClock)
        guestListEntryRepo = GuestListEntryDbRepository(database, fixedClock)
        invitationRepo = InvitationDbRepository(database, fixedClock)
    }

    @Test
    fun `revoke older active invitations keeps newer and inactive unchanged`() =
        runBlocking {
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
                guestListEntryRepo
                    .insertMany(
                        guestList.id,
                        listOf(NewGuestListEntry(displayName = "Alice", telegramUserId = null, status = GuestListEntryStatus.ADDED)),
                    ).single()
            val anotherEntry =
                guestListEntryRepo
                    .insertMany(
                        guestList.id,
                        listOf(NewGuestListEntry(displayName = "Bob", telegramUserId = null, status = GuestListEntryStatus.ADDED)),
                    ).single()

            val futureExpiry = fixedInstant.plusSeconds(7200)
            val expiredInvite =
                invitationRepo.create(
                    entry.id,
                    tokenHash = sha256Hex("token-expired"),
                    channel = InvitationChannel.TELEGRAM,
                    expiresAt = fixedInstant.minusSeconds(60),
                    createdBy = ownerId,
                )
            val usedInvite =
                invitationRepo.create(
                    entry.id,
                    tokenHash = sha256Hex("token-used"),
                    channel = InvitationChannel.TELEGRAM,
                    expiresAt = futureExpiry,
                    createdBy = ownerId,
                )
            invitationRepo.markUsed(usedInvite.id, fixedInstant.minusSeconds(30))

            val alreadyRevokedAt = fixedInstant.minusSeconds(120)
            val revokedInvite =
                invitationRepo.create(
                    entry.id,
                    tokenHash = sha256Hex("token-revoked"),
                    channel = InvitationChannel.TELEGRAM,
                    expiresAt = futureExpiry,
                    createdBy = ownerId,
                )
            invitationRepo.revoke(revokedInvite.id, alreadyRevokedAt)

            val inv1 =
                invitationRepo.create(
                    entry.id,
                    tokenHash = sha256Hex("token-old"),
                    channel = InvitationChannel.TELEGRAM,
                    expiresAt = futureExpiry,
                    createdBy = ownerId,
                )
            val inv2 =
                invitationRepo.create(
                    entry.id,
                    tokenHash = sha256Hex("token-keep"),
                    channel = InvitationChannel.TELEGRAM,
                    expiresAt = futureExpiry,
                    createdBy = ownerId,
                )
            val inv3 =
                invitationRepo.create(
                    entry.id,
                    tokenHash = sha256Hex("token-new"),
                    channel = InvitationChannel.TELEGRAM,
                    expiresAt = futureExpiry,
                    createdBy = ownerId,
                )
            val otherEntryInvite =
                invitationRepo.create(
                    anotherEntry.id,
                    tokenHash = sha256Hex("token-other-entry"),
                    channel = InvitationChannel.TELEGRAM,
                    expiresAt = futureExpiry,
                    createdBy = ownerId,
                )

            val revokeCount = invitationRepo.revokeOlderActiveByEntryId(entry.id, inv2.id, revokedAt = fixedInstant)

            assertEquals(1, revokeCount)

            val invitations = loadInvitations(entry.id)
            assertEquals(fixedInstant, invitations.getValue(inv1.id).revokedAt)
            assertNull(invitations.getValue(inv2.id).revokedAt)
            assertNull(invitations.getValue(inv3.id).revokedAt)
            assertNull(invitations.getValue(expiredInvite.id).revokedAt)
            assertEquals(alreadyRevokedAt, invitations.getValue(revokedInvite.id).revokedAt)
            assertEquals(fixedInstant.minusSeconds(30), invitations.getValue(usedInvite.id).usedAt)
            assertNull(invitations.getValue(usedInvite.id).revokedAt)
            assertNull(invitations.getValue(expiredInvite.id).usedAt)

            val otherEntryInvitations = loadInvitations(anotherEntry.id)
            assertNull(otherEntryInvitations.getValue(otherEntryInvite.id).revokedAt)
            assertNull(otherEntryInvitations.getValue(otherEntryInvite.id).usedAt)
        }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun loadInvitations(entryId: Long): Map<Long, InvitationState> =
        transaction(database) {
            InvitationsTable
                .selectAll()
                .where { InvitationsTable.guestListEntryId eq entryId }
                .associate { result ->
                    val id = result[InvitationsTable.id]
                    val revokedAt = result[InvitationsTable.revokedAt]?.toInstant()
                    val usedAt = result[InvitationsTable.usedAt]?.toInstant()

                    id to InvitationState(revokedAt = revokedAt, usedAt = usedAt)
                }
        }

    private data class InvitationState(
        val revokedAt: Instant?,
        val usedAt: Instant?,
    )
}
