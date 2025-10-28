package com.example.bot.promo

import com.example.bot.data.security.Role
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TOKEN_SEPARATOR = ":"
private const val MAX_TOKEN_LENGTH = 64
private const val UTM_SOURCE = "telegram"
private const val UTM_MEDIUM = "bot"
private const val UTM_CAMPAIGN_PREFIX = "promo"

/**
 * Base64url encoded token that references a promo link and optional club.
 */
data class PromoLinkToken(
    val promoLinkId: Long,
    val clubId: Long?,
)

/**
 * Encodes/decodes promo link deep link tokens using Base64 URL-safe alphabet.
 */
object PromoLinkTokenCodec {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(token: PromoLinkToken): String {
        val payload =
            buildString {
                append(token.promoLinkId)
                token.clubId?.let {
                    append(TOKEN_SEPARATOR)
                    append(it)
                }
            }
        val encoded = encoder.encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        require(encoded.length <= MAX_TOKEN_LENGTH) { "promo token exceeds $MAX_TOKEN_LENGTH characters" }
        return encoded
    }

    fun decode(value: String): PromoLinkToken? {
        if (value.isBlank() || value.length > MAX_TOKEN_LENGTH) return null
        val decoded =
            try {
                decoder.decode(value)
            } catch (_: IllegalArgumentException) {
                return null
            }
        val text = decoded.toString(StandardCharsets.UTF_8)
        val parts = text.split(TOKEN_SEPARATOR)
        if (parts.isEmpty() || parts.size > 2) return null
        val promoId = parts[0].toLongOrNull() ?: return null
        val clubId =
            if (parts.size == 2) {
                if (parts[1].isEmpty()) return null
                parts[1].toLongOrNull() ?: return null
            } else {
                null
            }
        return PromoLinkToken(promoId, clubId)
    }
}

/** Pending attribution captured from a deep link. */
data class PendingPromoAttribution(
    val telegramUserId: Long,
    val promoLinkId: Long,
    val promoterUserId: Long,
    val utmSource: String,
    val utmMedium: String,
    val utmCampaign: String,
    val utmContent: String?,
)

interface PromoAttributionStore {
    fun put(entry: PendingPromoAttribution)

    fun popFresh(
        telegramUserId: Long,
        now: Instant = Instant.now(),
    ): PendingPromoAttribution?
}

class InMemoryPromoAttributionStore(
    private val ttl: Duration = Duration.ofHours(24),
    private val clock: Clock = Clock.systemUTC(),
) : PromoAttributionStore {
    private data class Entry(val value: PendingPromoAttribution, val storedAt: Instant)

    private val entries = ConcurrentHashMap<Long, Entry>()

    override fun put(entry: PendingPromoAttribution) {
        cleanup()
        entries[entry.telegramUserId] = Entry(entry, clock.instant())
    }

    override fun popFresh(
        telegramUserId: Long,
        now: Instant,
    ): PendingPromoAttribution? {
        cleanup()
        val entry = entries.remove(telegramUserId) ?: return null
        return if (now.isAfter(entry.storedAt.plus(ttl))) null else entry.value
    }

    private fun cleanup() {
        val now = clock.instant()
        entries.entries.removeIf { (_, entry) -> now.isAfter(entry.storedAt.plus(ttl)) }
    }
}

sealed interface PromoLinkIssueResult {
    data class Success(val token: String, val promoLink: PromoLink) : PromoLinkIssueResult

    data object NotAuthorized : PromoLinkIssueResult
}

sealed interface PromoStartResult {
    data object Stored : PromoStartResult

    data object Invalid : PromoStartResult
}

interface PromoAttributionCoordinator {
    suspend fun attachPending(
        bookingId: UUID,
        telegramUserId: Long?,
    )

    object Noop : PromoAttributionCoordinator {
        override suspend fun attachPending(
            bookingId: UUID,
            telegramUserId: Long?,
        ) {
        }
    }
}

