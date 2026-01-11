@file:Suppress("Filename")

package com.example.bot.routes

import com.example.bot.club.GuestList
import com.example.bot.club.GuestListEntry
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListEntryPage
import com.example.bot.club.GuestListEntrySearch
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListRepository
import com.example.bot.club.GuestListStatus
import com.example.bot.club.ParsedGuest
import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.guestlists.QrGuestListCodec
import com.example.bot.metrics.UiCheckinMetrics
import com.example.bot.metrics.QrRotationConfig
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.security.auth.InitDataValidator
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.testing.applicationDev
import com.example.bot.testing.withInitData
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private const val ROTATION_TELEGRAM_USER_ID = 987654321L
private const val ROTATION_INTERNAL_USER_ID = 9000L
private const val ROTATION_CLUB_ID = 42L
private const val ROTATION_LIST_ID = 4242L
private const val ROTATION_ENTRY_ID = 5151L
private const val PRIMARY_QR_SECRET = "primary_secret"
private const val OLD_QR_SECRET = "old_secret"

private val ROTATION_FIXED_NOW: Instant = Instant.parse("2024-06-02T12:00:00Z")
private val ROTATION_FIXED_CLOCK: Clock = Clock.fixed(ROTATION_FIXED_NOW, ZoneOffset.UTC)
private val ROTATION_QR_TTL: Duration = Duration.ofHours(12)

class QrSecretRotationTest {
    @Test
    fun `scan accepts both primary and old secret`() = testApplication {
        val module = rotationModule()
        val meterRegistry = SimpleMeterRegistry()
        applicationDev { configureRotationApp(module, meterRegistry = meterRegistry) }

        val issued = ROTATION_FIXED_NOW.minusSeconds(30)
        val primaryQr = QrGuestListCodec.encode(ROTATION_LIST_ID, ROTATION_ENTRY_ID, issued, PRIMARY_QR_SECRET)
        val oldSecretQr = QrGuestListCodec.encode(ROTATION_LIST_ID, ROTATION_ENTRY_ID, issued, OLD_QR_SECRET)
        val initData = rotationInitData()

        val path = "/api/clubs/$ROTATION_CLUB_ID/checkin/scan"
        val oldSecretMetricBefore = meterRegistry.oldSecretMetricCount()

        val primaryResponse =
            client.post(path) {
                contentType(ContentType.Application.Json)
                withInitData(initData)
                setBody("""{"qr":"$primaryQr"}""")
            }
        val oldSecretResponse =
            client.post(path) {
                contentType(ContentType.Application.Json)
                withInitData(initData)
                setBody("""{"qr":"$oldSecretQr"}""")
            }

        val oldSecretMetricAfter = meterRegistry.oldSecretMetricCount()

        assertEquals(HttpStatusCode.OK, primaryResponse.status)
        assertEquals(HttpStatusCode.OK, oldSecretResponse.status)
        assertTrue(oldSecretMetricAfter >= oldSecretMetricBefore + 1.0)
    }
    @Test
    fun `old secret is rejected when fallback disabled`() = testApplication {
        val module = rotationModule()
        val meterRegistry = SimpleMeterRegistry()
        applicationDev {
            configureRotationApp(module, oldSecretProvider = { null }, meterRegistry = meterRegistry)
        }

        val issued = ROTATION_FIXED_NOW.minusSeconds(30)
        val oldSecretQr = QrGuestListCodec.encode(ROTATION_LIST_ID, ROTATION_ENTRY_ID, issued, OLD_QR_SECRET)
        val initData = rotationInitData()

        val path = "/api/clubs/$ROTATION_CLUB_ID/checkin/scan"
        val response =
            client.post(path) {
                contentType(ContentType.Application.Json)
                withInitData(initData)
                setBody("""{"qr":"$oldSecretQr"}""")
            }

        val oldSecretMetricAfter = meterRegistry.oldSecretMetricCount()

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0.0, oldSecretMetricAfter)
    }

    @Test
    fun `old secret equal to primary does not trigger fallback metric`() = testApplication {
        val module = rotationModule()
        val meterRegistry = SimpleMeterRegistry()
        applicationDev {
            configureRotationApp(
                module = module,
                oldSecretProvider = { PRIMARY_QR_SECRET },
                meterRegistry = meterRegistry,
            )
        }

        val issued = ROTATION_FIXED_NOW.minusSeconds(30)
        val qr = QrGuestListCodec.encode(ROTATION_LIST_ID, ROTATION_ENTRY_ID, issued, PRIMARY_QR_SECRET)
        val initData = rotationInitData()

        val path = "/api/clubs/$ROTATION_CLUB_ID/checkin/scan"
        val metricBefore = meterRegistry.oldSecretMetricCount()

        val response =
            client.post(path) {
                contentType(ContentType.Application.Json)
                withInitData(initData)
                setBody("""{"qr":"$qr"}""")
            }

        val metricAfter = meterRegistry.oldSecretMetricCount()

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(metricBefore, metricAfter)
    }
}

