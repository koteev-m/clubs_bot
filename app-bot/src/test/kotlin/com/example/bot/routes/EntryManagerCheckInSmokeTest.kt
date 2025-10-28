@file:Suppress("Filename")

package com.example.bot.routes

import com.example.bot.club.GuestList
import com.example.bot.club.GuestListEntry
import com.example.bot.club.GuestListEntryPage
import com.example.bot.club.GuestListEntrySearch
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListStatus
import com.example.bot.club.ParsedGuest
import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.guestlists.GuestListRepository
import com.example.bot.guestlists.QrGuestListCodec
import com.example.bot.metrics.AppMetricsBinder
import com.example.bot.plugins.installMetrics
import com.example.bot.plugins.meterRegistry
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.testing.applicationDev
import com.example.bot.testing.withInitData
import com.example.bot.webapp.InitDataPrincipalKey
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import com.example.bot.webapp.WebAppInitDataVerifier
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.testing.testApplication
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

// ====== уникальные константы для этого файла, чтобы не конфликтовать с CheckinRoutesTest.kt ======
private const val EM_TELEGRAM_USER_ID = 123456789L
private const val EM_INTERNAL_USER_ID = 5000L
private const val EM_CLUB_ID = 1L
private const val EM_LIST_ID = 100L
private const val EM_ENTRY_ID = 200L
private const val EM_QR_SECRET = "qr_test_secret"

private val EM_FIXED_NOW: Instant = Instant.parse("2024-06-01T10:15:30Z")
private val EM_FIXED_CLOCK: Clock = Clock.fixed(EM_FIXED_NOW, ZoneOffset.UTC)
private val EM_QR_TTL: Duration = Duration.ofHours(12)

// Класс под имя файла
class EntryManagerCheckInSmokeTest {
    @Test
    fun `happy path ARRIVED`() =
        testApplication {
            val guestListRepository = EMGuestListRepository()
            val module = emBaseModule(guestListRepository = guestListRepository)
            applicationDev { emConfigureApp(module) }

            val issued = EM_FIXED_NOW.minusSeconds(60)
            val qrToken = QrGuestListCodec.encode(EM_LIST_ID, EM_ENTRY_ID, issued, EM_QR_SECRET)
            val initData = emCreateInitData()

            val path = "/api/clubs/$EM_CLUB_ID/checkin/scan"
            meterRegistry().clear()
            AppMetricsBinder.bindAll(meterRegistry())

            val beforeMetrics = emCurrentPrometheusSnapshot()
            val totalBefore = beforeMetrics.metricValue("ui_checkin_scan_total")
            val durationCountBefore = beforeMetrics.metricValue("ui_checkin_scan_duration_ms_seconds_count")

            val first =
                client.post(path) {
                    contentType(ContentType.Application.Json)
                    withInitData(initData)
                    setBody("""{"qr":"$qrToken"}""")
                }
            val firstBody = first.bodyAsText()
            println("DBG happy path: status1=${first.status} body1=$firstBody")

            val second =
                client.post(path) {
                    contentType(ContentType.Application.Json)
                    withInitData(initData)
                    setBody("""{"qr":"$qrToken"}""")
                }
            println("DBG happy path: status2=${second.status}")

            val afterMetrics = emCurrentPrometheusSnapshot()
            val totalAfter = afterMetrics.metricValue("ui_checkin_scan_total")
            val durationCountAfter = afterMetrics.metricValue("ui_checkin_scan_duration_ms_seconds_count")

            assertAll(
                { assertEquals(HttpStatusCode.OK, first.status) },
                { assertTrue(firstBody.contains("\"ARRIVED\"")) },
                { assertEquals(HttpStatusCode.OK, second.status) },
                { assertTrue(totalAfter >= totalBefore + 1.0) },
                { assertTrue(durationCountAfter >= durationCountBefore + 1.0) },
            )
        }

