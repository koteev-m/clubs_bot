package com.example.bot.data.checkin

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
import com.example.bot.data.club.CheckinDbRepository
import com.example.bot.data.club.CheckinDbRepository.InvitationUse
import com.example.bot.data.club.GuestListDbRepository
import com.example.bot.data.club.GuestListEntryDbRepository
import com.example.bot.data.db.isUniqueViolation
import com.example.bot.data.security.AuthContext
import com.example.bot.data.security.Role
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

class CheckinServiceImpl(
    private val checkinRepo: CheckinDbRepository,
    private val invitationService: InvitationService,
    private val guestListRepo: GuestListDbRepository,
    private val guestListEntryRepo: GuestListEntryDbRepository,
    private val checkinConfig: CheckinConfig = CheckinConfig.fromEnv(),
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

        val rawToken = parseInvitationToken(payload.trim())
            ?: return CheckinServiceResult.Success(CheckinResult.Invalid(CheckinInvalidReason.UNKNOWN_FORMAT))

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

        if (subjectType != CheckinSubjectType.GUEST_LIST_ENTRY) {
            return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_INVALID_PAYLOAD)
        }

        val entryId = subjectId.toLongOrNull() ?: return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_INVALID_PAYLOAD)
        val canonicalSubjectId = entryId.toString()
        val entry = guestListEntryRepo.findById(entryId)
            ?: return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_SUBJECT_NOT_FOUND)
        val guestList = guestListRepo.findById(entry.guestListId)
            ?: return CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_SUBJECT_NOT_FOUND)

        val existing = checkinRepo.findBySubject(CheckinSubjectType.GUEST_LIST_ENTRY, canonicalSubjectId)
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
                val duplicate = checkinRepo.findBySubject(CheckinSubjectType.GUEST_LIST_ENTRY, canonicalSubjectId)
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

    private fun parseInvitationToken(payload: String): String? =
        when {
            payload.startsWith("inv:") && payload.length > 4 -> payload.removePrefix("inv:")
            payload.startsWith("inv_") && payload.length > 4 -> payload.removePrefix("inv_")
            else -> null
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