private fun Application.configureRotationApp(
    module: Module,
    oldSecretProvider: () -> String? = { OLD_QR_SECRET },
    meterRegistry: SimpleMeterRegistry,
) {
    UiCheckinMetrics.bind(meterRegistry)

    install(ContentNegotiation) { json() }
    install(Koin) { modules(module) }

    install(RbacPlugin) {
        userRepository = get()
        userRoleRepository = get()
        auditLogRepository = get()
        principalExtractor = { call ->
            val attr =
                if (call.attributes.contains(MiniAppUserKey)) {
                    call.attributes[MiniAppUserKey]
                } else {
                    null
                }
            if (attr != null) {
                TelegramPrincipal(attr.id, attr.username)
            } else {
                val header = sequenceOf(
                    "X-Telegram-Init-Data",
                    "X-Telegram-InitData",
                    "initData",
                ).mapNotNull { key -> call.request.header(key) }
                    .firstOrNull()
                header?.let { InitDataValidator.validate(it, TEST_BOT_TOKEN) }
                    ?.let { TelegramPrincipal(it.id, it.username) }
            }
        }
    }

    val rotationConfig =
        QrRotationConfig(
            oldSecret = oldSecretProvider(),
            rotationDeadlineEpochSeconds = null,
        )
    checkinRoutes(
        repository = get(),
        qrSecretProvider = { PRIMARY_QR_SECRET },
        rotationConfig = rotationConfig,
        oldQrSecretProvider = oldSecretProvider,
        clock = ROTATION_FIXED_CLOCK,
        qrTtl = ROTATION_QR_TTL,
        botTokenProvider = { TEST_BOT_TOKEN },
    )
}

private fun rotationModule(): Module =
    module {
        single<GuestListRepository> { RotationGuestListRepository() }
        single<UserRepository> { RotationUserRepository() }
        single<UserRoleRepository> { RotationUserRoleRepository() }
        single<AuditLogRepository> { mockk(relaxed = true) }
    }

private class RotationGuestListRepository : GuestListRepository {
    private val list =
        GuestList(
            id = ROTATION_LIST_ID,
            clubId = ROTATION_CLUB_ID,
            eventId = 11L,
            ownerType = GuestListOwnerType.MANAGER,
            ownerUserId = ROTATION_INTERNAL_USER_ID,
            title = "Rotation",
            capacity = 10,
            arrivalWindowStart = null,
            arrivalWindowEnd = null,
            status = GuestListStatus.ACTIVE,
            createdAt = ROTATION_FIXED_NOW,
        )

    private val entry =
        GuestListEntry(
            id = ROTATION_ENTRY_ID,
            listId = ROTATION_LIST_ID,
            fullName = "Guest",
            phone = null,
            guestsCount = 1,
            notes = null,
            status = GuestListEntryStatus.PLANNED,
            checkedInAt = null,
            checkedInBy = null,
        )

    override suspend fun getList(id: Long): GuestList? = if (id == list.id) list else null

    override suspend fun findEntry(id: Long): GuestListEntry? = if (id == entry.id) entry else null

    override suspend fun markArrived(entryId: Long, at: Instant): Boolean = entryId == entry.id

    // Unused operations
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

    override suspend fun listListsByClub(clubId: Long, page: Int, size: Int): List<GuestList> = throw UnsupportedOperationException()

    override suspend fun addEntry(
        listId: Long,
        fullName: String,
        phone: String?,
        guestsCount: Int,
        notes: String?,
        status: GuestListEntryStatus,
    ): GuestListEntry = throw UnsupportedOperationException()

    override suspend fun setEntryStatus(entryId: Long, status: GuestListEntryStatus, checkedInBy: Long?, at: Instant?): GuestListEntry? =
        throw UnsupportedOperationException()

    override suspend fun listEntries(
        listId: Long,
        page: Int,
        size: Int,
        statusFilter: GuestListEntryStatus?,
    ): List<GuestListEntry> = throw UnsupportedOperationException()

    override suspend fun bulkImport(listId: Long, rows: List<ParsedGuest>, dryRun: Boolean): GuestListEntryPage =
        throw UnsupportedOperationException()

    override suspend fun searchEntries(
        filter: GuestListEntrySearch,
        page: Int,
        size: Int,
    ): GuestListEntryPage = throw UnsupportedOperationException()
}

private class RotationUserRepository : UserRepository {
    private val user =
        User(
            id = ROTATION_INTERNAL_USER_ID,
            telegramId = ROTATION_TELEGRAM_USER_ID,
            username = "entry_rotation",
        )

    override suspend fun getByTelegramId(id: Long): User? = if (id == ROTATION_TELEGRAM_USER_ID) user else null

    override suspend fun getById(id: Long): User? = if (id == ROTATION_INTERNAL_USER_ID) user else null
}

private class RotationUserRoleRepository : UserRoleRepository {
    override suspend fun listRoles(userId: Long): Set<Role> = setOf(Role.ENTRY_MANAGER)

    override suspend fun listClubIdsFor(userId: Long): Set<Long> = setOf(ROTATION_CLUB_ID)
}

private fun rotationInitData(): String {
    val params =
        linkedMapOf(
            "user" to WebAppInitDataTestHelper.encodeUser(id = ROTATION_TELEGRAM_USER_ID, username = "entry_rotation"),
            "auth_date" to Instant.now().epochSecond.toString(),
        )
    return WebAppInitDataTestHelper.createInitData(TEST_BOT_TOKEN, params)
}

private fun SimpleMeterRegistry.oldSecretMetricCount(): Double =
    find("ui_checkin_old_secret_fallback_total").counter()?.count() ?: 0.0
