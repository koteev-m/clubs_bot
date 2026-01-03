package com.example.bot.club

import com.example.bot.config.env
import com.example.bot.config.envInt
import java.time.Instant

@Suppress("DataClassPrivateConstructor")
data class InvitationConfig private constructor(
    val ttlHours: Int,
    val botUsername: String?,
) {
    init {
        require(ttlHours > 0) {
            "$ENV_INVITATION_TTL_HOURS must be positive (was $ttlHours)"
        }
    }

    companion object {
        const val ENV_INVITATION_TTL_HOURS: String = "INVITATION_TTL_HOURS"
        const val ENV_BOT_USERNAME: String = "BOT_USERNAME"
        private const val DEFAULT_INVITATION_TTL_HOURS: Int = 72

        operator fun invoke(
            ttlHours: Int = envInt(ENV_INVITATION_TTL_HOURS, DEFAULT_INVITATION_TTL_HOURS),
            botUsername: String? = env(ENV_BOT_USERNAME),
        ): InvitationConfig = InvitationConfig(ttlHours, normalizeBotUsername(botUsername))

        fun fromEnv(): InvitationConfig = InvitationConfig()

        private fun normalizeBotUsername(raw: String?): String? {
            val trimmed = raw?.trim() ?: return null
            val withoutAt = trimmed.removePrefix("@")
            return withoutAt.takeIf { it.isNotEmpty() }
        }
    }
}

sealed interface InvitationServiceError {
    data object INVITATION_INVALID : InvitationServiceError
    data object INVITATION_REVOKED : InvitationServiceError
    data object INVITATION_EXPIRED : InvitationServiceError
    data object INVITATION_ALREADY_USED : InvitationServiceError
    data object GUEST_LIST_ENTRY_NOT_FOUND : InvitationServiceError
    data object GUEST_LIST_NOT_ACTIVE : InvitationServiceError
}

sealed interface InvitationServiceResult<out T> {
    data class Success<T>(val value: T) : InvitationServiceResult<T>

    data class Failure(val error: InvitationServiceError) : InvitationServiceResult<Nothing>
}

enum class InvitationResponse { CONFIRM, DECLINE }

data class InvitationCreateResult(
    val token: String,
    val deepLinkUrl: String,
    val qrPayload: String,
    val expiresAt: Instant,
)

data class InvitationCard(
    val invitationId: Long,
    val entryId: Long,
    val guestListId: Long,
    val clubId: Long,
    val clubName: String?,
    val eventId: Long,
    val arrivalWindowStart: Instant?,
    val arrivalWindowEnd: Instant?,
    val displayName: String,
    val entryStatus: GuestListEntryStatus,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val usedAt: Instant?,
)

interface InvitationService {
    suspend fun createInvitation(
        entryId: Long,
        channel: InvitationChannel,
        createdBy: Long,
    ): InvitationServiceResult<InvitationCreateResult>

    suspend fun resolveInvitation(rawToken: String): InvitationServiceResult<InvitationCard>

    suspend fun respondToInvitation(
        rawToken: String,
        telegramUserId: Long,
        response: InvitationResponse,
    ): InvitationServiceResult<InvitationCard>
}
