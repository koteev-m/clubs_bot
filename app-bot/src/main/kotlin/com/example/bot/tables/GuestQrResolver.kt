package com.example.bot.tables

import com.example.bot.data.club.GuestListDbRepository
import com.example.bot.data.club.GuestListEntryDbRepository
import com.example.bot.data.security.UserRepository
import com.example.bot.guestlists.QrGuestListCodec
import com.example.bot.guestlists.QrVerificationResult
import com.example.bot.guestlists.quickValidateQr
import com.example.bot.http.ErrorCodes
import io.ktor.http.HttpStatusCode
import java.time.Duration
import java.time.Instant

interface GuestQrResolver {
    suspend fun resolveGuest(
        clubId: Long,
        qrPayload: String,
        now: Instant,
    ): GuestQrResolveResult
}

sealed interface GuestQrResolveResult {
    data class Success(
        val guestUserId: Long?,
        val eventId: Long,
        val listId: Long,
        val entryId: Long,
    ) : GuestQrResolveResult

    data class Failure(
        val status: HttpStatusCode,
        val errorCode: String,
    ) : GuestQrResolveResult
}

class DefaultGuestQrResolver(
    private val guestListRepository: GuestListDbRepository,
    private val guestListEntryRepository: GuestListEntryDbRepository,
    private val userRepository: UserRepository,
    private val qrSecretProvider: () -> String = {
        System.getenv("QR_SECRET") ?: error("QR_SECRET missing")
    },
    private val oldQrSecretProvider: () -> String? = { System.getenv("QR_OLD_SECRET") },
    private val qrTtl: Duration = Duration.ofHours(12),
) : GuestQrResolver {
    override suspend fun resolveGuest(
        clubId: Long,
        qrPayload: String,
        now: Instant,
    ): GuestQrResolveResult {
        val qr = qrPayload.trim()
        val validationError = quickValidateQr(qr)
        if (validationError != null) {
            return GuestQrResolveResult.Failure(HttpStatusCode.BadRequest, validationError)
        }
        val primarySecret = qrSecretProvider()
        val oldSecret = oldQrSecretProvider()?.takeIf { it.isNotBlank() && it != primarySecret }
        val primaryVerification =
            runCatching { QrGuestListCodec.verifyWithReason(qr, now, qrTtl, primarySecret) }
                .getOrDefault(QrVerificationResult.Invalid)
        val oldVerification =
            if (primaryVerification is QrVerificationResult.Valid) {
                null
            } else {
                oldSecret?.let {
                    runCatching { QrGuestListCodec.verifyWithReason(qr, now, qrTtl, it) }
                        .getOrDefault(QrVerificationResult.Invalid)
                }
            }
        val decoded =
            when {
                primaryVerification is QrVerificationResult.Valid -> primaryVerification.decoded
                oldVerification is QrVerificationResult.Valid -> oldVerification.decoded
                else -> null
            } ?: return GuestQrResolveResult.Failure(HttpStatusCode.BadRequest, ErrorCodes.invalid_or_expired_qr)

        val list =
            guestListRepository.findById(decoded.listId)
                ?: return GuestQrResolveResult.Failure(HttpStatusCode.NotFound, ErrorCodes.list_not_found)
        if (list.clubId != clubId) {
            return GuestQrResolveResult.Failure(HttpStatusCode.Forbidden, ErrorCodes.club_scope_mismatch)
        }
        val entry =
            guestListEntryRepository.findById(decoded.entryId)
                ?: return GuestQrResolveResult.Failure(HttpStatusCode.NotFound, ErrorCodes.entry_not_found)
        if (entry.guestListId != list.id) {
            return GuestQrResolveResult.Failure(HttpStatusCode.BadRequest, ErrorCodes.entry_list_mismatch)
        }
        val guestUserId =
            entry.telegramUserId
                ?.let { userRepository.getByTelegramId(it)?.id }
        return GuestQrResolveResult.Success(
            guestUserId = guestUserId,
            eventId = list.eventId,
            listId = list.id,
            entryId = entry.id,
        )
    }
}
