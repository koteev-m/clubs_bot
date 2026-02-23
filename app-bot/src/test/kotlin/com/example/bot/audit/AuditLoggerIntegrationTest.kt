package com.example.bot.audit

import com.example.bot.booking.PaymentMode
import com.example.bot.booking.PaymentPolicy
import com.example.bot.booking.legacy.BookingService
import com.example.bot.booking.payments.ConfirmInput
import com.example.bot.booking.payments.PaymentsService
import com.example.bot.checkin.CheckinConfig
import com.example.bot.checkin.CheckinServiceResult
import com.example.bot.checkin.HostCheckinRequest
import com.example.bot.checkin.BookingQrConfig
import com.example.bot.club.CheckinMethod
import com.example.bot.club.CheckinResultStatus
import com.example.bot.club.CheckinSubjectType
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListStatus
import com.example.bot.club.InvitationService
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.checkin.CheckinServiceImpl
import com.example.bot.data.club.CheckinDbRepository
import com.example.bot.data.club.CheckinRecord
import com.example.bot.data.club.GuestListDbRepository
import com.example.bot.data.club.GuestListEntryDbRepository
import com.example.bot.data.club.GuestListEntryRecord
import com.example.bot.data.club.GuestListRecord
import com.example.bot.data.promoter.PromoterBookingAssignmentsRepository
import com.example.bot.data.gamification.GamificationSettingsRepository
import com.example.bot.data.security.AuthContext
import com.example.bot.data.security.Role
import com.example.bot.data.security.UserRepository
import com.example.bot.data.visits.NightOverrideRepository
import com.example.bot.data.visits.VisitRepository
import com.example.bot.gamification.GamificationEngine
import com.example.bot.payments.PaymentsRepository
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.mockk.coEvery
import io.mockk.mockk

class AuditLoggerIntegrationTest {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2024-08-01T18:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `checkin emits audit log on success`() = runBlocking {
        val auditRepo = FakeAuditLogRepository()
        val auditLogger = AuditLogger(auditRepo)
        val checkinRepo = mockk<CheckinDbRepository>()
        val invitationService = mockk<InvitationService>()
        val guestListRepo = mockk<GuestListDbRepository>()
        val guestListEntryRepo = mockk<GuestListEntryDbRepository>()
        val bookingRepo = mockk<BookingRepository>()
        val userRepository = mockk<UserRepository>()
        val eventRepository = mockk<com.example.bot.club.EventRepository>()
        val nightOverrideRepository = mockk<NightOverrideRepository>()
        val visitRepository = mockk<VisitRepository>()
        val gamificationSettingsRepository = mockk<GamificationSettingsRepository>()
        val gamificationEngine = mockk<GamificationEngine>()

        val entry =
            GuestListEntryRecord(
                id = 11,
                guestListId = 21,
                displayName = "Guest",
                fullName = "Guest",
                telegramUserId = 501,
                status = GuestListEntryStatus.CONFIRMED,
                createdAt = fixedClock.instant(),
                updatedAt = fixedClock.instant(),
            )
        val guestList =
            GuestListRecord(
                id = 21,
                clubId = 9,
                eventId = 7,
                promoterId = null,
                ownerType = com.example.bot.club.GuestListOwnerType.MANAGER,
                ownerUserId = 101,
                title = "List",
                capacity = 100,
                arrivalWindowStart = null,
                arrivalWindowEnd = fixedClock.instant().plusSeconds(3600),
                status = GuestListStatus.ACTIVE,
                createdAt = fixedClock.instant(),
                updatedAt = fixedClock.instant(),
            )
        val record =
            CheckinRecord(
                id = 44,
                clubId = guestList.clubId,
                eventId = guestList.eventId,
                subjectType = CheckinSubjectType.GUEST_LIST_ENTRY,
                subjectId = entry.id.toString(),
                checkedBy = 77,
                method = CheckinMethod.NAME,
                resultStatus = CheckinResultStatus.ARRIVED,
                denyReason = null,
                occurredAt = fixedClock.instant(),
                createdAt = fixedClock.instant(),
            )

        coEvery { guestListEntryRepo.findById(entry.id) } returns entry
        coEvery { guestListRepo.findById(entry.guestListId) } returns guestList
        coEvery { checkinRepo.findBySubject(CheckinSubjectType.GUEST_LIST_ENTRY, entry.id.toString()) } returns null
        coEvery { checkinRepo.insertWithEntryUpdate(any(), any(), any(), any()) } returns record
        coEvery { userRepository.getByTelegramId(entry.telegramUserId!!) } returns null