    @Test
    fun `malformed or expired qr returns 400`() =
        testApplication {
            val module = emBaseModule()
            applicationDev { emConfigureApp(module) }

            val initData = emCreateInitData()
            val path = "/api/clubs/$EM_CLUB_ID/checkin/scan"

            meterRegistry().clear()
            AppMetricsBinder.bindAll(meterRegistry())

            val before = emCurrentPrometheusSnapshot()
            val errorBefore = before.metricValue("ui_checkin_scan_error_total")

            val malformed =
                client.post(path) {
                    contentType(ContentType.Application.Json)
                    withInitData(initData)
                    setBody("""{"qr":"GL:malformed"}""")
                }
            println("DBG malformed: ${malformed.status} ${malformed.bodyAsText()}")

            val expiredIssued = EM_FIXED_NOW.minus(EM_QR_TTL).minusSeconds(1)
            val expiredQr = QrGuestListCodec.encode(EM_LIST_ID, EM_ENTRY_ID, expiredIssued, EM_QR_SECRET)

            val expired =
                client.post(path) {
                    contentType(ContentType.Application.Json)
                    withInitData(initData)
                    setBody("""{"qr":"$expiredQr"}""")
                }
            println("DBG expired:   ${expired.status} ${expired.bodyAsText()}")

            val after = emCurrentPrometheusSnapshot()
            val errorAfter = after.metricValue("ui_checkin_scan_error_total")

            assertAll(
                { assertEquals(HttpStatusCode.BadRequest, malformed.status) },
                { assertEquals(HttpStatusCode.BadRequest, expired.status) },
                { assertTrue(errorAfter >= errorBefore + 2.0) },
            )
        }

