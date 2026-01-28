package com.example.bot.data.checkin

import com.example.bot.checkin.BookingQrConfig
import com.example.bot.checkin.CheckinConfig
import com.example.bot.checkin.CheckinServiceResult
import com.example.bot.checkin.HostCheckinOutcome
import com.example.bot.checkin.HostCheckinRequest
import com.example.bot.club.CheckinMethod
import com.example.bot.club.CheckinResultStatus
import com.example.bot.club.CheckinSubjectType
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListStatus
import com.example.bot.club.InvitationCard
import com.example.bot.club.InvitationService
import com.example.bot.club.InvitationServiceResult
import com.example.bot.audit.AuditLogRepository
import com.example.bot.audit.AuditLogger
import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.core.BookingRecord
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.club.CheckinDbRepository
import com.example.bot.data.club.CheckinRecord
import com.example.bot.data.club.GuestListDbRepository
import com.example.bot.data.club.GuestListEntryDbRepository
import com.example.bot.data.club.GuestListEntryRecord
import com.example.bot.data.club.GuestListRecord
import com.example.bot.data.security.AuthContext
import com.example.bot.data.security.Role
import io.mockk.coEvery
import io.mockk.mockk
import java.sql.SQLException
import java.time.Duration
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CheckinServiceTest {
    private val checkinRepo: CheckinDbRepository = mockk()
    private val invitationService: InvitationService = mockk()
    private val guestListRepo: GuestListDbRepository = mockk()
    private val guestListEntryRepo: GuestListEntryDbRepository = mockk()
    private val bookingRepo: BookingRepository = mockk()
    private val auditRepo: AuditLogRepository = mockk(relaxed = true)
    private val auditLogger = AuditLogger(auditRepo)
    private val bookingSecret = "booking-secret"
    private val bookingQrConfig = BookingQrConfig(secret = bookingSecret, oldSecret = null, ttl = Duration.ofHours(12))
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2024-07-01T18:00:00Z"), ZoneOffset.UTC)
    private val service =
        CheckinServiceImpl(
            checkinRepo,
            invitationService,
            guestListRepo,
            guestListEntryRepo,
            bookingRepo,
            auditLogger,
            CheckinConfig(lateGraceMinutes = 0),
            bookingQrConfig = bookingQrConfig,
            clock = fixedClock,
        )

    private val actor = AuthContext(userId = 10, telegramUserId = 11, roles = setOf(Role.ENTRY_MANAGER))

    @Test
    fun `host scan invitation denies when invitation already used`() = runBlocking {
        val card = invitationCard(entryId = 5, guestListId = 6)
        val checkin = checkinRecord(subjectId = card.entryId.toString(), occurredAt = fixedClock.instant())

        coEvery { invitationService.resolveInvitation("token") } returns InvitationServiceResult.Success(card)
        coEvery { guestListRepo.findById(card.guestListId) } returns guestListRecord(id = card.guestListId)
        coEvery { checkinRepo.insertWithEntryUpdate(any(), any(), any(), any()) } returnsMany listOf(checkin, null)

        val first = service.hostScan("inv:token", card.clubId, card.eventId, actor)
        check(first is CheckinServiceResult.Success)
        val firstPayload = (first as CheckinServiceResult.Success).value
        assertTrue(firstPayload is HostCheckinOutcome)
        assertEquals(CheckinResultStatus.ARRIVED, firstPayload.outcomeStatus)

        val second = service.hostScan("inv:token", card.clubId, card.eventId, actor)
        check(second is CheckinServiceResult.Success)
        val secondPayload = (second as CheckinServiceResult.Success).value
        assertEquals(CheckinResultStatus.DENIED, secondPayload.outcomeStatus)
        assertEquals("ALREADY_USED", secondPayload.denyReason)
    }

    @Test
    fun `host checkin booking rejects invalid status`() = runBlocking {
        val bookingId = UUID(0L, 15L)
        val booking = bookingRecord(id = bookingId, status = BookingStatus.CANCELLED)
        coEvery { bookingRepo.findById(bookingId) } returns booking
        coEvery { checkinRepo.findBySubject(CheckinSubjectType.BOOKING, "15") } returns null

        val result = service.hostCheckin(
            HostCheckinRequest(clubId = booking.clubId, eventId = booking.eventId, bookingId = "15"),
            actor,
        )

        check(result is CheckinServiceResult.Success)
        val payload = (result as CheckinServiceResult.Success).value
        assertEquals(CheckinResultStatus.DENIED, payload.outcomeStatus)
        assertEquals("INVALID_STATUS", payload.denyReason)
    }

    @Test
    fun `host checkin entry denies already arrived`() = runBlocking {
        val entry = entryRecord(id = 20, guestListId = 30, status = GuestListEntryStatus.ARRIVED)
        val guestList = guestListRecord(id = entry.guestListId)

        coEvery { guestListEntryRepo.findById(entry.id) } returns entry
        coEvery { guestListRepo.findById(entry.guestListId) } returns guestList
        coEvery { checkinRepo.findBySubject(CheckinSubjectType.GUEST_LIST_ENTRY, entry.id.toString()) } returns null

        val result = service.hostCheckin(
            HostCheckinRequest(
                clubId = guestList.clubId,
                eventId = guestList.eventId,
                guestListEntryId = entry.id,
            ),
            actor,
        )

        check(result is CheckinServiceResult.Success)
        val payload = (result as CheckinServiceResult.Success).value
        assertEquals(CheckinResultStatus.DENIED, payload.outcomeStatus)
        assertEquals("ALREADY_CHECKED_IN", payload.denyReason)
    }

    @Test
    fun `host checkin entry returns denied on unique violation`() = runBlocking {
        val entry = entryRecord(id = 21, guestListId = 31, status = GuestListEntryStatus.CONFIRMED)
        val guestList = guestListRecord(id = entry.guestListId)

        coEvery { guestListEntryRepo.findById(entry.id) } returns entry
        coEvery { guestListRepo.findById(entry.guestListId) } returns guestList
        coEvery { checkinRepo.findBySubject(CheckinSubjectType.GUEST_LIST_ENTRY, entry.id.toString()) } returns null
        coEvery { checkinRepo.insertWithEntryUpdate(any(), any(), any(), any()) } throws SQLException("duplicate", "23505")

        val result = service.hostCheckin(
            HostCheckinRequest(
                clubId = guestList.clubId,
                eventId = guestList.eventId,
                guestListEntryId = entry.id,
            ),
            actor,
        )

        check(result is CheckinServiceResult.Success)
        val payload = (result as CheckinServiceResult.Success).value
        assertEquals(CheckinResultStatus.DENIED, payload.outcomeStatus)
        assertEquals("ALREADY_USED", payload.denyReason)
    }

    private fun bookingRecord(
        id: UUID,
        status: BookingStatus,
        clubId: Long = 1,
        eventId: Long = 2,
    ): BookingRecord =
        BookingRecord(
            id = id,
            clubId = clubId,
            tableId = 10,
            tableNumber = 5,
            eventId = eventId,
            guests = 2,
            minRate = java.math.BigDecimal("10.00"),
            totalRate = java.math.BigDecimal("20.00"),
            slotStart = fixedClock.instant(),
            slotEnd = fixedClock.instant().plusSeconds(3600),
            status = status,
            arrivalBy = fixedClock.instant().plusSeconds(900),
            qrSecret = "qr",
            idempotencyKey = "key",
            createdAt = fixedClock.instant(),
            updatedAt = fixedClock.instant(),
        )

    private fun invitationCard(entryId: Long, guestListId: Long): InvitationCard =
        InvitationCard(
            invitationId = 99,
            entryId = entryId,
            guestListId = guestListId,
            clubId = 1,
            clubName = "Club",
            eventId = 2,
            arrivalWindowStart = null,
            arrivalWindowEnd = fixedClock.instant().plusSeconds(60),
            displayName = "Guest",
            entryStatus = GuestListEntryStatus.CONFIRMED,
            expiresAt = fixedClock.instant().plusSeconds(3600),
            revokedAt = null,
            usedAt = null,
        )

    private fun checkinRecord(
        subjectId: String,
        occurredAt: Instant,
    ): CheckinRecord =
        CheckinRecord(
            id = 1,
            clubId = 1,
            eventId = 2,
            subjectType = CheckinSubjectType.GUEST_LIST_ENTRY,
            subjectId = subjectId,
            checkedBy = 10,
            method = CheckinMethod.QR,
            resultStatus = CheckinResultStatus.ARRIVED,
            denyReason = null,
            occurredAt = occurredAt,
            createdAt = occurredAt,
        )

    private fun entryRecord(
        id: Long,
        guestListId: Long,
        status: GuestListEntryStatus,
    ): GuestListEntryRecord =
        GuestListEntryRecord(
            id = id,
            guestListId = guestListId,
            displayName = "Guest",
            fullName = "Guest",
            telegramUserId = null,
            status = status,
            createdAt = fixedClock.instant(),
            updatedAt = fixedClock.instant(),
        )

    private fun guestListRecord(id: Long): GuestListRecord =
        GuestListRecord(
            id = id,
            clubId = 1,
            eventId = 2,
            promoterId = null,
            ownerType = com.example.bot.club.GuestListOwnerType.MANAGER,
            ownerUserId = 10,
            title = "List",
            capacity = 100,
            arrivalWindowStart = null,
            arrivalWindowEnd = fixedClock.instant().plusSeconds(3600),
            status = GuestListStatus.ACTIVE,
            createdAt = fixedClock.instant(),
            updatedAt = fixedClock.instant(),
        )
}
