package com.example.bot.data.checkin

import com.example.bot.checkin.CheckinConfig
import com.example.bot.checkin.CheckinResult
import com.example.bot.checkin.CheckinServiceError
import com.example.bot.checkin.CheckinServiceResult
import com.example.bot.club.CheckinMethod
import com.example.bot.club.CheckinResultStatus
import com.example.bot.club.CheckinSubjectType
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListStatus
import com.example.bot.club.InvitationCard
import com.example.bot.club.InvitationService
import com.example.bot.club.InvitationServiceResult
import com.example.bot.data.club.CheckinDbRepository
import com.example.bot.data.club.CheckinRecord
import com.example.bot.data.club.GuestListDbRepository
import com.example.bot.data.club.GuestListEntryDbRepository
import com.example.bot.data.club.GuestListEntryRecord
import com.example.bot.data.club.GuestListRecord
import com.example.bot.data.security.AuthContext
import com.example.bot.data.security.Role
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CheckinServiceTest {
    private val checkinRepo: CheckinDbRepository = mockk()
    private val invitationService: InvitationService = mockk()
    private val guestListRepo: GuestListDbRepository = mockk()
    private val guestListEntryRepo: GuestListEntryDbRepository = mockk()
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2024-07-01T18:00:00Z"), ZoneOffset.UTC)
    private val service =
        CheckinServiceImpl(
            checkinRepo,
            invitationService,
            guestListRepo,
            guestListEntryRepo,
            CheckinConfig(lateGraceMinutes = 0),
            fixedClock,
        )

    private val actor = AuthContext(userId = 10, telegramUserId = 11, roles = setOf(Role.ENTRY_MANAGER))

    @Test
    fun `scan invitation creates checkin and updates status`() = runBlocking {
        val card = invitationCard(entryId = 5, arrivalWindowEnd = fixedClock.instant().plusSeconds(60))
        val checkin = checkinRecord(subjectId = card.entryId.toString(), occurredAt = fixedClock.instant())

        coEvery { invitationService.resolveInvitation("token") } returns InvitationServiceResult.Success(card)
        coEvery {
            checkinRepo.insertWithEntryUpdate(
                checkin = any(),
                entryId = card.entryId,
                entryStatus = GuestListEntryStatus.ARRIVED,
                invitationUse = any(),
            )
        } returns checkin
        coEvery { checkinRepo.findBySubject(any(), any()) } returns null

        val result = service.scanQr("inv:token", actor)

        check(result is CheckinServiceResult.Success)
        val payload = (result as CheckinServiceResult.Success).value
        assertTrue(payload is CheckinResult.Success)
        val success = payload as CheckinResult.Success
        assertEquals(CheckinResultStatus.ARRIVED, success.resultStatus)
        assertEquals(card.displayName, success.displayName)
        assertEquals(actor.userId, success.checkedBy)

        coVerify(exactly = 1) {
            checkinRepo.insertWithEntryUpdate(
                checkin = any(),
                entryId = card.entryId,
                entryStatus = GuestListEntryStatus.ARRIVED,
                invitationUse = any(),
            )
        }
        coVerify(exactly = 1) { checkinRepo.findBySubject(CheckinSubjectType.GUEST_LIST_ENTRY, card.entryId.toString()) }
        confirmVerified(checkinRepo)
    }

    @Test
    fun `scan invitation marks late when arrival window ended`() = runBlocking {
        val card = invitationCard(entryId = 7, arrivalWindowEnd = fixedClock.instant().minusSeconds(60))
        val checkin =
            checkinRecord(
                subjectId = card.entryId.toString(),
                occurredAt = fixedClock.instant(),
                resultStatus = CheckinResultStatus.LATE,
            )

        coEvery { invitationService.resolveInvitation("token") } returns InvitationServiceResult.Success(card)
        coEvery {
            checkinRepo.insertWithEntryUpdate(
                checkin = any(),
                entryId = card.entryId,
                entryStatus = GuestListEntryStatus.LATE,
                invitationUse = any(),
            )
        } returns checkin
        coEvery { checkinRepo.findBySubject(any(), any()) } returns null

        val result = service.scanQr("inv:token", actor)

        check(result is CheckinServiceResult.Success)
        val payload = (result as CheckinServiceResult.Success).value
        assertTrue(payload is CheckinResult.Success)
        val success = payload as CheckinResult.Success
        assertEquals(CheckinResultStatus.LATE, success.resultStatus)

        coVerify(exactly = 1) {
            checkinRepo.insertWithEntryUpdate(
                checkin = any(),
                entryId = card.entryId,
                entryStatus = GuestListEntryStatus.LATE,
                invitationUse = any(),
            )
        }
        coVerify(exactly = 1) { checkinRepo.findBySubject(CheckinSubjectType.GUEST_LIST_ENTRY, card.entryId.toString()) }
        confirmVerified(checkinRepo)
    }

    @Test
    fun `repeat scan returns already used`() = runBlocking {
        val existing = checkinRecord(subjectId = "15", method = CheckinMethod.QR)

        coEvery { invitationService.resolveInvitation("token") } returns
            InvitationServiceResult.Success(invitationCard(entryId = 15))
        coEvery { checkinRepo.findBySubject(CheckinSubjectType.GUEST_LIST_ENTRY, "15") } returns existing

        val result = service.scanQr("inv_token", actor)

        check(result is CheckinServiceResult.Success)
        val payload = (result as CheckinServiceResult.Success).value
        assertTrue(payload is CheckinResult.AlreadyUsed)
        val alreadyUsed = payload as CheckinResult.AlreadyUsed
        assertEquals(existing.subjectId, alreadyUsed.subjectId)
        assertEquals(existing.method, alreadyUsed.existingCheckin.method)

        coVerify(exactly = 1) { checkinRepo.findBySubject(CheckinSubjectType.GUEST_LIST_ENTRY, "15") }
        confirmVerified(checkinRepo)
    }

    @Test
    fun `manual denied without reason fails`() = runBlocking {
        val entry = entryRecord(id = 20, guestListId = 30)
        val guestList = guestListRecord(id = entry.guestListId)

        coEvery { guestListEntryRepo.findById(entry.id) } returns entry
        coEvery { guestListRepo.findById(entry.guestListId) } returns guestList
        coEvery { checkinRepo.findBySubject(any(), any()) } returns null

        val result =
            service.manualCheckin(
                subjectType = CheckinSubjectType.GUEST_LIST_ENTRY,
                subjectId = entry.id.toString(),
                status = CheckinResultStatus.DENIED,
                denyReason = null,
                actor = actor,
            )

        assertTrue(result is CheckinServiceResult.Failure)
        assertEquals(CheckinServiceError.CHECKIN_DENY_REASON_REQUIRED, (result as CheckinServiceResult.Failure).error)
    }

    @Test
    fun `forbidden when actor lacks role`() = runBlocking {
        val noRoleActor = actor.copy(roles = emptySet())

        val scan = service.scanQr("inv:token", noRoleActor)
        val manual =
            service.manualCheckin(
                subjectType = CheckinSubjectType.GUEST_LIST_ENTRY,
                subjectId = "1",
                status = CheckinResultStatus.ARRIVED,
                denyReason = null,
                actor = noRoleActor,
            )

        val scanPayload = (scan as CheckinServiceResult.Success).value
        val manualPayload = (manual as CheckinServiceResult.Success).value

        assertTrue(scanPayload is CheckinResult.Forbidden)
        assertTrue(manualPayload is CheckinResult.Forbidden)
    }

    private fun invitationCard(
        entryId: Long,
        arrivalWindowEnd: Instant? = null,
    ): InvitationCard =
        InvitationCard(
            invitationId = 1,
            entryId = entryId,
            guestListId = 2,
            clubId = 3,
            clubName = "Club",
            eventId = 4,
            arrivalWindowStart = null,
            arrivalWindowEnd = arrivalWindowEnd,
            displayName = "Guest",
            entryStatus = GuestListEntryStatus.ADDED,
            expiresAt = fixedClock.instant().plusSeconds(3600),
            revokedAt = null,
            usedAt = null,
        )

    private fun checkinRecord(
        subjectId: String,
        method: CheckinMethod = CheckinMethod.NAME,
        occurredAt: Instant = fixedClock.instant(),
        resultStatus: CheckinResultStatus = CheckinResultStatus.ARRIVED,
    ): CheckinRecord =
        CheckinRecord(
            id = 1,
            clubId = 3,
            eventId = 4,
            subjectType = CheckinSubjectType.GUEST_LIST_ENTRY,
            subjectId = subjectId,
            checkedBy = actor.userId,
            method = method,
            resultStatus = resultStatus,
            denyReason = null,
            occurredAt = occurredAt,
            createdAt = occurredAt,
        )

    private fun entryRecord(
        id: Long,
        guestListId: Long,
    ): GuestListEntryRecord =
        GuestListEntryRecord(
            id = id,
            guestListId = guestListId,
            displayName = "Guest",
            fullName = "Guest",
            telegramUserId = null,
            status = GuestListEntryStatus.ADDED,
            createdAt = fixedClock.instant(),
            updatedAt = fixedClock.instant(),
        )

    private fun guestListRecord(
        id: Long,
    ): GuestListRecord =
        GuestListRecord(
            id = id,
            clubId = 3,
            eventId = 4,
            promoterId = null,
            ownerType = GuestListOwnerType.ADMIN,
            ownerUserId = 1,
            title = "List",
            capacity = 10,
            arrivalWindowStart = null,
            arrivalWindowEnd = null,
            status = GuestListStatus.ACTIVE,
            createdAt = fixedClock.instant(),
            updatedAt = fixedClock.instant(),
        )
}
