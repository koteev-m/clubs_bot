package com.example.bot.telegram

import com.example.bot.club.GuestListRepository
import com.example.bot.guestlists.StartParamGuestListCodec
import java.time.Clock
import java.time.Duration
import java.time.Instant

class StartInviteArriveHandler(
    private val repository: GuestListRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofHours(12),
    private val secretProvider: () -> String = { System.getenv("QR_SECRET") ?: error("QR_SECRET missing") },
) {
    sealed interface Result {
        data class Arrived(
            val clubId: Long,
            val listId: Long,
            val entryId: Long,
        ) : Result

        data object Invalid : Result

        data object ExpiredOrSignatureError : Result

        data object NotFoundList : Result

        data object NotFoundEntry : Result

        data object ScopeMismatch : Result // если понадобится проверка границ клуба
    }

    suspend fun handleStartPayload(startPayload: String): Result {
        val payload = startPayload.trim()
        if (payload.isEmpty() || !payload.startsWith("G_")) return Result.Invalid

        val secret = secretProvider()
        val now = Instant.now(clock)
        val decoded =
            StartParamGuestListCodec.verify(
                payload,
                now,
                ttl,
                secret,
            ) ?: return Result.ExpiredOrSignatureError

        val list = repository.getList(decoded.listId) ?: return Result.NotFoundList
        val entry = repository.findEntry(decoded.entryId) ?: return Result.NotFoundEntry
        if (entry.listId != list.id) return Result.NotFoundEntry

        val ok = repository.markArrived(entry.id, now)
        return if (ok) Result.Arrived(list.clubId, list.id, entry.id) else Result.NotFoundEntry
    }
}
