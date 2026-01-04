package com.example.bot.data.club

import com.example.bot.club.GuestListConfig
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListStatus
import com.example.bot.club.InvitationChannel
import com.example.bot.club.InvitationConfig
import com.example.bot.club.InvitationResponse
import com.example.bot.club.InvitationServiceError
import com.example.bot.club.InvitationServiceResult
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class InvitationServiceTest {
    private val invitationRepo: InvitationDbRepository = mockk()
    private val guestListRepo: GuestListDbRepository = mockk()
    private val entryRepo: GuestListEntryDbRepository = mockk()
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2024-07-01T18:00:00Z"), ZoneOffset.UTC)
    private val guestListConfig = GuestListConfig(bulkMaxChars = 20_000, noShowGraceMinutes = 30)
    private val invitationConfig = InvitationConfig(ttlHours = 72, botUsername = "clubbot")

    @Test
    fun `createInvitation generates token and hashes`() = runBlocking {
        val entry = entryRecord(status = GuestListEntryStatus.ADDED)
        val guestList = guestListRecord(arrivalWindowEnd = null)
        val tokenBytes = ByteArray(32) { it.toByte() }
        val expectedToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val tokenHashSlot: CapturingSlot<String> = slot()

        coEvery { entryRepo.findById(entry.id) } returns entry
        coEvery { guestListRepo.findById(entry.guestListId) } returns guestList
        coEvery {
            invitationRepo.createAndRevokeOtherActiveByEntryId(
                entry.id,
                capture(tokenHashSlot),
                InvitationChannel.TELEGRAM,
                any(),
                createdBy = 42,
                now = fixedClock.instant(),
            )
        } answers {
            val expires = arg<Instant>(3)
            invitationRecord(
                id = 10,
                entryId = entry.id,
                expiresAt = expires,
                createdBy = 42,
            )
        }

        val service =
            InvitationServiceImpl(
                invitationRepo,
                guestListRepo,
                entryRepo,
                guestListConfig,
                invitationConfig,
                fixedClock,
                FixedSecureRandom(tokenBytes),
            )

        val result = service.createInvitation(entry.id, InvitationChannel.TELEGRAM, createdBy = 42)

        when (result) {
            is InvitationServiceResult.Success -> {
                val payload = result.value
                assertEquals(expectedToken, payload.token)
                assertEquals(43, payload.token.length)
                assertTrue(payload.token.matches(Regex("^[A-Za-z0-9_-]+$")))
                assertEquals("https://t.me/clubbot?start=inv_${expectedToken}", payload.deepLinkUrl)
                assertEquals("inv:${expectedToken}", payload.qrPayload)
                assertEquals(fixedClock.instant().plus(Duration.ofHours(invitationConfig.ttlHours.toLong())), payload.expiresAt)
                val expectedHash = expectedToken.sha256Hex()
                assertEquals(expectedHash, tokenHashSlot.captured)
                assertEquals(64, tokenHashSlot.captured.length)
                assertTrue(tokenHashSlot.captured.matches(Regex("^[0-9a-f]{64}$")))
            }
            is InvitationServiceResult.Failure -> fail("expected success, got ${result.error}")
        }

        coVerify(exactly = 0) { entryRepo.updateStatus(any(), any()) }
    }

    @Test
    fun `createInvitation keeps confirmed status`() = runBlocking {
        val entry = entryRecord(status = GuestListEntryStatus.CONFIRMED)
        val guestList = guestListRecord(arrivalWindowEnd = fixedClock.instant())
        coEvery { entryRepo.findById(entry.id) } returns entry
        coEvery { guestListRepo.findById(entry.guestListId) } returns guestList
        coEvery {
            invitationRepo.createAndRevokeOtherActiveByEntryId(
                any(),
                any(),
                any(),
                any(),
                any(),
                now = any(),
            )
        } returns
            invitationRecord(
                id = 11,
                entryId = entry.id,
                expiresAt = guestList.arrivalWindowEnd!!.plus(Duration.ofMinutes(30)),
            )

        val service = InvitationServiceImpl(
            invitationRepo,
            guestListRepo,
            entryRepo,
            guestListConfig,
            invitationConfig,
            fixedClock,
            FixedSecureRandom(ByteArray(32)),
        )

        val result = service.createInvitation(entry.id, InvitationChannel.EXTERNAL, createdBy = 7)
        check(result is InvitationServiceResult.Success)
        assertEquals(
            guestList.arrivalWindowEnd!!.plus(Duration.ofMinutes(guestListConfig.noShowGraceMinutes.toLong())),
            result.value.expiresAt,
        )
        coVerify(exactly = 0) { entryRepo.updateStatus(any(), any()) }
    }

    @Test
    fun `reissue revokes active invitation`() = runBlocking {
        val entry = entryRecord(status = GuestListEntryStatus.ADDED)
        val guestList = guestListRecord()
        coEvery { entryRepo.findById(entry.id) } returns entry
        coEvery { guestListRepo.findById(entry.guestListId) } returns guestList
        coEvery {
            invitationRepo.createAndRevokeOtherActiveByEntryId(
                any(),
                any(),
                any(),
                any(),
                any(),
                now = fixedClock.instant(),
            )
        } returns invitationRecord(id = 12, entryId = entry.id, expiresAt = fixedClock.instant().plusSeconds(7200))

        val service = InvitationServiceImpl(
            invitationRepo,
            guestListRepo,
            entryRepo,
            guestListConfig,
            invitationConfig,
            fixedClock,
            FixedSecureRandom(ByteArray(32)),
        )

        service.createInvitation(entry.id, InvitationChannel.TELEGRAM, createdBy = 1)

        coVerify(exactly = 1) {
            invitationRepo.createAndRevokeOtherActiveByEntryId(
                entry.id,
                any(),
                InvitationChannel.TELEGRAM,
                any(),
                any(),
                now = fixedClock.instant(),
            )
        }
        coVerify(exactly = 0) { entryRepo.updateStatus(any(), any()) }
    }

    @Test
    fun `reissue does not revoke when creation fails`() = runBlocking {
        val entry = entryRecord(status = GuestListEntryStatus.ADDED)
        val guestList = guestListRecord()
        coEvery { entryRepo.findById(entry.id) } returns entry
        coEvery { guestListRepo.findById(entry.guestListId) } returns guestList
        coEvery {
            invitationRepo.createAndRevokeOtherActiveByEntryId(
                any(),
                any(),
                any(),
                any(),
                any(),
                now = fixedClock.instant(),
            )
        } throws IllegalStateException("fail")

        val service = InvitationServiceImpl(
            invitationRepo,
            guestListRepo,
            entryRepo,
            guestListConfig,
            invitationConfig,
            fixedClock,
            FixedSecureRandom(ByteArray(32)),
        )

        try {
            service.createInvitation(entry.id, InvitationChannel.TELEGRAM, createdBy = 1)
            fail("expected failure")
        } catch (_: IllegalStateException) {
        }

        coVerify(exactly = 1) { entryRepo.findById(entry.id) }
        coVerify(exactly = 1) { guestListRepo.findById(entry.guestListId) }
        coVerify(exactly = 1) {
            invitationRepo.createAndRevokeOtherActiveByEntryId(
                entry.id,
                any(),
                InvitationChannel.TELEGRAM,
                any(),
                any(),
                now = fixedClock.instant(),
            )
        }
        coVerify(exactly = 0) { entryRepo.updateStatus(any(), any()) }
        confirmVerified(invitationRepo, entryRepo, guestListRepo)
    }

    @Test
    fun `bot username is normalized for deep link`() = runBlocking {
        val entry = entryRecord(status = GuestListEntryStatus.ADDED)
        val guestList = guestListRecord(arrivalWindowEnd = null)
        val tokenBytes = ByteArray(32) { it.toByte() }
        val expectedToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val tokenHashSlot: CapturingSlot<String> = slot()

        coEvery { entryRepo.findById(entry.id) } returns entry
        coEvery { guestListRepo.findById(entry.guestListId) } returns guestList
        coEvery {
            invitationRepo.createAndRevokeOtherActiveByEntryId(
                entry.id,
                capture(tokenHashSlot),
                InvitationChannel.TELEGRAM,
                any(),
                createdBy = 42,
                now = fixedClock.instant(),
            )
        } answers {
            val expires = arg<Instant>(3)
            invitationRecord(
                id = 12,
                entryId = entry.id,
                expiresAt = expires,
                createdBy = 42,
            )
        }

        val normalizedConfig = InvitationConfig(ttlHours = 72, botUsername = "@clubbot ")
        val service =
            InvitationServiceImpl(
                invitationRepo,
                guestListRepo,
                entryRepo,
                guestListConfig,
                normalizedConfig,
                fixedClock,
                FixedSecureRandom(tokenBytes),
            )

        val result = service.createInvitation(entry.id, InvitationChannel.TELEGRAM, createdBy = 42)

        when (result) {
            is InvitationServiceResult.Success -> {
                val payload = result.value
                assertEquals("https://t.me/clubbot?start=inv_${expectedToken}", payload.deepLinkUrl)
            }
            is InvitationServiceResult.Failure -> fail("expected success, got ${result.error}")
        }
    }

    @Test
    fun `createInvitation fails when guest list is not active`() = runBlocking {
        val entry = entryRecord(status = GuestListEntryStatus.ADDED)
        val guestList = guestListRecord(status = GuestListStatus.CLOSED)

        coEvery { entryRepo.findById(entry.id) } returns entry
        coEvery { guestListRepo.findById(entry.guestListId) } returns guestList

        val service = InvitationServiceImpl(
            invitationRepo,
            guestListRepo,
            entryRepo,
            guestListConfig,
            invitationConfig,
            fixedClock,
            FixedSecureRandom(ByteArray(32)),
        )

        val result = service.createInvitation(entry.id, InvitationChannel.TELEGRAM, createdBy = 1)

        assertTrue(result is InvitationServiceResult.Failure && result.error == InvitationServiceError.GUEST_LIST_NOT_ACTIVE)
        coVerify(exactly = 0) { invitationRepo.createAndRevokeOtherActiveByEntryId(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `createInvitation fails when expiry is not after now`() = runBlocking {
        val entry = entryRecord(status = GuestListEntryStatus.ADDED)
        val expiredArrivalEnd = fixedClock.instant().minus(Duration.ofMinutes(30))
        val guestList = guestListRecord(arrivalWindowEnd = expiredArrivalEnd)

        coEvery { entryRepo.findById(entry.id) } returns entry
        coEvery { guestListRepo.findById(entry.guestListId) } returns guestList

        val service = InvitationServiceImpl(
            invitationRepo,
            guestListRepo,
            entryRepo,
            guestListConfig,
            invitationConfig,
            fixedClock,
            FixedSecureRandom(ByteArray(32)),
        )

        val result = service.createInvitation(entry.id, InvitationChannel.TELEGRAM, createdBy = 1)

        assertTrue(result is InvitationServiceResult.Failure && result.error == InvitationServiceError.GUEST_LIST_NOT_ACTIVE)
        coVerify(exactly = 0) { invitationRepo.createAndRevokeOtherActiveByEntryId(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `resolve errors propagate states`() = runBlocking {
        val rawToken = "token"
        val tokenHash = rawToken.sha256Hex()
        val expiredInvitation = invitationRecord(expiresAt = fixedClock.instant().minusSeconds(10))
        val revokedInvitation = invitationRecord(revokedAt = fixedClock.instant())
        val usedInvitation =
            invitationRecord(
                expiresAt = fixedClock.instant().plusSeconds(3600),
                usedAt = fixedClock.instant(),
            )

        coEvery { invitationRepo.findByTokenHash(tokenHash) } returnsMany listOf(null, revokedInvitation, expiredInvitation, usedInvitation)

        val service = InvitationServiceImpl(
            invitationRepo,
            guestListRepo,
            entryRepo,
            guestListConfig,
            invitationConfig,
            fixedClock,
            FixedSecureRandom(ByteArray(32)),
        )

        val invalid = service.resolveInvitation(rawToken)
        assertTrue(invalid is InvitationServiceResult.Failure && invalid.error == InvitationServiceError.INVITATION_INVALID)

        val revoked = service.resolveInvitation(rawToken)
        assertTrue(revoked is InvitationServiceResult.Failure && revoked.error == InvitationServiceError.INVITATION_REVOKED)

        val expired = service.resolveInvitation(rawToken)
        assertTrue(expired is InvitationServiceResult.Failure && expired.error == InvitationServiceError.INVITATION_EXPIRED)

        val used = service.resolveInvitation(rawToken)
        assertTrue(used is InvitationServiceResult.Failure && used.error == InvitationServiceError.INVITATION_ALREADY_USED)
    }

    @Test
    fun `resolve rejects overlong token without db lookup`() = runBlocking {
        val longToken = "a".repeat(129)

        val service = InvitationServiceImpl(
            invitationRepo,
            guestListRepo,
            entryRepo,
            guestListConfig,
            invitationConfig,
            fixedClock,
            FixedSecureRandom(ByteArray(32)),
        )

        val result = service.resolveInvitation(longToken)

        assertTrue(result is InvitationServiceResult.Failure && result.error == InvitationServiceError.INVITATION_INVALID)
        coVerify(exactly = 0) { invitationRepo.findByTokenHash(any()) }
    }

    @Test
    fun `resolve rejects invalid characters without db lookup`() = runBlocking {
        val invalidToken = "inv@token"

        val service = InvitationServiceImpl(
            invitationRepo,
            guestListRepo,
            entryRepo,
            guestListConfig,
            invitationConfig,
            fixedClock,
            FixedSecureRandom(ByteArray(32)),
        )

        val result = service.resolveInvitation(invalidToken)

        assertTrue(result is InvitationServiceResult.Failure && result.error == InvitationServiceError.INVITATION_INVALID)
        coVerify(exactly = 0) { invitationRepo.findByTokenHash(any()) }
    }

    @Test
    fun `resolve rejects non-ascii letters without db lookup`() = runBlocking {
        val invalidToken = "токен"

        val service = InvitationServiceImpl(
            invitationRepo,
            guestListRepo,
            entryRepo,
            guestListConfig,
            invitationConfig,
            fixedClock,
            FixedSecureRandom(ByteArray(32)),
        )

        val result = service.resolveInvitation(invalidToken)

        assertTrue(result is InvitationServiceResult.Failure && result.error == InvitationServiceError.INVITATION_INVALID)
        coVerify(exactly = 0) { invitationRepo.findByTokenHash(any()) }
    }

    @Test
    fun `resolve rejects non-ascii digits without db lookup`() = runBlocking {
        val invalidToken = "١٢٣"

        val service = InvitationServiceImpl(
            invitationRepo,
            guestListRepo,
            entryRepo,
            guestListConfig,
            invitationConfig,
            fixedClock,
            FixedSecureRandom(ByteArray(32)),
        )

        val result = service.resolveInvitation(invalidToken)

        assertTrue(result is InvitationServiceResult.Failure && result.error == InvitationServiceError.INVITATION_INVALID)
        coVerify(exactly = 0) { invitationRepo.findByTokenHash(any()) }
    }

    @Test
    fun `respond updates status and telegram id`() = runBlocking {
        val entry = entryRecord(status = GuestListEntryStatus.INVITED, telegramUserId = null)
        val guestList = guestListRecord()
        val invitation = invitationRecord(entryId = entry.id, expiresAt = fixedClock.instant().plusSeconds(3600))
        val token = "replyToken"
        val tokenHash = token.sha256Hex()

        coEvery { invitationRepo.findByTokenHash(tokenHash) } returns invitation
        coEvery { entryRepo.findById(entry.id) } returns entry
        coEvery { guestListRepo.findById(guestList.id) } returns guestList
        coEvery { entryRepo.setTelegramUserIdIfNull(entry.id, 1001) } returns true
        coEvery { entryRepo.updateStatus(entry.id, GuestListEntryStatus.CONFIRMED) } returns true

        val service = InvitationServiceImpl(
            invitationRepo,
            guestListRepo,
            entryRepo,
            guestListConfig,
            invitationConfig,
            fixedClock,
            FixedSecureRandom(ByteArray(32)),
        )

        val result = service.respondToInvitation(token, telegramUserId = 1001, response = InvitationResponse.CONFIRM)

        check(result is InvitationServiceResult.Success)
        assertEquals(GuestListEntryStatus.CONFIRMED, result.value.entryStatus)
        coVerify(exactly = 1) { entryRepo.setTelegramUserIdIfNull(entry.id, 1001) }
        coVerify(exactly = 1) { entryRepo.updateStatus(entry.id, GuestListEntryStatus.CONFIRMED) }
    }

    @Test
    fun `respond decline does not override terminal status`() = runBlocking {
        val entry = entryRecord(status = GuestListEntryStatus.CHECKED_IN, telegramUserId = 55)
        val guestList = guestListRecord()
        val invitation = invitationRecord(entryId = entry.id, expiresAt = fixedClock.instant().plusSeconds(3600))
        val tokenHash = "terminal".sha256Hex()

        coEvery { invitationRepo.findByTokenHash(tokenHash) } returns invitation
        coEvery { entryRepo.findById(entry.id) } returns entry
        coEvery { guestListRepo.findById(guestList.id) } returns guestList

        val service = InvitationServiceImpl(
            invitationRepo,
            guestListRepo,
            entryRepo,
            guestListConfig,
            invitationConfig,
            fixedClock,
            FixedSecureRandom(ByteArray(32)),
        )

        val result = service.respondToInvitation("terminal", telegramUserId = 200, response = InvitationResponse.DECLINE)

        check(result is InvitationServiceResult.Success)
        assertEquals(GuestListEntryStatus.CHECKED_IN, result.value.entryStatus)
        coVerify(exactly = 0) { entryRepo.updateStatus(any(), any()) }
        coVerify(exactly = 0) { entryRepo.setTelegramUserIdIfNull(any(), any()) }
    }

    private fun invitationRecord(
        id: Long = 1,
        entryId: Long = 10,
        expiresAt: Instant = fixedClock.instant(),
        revokedAt: Instant? = null,
        usedAt: Instant? = null,
        createdBy: Long? = null,
    ) =
        InvitationRecord(
            id = id,
            guestListEntryId = entryId,
            tokenHash = "hash",
            channel = InvitationChannel.TELEGRAM,
            expiresAt = expiresAt,
            revokedAt = revokedAt,
            usedAt = usedAt,
            createdBy = createdBy,
            createdAt = fixedClock.instant(),
        )

    private fun entryRecord(
        id: Long = 10,
        status: GuestListEntryStatus,
        telegramUserId: Long? = null,
    ) =
        GuestListEntryRecord(
            id = id,
            guestListId = 5,
            displayName = "Guest",
            fullName = "Guest",
            telegramUserId = telegramUserId,
            status = status,
            createdAt = fixedClock.instant(),
            updatedAt = fixedClock.instant(),
        )

    private fun guestListRecord(
        id: Long = 5,
        arrivalWindowEnd: Instant? = fixedClock.instant().plusSeconds(1800),
        status: GuestListStatus = GuestListStatus.ACTIVE,
    ) =
        GuestListRecord(
            id = id,
            clubId = 2,
            eventId = 3,
            promoterId = 1,
            ownerType = GuestListOwnerType.ADMIN,
            ownerUserId = 1,
            title = "List",
            capacity = 100,
            arrivalWindowStart = arrivalWindowEnd?.minusSeconds(3600),
            arrivalWindowEnd = arrivalWindowEnd,
            status = status,
            createdAt = fixedClock.instant(),
            updatedAt = fixedClock.instant(),
        )

    private class FixedSecureRandom(private val bytes: ByteArray) : SecureRandom() {
        override fun nextBytes(dest: ByteArray) {
            bytes.copyInto(dest)
        }
    }

    private fun String.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
}
