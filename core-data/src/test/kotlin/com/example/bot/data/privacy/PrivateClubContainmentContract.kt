package com.example.bot.data.privacy

import com.example.bot.admin.AdminClubUpdate
import com.example.bot.admin.AdminHallUpdate
import com.example.bot.data.admin.AdminClubsDbRepository
import com.example.bot.data.admin.AdminHallsDbRepository
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.club.GuestListDbRepository
import com.example.bot.data.club.GuestListEntryDbRepository
import com.example.bot.data.club.InvitationDbRepository
import com.example.bot.data.clubs.ClubsDbRepository
import com.example.bot.data.clubs.EventsDbRepository
import com.example.bot.data.layout.LayoutDbRepository
import com.example.bot.data.music.MusicItemRepositoryImpl
import com.example.bot.data.music.MusicPlaylistRepositoryImpl
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI
import java.sql.DriverManager
import java.util.UUID

internal abstract class PrivateClubContainmentContract {
    protected abstract val postgres: Boolean

    @Test
    fun `SQL excludes seeded private events layouts halls lists invitations and music before returning content`() =
        fixture { source ->
            runBlocking {
                val db = Database.connect(source)
                val bookings = BookingRepository(db)
                assertNull(bookings.findById(bookingId(8200)))
                assertNull(bookings.findByIdempotencyKey("synthetic-booking-8200"))
                assertEquals(8101L, bookings.findById(bookingId(8100))?.clubId)
                assertEquals(8101L, bookings.findByIdempotencyKey("synthetic-booking-8100")?.clubId)
                val clubs = ClubsDbRepository(db)
                val events = EventsDbRepository(db)
                assertNull(clubs.getById(8201))
                assertEquals("Legacy", clubs.getById(8101)?.name)
                assertEquals(listOf(8105L), events.list(null, "Synthetic", null, null, 0, 100).map { it.id })
                val globalEvents = events.list(null, null, null, null, 0, 100)
                assertTrue(globalEvents.any { it.id == 8105L })
                assertFalse(globalEvents.any { it.clubId == 8201L || it.id == 8205L })
                assertTrue(events.list(8201, null, null, null, 0, 100).isEmpty())
                assertEquals(listOf(8105L), events.list(8101, null, null, null, 0, 100).map { it.id })
                assertNull(events.findById(8201, 8205))
                val layout = LayoutDbRepository(db)
                assertNull(layout.getLayout(8201, 8205))
                assertNull(layout.loadGeometry(8201, "private"))
                assertTrue(layout.listForHall(8202).isEmpty())
                assertTrue(layout.listZonesForHall(8202).isEmpty())
                assertNull(layout.findByIdForHall(8202, 8203))
                assertFalse(layout.deleteForHall(8202, 8203))
                assertNull(layout.getPlanMetaForClub(8201, 8202))
                assertNull(layout.getPlanForClub(8201, 8202))
                assertEquals(1, layout.listForHall(8102).size)
                val admin = AdminClubsDbRepository(db)
                assertNull(admin.update(8201, AdminClubUpdate(isActive = true)))
                assertFalse(admin.delete(8201))
                val halls = AdminHallsDbRepository(db)
                assertNull(halls.update(8202, AdminHallUpdate(name = "Injected")))
                assertFalse(halls.delete(8202))
                assertNull(halls.makeActive(8202))
                assertTrue(halls.listForClub(8201).isEmpty())
                assertNull(GuestListDbRepository(db).findById(8206))
                assertNull(GuestListEntryDbRepository(db).findById(8207))
                assertNull(InvitationDbRepository(db).findByTokenHash("private-token-hash"))
                assertEquals(8106L, GuestListDbRepository(db).findById(8106)?.id)
                val music = MusicItemRepositoryImpl(db)
                assertNull(music.getById(8208))
                assertEquals(listOf(8108L), music.listActive(null, 100, 0, null, null, null).map { it.id })
                val playlists = MusicPlaylistRepositoryImpl(db)
                assertNull(playlists.getFull(8209))
                assertEquals(listOf(8109L), playlists.listActive(100, 0).map { it.id })
                source.connection.use { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT is_active,name FROM clubs WHERE id=8201").use { rs ->
                            assertTrue(rs.next())
                            assertFalse(rs.getBoolean(1))
                            assertEquals("PRIVATE_CANARY", rs.getString(2))
                        }
                    }
                }
            }
        }

    @Test
    fun `ownership resolver recognizes every seeded descendant without active or GLOBAL bypass`() =
        fixture { source ->
            runBlocking {
                val access = JdbcLegacyClubAccess(source)
                val identities =
                    mapOf(
                        LegacyResource.CLUB to 1,
                        LegacyResource.HALL to 2,
                        LegacyResource.HALL_TABLE to 3,
                        LegacyResource.TABLE to 4,
                        LegacyResource.EVENT to 5,
                        LegacyResource.GUEST_LIST to 6,
                        LegacyResource.GUEST_ENTRY to 7,
                        LegacyResource.MUSIC_ITEM to 8,
                        LegacyResource.MUSIC_PLAYLIST to 9,
                        LegacyResource.INVITATION to 10,
                        LegacyResource.WAITLIST to 11,
                    )
                for ((kind, offset) in identities) {
                    assertTrue(
                        access.containsMappedResource(listOf(LegacyReference(kind, (8200 + offset).toLong()))),
                        kind.name,
                    )
                    assertFalse(
                        access.containsMappedResource(listOf(LegacyReference(kind, (8100 + offset).toLong()))),
                        kind.name,
                    )
                    assertFalse(access.containsMappedResource(listOf(LegacyReference(kind, 999999L))), kind.name)
                }
                assertTrue(
                    access.containsMappedResource(
                        listOf(LegacyReference(LegacyResource.BOOKING, bookingId(8200).toString().uppercase())),
                    ),
                )
                assertFalse(
                    access.containsMappedResource(
                        listOf(LegacyReference(LegacyResource.BOOKING, bookingId(8100).toString())),
                    ),
                )
                assertTrue(access.containsMappedResource(listOf(LegacyReference(LegacyResource.CLUB, "08201"))))
                assertTrue(access.containsMappedResource(listOf(LegacyReference(LegacyResource.EVENT, "+8205"))))
                source.sql("UPDATE clubs SET is_active=TRUE WHERE id=8201")
                assertFalse(access.isLegacyClub(8201))
                assertNull(ClubsDbRepository(Database.connect(source)).getById(8201))
            }
        }

    private fun fixture(block: (HikariDataSource) -> Unit) {
        val schema = "private_test_" + UUID.randomUUID().toString().replace("-", "")
        val supplied = System.getenv("CLB_CLIENT_TEST_PG_URL")
        val ci = System.getenv("CI") == "true" && System.getenv("GITHUB_ACTIONS") == "true"
        val url =
            if (postgres) {
                if (supplied == null && ci) {
                    "jdbc:postgresql://127.0.0.1:5432/botdb"
                } else {
                    checkNotNull(supplied) { "Explicit disposable CLB_CLIENT_TEST_PG_URL required" }
                    val uri = URI(supplied.removePrefix("jdbc:"))
                    require(
                        uri.scheme == "postgresql" &&
                            uri.host == "127.0.0.1" &&
                            uri.path == "/clb_client_test" &&
                            uri.query == null &&
                            uri.userInfo == null,
                    )
                    supplied
                }
            } else {
                "jdbc:h2:mem:$schema;MODE=PostgreSQL;DATABASE_TO_UPPER=false"
            }
        val username =
            if (postgres) {
                if (supplied == null && ci) "botuser" else "clb_client_test"
            } else {
                "sa"
            }
        val password = if (postgres && supplied == null && ci) "botpass" else ""
        if (postgres) {
            DriverManager.getConnection(url, username, password).use { c ->
                assertEquals(16, c.metaData.databaseMajorVersion)
                c.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            }
        }
        try {
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = url
                    this.username = username
                    this.password = password
                    maximumPoolSize = 3
                    if (postgres) connectionInitSql = "SET search_path TO $schema,public"
                },
            ).use { source ->
                Flyway
                    .configure()
                    .dataSource(source)
                    .locations(
                        "classpath:db/migration/common",
                        "classpath:db/migration/" + if (postgres) "postgresql" else "h2",
                    ).load()
                    .migrate()
                seed(source)
                block(source)
            }
        } finally {
            if (postgres) {
                dropSchema(url, username, password, schema)
            }
        }
    }

    private fun dropSchema(
        url: String,
        username: String,
        password: String,
        schema: String,
    ) {
        DriverManager.getConnection(url, username, password).use { c ->
            c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
        }
    }

    private fun seed(source: HikariDataSource) {
        source.sql(
            "CREATE TABLE IF NOT EXISTS waitlist(id BIGINT PRIMARY KEY,club_id BIGINT NOT NULL,event_id BIGINT NOT NULL,user_id BIGINT NOT NULL,party_size INT NOT NULL,created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,called_at TIMESTAMP WITH TIME ZONE,expires_at TIMESTAMP WITH TIME ZONE,status VARCHAR(30) NOT NULL)",
        )
        source.sql("INSERT INTO users(id,telegram_user_id) VALUES (99101,99001)")
        source.sql("INSERT INTO clients(id,display_name,is_active) VALUES (101,'Synthetic client',TRUE)")
        for (base in listOf(8100, 8200)) {
            val name = if (base == 8200) "PRIVATE_CANARY" else "Legacy"
            source.sql(
                "INSERT INTO clubs(id,name,timezone,city,is_active) VALUES (${base + 1},'$name','UTC','Synthetic',${base != 8200})",
            )
            source.sql(
                "INSERT INTO events(id,club_id,title,start_at,end_at) VALUES (${base + 5},${base + 1},'$name',TIMESTAMP WITH TIME ZONE '2030-01-01 19:00:00+00',TIMESTAMP WITH TIME ZONE '2030-01-02 03:00:00+00')",
            )
            source.sql(
                "INSERT INTO halls(id,club_id,name,is_active,geometry_json,geometry_fingerprint) VALUES (${base + 2},${base + 1},'$name',TRUE,'{\"zones\":[{\"id\":\"main\",\"name\":\"$name\"}]}','private')",
            )
            source.sql("INSERT INTO hall_zones(hall_id,zone_id,name) VALUES (${base + 2},'main','$name')")
            source.sql(
                "INSERT INTO hall_tables(id,hall_id,table_number,label,capacity,zone_id) VALUES (${base + 3},${base + 2},1,'$name',4,'main')",
            )
            source.sql(
                "INSERT INTO tables(id,club_id,table_number,capacity,min_deposit) VALUES (${base + 4},${base + 1},1,4,0)",
            )
            source.sql(
                "INSERT INTO guest_lists(id,club_id,event_id,owner_type,owner_user_id,title,capacity) VALUES (${base + 6},${base + 1},${base + 5},'ADMIN',99101,'$name',10)",
            )
            source.sql(
                "INSERT INTO guest_list_entries(id,guest_list_id,full_name,display_name) VALUES (${base + 7},${base + 6},'$name','$name')",
            )
            source.sql(
                "INSERT INTO invitations(id,guest_list_entry_id,token_hash,channel,expires_at) VALUES (${base + 10},${base + 7},'${if (base == 8200) "private-token-hash" else "legacy-token-hash"}','TELEGRAM',TIMESTAMP WITH TIME ZONE '2031-01-01 00:00:00+00')",
            )
            source.sql(
                "INSERT INTO music_items(id,club_id,title,source_type,published_at) VALUES (${base + 8},${base + 1},'$name','LINK',CURRENT_TIMESTAMP)",
            )
            source.sql(
                "INSERT INTO bookings(id,event_id,club_id,table_id,table_number,guest_user_id,guest_name," +
                    "guests_count,min_deposit,total_deposit,slot_start,slot_end,status,qr_secret,idempotency_key) " +
                    "VALUES ('${bookingId(base)}',${base + 5},${base + 1},${base + 4},1,99101,'$name',2,0,0," +
                    "TIMESTAMP WITH TIME ZONE '2030-01-01 19:00:00+00'," +
                    "TIMESTAMP WITH TIME ZONE '2030-01-02 03:00:00+00'," +
                    "'SEATED','synthetic-qr-$base','synthetic-booking-$base')",
            )
            source.sql(
                "INSERT INTO waitlist(id,club_id,event_id,user_id,party_size,status) VALUES (${base + 11},${base + 1},${base + 5},99101,2,'WAITING')",
            )
            source.sql("INSERT INTO music_playlists(id,club_id,title) VALUES (${base + 9},${base + 1},'$name')")
        }
        source.sql("INSERT INTO client_clubs(client_id,club_id) VALUES (101,8201)")
    }

    private fun bookingId(base: Int): UUID = UUID.fromString("000000ab-0000-4000-8000-00000000${base + 12}")

    private fun HikariDataSource.sql(sql: String) =
        connection.use { c ->
            c.createStatement().use { it.execute(sql) }
            Unit
        }
}

internal class PrivateClubDataContainmentTest : PrivateClubContainmentContract() {
    override val postgres = false
}

@Tag("it")
internal class PrivateClubContainmentPostgresIT : PrivateClubContainmentContract() {
    override val postgres = true
}