class PromoAttributionService(
    private val promoLinkRepository: PromoLinkRepository,
    private val promoAttributionRepository: PromoAttributionRepository,
    private val store: PromoAttributionStore,
    private val userRepository: UserRepository,
    private val userRoleRepository: UserRoleRepository,
    private val clock: Clock = Clock.systemUTC(),
) : PromoAttributionCoordinator {
    suspend fun issuePromoLink(telegramUserId: Long): PromoLinkIssueResult {
        val result =
            userRepository.getByTelegramId(telegramUserId)?.let { user ->
                val roles = userRoleRepository.listRoles(user.id)
                if (Role.PROMOTER !in roles) {
                    PromoLinkIssueResult.NotAuthorized
                } else {
                    val clubId = userRoleRepository.listClubIdsFor(user.id).firstOrNull()
                    val promoLink =
                        promoLinkRepository.issueLink(
                            promoterUserId = user.id,
                            clubId = clubId,
                            utmSource = UTM_SOURCE,
                            utmMedium = UTM_MEDIUM,
                            utmCampaign = "$UTM_CAMPAIGN_PREFIX-${user.id}",
                            utmContent = clubId?.toString(),
                        )
                    val token = PromoLinkTokenCodec.encode(PromoLinkToken(promoLink.id, promoLink.clubId))
                    PromoLinkIssueResult.Success(token, promoLink)
                }
            } ?: PromoLinkIssueResult.NotAuthorized
        return result
    }

    suspend fun registerStart(
        telegramUserId: Long,
        token: String,
    ): PromoStartResult {
        val result =
            PromoLinkTokenCodec.decode(token)?.let { decoded ->
                val promoLink = promoLinkRepository.get(decoded.promoLinkId)
                when {
                    promoLink == null -> PromoStartResult.Invalid
                    decoded.clubId != null && promoLink.clubId != decoded.clubId -> PromoStartResult.Invalid
                    else -> {
                        val entry =
                            PendingPromoAttribution(
                                telegramUserId = telegramUserId,
                                promoLinkId = promoLink.id,
                                promoterUserId = promoLink.promoterUserId,
                                utmSource = promoLink.utmSource,
                                utmMedium = promoLink.utmMedium,
                                utmCampaign = promoLink.utmCampaign,
                                utmContent = promoLink.utmContent,
                            )
                        store.put(entry)
                        PromoStartResult.Stored
                    }
                }
            } ?: PromoStartResult.Invalid
        return result
    }

    suspend fun attachDeepLink(
        bookingId: UUID,
        deepLink: String,
    ): Boolean {
        val decoded = extractTokenFromDeepLink(deepLink) ?: return false
        val promoLink = promoLinkRepository.get(decoded.promoLinkId) ?: return false
        if (decoded.clubId != null && promoLink.clubId != decoded.clubId) {
            return false
        }
        val attributionResult =
            promoAttributionRepository.attachUnique(
                bookingId = bookingId,
                promoLinkId = promoLink.id,
                promoterUserId = promoLink.promoterUserId,
                utmSource = promoLink.utmSource,
                utmMedium = promoLink.utmMedium,
                utmCampaign = promoLink.utmCampaign,
                utmContent = promoLink.utmContent,
            )
        return attributionResult is PromoAttributionResult.Success
    }

    override suspend fun attachPending(
        bookingId: UUID,
        telegramUserId: Long?,
    ) {
        if (telegramUserId == null) return
        val entry = store.popFresh(telegramUserId, clock.instant()) ?: return
        when (
            promoAttributionRepository.attachUnique(
                bookingId = bookingId,
                promoLinkId = entry.promoLinkId,
                promoterUserId = entry.promoterUserId,
                utmSource = entry.utmSource,
                utmMedium = entry.utmMedium,
                utmCampaign = entry.utmCampaign,
                utmContent = entry.utmContent,
            )
        ) {
            is PromoAttributionResult.Success -> Unit
            is PromoAttributionResult.Failure -> Unit
        }
    }

    private fun extractTokenFromDeepLink(value: String): PromoLinkToken? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null

        PromoLinkTokenCodec.decode(trimmed)?.let { return it }

        val uri = runCatching { URI(trimmed) }.getOrNull()
        val candidates =
            buildList {
                uri?.let { parsed ->
                    parsed.query?.split('&')?.mapNotNull { param ->
                        val parts = param.split('=', limit = 2)
                        if (parts.size == 2) parts[1] else null
                    }?.let { addAll(it) }
                    parsed.fragment?.takeIf { it.isNotBlank() }?.let(::add)
                    parsed.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let(::add)
                }
                add(trimmed.substringAfterLast('/'))
                add(trimmed.substringAfterLast('='))
            }
                .asSequence()
                .map { candidate -> candidate.substringBefore('&').substringBefore('?').trim() }
                .filter { it.isNotEmpty() }

        for (candidate in candidates) {
            val decoded = PromoLinkTokenCodec.decode(candidate)
            if (decoded != null) {
                return decoded
            }
        }
        return null
    }
}
