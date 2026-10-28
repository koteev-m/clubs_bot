package com.example.bot.routes

import com.example.bot.booking.a3.BookingState
import com.example.bot.booking.a3.ConfirmResult
import com.example.bot.booking.a3.HoldResult
import com.example.bot.data.admin.AdminClubsDbRepository
import com.example.bot.data.admin.AdminHallsDbRepository
import com.example.bot.data.club.GuestListRepositoryImpl
import com.example.bot.data.club.WaitlistRepositoryImpl
import com.example.bot.data.clubs.ClubsDbRepository
import com.example.bot.data.clubs.EventsDbRepository
import com.example.bot.data.layout.LayoutDbRepository
import com.example.bot.data.music.MusicItemRepositoryImpl
import com.example.bot.data.music.MusicLikesRepositoryImpl
import com.example.bot.data.music.MusicPlaylistRepositoryImpl
import com.example.bot.data.music.TrackOfNightRepositoryImpl
import com.example.bot.data.privacy.JdbcLegacyClubAccess
import com.example.bot.data.privacy.LegacyClubAccess
import com.example.bot.music.MusicService
import com.example.bot.plugins.DataSourceHolder
import com.example.bot.plugins.configureSecurity
import com.example.bot.plugins.installPrivateClubContainment
import com.example.bot.promoter.invites.InMemoryPromoterInviteRepository
import com.example.bot.promoter.invites.PromoterInviteQrCodec
import com.example.bot.promoter.invites.PromoterInviteService
import com.example.bot.promoter.invites.PromoterInviteStatus
import com.example.bot.promoter.quotas.InMemoryPromoterQuotaRepository
import com.example.bot.promoter.quotas.PromoterQuotaService
import com.example.bot.testing.applicationDev
import com.example.bot.testing.createInitData
import com.example.bot.webapp.TEST_BOT_TOKEN
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateClubContainmentTest {
    @Test
    fun `guest direct descendants and cached GET HEAD ETag are denied before handlers`() =
        withDatabase { source ->
            withApp(source) {
                val paths =
                    listOf(
                        "/api/clubs/8201/layout?eventId=8205",
                        "/api/clubs/08201/layout?eventId=8205",
                        "/api/clubs/8101/layout?eventId=08205",
                        "/api/clubs/8101/layout?eventId=8205",
                        "/api/clubs/8201/halls/8202/plan",
                        "/api/clubs/8101/halls/8202/plan",
                        "/assets/layouts/8201/ppppppppppppppppppppppppppppppppppppppppppp.json",
                        "/api/events?clubId=8201",
                    )
                for (path in paths) {
                    for (method in listOf(HttpMethod.Get, HttpMethod.Head)) {
                        val response =
                            client.request(path) {
                                this.method = method
                                header("X-Telegram-Init-Data", createInitData(userId = 99001))
                                header(HttpHeaders.IfNoneMatch, "*")
                            }
                        assertEquals(HttpStatusCode.NotFound, response.status, "$method $path")
                        assertTrue(response.headers[HttpHeaders.CacheControl].orEmpty().contains("no-store"))
                        assertNull(response.headers[HttpHeaders.ETag])
                        assertFalse(response.bodyAsText().contains("PRIVATE_CANARY"))
                    }
                }
                val clubs = client.get("/api/clubs?city=Synthetic") { identity() }
                assertEquals(HttpStatusCode.OK, clubs.status)
                assertTrue(clubs.bodyAsText().contains("Legacy public"))
                assertFalse(clubs.bodyAsText().contains("PRIVATE_CANARY"))
                val events = client.get("/api/events?city=Synthetic") { identity() }
                assertEquals(HttpStatusCode.OK, events.status)
                assertTrue(events.bodyAsText().contains("Legacy event"))
                assertFalse(events.bodyAsText().contains("PRIVATE_CANARY"))
                assertEquals(HttpStatusCode.OK, client.get("/api/clubs/8101/layout?eventId=8105") { identity() }.status)
                val warm = client.get("/assets/layouts/8301/wwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwww.json")
                assertEquals(HttpStatusCode.OK, warm.status)
                source.sql("INSERT INTO client_clubs(client_id,club_id) VALUES (101,8301)")
                val denied =
                    client.get("/assets/layouts/8301/wwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwww.json") {
                        header(HttpHeaders.IfNoneMatch, warm.headers[HttpHeaders.ETag]!!)
                    }
                assertEquals(HttpStatusCode.NotFound, denied.status)
                assertNull(denied.headers[HttpHeaders.ETag])
            }
        }

    @Test
    fun `GLOBAL cannot publish mutate halls tables or plans but legacy mutation succeeds`() =
        withDatabase { source ->
            val before = source.snapshot()
            withApp(source) {
                val mutations =
                    listOf(
                        HttpMethod.Patch to "/api/admin/clubs/8201",
                        HttpMethod.Delete to "/api/admin/clubs/8201",
                        HttpMethod.Post to "/api/admin/clubs/8201/halls",
                        HttpMethod.Patch to "/api/admin/halls/8202",
                        HttpMethod.Delete to "/api/admin/halls/8202",
                        HttpMethod.Post to "/api/admin/halls/8202/make-active",
                        HttpMethod.Post to "/api/admin/halls/8202/tables",
                        HttpMethod.Patch to "/api/admin/halls/8102/tables/8203",
                        HttpMethod.Delete to "/api/admin/tables/8203?clubId=8101",
                        HttpMethod.Put to "/api/admin/halls/8202/plan",
                    )
                for ((method, path) in mutations) {
                    val response =
                        client.request(path) {
                            this.method = method
                            identity()
                            header(HttpHeaders.ContentType, "application/json")
                            setBody("{\"name\":\"Injected\",\"isActive\":true}")
                        }
                    assertEquals(HttpStatusCode.NotFound, response.status, "$method $path")
                }
                assertEquals(before, source.snapshot())
                val response =
                    client.request("/api/admin/clubs/8101") {
                        method = HttpMethod.Patch
                        identity()
                        header(HttpHeaders.ContentType, "application/json")
                        setBody("{\"name\":\"Legacy renamed\"}")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("Legacy renamed"))
            }
        }

    @Test
    fun `HOLD blocks private club and private event or table with a public path before side effects`() =
        withDatabase { source ->
            withApp(source) {
                for ((club, event, table) in listOf(
                    Triple(8201, 8205, 8203),
                    Triple(8101, 8205, 8103),
                    Triple(8101, 8105, 8203),
                )) {
                    val response =
                        client.request("/api/clubs/$club/bookings/hold") {
                            method = HttpMethod.Post
                            identity()
                            header(HttpHeaders.ContentType, "application/json")
                            header("Idempotency-Key", "synthetic-hold-$club-$event-$table")
                            setBody("{\"tableId\":$table,\"eventId\":$event,\"guestCount\":2}")
                        }
                    assertEquals(HttpStatusCode.NotFound, response.status)
                }
                val legacy =
                    client.request("/api/clubs/8101/bookings/hold") {
                        method = HttpMethod.Post
                        identity()
                        header(HttpHeaders.ContentType, "application/json")
                        header("Idempotency-Key", "synthetic-legacy-hold")
                        setBody("{\"tableId\":8103,\"eventId\":8105,\"guestCount\":2}")
                    }
                assertEquals(HttpStatusCode.OK, legacy.status)
            }
        }

    @Test
    fun `GLOBAL body quotas and waitlist direct IDs cannot access private descendants`() =
        withDatabase { source ->
            withApp(source) {
                for ((club, table) in listOf(8201 to 8203, 8101 to 8203)) {
                    for (method in listOf(HttpMethod.Post, HttpMethod.Put)) {
                        val response =
                            client.request("/api/admin/quotas") {
                                this.method = method
                                identity()
                                header(HttpHeaders.ContentType, "application/json")
                                setBody(
                                    """{"clubId":$club,"promoterId":99101,"tableId":$table,"quota":2,"expiresAt":"2031-01-01T00:00:00Z"}""",
                                )
                            }
                        assertEquals(HttpStatusCode.NotFound, response.status)
                    }
                }
                val legacy =
                    client.request("/api/admin/quotas") {
                        method = HttpMethod.Post
                        identity()
                        header(HttpHeaders.ContentType, "application/json")
                        setBody(
                            """{"clubId":8101,"promoterId":99101,"tableId":8103,"quota":2,"expiresAt":"2031-01-01T00:00:00Z"}""",
                        )
                    }
                assertEquals(HttpStatusCode.OK, legacy.status)
                for ((entry, club, event) in listOf(
                    Triple(8206, 8101, 8105),
                    Triple(8106, 8201, 8205),
                    Triple(8106, 8101, 8205),
                )) {
                    val response =
                        client.request("/api/host/waitlist/$entry/invite") {
                            method = HttpMethod.Post
                            identity()
                            header(HttpHeaders.ContentType, "application/json")
                            setBody("""{"clubId":$club,"eventId":$event}""")
                        }
                    assertEquals(HttpStatusCode.NotFound, response.status)
                }
                val invitation =
                    client.request("/api/host/waitlist/8106/invite") {
                        method = HttpMethod.Post
                        identity()
                        header(HttpHeaders.ContentType, "application/json")
                        setBody("""{"clubId":8101,"eventId":8105}""")
                    }
                assertEquals(HttpStatusCode.OK, invitation.status)
                assertTrue(invitation.bodyAsText().contains("invitation_unavailable"))
            }
        }

    @Test
    fun `cached booking replay and Telegram booking source are hidden after synthetic mapping`() =
        withDatabase { source ->
            val database = Database.connect(source)
            val access = JdbcLegacyClubAccess(source)
            BookingState(
                LayoutDbRepository(database),
                EventsDbRepository(database),
                isLegacyClub = access::isLegacyClub,
            ).use { state ->
                runBlocking {
                    val hold = assertIs<HoldResult.Success>(state.hold(99001, 8301, 8303, 8305, 2, "warm-hold", "same"))
                    assertIs<ConfirmResult.Success>(state.confirm(99001, 8301, hold.booking.id, "warm-confirm", "same"))
                    assertEquals(1, state.findUserBookings(99001).size)
                    source.sql("INSERT INTO client_clubs(client_id,club_id) VALUES (101,8301)")
                    assertIs<HoldResult.Error>(state.hold(99001, 8301, 8303, 8305, 2, "warm-hold", "same"))
                    assertIs<ConfirmResult.Error>(state.confirm(99001, 8301, hold.booking.id, "warm-confirm", "same"))
                    assertTrue(state.findUserBookings(99001).isEmpty())
                    assertNull(state.findBookingById(hold.booking.id))
                    assertTrue(state.findBookingsForEvent(8301, 8305).isEmpty())
                }
            }
        }

    @Test
    fun `cached private promoter invites are hidden and cannot be scanned through a public club`() =
        withDatabase { source ->
            source.sql(
                "INSERT INTO user_roles(user_id,role_code,scope_type,scope_club_id) VALUES (99101,'ENTRY_MANAGER','CLUB',8101)",
            )
            val access = JdbcLegacyClubAccess(source)
            var seeding = true
            val repository = InMemoryPromoterInviteRepository { seeding || access.isLegacyClub(it) }
            val service = PromoterInviteService(repository, Clock.systemUTC())
            val privateInvite = service.issueInvite(99001, 8201, 8205, "PRIVATE_CANARY", 2)
            val legacy = service.issueInvite(99001, 8101, 8105, "Legacy guest", 2)
            seeding = false
            assertEquals(listOf(legacy.id), service.listInvites(99001, null).map { it.id })
            assertFalse(service.exportCsv(99001, 8205).contains("PRIVATE_CANARY"))
            assertIs<PromoterInviteService.RevokeResult.NotFound>(service.revokeInvite(99001, privateInvite.id))
            withApp(source, service) {
                for ((invite, event, expected) in listOf(
                    Triple(privateInvite, 8205L, HttpStatusCode.NotFound),
                    Triple(privateInvite, 8105L, HttpStatusCode.Conflict),
                    Triple(legacy, 8105L, HttpStatusCode.OK),
                )) {
                    val qr =
                        PromoterInviteQrCodec.encode(
                            invite.id,
                            event,
                            Instant.parse(invite.issuedAt),
                            "synthetic-private-qr",
                        )
                    val response =
                        client.request("/api/clubs/8101/checkin/scan") {
                            method = HttpMethod.Post
                            identity()
                            header(HttpHeaders.ContentType, "application/json")
                            setBody("""{"qr":"$qr"}""")
                        }
                    assertEquals(expected, response.status)
                }
            }
            seeding = true
            assertEquals(PromoterInviteStatus.ISSUED, repository.findById(privateInvite.id)?.status)
            assertEquals(PromoterInviteStatus.ARRIVED, repository.findById(legacy.id)?.status)
        }

    @Test
    fun `cached club and event pages replace mapped members even at equal watermark and page size`() =
        withDatabase { source ->
            source.sql("UPDATE clubs SET updated_at=TIMESTAMP WITH TIME ZONE '2040-01-01 00:00:00+00'")
            source.sql("UPDATE clubs SET name='A previously visible' WHERE id=8101")
            source.sql("UPDATE clubs SET name='B replacement' WHERE id=8301")
            withApp(source) {
                val paths = listOf("/api/clubs?city=Synthetic&size=1", "/api/events?city=Synthetic&size=1")
                val tags =
                    paths.map { path ->
                        val first = client.get(path) { identity() }
                        assertEquals(HttpStatusCode.OK, first.status)
                        val tag = requireNotNull(first.headers[HttpHeaders.ETag])
                        val unchanged =
                            client.get(path) {
                                identity()
                                header(HttpHeaders.IfNoneMatch, tag)
                            }
                        assertEquals(HttpStatusCode.NotModified, unchanged.status)
                        tag
                    }
                source.sql("INSERT INTO client_clubs(client_id,club_id) VALUES (101,8101)")
                for ((path, tag) in paths.zip(tags)) {
                    val changed =
                        client.get(path) {
                            identity()
                            header(HttpHeaders.IfNoneMatch, tag)
                        }
                    assertEquals(HttpStatusCode.OK, changed.status)
                    assertNotEquals(tag, changed.headers[HttpHeaders.ETag])
                    assertFalse(changed.bodyAsText().contains("8101"))
                    assertFalse(changed.bodyAsText().contains("8105"))
                    assertTrue(changed.bodyAsText().contains("8301"))
                }
            }
        }

    @Test
    fun `music validators change with visible membership at equal count and watermark`() {
        assertMusicMembership("items")
        assertMusicMembership("sets")
        assertMusicMembership("playlists")
    }

    private fun assertMusicMembership(kind: String) =
        withDatabase { source ->
            for (base in listOf(8100, 8300)) {
                source.sql(
                    "INSERT INTO music_items(id,club_id,title,source_type,item_type,published_at,updated_at) VALUES (${base + 8},${base + 1},'Synthetic $base','LINK','SET',TIMESTAMP WITH TIME ZONE '2040-01-01 00:00:00+00',TIMESTAMP WITH TIME ZONE '2040-01-01 00:00:00+00')",
                )
                source.sql(
                    "INSERT INTO music_playlists(id,club_id,title,updated_at) VALUES (${base + 9},${base + 1},'Synthetic $base',TIMESTAMP WITH TIME ZONE '2040-01-01 00:00:00+00')",
                )
            }
            val db = Database.connect(source)
            val service =
                MusicService(
                    MusicItemRepositoryImpl(db),
                    MusicPlaylistRepositoryImpl(db),
                    MusicLikesRepositoryImpl(db),
                    Clock.fixed(Instant.parse("2040-01-01T00:00:00Z"), ZoneOffset.UTC),
                    TrackOfNightRepositoryImpl(db),
                )
            runBlocking {
                val before = service.collectionIds(kind)
                val oldId = before.second.single()
                val club =
                    when (oldId) {
                        8108L, 8109L -> 8101L
                        8308L, 8309L -> 8301L
                        else -> error("Unexpected fixture member")
                    }
                assertEquals(before, service.collectionIds(kind))
                source.sql("INSERT INTO client_clubs(client_id,club_id) VALUES (101,$club)")
                val after = service.collectionIds(kind)
                assertEquals(before.second.size, after.second.size)
                assertFalse(oldId in after.second)
                assertNotEquals(before.first, after.first)
                assertEquals(after, service.collectionIds(kind))
            }
        }

    @Test
    fun `containment persistence failure is fail closed including asset cache revalidation`() =
        testApplication {
            applicationDev {
                install(ContentNegotiation) { json() }
                installPrivateClubContainment(LegacyClubAccess { error("synthetic database failure") }) { null }
            }
            val response =
                client.get("/assets/layouts/8201/ppppppppppppppppppppppppppppppppppppppppppp.json") {
                    header(HttpHeaders.IfNoneMatch, "*")
                }
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertTrue(response.headers[HttpHeaders.CacheControl].orEmpty().contains("no-store"))
            assertFalse(response.bodyAsText().contains("synthetic database failure"))
        }

    private fun withApp(
        source: JdbcDataSource,
        invites: PromoterInviteService = PromoterInviteService(InMemoryPromoterInviteRepository(), Clock.systemUTC()),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        val database = Database.connect(source)
        val layout = LayoutDbRepository(database)
        val access = JdbcLegacyClubAccess(source)
        val state = BookingState(layout, EventsDbRepository(database), isLegacyClub = access::isLegacyClub)
        applicationDev {
            install(ContentNegotiation) { json() }
            install(AutoHeadResponse)
            DataSourceHolder.dataSource = source
            configureSecurity()
            installPrivateClubContainment(access, state::bookingClubId)
            clubsRoutes(ClubsDbRepository(database), EventsDbRepository(database))
            layoutRoutes(layout, layout)
            hallPlanRoutes(layout)
            adminClubsRoutes(AdminClubsDbRepository(database)) { TEST_BOT_TOKEN }
            adminHallsRoutes(AdminHallsDbRepository(database), AdminClubsDbRepository(database)) { TEST_BOT_TOKEN }
            adminHallPlanRoutes(AdminHallsDbRepository(database), layout) { TEST_BOT_TOKEN }
            adminTablesRoutes(layout, AdminHallsDbRepository(database)) { TEST_BOT_TOKEN }
            bookingA3Routes(state, botTokenProvider = { TEST_BOT_TOKEN })
            promoterQuotasAdminRoutes(PromoterQuotaService(InMemoryPromoterQuotaRepository(), Clock.systemUTC()), {
                TEST_BOT_TOKEN
            })
            hostWaitlistRoutes(WaitlistRepositoryImpl(database)) { TEST_BOT_TOKEN }
            checkinRoutes(GuestListRepositoryImpl(database), invites, { TEST_BOT_TOKEN }, { "synthetic-private-qr" })
        }
        try {
            block()
        } finally {
            state.close()
            DataSourceHolder.dataSource = null
        }
    }

    private fun withDatabase(block: (JdbcDataSource) -> Unit) {
        val source =
            JdbcDataSource().apply {
                setURL(
                    "jdbc:h2:mem:private_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                )
                user = "sa"
                password = ""
            }
        Flyway
            .configure()
            .dataSource(
                source,
            ).locations("classpath:db/migration/common", "classpath:db/migration/h2")
            .load()
            .migrate()
        source.sql(
            "CREATE TABLE waitlist(id BIGINT PRIMARY KEY,club_id BIGINT NOT NULL,event_id BIGINT NOT NULL,user_id BIGINT NOT NULL,party_size INT NOT NULL,created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,called_at TIMESTAMP WITH TIME ZONE,expires_at TIMESTAMP WITH TIME ZONE,status VARCHAR(30) NOT NULL)",
        )
        source.sql("INSERT INTO users(id,telegram_user_id) VALUES (99101,99001)")
        source.sql("INSERT INTO user_roles(user_id,role_code,scope_type) VALUES (99101,'OWNER','GLOBAL')")
        source.sql("INSERT INTO clients(id,display_name,is_active) VALUES (101,'Synthetic client',TRUE)")
        for (base in listOf(8100, 8200, 8300)) {
            val name = if (base == 8200) "PRIVATE_CANARY" else "Legacy public"
            source.sql(
                "INSERT INTO clubs(id,name,timezone,city,is_active) VALUES (${base + 1},'$name','UTC','Synthetic',${base != 8200})",
            )
            source.sql(
                "INSERT INTO events(id,club_id,title,start_at,end_at) VALUES (${base + 5},${base + 1},'${if (base == 8200) "PRIVATE_CANARY" else "Legacy event"}',TIMESTAMP WITH TIME ZONE '2030-01-01 19:00:00+00',TIMESTAMP WITH TIME ZONE '2030-01-02 03:00:00+00')",
            )
            val fingerprint =
                if (base == 8200) {
                    "p".repeat(43)
                } else if (base == 8300) {
                    "w".repeat(43)
                } else {
                    "l".repeat(43)
                }
            source.sql(
                "INSERT INTO halls(id,club_id,name,is_active,geometry_json,geometry_fingerprint) VALUES (${base + 2},${base + 1},'$name',TRUE,'{\"zones\":[{\"id\":\"main\",\"name\":\"$name\"}]}','$fingerprint')",
            )
            source.sql("INSERT INTO hall_zones(hall_id,zone_id,name) VALUES (${base + 2},'main','$name')")
            source.sql(
                "INSERT INTO hall_tables(id,hall_id,table_number,label,capacity,zone_id) VALUES (${base + 3},${base + 2},1,'$name',4,'main')",
            )
            source.sql(
                "INSERT INTO tables(id,club_id,table_number,capacity,min_deposit) VALUES (${base + 4},${base + 1},1,4,0)",
            )
            source.sql(
                "INSERT INTO waitlist(id,club_id,event_id,user_id,party_size,status) VALUES (${base + 6},${base + 1},${base + 5},99101,2,'WAITING')",
            )
            source.connection.use { connection ->
                connection
                    .prepareStatement(
                        "INSERT INTO hall_plans(hall_id,bytes,content_type,sha256,size_bytes) VALUES (?,?,'image/png','synthetic',?)",
                    ).use {
                        val bytes = name.toByteArray()
                        it.setLong(1, (base + 2).toLong())
                        it.setBytes(2, bytes)
                        it.setInt(3, bytes.size)
                        it.executeUpdate()
                    }
            }
        }
        source.sql("INSERT INTO client_clubs(client_id,club_id) VALUES (101,8201)")
        try {
            block(source)
        } finally {
            source.sql("SHUTDOWN")
        }
    }

    private fun JdbcDataSource.sql(sql: String) =
        connection.use { c ->
            c.createStatement().use { it.execute(sql) }
            Unit
        }

    private fun JdbcDataSource.snapshot(): List<String> =
        connection.use { c ->
            listOf("clubs", "halls", "hall_tables", "hall_plans", "client_clubs").flatMap { table ->
                c.createStatement().use { statement ->
                    statement.executeQuery("SELECT * FROM $table ORDER BY 1").use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    (1..rs.metaData.columnCount).joinToString("|") { rs.getString(it).orEmpty() },
                                )
                            }
                        }
                    }
                }
            }
        }

    private fun io.ktor.client.request.HttpRequestBuilder.identity() {
        header("X-Telegram-Init-Data", createInitData(userId = 99001))
    }
}

private suspend fun MusicService.collectionIds(kind: String): Pair<String, List<Long>> =
    when (kind) {
        "items" -> listItems(1).let { it.first to it.second.map { item -> item.id } }
        "sets" -> listSets(limit = 1, userId = null).let { it.first to it.second.map { item -> item.id } }
        "playlists" -> listPlaylists(1).let { it.first to it.second.map { item -> item.id } }
        else -> error("Unknown fixture collection")
    }
