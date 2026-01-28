package com.example.bot.routes

import com.example.bot.club.GuestList
import com.example.bot.club.GuestListEntry
import com.example.bot.club.GuestListEntryPage
import com.example.bot.club.GuestListEntrySearch
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListRepository
import com.example.bot.club.GuestListStatus
import com.example.bot.club.ParsedGuest
import com.example.bot.audit.AuditLogRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.promoter.invites.InMemoryPromoterInviteRepository
import com.example.bot.promoter.invites.PromoterInviteQrCodec
import com.example.bot.promoter.invites.PromoterInviteService
import com.example.bot.promoter.invites.PromoterInviteStatus
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.ext.get
import com.example.bot.security.auth.TelegramPrincipal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private const val TEST_BOT_TOKEN = "test-bot-token"
private const val QR_SECRET = "secret"
private const val CLUB_ID = 1L
private const val EVENT_ID = 10L
private const val TELEGRAM_USER_ID = 4242L
private const val INTERNAL_USER_ID = 777L

class PromoterInviteCheckinIntegrationTest {
    private val inviteRepository = InMemoryPromoterInviteRepository()
    private val clock = Clock.fixed(Instant.parse("2024-06-02T18:00:00Z"), ZoneOffset.UTC)
    private val promoterService = PromoterInviteService(inviteRepository, clock)

