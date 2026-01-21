package com.example.bot.data.club

import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListStatus
import com.example.bot.club.InvitationChannel
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

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
    fun `createAndRevokeOtherActiveByEntryId revokes other active invitations and keeps inactive unchanged`() =
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
                        listOf(NewGuestListEntry(displayName = "Bob", telegramUserId = null, status = GuestListEntryStatus.CONFIRMED)),
                    ).single()

            val futureExpiry = fixedInstant.plusSeconds(7200)
            val expiredInvite =
                invitationRepo.create(
                    entry.id,
                    token = "token-expired",
                    tokenHash = sha256Hex("token-expired"),
                    channel = InvitationChannel.TELEGRAM,
                    expiresAt = fixedInstant.minusSeconds(60),
                    createdBy = ownerId,
                )
            val usedInvite =
                invitationRepo.create(
                    entry.id,
                    token = "token-used",
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
                    token = "token-revoked",
                    tokenHash = sha256Hex("token-revoked"),
                    channel = InvitationChannel.TELEGRAM,
                    expiresAt = futureExpiry,
                    createdBy = ownerId,
                )
            invitationRepo.revoke(revokedInvite.id, alreadyRevokedAt)

            val inv1 =
                invitationRepo.create(
                    entry.id,
                    token = "token-old",
                    tokenHash = sha256Hex("token-old"),
                    channel = InvitationChannel.TELEGRAM,
                    expiresAt = futureExpiry,
                    createdBy = ownerId,
                )
            val inv2 =
                invitationRepo.create(
                    entry.id,
                    token = "token-keep",
                    tokenHash = sha256Hex("token-keep"),
                    channel = InvitationChannel.TELEGRAM,
                    expiresAt = futureExpiry,
                    createdBy = ownerId,
                )
            val inv3 =
                invitationRepo.create(
                    entry.id,
                    token = "token-new",
                    tokenHash = sha256Hex("token-new"),
                    channel = InvitationChannel.TELEGRAM,
                    expiresAt = futureExpiry,
                    createdBy = ownerId,
                )
            val otherEntryInvite =
                invitationRepo.create(
                    anotherEntry.id,
                    token = "token-other-entry",
                    tokenHash = sha256Hex("token-other-entry"),
                    channel = InvitationChannel.TELEGRAM,
                    expiresAt = futureExpiry,
                    createdBy = ownerId,
                )

            val created =
                invitationRepo.createAndRevokeOtherActiveByEntryId(
                    entry.id,
                    token = "token-created",
                    tokenHash = sha256Hex("token-created"),
                    channel = InvitationChannel.TELEGRAM,
                    expiresAt = futureExpiry,
                    createdBy = ownerId,
                    now = fixedInstant,
                )

            val invitations = loadInvitations(entry.id)
            assertEquals(fixedInstant, invitations.getValue(inv1.id).revokedAt)
            assertEquals(fixedInstant, invitations.getValue(inv2.id).revokedAt)
            assertEquals(fixedInstant, invitations.getValue(inv3.id).revokedAt)
            assertNull(invitations.getValue(created.id).revokedAt)
            assertNull(invitations.getValue(created.id).usedAt)
            assertEquals(futureExpiry, invitations.getValue(created.id).expiresAt)
            assertNull(invitations.getValue(expiredInvite.id).revokedAt)
            assertNull(invitations.getValue(expiredInvite.id).usedAt)
            assertEquals(alreadyRevokedAt, invitations.getValue(revokedInvite.id).revokedAt)
            assertNull(invitations.getValue(revokedInvite.id).usedAt)
            assertEquals(fixedInstant.minusSeconds(30), invitations.getValue(usedInvite.id).usedAt)
            assertNull(invitations.getValue(usedInvite.id).revokedAt)

            val otherEntryInvitations = loadInvitations(anotherEntry.id)
            assertNull(otherEntryInvitations.getValue(otherEntryInvite.id).revokedAt)
            assertNull(otherEntryInvitations.getValue(otherEntryInvite.id).usedAt)

            val entryAfter = guestListEntryRepo.findById(entry.id)
            val anotherEntryAfter = guestListEntryRepo.findById(anotherEntry.id)
            assertEquals(GuestListEntryStatus.INVITED, entryAfter?.status)
            assertEquals(GuestListEntryStatus.CONFIRMED, anotherEntryAfter?.status)

            val activeCount =
                invitations.values.count { state ->
                    state.revokedAt == null && state.usedAt == null && state.expiresAt > fixedInstant
                }
            assertEquals(1, activeCount)
        }

    @Test
    fun `cannot mark used after revoke`() = runBlocking {
        val clubId = insertClub(name = "Aurora")
        val eventId = insertEvent(clubId, "Showcase", fixedInstant, fixedInstant.plusSeconds(3600))
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
            guestListEntryRepo.insertMany(
                guestList.id,
                listOf(NewGuestListEntry(displayName = "Alice", telegramUserId = null, status = GuestListEntryStatus.ADDED)),
            ).single()

        val invite =
            invitationRepo.create(
                entry.id,
                token = "token",
                tokenHash = sha256Hex("token"),
                channel = InvitationChannel.TELEGRAM,
                expiresAt = fixedInstant.plusSeconds(1200),
                createdBy = ownerId,
            )

        val revoked = invitationRepo.revoke(invite.id, fixedInstant)
        val used = invitationRepo.markUsed(invite.id, fixedInstant.plusSeconds(60))

        assertTrue(revoked)
        assertFalse(used)

        val invitations = loadInvitations(entry.id)
        assertEquals(fixedInstant, invitations.getValue(invite.id).revokedAt)
        assertNull(invitations.getValue(invite.id).usedAt)
    }

    @Test
    fun `cannot revoke after mark used`() = runBlocking {
        val clubId = insertClub(name = "Aurora")
        val eventId = insertEvent(clubId, "Showcase", fixedInstant, fixedInstant.plusSeconds(3600))
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
            guestListEntryRepo.insertMany(
                guestList.id,
                listOf(NewGuestListEntry(displayName = "Alice", telegramUserId = null, status = GuestListEntryStatus.ADDED)),
            ).single()

        val invite =
            invitationRepo.create(
                entry.id,
                token = "token",
                tokenHash = sha256Hex("token"),
                channel = InvitationChannel.TELEGRAM,
                expiresAt = fixedInstant.plusSeconds(1200),
                createdBy = ownerId,
            )

        val used = invitationRepo.markUsed(invite.id, fixedInstant)
        val revoked = invitationRepo.revoke(invite.id, fixedInstant.plusSeconds(60))

        assertTrue(used)
        assertFalse(revoked)

        val invitations = loadInvitations(entry.id)
        assertNull(invitations.getValue(invite.id).revokedAt)
        assertEquals(fixedInstant, invitations.getValue(invite.id).usedAt)
    }

    @Test
    fun `check constraint prevents revoked and used simultaneously`() = runBlocking {
        val clubId = insertClub(name = "Aurora")
        val eventId = insertEvent(clubId, "Showcase", fixedInstant, fixedInstant.plusSeconds(3600))
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
            guestListEntryRepo.insertMany(
                guestList.id,
                listOf(NewGuestListEntry(displayName = "Alice", telegramUserId = null, status = GuestListEntryStatus.ADDED)),
            ).single()

        val invite =
            invitationRepo.create(
                entry.id,
                token = "token",
                tokenHash = sha256Hex("token"),
                channel = InvitationChannel.TELEGRAM,
                expiresAt = fixedInstant.plusSeconds(1200),
                createdBy = ownerId,
            )

        val exception =
            assertThrows<ExposedSQLException> {
                transaction(database) {
                    InvitationsTable.update({ InvitationsTable.id eq invite.id }) {
                        it[revokedAt] = fixedInstant.atOffset(ZoneOffset.UTC)
                        it[usedAt] = fixedInstant.plusSeconds(60).atOffset(ZoneOffset.UTC)
                    }
                }
            }

        val psqlException = generateSequence(exception.cause) { it?.cause }.filterIsInstance<PSQLException>().firstOrNull()
        assertNotNull(psqlException)
        assertEquals("invitations_revoked_and_used_mutual_exclusion", psqlException?.serverErrorMessage?.constraint)
        assertEquals("23514", psqlException?.sqlState)
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
                    val expiresAt = result[InvitationsTable.expiresAt].toInstant()

                    id to InvitationState(revokedAt = revokedAt, usedAt = usedAt, expiresAt = expiresAt)
                }
        }

    private data class InvitationState(
        val revokedAt: Instant?,
        val usedAt: Instant?,
        val expiresAt: Instant,
    )
}
