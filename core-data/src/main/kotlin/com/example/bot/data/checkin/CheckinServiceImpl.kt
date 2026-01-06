package com.example.bot.data.checkin

import com.example.bot.booking.a3.QrBookingCodec
import com.example.bot.checkin.CheckinConfig
import com.example.bot.checkin.CheckinInvalidReason
import com.example.bot.checkin.CheckinResult
import com.example.bot.checkin.CheckinService
import com.example.bot.checkin.CheckinServiceError
import com.example.bot.checkin.CheckinServiceResult
import com.example.bot.checkin.ExistingCheckin
import com.example.bot.club.CheckinMethod
import com.example.bot.club.CheckinResultStatus
import com.example.bot.club.CheckinSubjectType
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.InvitationService
import com.example.bot.club.InvitationServiceError
import com.example.bot.club.InvitationServiceResult
import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.club.CheckinDbRepository
import com.example.bot.data.club.CheckinDbRepository.InvitationUse
import com.example.bot.data.club.GuestListDbRepository
import com.example.bot.data.club.GuestListEntryDbRepository
import com.example.bot.data.db.isUniqueViolation
import com.example.bot.data.security.AuthContext
import com.example.bot.data.security.Role
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.slf4j.LoggerFactory

class CheckinServiceImpl(
    private val checkinRepo: CheckinDbRepository,
    private val invitationService: InvitationService,
    private val guestListRepo: GuestListDbRepository,
    private val guestListEntryRepo: GuestListEntryDbRepository,
    private val bookingRepo: BookingRepository,
    private val checkinConfig: CheckinConfig = CheckinConfig.fromEnv(),
    private val bookingQrSecretProvider: () -> String = { System.getenv("QR_SECRET") ?: "" },
    private val oldBookingQrSecretProvider: () -> String? = { System.getenv("QR_OLD_SECRET") },
    private val bookingQrTtl: Duration = Duration.ofHours(12),
    private val clock: Clock = Clock.systemUTC(),
) : CheckinService {
    private val logger = LoggerFactory.getLogger(CheckinServiceImpl::class.java)

    override suspend fun scanQr(
        payload: String,
        actor: AuthContext,
    ): CheckinServiceResult<CheckinResult> {
        if (!hasEntryManagerRole(actor)) {
            return CheckinServiceResult.Success(CheckinResult.Forbidden)
        }

        val trimmed = payload.trim()
        val rawToken = parseInvitationToken(trimmed)
        if (rawToken == null) {
            val bookingDecoded = parseBookingToken(trimmed)
                ?: return CheckinServiceResult.Success(
                    CheckinResult.Invalid(CheckinInvalidReason.UNKNOWN_FORMAT),
                )

            return handleBookingScan(bookingDecoded, actor)
        }

        val resolved = invitationService.resolveInvitation(rawToken)
        if (resolved is InvitationServiceResult.Failure) {
            val reason =
                when (resolved.error) {
                    InvitationServiceError.INVITATION_INVALID -> CheckinInvalidReason.TOKEN_INVALID
                    InvitationServiceError.INVITATION_REVOKED -> CheckinInvalidReason.TOKEN_REVOKED
                    InvitationServiceError.INVITATION_EXPIRED -> CheckinInvalidReason.TOKEN_EXPIRED
                    InvitationServiceError.INVITATION_ALREADY_USED -> CheckinInvalidReason.TOKEN_USED
                    InvitationServiceError.GUEST_LIST_ENTRY_NOT_FOUND -> null
                    InvitationServiceError.GUEST_LIST_NOT_ACTIVE -> null
                }
            return if (reason != null) {
                CheckinServiceResult.Success(CheckinResult.Invalid(reason))
            } else {
                val error =
                    if (resolved.error == InvitationServiceError.GUEST_LIST_ENTRY_NOT_FOUND) {
                        CheckinServiceError.CHECKIN_SUBJECT_NOT_FOUND
                    } else {
                        CheckinServiceError.CHECKIN_INVALID_PAYLOAD
                    }
                CheckinServiceResult.Failure(error)
            }
        }

        val card = (resolved as InvitationServiceResult.Success).value
        val existingCheckin = checkinRepo.findBySubject(CheckinSubjectType.GUEST_LIST_ENTRY, card.entryId.toString())
        if (existingCheckin != null) {
            return CheckinServiceResult.Success(existingCheckin.toAlreadyUsed())
        }

        val occurredAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val resultStatus = calculateResultStatus(card.arrivalWindowEnd, occurredAt)

        return try {
            insertCheckinWithUpdates(
                checkin =
                    com.example.bot.data.club.NewCheckin(
                        clubId = card.clubId,
                        eventId = card.eventId,
                        subjectType = CheckinSubjectType.GUEST_LIST_ENTRY,
                        subjectId = card.entryId.toString(),
                        checkedBy = actor.userId,
                        method = CheckinMethod.QR,
                        resultStatus = resultStatus,
                        denyReason = null,
                        occurredAt = occurredAt,
                    ),
                entryId = card.entryId,
                entryStatus = resultStatus.toEntryStatus(),
                invitationUse =
                    if (card.usedAt != null) {
                        null
                    } else {
                        InvitationUse(card.invitationId, occurredAt)
                    },
                displayName = card.displayName,
            )
        } catch (ex: Exception) {
            if (ex.isUniqueViolation()) {
                val existing = checkinRepo.findBySubject(CheckinSubjectType.GUEST_LIST_ENTRY, card.entryId.toString())
                if (existing != null) {
                    return CheckinServiceResult.Success(existing.toAlreadyUsed())
                }
            }
            logger.warn("checkin.scan failed", ex)
            CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_INVALID_PAYLOAD)
        }
    }

    override suspend fun manualCheckin(
        subjectType: CheckinSubjectType,
        subjectId: String,
        status: CheckinResultStatus,
        denyReason: String?,
        actor: AuthContext,
    ): CheckinServiceResult<CheckinResult> {
        if (!hasEntryManagerRole(actor)) {
            return CheckinServiceResult.Success(CheckinResult.Forbidden)
        }

        return when (subjectType) {
            CheckinSubjectType.GUEST_LIST_ENTRY ->
                manualGuestListCheckin(subjectId, status, denyReason, actor)
            CheckinSubjectType.BOOKING -> manualBookingCheckin(subjectId, status, denyReason, actor)
        }
    }

    private suspend fun manualGuestListCheckin(
        subjectId: String,
        status: CheckinResultStatus,
        denyReason: String?,
        actor: AuthContext,
    ): CheckinServiceResult<CheckinResult> {
        val entryId = subjectId.toLongOrNull()
            ?: return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_INVALID_PAYLOAD)
        val canonicalSubjectId = entryId.toString()
        val entry = guestListEntryRepo.findById(entryId)
            ?: return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_SUBJECT_NOT_FOUND)
        val guestList = guestListRepo.findById(entry.guestListId)
            ?: return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_SUBJECT_NOT_FOUND)

        val existing =
            checkinRepo.findBySubject(CheckinSubjectType.GUEST_LIST_ENTRY, canonicalSubjectId)
        if (existing != null) {
            return CheckinServiceResult.Success(existing.toAlreadyUsed())
        }

        if (status == CheckinResultStatus.DENIED && denyReason.isNullOrBlank()) {
            return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_DENY_REASON_REQUIRED)
        }

        val occurredAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)

        return try {
            insertCheckinWithUpdates(
                checkin =
                    com.example.bot.data.club.NewCheckin(
                        clubId = guestList.clubId,
                        eventId = guestList.eventId,
                        subjectType = CheckinSubjectType.GUEST_LIST_ENTRY,
                        subjectId = canonicalSubjectId,
                        checkedBy = actor.userId,
                        method = CheckinMethod.NAME,
                        resultStatus = status,
                        denyReason = denyReason,
                        occurredAt = occurredAt,
                    ),
                entryId = entryId,
                entryStatus = status.toEntryStatus(),
                invitationUse = null,
                displayName = entry.displayName,
            )
        } catch (ex: Exception) {
            if (ex.isUniqueViolation()) {
                val duplicate =
                    checkinRepo.findBySubject(CheckinSubjectType.GUEST_LIST_ENTRY, canonicalSubjectId)
                if (duplicate != null) {
                    return CheckinServiceResult.Success(duplicate.toAlreadyUsed())
                }
            }
            logger.warn("checkin.manual failed", ex)
            CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_INVALID_PAYLOAD)
        }
    }

    private suspend fun manualBookingCheckin(
        subjectId: String,
        status: CheckinResultStatus,
        denyReason: String?,
        actor: AuthContext,
    ): CheckinServiceResult<CheckinResult> {
        val canonicalSubjectId = subjectId.toLongOrNull()?.toString() ?: subjectId
        val bookingUuid = resolveBookingUuid(canonicalSubjectId)
            ?: return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_INVALID_PAYLOAD)
        val booking = bookingRepo.findById(bookingUuid)
            ?: return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_SUBJECT_NOT_FOUND)

        val existing = checkinRepo.findBySubject(CheckinSubjectType.BOOKING, canonicalSubjectId)
        if (existing != null) {
            return CheckinServiceResult.Success(existing.toAlreadyUsed())
        }

        if (status == CheckinResultStatus.DENIED && denyReason.isNullOrBlank()) {
            return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_DENY_REASON_REQUIRED)
        }

        val occurredAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)

        return try {
            insertBookingCheckin(
                bookingId = bookingUuid,
                booking = booking,
                subjectId = canonicalSubjectId,
                actor = actor,
                method = CheckinMethod.NAME,
                status = status,
                denyReason = denyReason,
                occurredAt = occurredAt,
            )
        } catch (ex: Exception) {
            if (ex.isUniqueViolation()) {
                val duplicate =
                    checkinRepo.findBySubject(CheckinSubjectType.BOOKING, canonicalSubjectId)
                if (duplicate != null) {
                    return CheckinServiceResult.Success(duplicate.toAlreadyUsed())
                }
            }
            logger.warn("checkin.manual failed", ex)
            CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_INVALID_PAYLOAD)
        }
    }

    private fun hasEntryManagerRole(actor: AuthContext): Boolean {
        val allowed =
            setOf(
                Role.ENTRY_MANAGER,
                Role.MANAGER,
                Role.CLUB_ADMIN,
                Role.HEAD_MANAGER,
                Role.OWNER,
                Role.GLOBAL_ADMIN,
            )
        return actor.roles.any { it in allowed }
    }

    private fun parseBookingToken(payload: String): QrBookingCodec.Decoded? {
        val now = clock.instant()
        val primarySecret = bookingQrSecretProvider().takeIf { it.isNotBlank() }
        val decodedPrimary =
            primarySecret?.let { secret ->
                runCatching { QrBookingCodec.verify(payload, now, bookingQrTtl, secret) }.getOrNull()
            }
        if (decodedPrimary != null) return decodedPrimary

        val oldSecret = oldBookingQrSecretProvider()?.takeIf { it.isNotBlank() }
        return oldSecret?.let { secret ->
            runCatching { QrBookingCodec.verify(payload, now, bookingQrTtl, secret) }.getOrNull()
        }
    }

    private suspend fun handleBookingScan(
        decoded: QrBookingCodec.Decoded,
        actor: AuthContext,
    ): CheckinServiceResult<CheckinResult> {
        val bookingUuid = decoded.bookingId.toBookingUuid()
            ?: return CheckinServiceResult.Success(CheckinResult.Invalid(CheckinInvalidReason.BOOKING_INVALID))
        val booking = bookingRepo.findById(bookingUuid)
            ?: return CheckinServiceResult.Success(CheckinResult.Invalid(CheckinInvalidReason.BOOKING_INVALID))

        val subjectId = decoded.bookingId.toString()
        val existingCheckin = checkinRepo.findBySubject(CheckinSubjectType.BOOKING, subjectId)
        if (existingCheckin != null) {
            return CheckinServiceResult.Success(existingCheckin.toAlreadyUsed())
        }

        val occurredAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val resultStatus = calculateResultStatus(booking.arrivalBy, occurredAt)

        return try {
            insertBookingCheckin(
                bookingId = bookingUuid,
                booking = booking,
                subjectId = subjectId,
                actor = actor,
                method = CheckinMethod.QR,
                status = resultStatus,
                denyReason = null,
                occurredAt = occurredAt,
            )
        } catch (ex: Exception) {
            if (ex.isUniqueViolation()) {
                val existing = checkinRepo.findBySubject(CheckinSubjectType.BOOKING, subjectId)
                if (existing != null) {
                    return CheckinServiceResult.Success(existing.toAlreadyUsed())
                }
            }
            logger.warn("checkin.scan failed", ex)
            CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_INVALID_PAYLOAD)
        }
    }

    private fun parseInvitationToken(payload: String): String? =
        when {
            payload.startsWith("inv:") && payload.length > 4 -> payload.removePrefix("inv:")
            payload.startsWith("inv_") && payload.length > 4 -> payload.removePrefix("inv_")
            else -> null
        }

    private fun resolveBookingUuid(subjectId: String): UUID? =
        subjectId.toLongOrNull()?.toBookingUuid()
            ?: runCatching { UUID.fromString(subjectId) }.getOrNull()

    private fun Long.toBookingUuid(): UUID? = runCatching { UUID(0L, this) }.getOrNull()

    private suspend fun insertBookingCheckin(
        bookingId: UUID,
        booking: com.example.bot.data.booking.core.BookingRecord,
        subjectId: String,
        actor: AuthContext,
        method: CheckinMethod,
        status: CheckinResultStatus,
        denyReason: String?,
        occurredAt: Instant,
    ): CheckinServiceResult<CheckinResult> {
        val record =
            checkinRepo.insertWithBookingUpdate(
                checkin =
                    com.example.bot.data.club.NewCheckin(
                        clubId = booking.clubId,
                        eventId = booking.eventId,
                        subjectType = CheckinSubjectType.BOOKING,
                        subjectId = subjectId,
                        checkedBy = actor.userId,
                        method = method,
                        resultStatus = status,
                        denyReason = denyReason,
                        occurredAt = occurredAt,
                    ),
                bookingId = bookingId,
                bookingStatus = status.toBookingStatus(),
            )

        return CheckinServiceResult.Success(
            CheckinResult.Success(
                subjectType = record.subjectType,
                subjectId = record.subjectId,
                resultStatus = record.resultStatus,
                displayName = null,
                occurredAt = record.occurredAt,
                checkedBy = record.checkedBy,
            ),
        )
    }

    private fun CheckinResultStatus.toBookingStatus(): BookingStatus =
        when (this) {
            CheckinResultStatus.ARRIVED, CheckinResultStatus.LATE -> BookingStatus.SEATED
            CheckinResultStatus.DENIED -> BookingStatus.NO_SHOW
        }

    private fun calculateResultStatus(arrivalWindowEnd: Instant?, now: Instant): CheckinResultStatus {
        if (arrivalWindowEnd == null) return CheckinResultStatus.ARRIVED
        val graceEnd = arrivalWindowEnd.plus(Duration.ofMinutes(checkinConfig.lateGraceMinutes.toLong()))
        return if (!now.isAfter(graceEnd)) CheckinResultStatus.ARRIVED else CheckinResultStatus.LATE
    }

    private fun CheckinResultStatus.toEntryStatus(): GuestListEntryStatus =
        when (this) {
            CheckinResultStatus.ARRIVED -> GuestListEntryStatus.ARRIVED
            CheckinResultStatus.LATE -> GuestListEntryStatus.LATE
            CheckinResultStatus.DENIED -> GuestListEntryStatus.DENIED
        }

    private suspend fun insertCheckinWithUpdates(
        checkin: com.example.bot.data.club.NewCheckin,
        entryId: Long,
        entryStatus: GuestListEntryStatus,
        invitationUse: InvitationUse?,
        displayName: String?,
    ): CheckinServiceResult<CheckinResult> {
        val record =
            checkinRepo.insertWithEntryUpdate(
                checkin = checkin,
                entryId = entryId,
                entryStatus = entryStatus,
                invitationUse = invitationUse,
            )

        return CheckinServiceResult.Success(
            CheckinResult.Success(
                subjectType = record.subjectType,
                subjectId = record.subjectId,
                resultStatus = record.resultStatus,
                displayName = displayName,
                occurredAt = record.occurredAt,
                checkedBy = record.checkedBy,
            ),
        )
    }

    private fun com.example.bot.data.club.CheckinRecord.toAlreadyUsed(): CheckinResult.AlreadyUsed =
        CheckinResult.AlreadyUsed(
            subjectType = subjectType,
            subjectId = subjectId,
            existingCheckin =
                ExistingCheckin(
                    occurredAt = occurredAt,
                    checkedBy = checkedBy,
                    resultStatus = resultStatus,
                    method = method,
                ),
        )
}