    @Before
    fun setUp() {
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = TELEGRAM_USER_ID) }
        System.setProperty("TELEGRAM_BOT_TOKEN", TEST_BOT_TOKEN)
        System.setProperty("QR_SECRET", QR_SECRET)
    }

    @After
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `promoter invite qr marks arrived`() = testApplication {
        application {
            configureAppWithCheckin(promoterService, clock)
        }

        val invite = promoterService.issueInvite(
            promoterId = TELEGRAM_USER_ID,
            clubId = CLUB_ID,
            eventId = EVENT_ID,
            guestName = "Guest",
            guestCount = 2,
        )
        val qr = PromoterInviteQrCodec.encode(invite.id, invite.eventId, Instant.parse(invite.issuedAt), QR_SECRET)

        val response = client.post("/api/clubs/$CLUB_ID/checkin/scan?initData=init") {
            headers {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                append("X-Telegram-Init-Data", "init")
                append("X-Telegram-Id", TELEGRAM_USER_ID.toString())
            }
            setBody("""{"qr":"$qr"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ARRIVED", payload["status"]?.jsonPrimitive?.content)
        assertEquals("promoter_invite", payload["type"]?.jsonPrimitive?.content)
        assertEquals(invite.id, payload["inviteId"]?.jsonPrimitive?.longOrNull)
        val updated = inviteRepository.findById(invite.id)
        assertNotNull(updated)
        assertEquals(PromoterInviteStatus.ARRIVED, updated!!.status)
        assertTrue(updated.arrivedAt!!.isAfter(Instant.EPOCH))
    }

    @Test
    fun `unknown promoter invite returns invalid state`() = testApplication {
        application { configureAppWithCheckin(promoterService, clock) }

        val qr = PromoterInviteQrCodec.encode(inviteId = 9999, eventId = EVENT_ID, issuedAt = clock.instant(), secret = QR_SECRET)

        val response = client.post("/api/clubs/$CLUB_ID/checkin/scan?initData=init") {
            headers {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                append("X-Telegram-Init-Data", "init")
                append("X-Telegram-Id", TELEGRAM_USER_ID.toString())
            }
            setBody("""{"qr":"$qr"}""")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("invalid_state", response.errorCode())
    }

    @Test
    fun `revoked promoter invite stays revoked on scan`() = testApplication {
        application { configureAppWithCheckin(promoterService, clock) }

        val invite = promoterService.issueInvite(
            promoterId = TELEGRAM_USER_ID,
            clubId = CLUB_ID,
            eventId = EVENT_ID,
            guestName = "Guest",
            guestCount = 2,
        )
        promoterService.revokeInvite(promoterId = TELEGRAM_USER_ID, inviteId = invite.id)
        val qr = PromoterInviteQrCodec.encode(inviteId = invite.id, eventId = invite.eventId, issuedAt = Instant.parse(invite.issuedAt), secret = QR_SECRET)

        val response = client.post("/api/clubs/$CLUB_ID/checkin/scan?initData=init") {
            headers {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                append("X-Telegram-Init-Data", "init")
                append("X-Telegram-Id", TELEGRAM_USER_ID.toString())
            }
            setBody("""{"qr":"$qr"}""")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("invalid_state", response.errorCode())
        val stored = inviteRepository.findById(invite.id)!!
        assertEquals(PromoterInviteStatus.REVOKED, stored.status)
    }

    @Test
    fun `invalid promoter invite qr signature is rejected`() = testApplication {
        application { configureAppWithCheckin(promoterService, clock) }

        val invite = promoterService.issueInvite(
            promoterId = TELEGRAM_USER_ID,
            clubId = CLUB_ID,
            eventId = EVENT_ID,
            guestName = "Guest",
            guestCount = 2,
        )
        val token = PromoterInviteQrCodec.encode(inviteId = invite.id, eventId = invite.eventId, issuedAt = Instant.parse(invite.issuedAt), secret = QR_SECRET)
        val tampered = token.dropLast(1) + if (token.last() == '0') '1' else '0'

        val response = client.post("/api/clubs/$CLUB_ID/checkin/scan?initData=init") {
            headers {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                append("X-Telegram-Init-Data", "init")
                append("X-Telegram-Id", TELEGRAM_USER_ID.toString())
            }
            setBody("""{"qr":"$tampered"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_or_expired_qr", response.errorCode())
    }
}

private fun Application.configureAppWithCheckin(
    promoterInviteService: PromoterInviteService,
    clock: Clock,
) {
    install(ContentNegotiation) { json() }
    install(org.koin.ktor.plugin.Koin) { modules(testModule()) }
    install(com.example.bot.security.rbac.RbacPlugin) {
        userRepository = get()
        userRoleRepository = get()
        auditLogRepository = get()
        principalExtractor = { call ->
            val attr = call.attributes.takeIf { it.contains(MiniAppUserKey) }?.get(MiniAppUserKey)
            attr?.let { TelegramPrincipal(it.id, it.username) }
                ?: call.request.header("X-Telegram-Id")?.toLongOrNull()
                    ?.let { TelegramPrincipal(it, call.request.header("X-Telegram-Username")) }
        }
    }

    com.example.bot.metrics.UiCheckinMetrics.bind(io.micrometer.core.instrument.simple.SimpleMeterRegistry())

    checkinRoutes(
        repository = get(),
        promoterInviteService = promoterInviteService,
        botTokenProvider = { TEST_BOT_TOKEN },
        qrSecretProvider = { QR_SECRET },
        clock = clock,
        qrTtl = Duration.ofHours(1),
    )
}

private fun testModule(): Module =
    module {
        single<GuestListRepository> { DummyGuestListRepository() }
        single<UserRepository> { DummyUserRepository() }
        single<UserRoleRepository> { DummyUserRoleRepository() }
        single<AuditLogRepository> { mockk(relaxed = true) }
    }

private suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String? {
    val body = bodyAsText()
    val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val nestedError = json["error"]?.let {
        runCatching { it.jsonObject["code"]?.jsonPrimitive?.content ?: it.jsonPrimitive.content }.getOrNull()
    }
    return nestedError ?: json["code"]?.jsonPrimitive?.content
}

private class DummyGuestListRepository : GuestListRepository {
    override suspend fun createList(
        clubId: Long,
        eventId: Long,
        ownerType: GuestListOwnerType,
        ownerUserId: Long,
        title: String,
        capacity: Int,
        arrivalWindowStart: Instant?,
        arrivalWindowEnd: Instant?,
        status: GuestListStatus,
    ): GuestList = throw UnsupportedOperationException()

    override suspend fun getList(id: Long): GuestList? =
        GuestList(
            id = 1,
            clubId = CLUB_ID,
            eventId = EVENT_ID,
            ownerType = GuestListOwnerType.MANAGER,
            ownerUserId = INTERNAL_USER_ID,
            title = "list",
            capacity = 10,
            arrivalWindowStart = null,
            arrivalWindowEnd = null,
            status = GuestListStatus.ACTIVE,
            createdAt = Instant.EPOCH,
        )

    override suspend fun findEntry(id: Long): GuestListEntry? =
        GuestListEntry(
            id = 1,
            listId = 1,
            fullName = "name",
            phone = null,
            guestsCount = 1,
            notes = null,
            status = GuestListEntryStatus.PLANNED,
            checkedInAt = null,
            checkedInBy = null,
        )

    override suspend fun listListsByClub(clubId: Long, page: Int, size: Int): List<GuestList> = emptyList()

    override suspend fun addEntry(
        listId: Long,
        fullName: String,
        phone: String?,
        guestsCount: Int,
        notes: String?,
        status: GuestListEntryStatus,
    ): GuestListEntry = throw UnsupportedOperationException()

    override suspend fun setEntryStatus(
        entryId: Long,
        status: GuestListEntryStatus,
        checkedInBy: Long?,
        at: Instant?,
    ): GuestListEntry? = throw UnsupportedOperationException()

    override suspend fun listEntries(
        listId: Long,
        page: Int,
        size: Int,
        statusFilter: GuestListEntryStatus?,
    ): List<GuestListEntry> = emptyList()

    override suspend fun markArrived(entryId: Long, at: Instant): Boolean = true

    override suspend fun bulkImport(listId: Long, rows: List<ParsedGuest>, dryRun: Boolean): GuestListEntryPage =
        throw UnsupportedOperationException()

    override suspend fun searchEntries(filter: GuestListEntrySearch, page: Int, size: Int): GuestListEntryPage =
        throw UnsupportedOperationException()
}

private class DummyUserRepository : UserRepository {
    override suspend fun getByTelegramId(id: Long): User? =
        if (id == TELEGRAM_USER_ID) User(id = INTERNAL_USER_ID, telegramId = TELEGRAM_USER_ID, username = null) else null

    override suspend fun getById(id: Long): User? =
        if (id == INTERNAL_USER_ID) User(id = INTERNAL_USER_ID, telegramId = TELEGRAM_USER_ID, username = null) else null
}

private class DummyUserRoleRepository : UserRoleRepository {
    override suspend fun listRoles(userId: Long): Set<Role> = setOf(Role.ENTRY_MANAGER)

    override suspend fun listClubIdsFor(userId: Long): Set<Long> = setOf(CLUB_ID)
}
