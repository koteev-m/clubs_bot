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
import com.example.bot.data.gamification.GamificationSettingsRepository
import com.example.bot.data.security.AuthContext
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.promoter.PromoterBookingAssignmentsRepository
import com.example.bot.data.visits.NightOverrideRepository
import com.example.bot.data.visits.ClubVisit
import com.example.bot.data.visits.VisitCheckInResult
import com.example.bot.data.visits.VisitRepository
import com.example.bot.gamification.GamificationDelta
import com.example.bot.gamification.GamificationEngine
import io.mockk.coEvery
import io.mockk.coVerify
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
    private val userRepository: UserRepository = mockk()
    private val promoterBookingAssignmentsRepository: PromoterBookingAssignmentsRepository = mockk()
    private val eventRepository: com.example.bot.club.EventRepository = mockk()
    private val nightOverrideRepository: NightOverrideRepository = mockk()
    private val visitRepository: VisitRepository = mockk()
    private val gamificationSettingsRepository: GamificationSettingsRepository = mockk()
    private val gamificationEngine: GamificationEngine = mockk()
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
            userRepository,
            promoterBookingAssignmentsRepository,
            eventRepository,
            nightOverrideRepository,
            visitRepository,
            gamificationSettingsRepository,
            gamificationEngine,
            CheckinConfig(lateGraceMinutes = 0),
            bookingQrConfig = bookingQrConfig,
            clock = fixedClock,
        )

    private val actor = AuthContext(userId = 10, telegramUserId = 11, roles = setOf(Role.ENTRY_MANAGER))

    @Test
    fun `host scan invitation denies when invitation already used`() = runBlocking {
        val card = invitationCard(entryId = 5, guestListId = 6)
        val entry = entryRecord(id = card.entryId, guestListId = card.guestListId, status = GuestListEntryStatus.CONFIRMED)
        val checkin = checkinRecord(subjectId = card.entryId.toString(), occurredAt = fixedClock.instant())

        coEvery { invitationService.resolveInvitation("token") } returns InvitationServiceResult.Success(card)
        coEvery { guestListRepo.findById(card.guestListId) } returns guestListRecord(id = card.guestListId)
        coEvery { guestListEntryRepo.findById(card.entryId) } returns entry
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

    @Test
    fun `host checkin booking creates visit and gamification when user resolved`() = runBlocking {
        val bookingId = UUID(0L, 15L)
        val booking = bookingRecord(id = bookingId, status = BookingStatus.BOOKED, guestUserId = 42)
        val occurredAt = fixedClock.instant()
        val checkin = bookingCheckinRecord(occurredAt = occurredAt, subjectId = "15")
        val visit =
            ClubVisit(
                id = 99,
                clubId = booking.clubId,
                nightStartUtc = fixedClock.instant().minusSeconds(3600),
                eventId = booking.eventId,
                userId = booking.guestUserId ?: 0,
                firstCheckinAt = occurredAt,
                actorUserId = actor.userId,
                actorRole = Role.ENTRY_MANAGER,
                entryType = CheckinSubjectType.BOOKING.name,
                isEarly = false,
                hasTable = false,
                createdAt = occurredAt,
                updatedAt = occurredAt,
            )

        coEvery { bookingRepo.findById(bookingId) } returns booking
        coEvery { checkinRepo.findBySubject(CheckinSubjectType.BOOKING, "15") } returns null
        coEvery { checkinRepo.insertWithBookingUpdate(any(), any(), any(), any()) } returns checkin
        coEvery { eventRepository.get(booking.eventId) } returns event(booking)
        coEvery { nightOverrideRepository.getOverride(booking.clubId, any()) } returns null
        coEvery { gamificationSettingsRepository.getByClubId(booking.clubId) } returns null
        coEvery { visitRepository.tryCheckIn(any()) } returns VisitCheckInResult(created = true, visit = visit)
        coEvery { visitRepository.markHasTable(any(), any(), any(), any()) } returns true
        coEvery { gamificationEngine.onVisitCreated(any(), any()) } returns GamificationDelta()

        val result =
            service.hostCheckin(
                HostCheckinRequest(clubId = booking.clubId, eventId = booking.eventId, bookingId = "15"),
                actor,
            )

        check(result is CheckinServiceResult.Success)
        coVerify {
            visitRepository.tryCheckIn(
                match { input ->
                    input.userId == booking.guestUserId &&
                        input.firstCheckinAt == occurredAt &&
                        input.clubId == booking.clubId &&
                        input.eventId == booking.eventId
                },
            )
        }
        coVerify(exactly = 1) { gamificationEngine.onVisitCreated(any(), occurredAt) }
    }

    @Test
    fun `host checkin booking skips gamification when visit already exists`() = runBlocking {
        val bookingId = UUID(0L, 16L)
        val booking = bookingRecord(id = bookingId, status = BookingStatus.BOOKED, guestUserId = 77)
        val occurredAt = fixedClock.instant()
        val checkin = bookingCheckinRecord(occurredAt = occurredAt, subjectId = "16")
        val visit =
            ClubVisit(
                id = 100,
                clubId = booking.clubId,
                nightStartUtc = fixedClock.instant().minusSeconds(3600),
                eventId = booking.eventId,
                userId = booking.guestUserId ?: 0,
                firstCheckinAt = occurredAt,
                actorUserId = actor.userId,
                actorRole = Role.ENTRY_MANAGER,
                entryType = CheckinSubjectType.BOOKING.name,
                isEarly = false,
                hasTable = false,
                createdAt = occurredAt,
                updatedAt = occurredAt,
            )

        coEvery { bookingRepo.findById(bookingId) } returns booking
        coEvery { checkinRepo.findBySubject(CheckinSubjectType.BOOKING, "16") } returns null
        coEvery { checkinRepo.insertWithBookingUpdate(any(), any(), any(), any()) } returns checkin
        coEvery { eventRepository.get(booking.eventId) } returns event(booking)
        coEvery { nightOverrideRepository.getOverride(booking.clubId, any()) } returns null
        coEvery { gamificationSettingsRepository.getByClubId(booking.clubId) } returns null
        coEvery { visitRepository.tryCheckIn(any()) } returns VisitCheckInResult(created = false, visit = visit)
        coEvery { visitRepository.markHasTable(any(), any(), any(), any()) } returns true
        coEvery { gamificationEngine.onVisitCreated(any(), any()) } returns GamificationDelta()

        val result =
            service.hostCheckin(
                HostCheckinRequest(clubId = booking.clubId, eventId = booking.eventId, bookingId = "16"),
                actor,
            )

        check(result is CheckinServiceResult.Success)
        coVerify { visitRepository.tryCheckIn(any()) }
        coVerify(exactly = 0) { gamificationEngine.onVisitCreated(any(), any()) }
    }

    @Test
    fun `host checkin booking resolves guest via promoter assignment`() = runBlocking {
        val bookingId = UUID(0L, 15L)
        val booking = bookingRecord(
            id = bookingId,
            status = BookingStatus.BOOKED,
            guestUserId = null,
            promoterUserId = 99,
        )
        val occurredAt = fixedClock.instant()
        val checkin = bookingCheckinRecord(occurredAt = occurredAt, subjectId = "15")
        val entryId = 901L
        val entry = entryRecord(id = entryId, guestListId = 301, telegramUserId = 555L)
        val resolvedUser = User(id = 123L, telegramId = entry.telegramUserId!!, username = "guest")
        val visit =
            ClubVisit(
                id = 200,
                clubId = booking.clubId,
                nightStartUtc = fixedClock.instant().minusSeconds(3600),
                eventId = booking.eventId,
                userId = resolvedUser.id,
                firstCheckinAt = occurredAt,
                actorUserId = actor.userId,
                actorRole = Role.ENTRY_MANAGER,
                entryType = CheckinSubjectType.BOOKING.name,
                isEarly = false,
                hasTable = false,
                createdAt = occurredAt,
                updatedAt = occurredAt,
            )

        coEvery { bookingRepo.findById(bookingId) } returns booking
        coEvery { checkinRepo.findBySubject(CheckinSubjectType.BOOKING, "15") } returns null
        coEvery { checkinRepo.insertWithBookingUpdate(any(), any(), any(), any()) } returns checkin
        coEvery { promoterBookingAssignmentsRepository.findEntryIdForBooking(15L) } returns entryId
        coEvery { guestListEntryRepo.findById(entryId) } returns entry
        coEvery { userRepository.getByTelegramId(entry.telegramUserId!!) } returns resolvedUser
        coEvery { eventRepository.get(booking.eventId) } returns event(booking)
        coEvery { nightOverrideRepository.getOverride(booking.clubId, any()) } returns null
        coEvery { gamificationSettingsRepository.getByClubId(booking.clubId) } returns null
        coEvery { visitRepository.tryCheckIn(any()) } returns VisitCheckInResult(created = true, visit = visit)
        coEvery { visitRepository.markHasTable(any(), any(), any(), any()) } returns true
        coEvery { gamificationEngine.onVisitCreated(any(), any()) } returns GamificationDelta()

        val result =
            service.hostCheckin(
                HostCheckinRequest(clubId = booking.clubId, eventId = booking.eventId, bookingId = "15"),
                actor,
            )

        check(result is CheckinServiceResult.Success)
        coVerify {
            visitRepository.tryCheckIn(
                match { input ->
                    input.userId == resolvedUser.id &&
                        input.firstCheckinAt == occurredAt
                },
            )
        }
        coVerify(exactly = 0) {
            visitRepository.tryCheckIn(
                match { input -> input.userId == booking.promoterUserId },
            )
        }
        coVerify(exactly = 1) { gamificationEngine.onVisitCreated(any(), occurredAt) }
    }

    @Test
    fun `host checkin booking skips visit when guest cannot be resolved`() = runBlocking {
        val bookingId = UUID(0L, 17L)
        val booking = bookingRecord(
            id = bookingId,
            status = BookingStatus.BOOKED,
            guestUserId = null,
            promoterUserId = 55,
        )
        val occurredAt = fixedClock.instant()
        val checkin = bookingCheckinRecord(occurredAt = occurredAt, subjectId = "17")

        coEvery { bookingRepo.findById(bookingId) } returns booking
        coEvery { checkinRepo.findBySubject(CheckinSubjectType.BOOKING, "17") } returns null
        coEvery { checkinRepo.insertWithBookingUpdate(any(), any(), any(), any()) } returns checkin
        coEvery { promoterBookingAssignmentsRepository.findEntryIdForBooking(17L) } returns null

        val result =
            service.hostCheckin(
                HostCheckinRequest(clubId = booking.clubId, eventId = booking.eventId, bookingId = "17"),
                actor,
            )

        check(result is CheckinServiceResult.Success)
        coVerify(exactly = 0) { visitRepository.tryCheckIn(any()) }
        coVerify(exactly = 0) { gamificationEngine.onVisitCreated(any(), any()) }
    }

    private fun bookingRecord(
        id: UUID,
        status: BookingStatus,
        clubId: Long = 1,
        eventId: Long = 2,
        guestUserId: Long? = null,
        promoterUserId: Long? = null,
    ): BookingRecord =
        BookingRecord(
            id = id,
            clubId = clubId,
            tableId = 10,
            tableNumber = 5,
            eventId = eventId,
            guestUserId = guestUserId,
            promoterUserId = promoterUserId,
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

    private fun bookingCheckinRecord(
        occurredAt: Instant,
        subjectId: String,
        clubId: Long = 1,
        eventId: Long = 2,
    ): CheckinRecord =
        CheckinRecord(
            id = 22,
            clubId = clubId,
            eventId = eventId,
            subjectType = CheckinSubjectType.BOOKING,
            subjectId = subjectId,
            checkedBy = 10,
            method = CheckinMethod.NAME,
            resultStatus = CheckinResultStatus.ARRIVED,
            denyReason = null,
            occurredAt = occurredAt,
            createdAt = occurredAt,
        )

    private fun event(booking: BookingRecord): com.example.bot.club.Event =
        com.example.bot.club.Event(
            id = booking.eventId,
            clubId = booking.clubId,
            title = "Event",
            startAt = fixedClock.instant().minusSeconds(3600),
            endAt = fixedClock.instant(),
            isSpecial = false,
            posterUrl = null,
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
        status: GuestListEntryStatus = GuestListEntryStatus.CONFIRMED,
        telegramUserId: Long? = null,
    ): GuestListEntryRecord =
        GuestListEntryRecord(
            id = id,
            guestListId = guestListId,
            displayName = "Guest",
            fullName = "Guest",
            telegramUserId = telegramUserId,
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
