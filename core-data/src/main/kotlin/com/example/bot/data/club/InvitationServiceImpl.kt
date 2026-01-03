package com.example.bot.data.club

import com.example.bot.club.GuestListConfig
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListStatus
import com.example.bot.club.InvitationCard
import com.example.bot.club.InvitationChannel
import com.example.bot.club.InvitationConfig
import com.example.bot.club.InvitationCreateResult
import com.example.bot.club.InvitationResponse
import com.example.bot.club.InvitationService
import com.example.bot.club.InvitationServiceError
import com.example.bot.club.InvitationServiceResult
import com.example.bot.data.db.withRetriedTx
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

private val TERMINAL_ENTRY_STATUSES =
    setOf(
        GuestListEntryStatus.ARRIVED,
        GuestListEntryStatus.LATE,
        GuestListEntryStatus.CHECKED_IN,
        GuestListEntryStatus.DENIED,
        GuestListEntryStatus.NO_SHOW,
    )

class InvitationServiceImpl(
    private val invitationRepo: InvitationDbRepository,
    private val guestListRepo: GuestListDbRepository,
    private val guestListEntryRepo: GuestListEntryDbRepository,
    private val guestListConfig: GuestListConfig = GuestListConfig.fromEnv(),
    private val invitationConfig: InvitationConfig = InvitationConfig.fromEnv(),
    private val clock: Clock = Clock.systemUTC(),
    private val secureRandom: SecureRandom = SecureRandom(),
) : InvitationService {
    override suspend fun createInvitation(
        entryId: Long,
        channel: InvitationChannel,
        createdBy: Long,
    ): InvitationServiceResult<InvitationCreateResult> {
        return withRetriedTx(name = "invitation.create", manageTransaction = false) {
            val entry = guestListEntryRepo.findById(entryId)
                ?: return@withRetriedTx InvitationServiceResult.Failure(
                    InvitationServiceError.GUEST_LIST_ENTRY_NOT_FOUND,
                )
            val guestList = guestListRepo.findById(entry.guestListId)
                ?: return@withRetriedTx InvitationServiceResult.Failure(
                    InvitationServiceError.GUEST_LIST_ENTRY_NOT_FOUND,
                )

            if (guestList.status != GuestListStatus.ACTIVE) {
                return@withRetriedTx InvitationServiceResult.Failure(InvitationServiceError.GUEST_LIST_NOT_ACTIVE)
            }

            val now = clock.instant()
            val expiresAt = calculateExpiry(guestList, now)
            if (!expiresAt.isAfter(now)) {
                return@withRetriedTx InvitationServiceResult.Failure(InvitationServiceError.GUEST_LIST_NOT_ACTIVE)
            }
            val token = generateToken()
            val tokenHash = token.sha256Hex()

            val invitation = invitationRepo.create(entryId, tokenHash, channel, expiresAt, createdBy)

            invitationRepo.revokeOlderActiveByEntryId(entryId, invitation.id, now)
            if (entry.status == GuestListEntryStatus.ADDED) {
                guestListEntryRepo.updateStatus(entryId, GuestListEntryStatus.INVITED)
            }
            val deepLink = buildDeepLink(token)
            InvitationServiceResult.Success(
                InvitationCreateResult(
                    token = token,
                    deepLinkUrl = deepLink,
                    qrPayload = "inv:$token",
                    expiresAt = invitation.expiresAt,
                ),
            )
        }
    }

    override suspend fun resolveInvitation(rawToken: String): InvitationServiceResult<InvitationCard> =
        resolveInternal(rawToken).map { (invitation, entry, guestList) ->
            buildCard(invitation, entry, guestList)
        }

    override suspend fun respondToInvitation(
        rawToken: String,
        telegramUserId: Long,
        response: InvitationResponse,
    ): InvitationServiceResult<InvitationCard> {
        val resolved = resolveInternal(rawToken)
        if (resolved is InvitationServiceResult.Failure) {
            return resolved
        }

        val (invitation, entry, guestList) = (resolved as InvitationServiceResult.Success).value
        if (entry.telegramUserId == null) {
            guestListEntryRepo.setTelegramUserIdIfNull(entry.id, telegramUserId)
        }

        val newStatus =
            when {
                isTerminal(entry.status) -> entry.status
                response == InvitationResponse.CONFIRM -> GuestListEntryStatus.CONFIRMED
                else -> GuestListEntryStatus.DECLINED
            }

        if (newStatus != entry.status && !isTerminal(entry.status)) {
            guestListEntryRepo.updateStatus(entry.id, newStatus)
        }

        return InvitationServiceResult.Success(buildCard(invitation, entry.copy(status = newStatus), guestList))
    }

    private suspend fun resolveInternal(rawToken: String): InvitationServiceResult<ResolvedInvitation> {
        val token = rawToken.trim()
        if (token.isEmpty()) {
            return InvitationServiceResult.Failure(InvitationServiceError.INVITATION_INVALID)
        }
        if (token.length > MAX_TOKEN_LENGTH || token.any { !isBase64UrlChar(it) }) {
            return InvitationServiceResult.Failure(InvitationServiceError.INVITATION_INVALID)
        }

        val now = clock.instant()
        val tokenHash = token.sha256Hex()
        val invitation = invitationRepo.findByTokenHash(tokenHash)
            ?: return InvitationServiceResult.Failure(InvitationServiceError.INVITATION_INVALID)

        if (invitation.revokedAt != null) {
            return InvitationServiceResult.Failure(InvitationServiceError.INVITATION_REVOKED)
        }
        if (invitation.usedAt != null) {
            return InvitationServiceResult.Failure(InvitationServiceError.INVITATION_ALREADY_USED)
        }
        if (!invitation.expiresAt.isAfter(now)) {
            return InvitationServiceResult.Failure(InvitationServiceError.INVITATION_EXPIRED)
        }

        val entry = guestListEntryRepo.findById(invitation.guestListEntryId)
            ?: return InvitationServiceResult.Failure(InvitationServiceError.GUEST_LIST_ENTRY_NOT_FOUND)
        val guestList = guestListRepo.findById(entry.guestListId)
            ?: return InvitationServiceResult.Failure(InvitationServiceError.GUEST_LIST_ENTRY_NOT_FOUND)

        return InvitationServiceResult.Success(ResolvedInvitation(invitation, entry, guestList))
    }

    private fun calculateExpiry(guestList: GuestListRecord, now: Instant): Instant {
        val grace = Duration.ofMinutes(guestListConfig.noShowGraceMinutes.toLong())
        val arrivalEnd = guestList.arrivalWindowEnd
        return arrivalEnd?.plus(grace) ?: now.plus(Duration.ofHours(invitationConfig.ttlHours.toLong()))
    }

    private fun buildDeepLink(token: String): String =
        invitationConfig.botUsername?.let { "https://t.me/$it?start=inv_$token" } ?: "inv_$token"

    private fun buildCard(
        invitation: InvitationRecord,
        entry: GuestListEntryRecord,
        guestList: GuestListRecord,
    ): InvitationCard =
        InvitationCard(
            invitationId = invitation.id,
            entryId = entry.id,
            guestListId = guestList.id,
            clubId = guestList.clubId,
            clubName = null,
            eventId = guestList.eventId,
            arrivalWindowStart = guestList.arrivalWindowStart,
            arrivalWindowEnd = guestList.arrivalWindowEnd,
            displayName = entry.displayName,
            entryStatus = entry.status,
            expiresAt = invitation.expiresAt,
            revokedAt = invitation.revokedAt,
            usedAt = invitation.usedAt,
        )

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        // Safety bound to avoid expensive processing on obviously invalid tokens
        private const val MAX_TOKEN_LENGTH = 128
    }

    private fun isBase64UrlChar(c: Char): Boolean =
        (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '-' || c == '_'

    private fun String.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .toHex()

    private fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

    private data class ResolvedInvitation(
        val invitation: InvitationRecord,
        val entry: GuestListEntryRecord,
        val guestList: GuestListRecord,
    )

    private fun <T, R> InvitationServiceResult<T>.map(transform: (T) -> R): InvitationServiceResult<R> =
        when (this) {
            is InvitationServiceResult.Failure -> this
            is InvitationServiceResult.Success -> InvitationServiceResult.Success(transform(value))
        }

    private fun isTerminal(status: GuestListEntryStatus): Boolean = status in TERMINAL_ENTRY_STATUSES
}
