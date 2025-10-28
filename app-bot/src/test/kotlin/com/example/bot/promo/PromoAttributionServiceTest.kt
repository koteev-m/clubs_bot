package com.example.bot.promo

import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

class PromoAttributionServiceTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2024-05-01T12:00:00Z"), ZoneOffset.UTC)
    private val promoterTelegramId = 4_200_000L
    private val promoter =
        User(
            id = 101L,
            telegramId = promoterTelegramId,
            username = "promoter",
        )
    private val promoLink =
        PromoLink(
            id = 55L,
            promoterUserId = promoter.id,
            clubId = 77L,
            utmSource = "telegram",
            utmMedium = "bot",
            utmCampaign = "promo-${promoter.id}",
            utmContent = "77",
            createdAt = Instant.parse("2024-04-01T00:00:00Z"),
        )
    private val promoLinkRepository = StaticPromoLinkRepository(promoLink)
    private val promoAttributionRepository = RecordingPromoAttributionRepository(clock)
    private val userRepository = StaticUserRepository(mapOf(promoterTelegramId to promoter))
    private val userRoleRepository =
        StaticUserRoleRepository(
            roles = mapOf(promoter.id to setOf(Role.PROMOTER)),
            clubIds = mapOf(promoter.id to setOf(77L)),
        )
    private val store = InMemoryPromoAttributionStore(ttl = Duration.ofHours(1), clock = clock)
    private val service =
        PromoAttributionService(
            promoLinkRepository = promoLinkRepository,
            promoAttributionRepository = promoAttributionRepository,
            userRepository = userRepository,
            userRoleRepository = userRoleRepository,
            store = store,
            clock = clock,
        )

    @Test
    fun `attachPending consumes pending attribution exactly once`() =
        runBlocking {
            val guestTelegramId = 9_900_123L
            val bookingId = UUID.randomUUID()
            val token = PromoLinkTokenCodec.encode(PromoLinkToken(promoLink.id, promoLink.clubId))

            val startResult = service.registerStart(guestTelegramId, token)
            assertEquals(PromoStartResult.Stored, startResult)

            service.attachPending(bookingId, guestTelegramId)
            service.attachPending(bookingId, guestTelegramId)

            assertEquals(1, promoAttributionRepository.calls.size)
            val call = promoAttributionRepository.calls.single()
            assertEquals(bookingId, call.bookingId)
            assertEquals(promoLink.id, call.promoLinkId)
            assertEquals(promoLink.promoterUserId, call.promoterUserId)
            assertEquals(promoLink.utmSource, call.utmSource)
            assertEquals(promoLink.utmMedium, call.utmMedium)
            assertEquals(promoLink.utmCampaign, call.utmCampaign)
            assertEquals(promoLink.utmContent, call.utmContent)
        }
}

private class StaticPromoLinkRepository(private val link: PromoLink) : PromoLinkRepository {
    override suspend fun issueLink(
        promoterUserId: Long,
        clubId: Long?,
        utmSource: String,
        utmMedium: String,
        utmCampaign: String,
        utmContent: String?,
    ): PromoLink = throw UnsupportedOperationException("issueLink should not be called in tests")

    override suspend fun get(id: Long): PromoLink? = if (id == link.id) link else null

    override suspend fun listByPromoter(
        promoterUserId: Long,
        clubId: Long?,
    ): List<PromoLink> = emptyList()

    override suspend fun deactivate(id: Long) = throw UnsupportedOperationException("deactivate should not be called")
}

private class StaticUserRepository(private val users: Map<Long, User>) : UserRepository {
    override suspend fun getByTelegramId(id: Long): User? = users[id]
}

private class StaticUserRoleRepository(
    private val roles: Map<Long, Set<Role>>,
    private val clubIds: Map<Long, Set<Long>>,
) : UserRoleRepository {
    override suspend fun listRoles(userId: Long): Set<Role> = roles[userId] ?: emptySet()

    override suspend fun listClubIdsFor(userId: Long): Set<Long> = clubIds[userId] ?: emptySet()
}

private class RecordingPromoAttributionRepository(private val clock: Clock) : PromoAttributionRepository {
    data class Call(
        val bookingId: UUID,
        val promoLinkId: Long,
        val promoterUserId: Long,
        val utmSource: String,
        val utmMedium: String,
        val utmCampaign: String,
        val utmContent: String?,
    )

    val calls = mutableListOf<Call>()

    override suspend fun attachUnique(
        bookingId: UUID,
        promoLinkId: Long,
        promoterUserId: Long,
        utmSource: String,
        utmMedium: String,
        utmCampaign: String,
        utmContent: String?,
    ): PromoAttributionResult<PromoAttribution> {
        val call = Call(bookingId, promoLinkId, promoterUserId, utmSource, utmMedium, utmCampaign, utmContent)
        calls += call
        return PromoAttributionResult.Success(
            PromoAttribution(
                id = calls.size.toLong(),
                bookingId = bookingId,
                promoLinkId = promoLinkId,
                promoterUserId = promoterUserId,
                utmSource = utmSource,
                utmMedium = utmMedium,
                utmCampaign = utmCampaign,
                utmContent = utmContent,
                createdAt = Instant.now(clock),
            ),
        )
    }

    override suspend fun findByBooking(bookingId: UUID): PromoAttribution? = null
}