        val promoterBookingAssignmentsRepository = mockk<PromoterBookingAssignmentsRepository>()
        val service =
            CheckinServiceImpl(
                checkinRepo = checkinRepo,
                invitationService = invitationService,
                guestListRepo = guestListRepo,
                guestListEntryRepo = guestListEntryRepo,
                bookingRepo = bookingRepo,
                auditLogger = auditLogger,
                userRepository = userRepository,
                promoterBookingAssignmentsRepository = promoterBookingAssignmentsRepository,
                eventRepository = eventRepository,
                nightOverrideRepository = nightOverrideRepository,
                visitRepository = visitRepository,
                gamificationSettingsRepository = gamificationSettingsRepository,
                gamificationEngine = gamificationEngine,
                checkinConfig = CheckinConfig(lateGraceMinutes = 0),
                bookingQrConfig = BookingQrConfig(secret = "qr-secret", oldSecret = null, ttl = Duration.ofHours(1)),
                clock = fixedClock,
            )

        val actor = AuthContext(userId = 77, telegramUserId = 88, roles = setOf(Role.ENTRY_MANAGER))
        val result =
            service.hostCheckin(
                HostCheckinRequest(
                    clubId = guestList.clubId,
                    eventId = guestList.eventId,
                    guestListEntryId = entry.id,
                ),
                actor,
            )

        assertTrue(result is CheckinServiceResult.Success)
        assertEquals(1, auditRepo.events.size)
        val event = auditRepo.events.first()
        assertEquals(StandardAuditEntityType.VISIT, event.entityType)
        assertEquals(StandardAuditAction.CHECKIN, event.action)
        assertEquals(
            "VISIT:CHECKIN:club:${guestList.clubId}:night:${guestList.eventId}:checkin:${record.id}:v1",
            event.fingerprint,
        )
    }

    @Test
    fun `table deposit creation emits audit log`() = runBlocking {
        val auditRepo = FakeAuditLogRepository()
        val auditLogger = AuditLogger(auditRepo)
        val bookingService = mockk<BookingService>(relaxed = true)
        val paymentsRepo = mockk<PaymentsRepository>()
        val bookingId = UUID.randomUUID()
        val record =
            PaymentsRepository.PaymentRecord(
                id = UUID.randomUUID(),
                bookingId = bookingId,
                provider = "PROVIDER",
                currency = "RUB",
                amountMinor = 10_000,
                status = "INITIATED",
                payload = "payload",
                externalId = null,
                idempotencyKey = "idem",
                createdAt = fixedClock.instant(),
                updatedAt = fixedClock.instant(),
            )
        coEvery {
            paymentsRepo.createInitiated(
                bookingId = bookingId,
                provider = "PROVIDER",
                currency = "RUB",
                amountMinor = 10_000,
                payload = any(),
                idempotencyKey = "idem",
            )
        } returns record

        val service = PaymentsService(bookingService, paymentsRepo, auditLogger)
        val input =
            ConfirmInput(
                clubId = 31,
                eventStartUtc = fixedClock.instant(),
                tableId = 4,
                tableNumber = 7,
                guestsCount = 2,
                minDeposit = BigDecimal("50.00"),
                bookingId = bookingId,
            )
        val policy = PaymentPolicy(mode = PaymentMode.PROVIDER_DEPOSIT, currency = "RUB")

        val result = service.startConfirmation(input, null, policy, "idem")

        assertTrue(result is com.example.bot.booking.legacy.Either.Right<*>)
        assertEquals(1, auditRepo.events.size)
        val event = auditRepo.events.first()
        assertEquals(StandardAuditEntityType.TABLE_DEPOSIT, event.entityType)
        assertEquals(StandardAuditAction.CREATE, event.action)
        assertTrue(event.fingerprint.startsWith("TABLE_DEPOSIT:CREATE:${record.id}"))
    }
}

private class FakeAuditLogRepository : AuditLogRepository {
    val events = mutableListOf<AuditLogEvent>()

    override suspend fun append(event: AuditLogEvent): Long {
        events.add(event)
        return events.size.toLong()
    }

    override suspend fun listForClub(
        clubId: Long,
        limit: Int,
        offset: Int,
    ): List<AuditLogRecord> = emptyList()

    override suspend fun listForUser(
        userId: Long,
        limit: Int,
        offset: Int,
    ): List<AuditLogRecord> = emptyList()
}
