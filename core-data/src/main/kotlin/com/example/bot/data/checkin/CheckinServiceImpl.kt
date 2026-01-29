package com.example.bot.data.checkin

import com.example.bot.booking.a3.QrBookingCodec
import com.example.bot.checkin.BookingQrConfig
import com.example.bot.checkin.CheckinConfig
import com.example.bot.checkin.HostCheckinAction
import com.example.bot.checkin.HostCheckinDenyReason
import com.example.bot.checkin.HostCheckinOutcome
import com.example.bot.checkin.HostCheckinRequest
import com.example.bot.checkin.HostCheckinSubject
import com.example.bot.checkin.HostCheckinSubjectKind
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
import com.example.bot.club.GuestListStatus
import com.example.bot.club.InvitationService
import com.example.bot.club.InvitationServiceError
import com.example.bot.club.InvitationServiceResult
import com.example.bot.audit.AuditLogger
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
    private val auditLogger: AuditLogger,
    private val checkinConfig: CheckinConfig = CheckinConfig.fromEnv(),
    private val bookingQrConfig: BookingQrConfig = BookingQrConfig.fromEnv(),
    private val clock: Clock = Clock.systemUTC(),
) : CheckinService {
    private val logger = LoggerFactory.getLogger(CheckinServiceImpl::class.java)
    private val allowedBookingStatusTransitions = setOf(BookingStatus.BOOKED)
    private val arrivedEntryStatuses = setOf(
        GuestListEntryStatus.ARRIVED,
        GuestListEntryStatus.LATE,
        GuestListEntryStatus.CHECKED_IN,
    )
    private val terminalEntryStatuses =
        arrivedEntryStatuses + setOf(
            GuestListEntryStatus.DENIED,
            GuestListEntryStatus.NO_SHOW,
            GuestListEntryStatus.EXPIRED,
        )

    override suspend fun hostCheckin(
        request: HostCheckinRequest,
        actor: AuthContext,
    ): CheckinServiceResult<HostCheckinOutcome> {
        if (!hasEntryManagerRole(actor)) {
            return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_FORBIDDEN)
        }

        if (request.clubId <= 0 || request.eventId <= 0) {
            return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_INVALID_PAYLOAD)
        }

        val bookingId = request.bookingId?.trim()?.takeIf { it.isNotEmpty() }
        val entryId = request.guestListEntryId
        val invitationToken = request.invitationToken?.trim()?.takeIf { it.isNotEmpty() }
        val provided = listOfNotNull(bookingId, entryId?.toString(), invitationToken)
        if (provided.size != 1) {
            return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_INVALID_PAYLOAD)
        }

        if (request.action == HostCheckinAction.DENY && request.denyReason.isNullOrBlank()) {
            return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_DENY_REASON_REQUIRED)
        }

        if (invitationToken != null && request.action == HostCheckinAction.DENY) {
            return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_INVALID_PAYLOAD)
        }

        return when {
            bookingId != null ->
                handleBookingCheckin(
                    clubId = request.clubId,
                    eventId = request.eventId,
                    bookingId = bookingId,
                    method = CheckinMethod.NAME,
                    action = request.action,
                    denyReason = request.denyReason,
                    actor = actor,
                )
            entryId != null ->
                handleEntryCheckin(
                    clubId = request.clubId,
                    eventId = request.eventId,
                    entryId = entryId,
                    action = request.action,
                    denyReason = request.denyReason,
                    actor = actor,
                )
            invitationToken != null ->
                handleInvitationCheckin(
                    clubId = request.clubId,
                    eventId = request.eventId,
                    token = parseInvitationToken(invitationToken) ?: invitationToken,
                    actor = actor,
                )
            else -> CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_INVALID_PAYLOAD)
        }
    }

    override suspend fun hostScan(
        payload: String,
        clubId: Long,
        eventId: Long,
        actor: AuthContext,
    ): CheckinServiceResult<HostCheckinOutcome> {
        if (!hasEntryManagerRole(actor)) {
            return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_FORBIDDEN)
        }
        if (clubId <= 0 || eventId <= 0) {
            return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_INVALID_PAYLOAD)
        }
        val token = parseInvitationToken(payload.trim())
        if (token != null) {
            return handleInvitationCheckin(clubId, eventId, token, actor)
        }

        val bookingDecoded =
            parseBookingToken(payload.trim())
                ?: return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_INVALID_PAYLOAD)

        return handleBookingCheckin(
            clubId = clubId,
            eventId = eventId,
            bookingId = bookingDecoded.bookingId.toString(),
            method = CheckinMethod.QR,
            action = HostCheckinAction.AUTO,
            denyReason = null,
            actor = actor,
        )
    }

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
                subjectUserId = null,
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
                subjectUserId = entry.telegramUserId,
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
        val canonical = canonicalizeBookingSubject(subjectId)
            ?: return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_INVALID_PAYLOAD)
        val booking = bookingRepo.findById(canonical.bookingId)
            ?: return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_SUBJECT_NOT_FOUND)

        val existing = checkinRepo.findBySubject(CheckinSubjectType.BOOKING, canonical.subjectId)
        if (existing != null) {
            return CheckinServiceResult.Success(existing.toAlreadyUsed())
        }

        if (status == CheckinResultStatus.DENIED && denyReason.isNullOrBlank()) {
            return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_DENY_REASON_REQUIRED)
        }

        val occurredAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)

        return try {
            insertBookingCheckin(
                bookingId = canonical.bookingId,
                booking = booking,
                subjectId = canonical.subjectId,
                actor = actor,
                method = CheckinMethod.NAME,
                status = status,
                denyReason = denyReason,
                occurredAt = occurredAt,
            )
        } catch (ex: Exception) {
            if (ex.isUniqueViolation()) {
                val duplicate =
                    checkinRepo.findBySubject(CheckinSubjectType.BOOKING, canonical.subjectId)
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
            )
        return actor.roles.any { it in allowed }
    }

    private fun parseBookingToken(payload: String): QrBookingCodec.Decoded? {
        val now = clock.instant()
        val ttl = bookingQrConfig.ttl
        val primarySecret = bookingQrConfig.secret.takeIf { it.isNotBlank() }
        val decodedPrimary =
            primarySecret?.let { secret ->
                runCatching { QrBookingCodec.verify(payload, now, ttl, secret) }.getOrNull()
            }
        if (decodedPrimary != null) return decodedPrimary

        val oldSecret = bookingQrConfig.oldSecret?.takeIf { it.isNotBlank() }
        return oldSecret?.let { secret ->
            runCatching { QrBookingCodec.verify(payload, now, ttl, secret) }.getOrNull()
        }
    }

    private suspend fun handleBookingScan(
        decoded: QrBookingCodec.Decoded,
        actor: AuthContext,
    ): CheckinServiceResult<CheckinResult> {
        val canonical = canonicalizeBookingSubject(decoded.bookingId.toString())
            ?: return CheckinServiceResult.Success(CheckinResult.Invalid(CheckinInvalidReason.BOOKING_INVALID))
        val booking = bookingRepo.findById(canonical.bookingId)
            ?: return CheckinServiceResult.Success(CheckinResult.Invalid(CheckinInvalidReason.BOOKING_INVALID))

        val existingCheckin = checkinRepo.findBySubject(CheckinSubjectType.BOOKING, canonical.subjectId)
        if (existingCheckin != null) {
            return CheckinServiceResult.Success(existingCheckin.toAlreadyUsed())
        }

        val occurredAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val resultStatus = calculateResultStatus(booking.arrivalBy, occurredAt)

        return try {
            insertBookingCheckin(
                bookingId = canonical.bookingId,
                booking = booking,
                subjectId = canonical.subjectId,
                actor = actor,
                method = CheckinMethod.QR,
                status = resultStatus,
                denyReason = null,
                occurredAt = occurredAt,
            )
        } catch (ex: Exception) {
            if (ex.isUniqueViolation()) {
                val existing = checkinRepo.findBySubject(CheckinSubjectType.BOOKING, canonical.subjectId)
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

    private suspend fun handleBookingCheckin(
        clubId: Long,
        eventId: Long,
        bookingId: String,
        method: CheckinMethod,
        action: HostCheckinAction,
        denyReason: String?,
        actor: AuthContext,
    ): CheckinServiceResult<HostCheckinOutcome> {
        val canonical = canonicalizeBookingSubject(bookingId)
            ?: return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_INVALID_PAYLOAD)
        val booking = bookingRepo.findById(canonical.bookingId)
            ?: return CheckinServiceResult.Success(
                deniedOutcome(
                    reason = HostCheckinDenyReason.NOT_FOUND,
                    subject = bookingSubject(canonical.subjectId),
                ),
            )
        if (booking.clubId != clubId || booking.eventId != eventId) {
            return CheckinServiceResult.Success(
                deniedOutcome(
                    reason = HostCheckinDenyReason.SCOPE_MISMATCH,
                    subject = bookingSubject(canonical.subjectId),
                    bookingStatus = booking.status,
                ),
            )
        }

        val existing = checkinRepo.findBySubject(CheckinSubjectType.BOOKING, canonical.subjectId)
        if (existing != null) {
            return CheckinServiceResult.Success(
                deniedOutcome(
                    reason = HostCheckinDenyReason.ALREADY_USED,
                    subject = bookingSubject(canonical.subjectId),
                    bookingStatus = booking.status,
                ),
            )
        }

        val currentStatus = booking.status
        if (currentStatus == BookingStatus.SEATED) {
            return CheckinServiceResult.Success(
                deniedOutcome(
                    reason = HostCheckinDenyReason.ALREADY_CHECKED_IN,
                    subject = bookingSubject(canonical.subjectId),
                    bookingStatus = currentStatus,
                ),
            )
        }
        if (currentStatus != BookingStatus.BOOKED) {
            return CheckinServiceResult.Success(
                deniedOutcome(
                    reason = HostCheckinDenyReason.INVALID_STATUS,
                    subject = bookingSubject(canonical.subjectId),
                    bookingStatus = currentStatus,
                ),
            )
        }

        val occurredAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val resultStatus =
            if (action == HostCheckinAction.DENY) {
                CheckinResultStatus.DENIED
            } else {
                calculateResultStatus(booking.arrivalBy, occurredAt)
            }

        val record =
            try {
                checkinRepo.insertWithBookingUpdate(
                    checkin =
                        com.example.bot.data.club.NewCheckin(
                            clubId = booking.clubId,
                            eventId = booking.eventId,
                            subjectType = CheckinSubjectType.BOOKING,
                            subjectId = canonical.subjectId,
                            checkedBy = actor.userId,
                            method = method,
                            resultStatus = resultStatus,
                            denyReason = denyReason,
                            occurredAt = occurredAt,
                        ),
                    bookingId = canonical.bookingId,
                    bookingStatus = resultStatus.toBookingStatus(),
                    allowedFromStatuses = allowedBookingStatusTransitions,
                )
            } catch (ex: Exception) {
                if (ex.isUniqueViolation()) {
                    return CheckinServiceResult.Success(
                        deniedOutcome(
                            reason = HostCheckinDenyReason.ALREADY_USED,
                            subject = bookingSubject(canonical.subjectId),
                            bookingStatus = currentStatus,
                        ),
                    )
                }
                throw ex
            }
        if (record == null) {
            return CheckinServiceResult.Success(
                deniedOutcome(
                    reason = HostCheckinDenyReason.INVALID_STATUS,
                    subject = bookingSubject(canonical.subjectId),
                    bookingStatus = currentStatus,
                ),
            )
        }
        record?.let {
            auditLogger.visitCheckedIn(
                clubId = booking.clubId,
                nightId = booking.eventId,
                checkinId = it.id,
                actorUserId = actor.userId,
                subjectUserId = null,
                method = it.method.name,
                subjectType = it.subjectType.name,
                subjectId = it.subjectId,
                resultStatus = it.resultStatus.name,
            )
        }

        return CheckinServiceResult.Success(
            HostCheckinOutcome(
                outcomeStatus = resultStatus,
                denyReason = if (resultStatus == CheckinResultStatus.DENIED) denyReason else null,
                subject = bookingSubject(canonical.subjectId),
                bookingStatus = resultStatus.toBookingStatus(),
                occurredAt = occurredAt,
            ),
        )
    }

    private suspend fun handleEntryCheckin(
        clubId: Long,
        eventId: Long,
        entryId: Long,
        action: HostCheckinAction,
        denyReason: String?,
        actor: AuthContext,
    ): CheckinServiceResult<HostCheckinOutcome> {
        val entry = guestListEntryRepo.findById(entryId)
            ?: return CheckinServiceResult.Success(
                deniedOutcome(
                    reason = HostCheckinDenyReason.NOT_FOUND,
                    subject = entrySubject(entryId),
                ),
            )
        val guestList = guestListRepo.findById(entry.guestListId)
            ?: return CheckinServiceResult.Success(
                deniedOutcome(
                    reason = HostCheckinDenyReason.NOT_FOUND,
                    subject = entrySubject(entryId),
                ),
            )
        if (guestList.clubId != clubId || guestList.eventId != eventId) {
            return CheckinServiceResult.Success(
                deniedOutcome(
                    reason = HostCheckinDenyReason.SCOPE_MISMATCH,
                    subject = entrySubject(entryId),
                    entryStatus = entry.status,
                ),
            )
        }
        if (guestList.status != GuestListStatus.ACTIVE) {
            return CheckinServiceResult.Success(
                deniedOutcome(
                    reason = HostCheckinDenyReason.INVALID_STATUS,
                    subject = entrySubject(entryId),
                    entryStatus = entry.status,
                ),
            )
        }

        val existing = checkinRepo.findBySubject(CheckinSubjectType.GUEST_LIST_ENTRY, entryId.toString())
        if (existing != null) {
            return CheckinServiceResult.Success(
                deniedOutcome(
                    reason = HostCheckinDenyReason.ALREADY_USED,
                    subject = entrySubject(entryId),
                    entryStatus = entry.status,
                ),
            )
        }

        if (entry.status in arrivedEntryStatuses) {
            return CheckinServiceResult.Success(
                deniedOutcome(
                    reason = HostCheckinDenyReason.ALREADY_CHECKED_IN,
                    subject = entrySubject(entryId),
                    entryStatus = entry.status,
                ),
            )
        }
        if (entry.status in terminalEntryStatuses) {
            return CheckinServiceResult.Success(
                deniedOutcome(
                    reason = HostCheckinDenyReason.INVALID_STATUS,
                    subject = entrySubject(entryId),
                    entryStatus = entry.status,
                ),
            )
        }

        val occurredAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val resultStatus =
            if (action == HostCheckinAction.DENY) {
                CheckinResultStatus.DENIED
            } else {
                calculateResultStatus(guestList.arrivalWindowEnd, occurredAt)
            }

        val record =
            try {
                checkinRepo.insertWithEntryUpdate(
                    checkin =
                        com.example.bot.data.club.NewCheckin(
                            clubId = guestList.clubId,
                            eventId = guestList.eventId,
                            subjectType = CheckinSubjectType.GUEST_LIST_ENTRY,
                            subjectId = entryId.toString(),
                            checkedBy = actor.userId,
                            method = CheckinMethod.NAME,
                            resultStatus = resultStatus,
                            denyReason = denyReason,
                            occurredAt = occurredAt,
                        ),
                    entryId = entryId,
                    entryStatus = resultStatus.toEntryStatus(),
                    invitationUse = null,
                )
            } catch (ex: Exception) {
                if (ex.isUniqueViolation()) {
                    return CheckinServiceResult.Success(
                        deniedOutcome(
                            reason = HostCheckinDenyReason.ALREADY_USED,
                            subject = entrySubject(entryId),
                            entryStatus = entry.status,
                        ),
                    )
                }
                throw ex
            }
        if (record == null) {
            return CheckinServiceResult.Success(
                deniedOutcome(
                    reason = HostCheckinDenyReason.ALREADY_USED,
                    subject = entrySubject(entryId),
                    entryStatus = entry.status,
                ),
            )
        }
        record?.let {
            auditLogger.visitCheckedIn(
                clubId = guestList.clubId,
                nightId = guestList.eventId,
                checkinId = it.id,
                actorUserId = actor.userId,
                subjectUserId = entry.telegramUserId,
                method = it.method.name,
                subjectType = it.subjectType.name,
                subjectId = it.subjectId,
                resultStatus = it.resultStatus.name,
            )
        }

        return CheckinServiceResult.Success(
            HostCheckinOutcome(
                outcomeStatus = resultStatus,
                denyReason = if (resultStatus == CheckinResultStatus.DENIED) denyReason else null,
                subject = entrySubject(entryId),
                entryStatus = resultStatus.toEntryStatus(),
                occurredAt = occurredAt,
            ),
        )
    }

    private suspend fun handleInvitationCheckin(
        clubId: Long,
        eventId: Long,
        token: String,
        actor: AuthContext,
    ): CheckinServiceResult<HostCheckinOutcome> {
        if (!hasEntryManagerRole(actor)) {
            return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_FORBIDDEN)
        }
        if (clubId <= 0 || eventId <= 0) {
            return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_INVALID_PAYLOAD)
        }
        val resolved = invitationService.resolveInvitation(token)
        if (resolved is InvitationServiceResult.Failure) {
            val reason =
                when (resolved.error) {
                    InvitationServiceError.INVITATION_INVALID -> HostCheckinDenyReason.TOKEN_INVALID
                    InvitationServiceError.INVITATION_REVOKED -> HostCheckinDenyReason.TOKEN_REVOKED
                    InvitationServiceError.INVITATION_EXPIRED -> HostCheckinDenyReason.TOKEN_EXPIRED
                    InvitationServiceError.INVITATION_ALREADY_USED -> HostCheckinDenyReason.ALREADY_USED
                    InvitationServiceError.GUEST_LIST_ENTRY_NOT_FOUND -> HostCheckinDenyReason.NOT_FOUND
                    InvitationServiceError.GUEST_LIST_NOT_ACTIVE -> HostCheckinDenyReason.INVALID_STATUS
                }
            return CheckinServiceResult.Success(
                deniedOutcome(
                    reason = reason,
                    subject = HostCheckinSubject(kind = HostCheckinSubjectKind.INVITATION),
                ),
            )
        }

        val card = (resolved as InvitationServiceResult.Success).value
        val guestList = guestListRepo.findById(card.guestListId)
        if (guestList == null || guestList.status != GuestListStatus.ACTIVE) {
            return CheckinServiceResult.Success(
                deniedOutcome(
                    reason = HostCheckinDenyReason.INVALID_STATUS,
                    subject = invitationSubject(card.invitationId, card.entryId),
                    entryStatus = card.entryStatus,
                ),
            )
        }
        if (card.clubId != clubId || card.eventId != eventId) {
            return CheckinServiceResult.Success(
                deniedOutcome(
                    reason = HostCheckinDenyReason.SCOPE_MISMATCH,
                    subject = invitationSubject(card.invitationId, card.entryId),
                    entryStatus = card.entryStatus,
                ),
            )
        }

        if (card.entryStatus in arrivedEntryStatuses) {
            return CheckinServiceResult.Success(
                deniedOutcome(
                    reason = HostCheckinDenyReason.ALREADY_CHECKED_IN,
                    subject = invitationSubject(card.invitationId, card.entryId),
                    entryStatus = card.entryStatus,
                ),
            )
        }
        if (card.entryStatus in terminalEntryStatuses) {
            return CheckinServiceResult.Success(
                deniedOutcome(
                    reason = HostCheckinDenyReason.INVALID_STATUS,
                    subject = invitationSubject(card.invitationId, card.entryId),
                    entryStatus = card.entryStatus,
                ),
            )
        }

        val occurredAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val resultStatus = calculateResultStatus(card.arrivalWindowEnd, occurredAt)
        val record =
            try {
                checkinRepo.insertWithEntryUpdate(
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
                    invitationUse = InvitationUse(card.invitationId, occurredAt),
                )
            } catch (ex: Exception) {
                if (ex.isUniqueViolation()) {
                    return CheckinServiceResult.Success(
                        deniedOutcome(
                            reason = HostCheckinDenyReason.ALREADY_USED,
                            subject = invitationSubject(card.invitationId, card.entryId),
                            entryStatus = card.entryStatus,
                        ),
                    )
                }
                throw ex
            }
        if (record == null) {
            return CheckinServiceResult.Success(
                deniedOutcome(
                    reason = HostCheckinDenyReason.ALREADY_USED,
                    subject = invitationSubject(card.invitationId, card.entryId),
                    entryStatus = card.entryStatus,
                ),
            )
        }
        record?.let {
            auditLogger.visitCheckedIn(
                clubId = card.clubId,
                nightId = card.eventId,
                checkinId = it.id,
                actorUserId = actor.userId,
                subjectUserId = null,
                method = it.method.name,
                subjectType = it.subjectType.name,
                subjectId = it.subjectId,
                resultStatus = it.resultStatus.name,
            )
        }

        return CheckinServiceResult.Success(
            HostCheckinOutcome(
                outcomeStatus = resultStatus,
                subject = invitationSubject(card.invitationId, card.entryId),
                entryStatus = resultStatus.toEntryStatus(),
                occurredAt = occurredAt,
            ),
        )
    }

    private fun bookingSubject(subjectId: String): HostCheckinSubject =
        HostCheckinSubject(kind = HostCheckinSubjectKind.BOOKING, bookingId = subjectId)

    private fun entrySubject(entryId: Long): HostCheckinSubject =
        HostCheckinSubject(kind = HostCheckinSubjectKind.GUEST_LIST_ENTRY, guestListEntryId = entryId)

    private fun invitationSubject(invitationId: Long, entryId: Long): HostCheckinSubject =
        HostCheckinSubject(
            kind = HostCheckinSubjectKind.INVITATION,
            invitationId = invitationId,
            guestListEntryId = entryId,
        )

    private fun deniedOutcome(
        reason: HostCheckinDenyReason,
        subject: HostCheckinSubject,
        bookingStatus: BookingStatus? = null,
        entryStatus: GuestListEntryStatus? = null,
    ): HostCheckinOutcome =
        HostCheckinOutcome(
            outcomeStatus = CheckinResultStatus.DENIED,
            denyReason = reason.name,
            subject = subject,
            bookingStatus = bookingStatus,
            entryStatus = entryStatus,
        )

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
                allowedFromStatuses = allowedBookingStatusTransitions,
            ) ?: return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_INVALID_PAYLOAD)

        auditLogger.visitCheckedIn(
            clubId = booking.clubId,
            nightId = booking.eventId,
            checkinId = record.id,
            actorUserId = actor.userId,
            subjectUserId = null,
            method = record.method.name,
            subjectType = record.subjectType.name,
            subjectId = record.subjectId,
            resultStatus = record.resultStatus.name,
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
        subjectUserId: Long?,
    ): CheckinServiceResult<CheckinResult> {
        val record =
            checkinRepo.insertWithEntryUpdate(
                checkin = checkin,
                entryId = entryId,
                entryStatus = entryStatus,
                invitationUse = invitationUse,
            ) ?: return CheckinServiceResult.Success(CheckinResult.Invalid(CheckinInvalidReason.TOKEN_USED))

        if (record.clubId != null && record.eventId != null) {
            auditLogger.visitCheckedIn(
                clubId = record.clubId,
                nightId = record.eventId,
                checkinId = record.id,
                actorUserId = record.checkedBy,
                subjectUserId = subjectUserId,
                method = record.method.name,
                subjectType = record.subjectType.name,
                subjectId = record.subjectId,
                resultStatus = record.resultStatus.name,
            )
        }
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