    @Test
    fun `list not found returns 404`() =
        testApplication {
            val guestListRepository = EMGuestListRepository()
            guestListRepository.removeList(EM_LIST_ID)
            val module = emBaseModule(guestListRepository = guestListRepository)
            applicationDev { emConfigureApp(module) }

            val issued = EM_FIXED_NOW.minusSeconds(30)
            val qrToken = QrGuestListCodec.encode(EM_LIST_ID, EM_ENTRY_ID, issued, EM_QR_SECRET)
            val initData = emCreateInitData()

            val response =
                client.post("/api/clubs/$EM_CLUB_ID/checkin/scan") {
                    contentType(ContentType.Application.Json)
                    withInitData(initData)
                    setBody("""{"qr":"$qrToken"}""")
                }
            println("DBG list-not-found: ${response.status} ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `entry not found returns 404`() =
        testApplication {
            val guestListRepository = EMGuestListRepository()
            guestListRepository.removeEntry(EM_ENTRY_ID)
            val module = emBaseModule(guestListRepository = guestListRepository)
            applicationDev { emConfigureApp(module) }

            val issued = EM_FIXED_NOW.minusSeconds(30)
            val qrToken = QrGuestListCodec.encode(EM_LIST_ID, EM_ENTRY_ID, issued, EM_QR_SECRET)
            val initData = emCreateInitData()

            val response =
                client.post("/api/clubs/$EM_CLUB_ID/checkin/scan") {
                    contentType(ContentType.Application.Json)
                    withInitData(initData)
                    setBody("""{"qr":"$qrToken"}""")
                }
            println("DBG entry-not-found: ${response.status} ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `entry list mismatch returns 400`() =
        testApplication {
            val guestListRepository = EMGuestListRepository()
            guestListRepository.updateEntry(guestListRepository.currentEntry().copy(listId = EM_LIST_ID + 1))
            val module = emBaseModule(guestListRepository = guestListRepository)
            applicationDev { emConfigureApp(module) }

            val issued = EM_FIXED_NOW.minusSeconds(30)
            val qrToken = QrGuestListCodec.encode(EM_LIST_ID, EM_ENTRY_ID, issued, EM_QR_SECRET)
            val initData = emCreateInitData()

            val response =
                client.post("/api/clubs/$EM_CLUB_ID/checkin/scan") {
                    contentType(ContentType.Application.Json)
                    withInitData(initData)
                    setBody("""{"qr":"$qrToken"}""")
                }
            println("DBG list-mismatch: ${response.status} ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `scope mismatch returns 403`() =
        testApplication {
            val module = emBaseModule(userRoleRepository = EMUserRoleRepository(clubIds = setOf(EM_CLUB_ID + 1)))
            applicationDev { emConfigureApp(module) }

            val issued = EM_FIXED_NOW.minusSeconds(30)
            val qrToken = QrGuestListCodec.encode(EM_LIST_ID, EM_ENTRY_ID, issued, EM_QR_SECRET)
            val initData = emCreateInitData()

            val response =
                client.post("/api/clubs/$EM_CLUB_ID/checkin/scan") {
                    contentType(ContentType.Application.Json)
                    withInitData(initData)
                    setBody("""{"qr":"$qrToken"}""")
                }
            println("DBG scope-mismatch: ${response.status} ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `missing or invalid init data returns 401`() =
        testApplication {
            val module = emBaseModule()
            applicationDev { emConfigureApp(module) }

            val issued = EM_FIXED_NOW.minusSeconds(30)
            val qrToken = QrGuestListCodec.encode(EM_LIST_ID, EM_ENTRY_ID, issued, EM_QR_SECRET)
            val validInitData = emCreateInitData()
            val invalidInitData = emTamperLastCharacter(validInitData)

            val missingHeader =
                client.post("/api/clubs/$EM_CLUB_ID/checkin/scan") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"qr":"$qrToken"}""")
                }

            val invalidHeader =
                client.post("/api/clubs/$EM_CLUB_ID/checkin/scan") {
                    contentType(ContentType.Application.Json)
                    withInitData(invalidInitData)
                    setBody("""{"qr":"$qrToken"}""")
                }

            println("DBG missing=${missingHeader.status} ${missingHeader.bodyAsText()}")
            println("DBG invalid=${invalidHeader.status} ${invalidHeader.bodyAsText()}")

            assertAll(
                { assertEquals(HttpStatusCode.Unauthorized, missingHeader.status) },
                { assertEquals(HttpStatusCode.Unauthorized, invalidHeader.status) },
            )
        }
}

// ===================== Конфиг приложения для тестов =====================

private fun Application.emConfigureApp(module: Module) {
    meterRegistry().clear()
    installMetrics()
    AppMetricsBinder.bindAll(meterRegistry())

    install(ContentNegotiation) { json() }
    install(Koin) { modules(module) }

    install(RbacPlugin) {
        userRepository = get()
        userRoleRepository = get()
        auditLogRepository = get()
        principalExtractor = { call ->
            val initAttr =
                if (call.attributes.contains(InitDataPrincipalKey)) {
                    call.attributes[InitDataPrincipalKey]
                } else {
                    null
                }

            if (initAttr != null) {
                TelegramPrincipal(initAttr.userId, initAttr.username)
            } else {
                val header =
                    sequenceOf(
                        "X-Telegram-Init-Data",
                        "X-Init-Data",
                        "initData",
                        "Init-Data",
                        "x-telegram-init-data",
                    )
                        .mapNotNull { key -> call.request.header(key) }
                        .firstOrNull()

                val verified =
                    header?.let { value -> WebAppInitDataVerifier.verify(value, TEST_BOT_TOKEN) }

                verified?.let { TelegramPrincipal(it.userId, it.username) }
            }
        }
    }

    checkinRoutes(
        repository = get(),
        qrSecretProvider = { EM_QR_SECRET },
        clock = EM_FIXED_CLOCK,
        qrTtl = EM_QR_TTL,
        initDataAuth = { botTokenProvider = { TEST_BOT_TOKEN } },
    )
}

private fun emBaseModule(
    guestListRepository: GuestListRepository = EMGuestListRepository(),
    userRepository: UserRepository = EMUserRepository(),
    userRoleRepository: UserRoleRepository = EMUserRoleRepository(),
    auditLogRepository: AuditLogRepository = emRelaxedAuditRepository(),
): Module =
    module {
        single { guestListRepository }
        single { userRepository }
        single { userRoleRepository }
        single { auditLogRepository }
    }

// ===================== Стабы =====================

private class EMGuestListRepository : GuestListRepository {
    private var list: GuestList? = defaultList()
    private var entry: GuestListEntry? = defaultEntry()

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
    ): GuestList {
        throw UnsupportedOperationException()
    }

    override suspend fun getList(id: Long): GuestList? = if (list?.id == id) list else null

    override suspend fun findEntry(id: Long): GuestListEntry? = if (entry?.id == id) entry else null

    override suspend fun listListsByClub(
        clubId: Long,
        page: Int,
        size: Int,
    ): List<GuestList> {
        throw UnsupportedOperationException()
    }

    override suspend fun addEntry(
        listId: Long,
        fullName: String,
        phone: String?,
        guestsCount: Int,
        notes: String?,
        status: GuestListEntryStatus,
    ): GuestListEntry {
        throw UnsupportedOperationException()
    }

    override suspend fun setEntryStatus(
        entryId: Long,
        status: GuestListEntryStatus,
        checkedInBy: Long?,
        at: Instant?,
    ): GuestListEntry? {
        throw UnsupportedOperationException()
    }

    override suspend fun listEntries(
        listId: Long,
        page: Int,
        size: Int,
        statusFilter: GuestListEntryStatus?,
    ): List<GuestListEntry> {
        throw UnsupportedOperationException()
    }

    override suspend fun markArrived(
        entryId: Long,
        at: Instant,
    ): Boolean = entry?.id == entryId

    override suspend fun bulkImport(
        listId: Long,
        rows: List<ParsedGuest>,
        dryRun: Boolean,
    ): GuestListEntryPage {
        throw UnsupportedOperationException()
    }

    override suspend fun searchEntries(
        filter: GuestListEntrySearch,
        page: Int,
        size: Int,
    ): GuestListEntryPage {
        throw UnsupportedOperationException()
    }

    fun removeList(id: Long) {
        if (list?.id == id) list = null
    }

    fun removeEntry(id: Long) {
        if (entry?.id == id) entry = null
    }

    fun updateEntry(updated: GuestListEntry) {
        entry = updated
    }

    fun currentEntry(): GuestListEntry = requireNotNull(entry) { "Entry not configured" }

    companion object {
        private fun defaultList(): GuestList =
            GuestList(
                id = EM_LIST_ID,
                clubId = EM_CLUB_ID,
                eventId = 10L,
                ownerType = GuestListOwnerType.MANAGER,
                ownerUserId = EM_INTERNAL_USER_ID,
                title = "VIP",
                capacity = 100,
                arrivalWindowStart = null,
                arrivalWindowEnd = null,
                status = GuestListStatus.ACTIVE,
                createdAt = EM_FIXED_NOW,
            )

        private fun defaultEntry(): GuestListEntry =
            GuestListEntry(
                id = EM_ENTRY_ID,
                listId = EM_LIST_ID,
                fullName = "Guest",
                phone = null,
                guestsCount = 1,
                notes = null,
                status = GuestListEntryStatus.PLANNED,
                checkedInAt = null,
                checkedInBy = null,
            )
    }
}

private class EMUserRepository : UserRepository {
    private val user =
        User(
            id = EM_INTERNAL_USER_ID,
            telegramId = EM_TELEGRAM_USER_ID,
            username = "entry_mgr",
        )

    override suspend fun getByTelegramId(id: Long): User? = if (id == EM_TELEGRAM_USER_ID) user else null
}

private class EMUserRoleRepository(
    private val roles: Set<Role> = setOf(Role.ENTRY_MANAGER),
    private val clubIds: Set<Long> = setOf(EM_CLUB_ID),
) : UserRoleRepository {
    override suspend fun listRoles(userId: Long): Set<Role> = roles

    override suspend fun listClubIdsFor(userId: Long): Set<Long> = clubIds
}

private fun emRelaxedAuditRepository(): AuditLogRepository = mockk(relaxed = true)

// ===================== Утилиты =====================

private fun emCreateInitData(
    userId: Long = EM_TELEGRAM_USER_ID,
    username: String = "entry_mgr",
): String {
    val params =
        linkedMapOf(
            "user" to WebAppInitDataTestHelper.encodeUser(id = userId, username = username),
            "auth_date" to Instant.now().epochSecond.toString(),
        )
    return WebAppInitDataTestHelper.createInitData(TEST_BOT_TOKEN, params)
}

private fun emCurrentPrometheusSnapshot(): String {
    val registry = meterRegistry()
    return (registry as? PrometheusMeterRegistry)?.scrape() ?: ""
}

private fun emTamperLastCharacter(value: String): String {
    if (value.isEmpty()) return value
    val replacement = if (value.last() == '0') '1' else '0'
    return value.dropLast(1) + replacement
}

private fun String.metricValue(name: String): Double =
    lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith(name) }
        ?.substringAfter(' ')
        ?.trim()
        ?.toDoubleOrNull()
        ?: 0.0
