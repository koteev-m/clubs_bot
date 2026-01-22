package com.example.bot.checkin

import com.example.bot.club.CheckinMethod
import com.example.bot.club.CheckinResultStatus
import com.example.bot.club.CheckinSubjectType
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.db.envInt
import org.slf4j.LoggerFactory
import java.time.Instant

data class CheckinConfig(
    val lateGraceMinutes: Int =
        envInt(
            ENV_CHECKIN_LATE_GRACE_MINUTES,
            DEFAULT_LATE_GRACE_MINUTES,
            min = 0,
            envProvider = System::getenv,
            log = log,
        ),
) {
    init {
        require(lateGraceMinutes >= 0) { "$ENV_CHECKIN_LATE_GRACE_MINUTES must be non-negative (was $lateGraceMinutes)" }
    }

    companion object {
        const val ENV_CHECKIN_LATE_GRACE_MINUTES: String = "CHECKIN_LATE_GRACE_MINUTES"
        private const val DEFAULT_LATE_GRACE_MINUTES: Int = 15
        private val log = LoggerFactory.getLogger(CheckinConfig::class.java)

        fun fromEnv(): CheckinConfig = CheckinConfig()
    }
}

enum class CheckinInvalidReason {
    UNKNOWN_FORMAT,
    TOKEN_INVALID,
    TOKEN_REVOKED,
    TOKEN_EXPIRED,
    TOKEN_USED,
    BOOKING_INVALID,
}

enum class HostCheckinAction {
    AUTO,
    DENY,
}

enum class HostCheckinSubjectKind {
    BOOKING,
    GUEST_LIST_ENTRY,
    INVITATION,
}

enum class HostCheckinDenyReason {
    NOT_FOUND,
    ALREADY_USED,
    ALREADY_CHECKED_IN,
    INVALID_STATUS,
    SCOPE_MISMATCH,
    TOKEN_INVALID,
    TOKEN_REVOKED,
    TOKEN_EXPIRED,
}

data class HostCheckinRequest(
    val clubId: Long,
    val eventId: Long,
    val bookingId: String? = null,
    val guestListEntryId: Long? = null,
    val invitationToken: String? = null,
    val action: HostCheckinAction = HostCheckinAction.AUTO,
    val denyReason: String? = null,
)

data class HostCheckinSubject(
    val kind: HostCheckinSubjectKind,
    val bookingId: String? = null,
    val guestListEntryId: Long? = null,
    val invitationId: Long? = null,
)

data class HostCheckinOutcome(
    val outcomeStatus: CheckinResultStatus,
    val denyReason: String? = null,
    val subject: HostCheckinSubject,
    val bookingStatus: BookingStatus? = null,
    val entryStatus: GuestListEntryStatus? = null,
    val occurredAt: Instant? = null,
)

sealed interface CheckinResult {
    data class Success(
        val subjectType: CheckinSubjectType,
        val subjectId: String,
        val resultStatus: CheckinResultStatus,
        val displayName: String?,
        val alreadyUsed: Boolean = false,
        val occurredAt: Instant,
        val checkedBy: Long?,
    ) : CheckinResult

    data class AlreadyUsed(
        val subjectType: CheckinSubjectType,
        val subjectId: String,
        val existingCheckin: ExistingCheckin,
    ) : CheckinResult

    data class Invalid(val reason: CheckinInvalidReason) : CheckinResult

    data object Forbidden : CheckinResult
}

data class ExistingCheckin(
    val occurredAt: Instant,
    val checkedBy: Long?,
    val resultStatus: CheckinResultStatus,
    val method: CheckinMethod,
)

sealed interface CheckinServiceError {
    data object CHECKIN_FORBIDDEN : CheckinServiceError
    data object CHECKIN_SUBJECT_NOT_FOUND : CheckinServiceError
    data object CHECKIN_INVALID_PAYLOAD : CheckinServiceError
    data object CHECKIN_DENY_REASON_REQUIRED : CheckinServiceError
}

sealed interface CheckinServiceResult<out T> {
    data class Success<T>(val value: T) : CheckinServiceResult<T>

    data class Failure(val error: CheckinServiceError) : CheckinServiceResult<Nothing>
}

interface CheckinService {
    suspend fun scanQr(
        payload: String,
        actor: com.example.bot.data.security.AuthContext,
    ): CheckinServiceResult<CheckinResult>

    suspend fun manualCheckin(
        subjectType: CheckinSubjectType,
        subjectId: String,
        status: CheckinResultStatus,
        denyReason: String?,
        actor: com.example.bot.data.security.AuthContext,
    ): CheckinServiceResult<CheckinResult>

    suspend fun hostCheckin(
        request: HostCheckinRequest,
        actor: com.example.bot.data.security.AuthContext,
    ): CheckinServiceResult<HostCheckinOutcome>

    suspend fun hostScan(
        payload: String,
        clubId: Long,
        eventId: Long,
        actor: com.example.bot.data.security.AuthContext,
    ): CheckinServiceResult<HostCheckinOutcome>
}
