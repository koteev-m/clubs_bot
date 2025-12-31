package com.example.bot.data.club

import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListStatus
import com.example.bot.data.booking.EventsTable
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class GuestListDbRepositoryH2Test {
    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database

    private val fixedInstant: Instant = Instant.parse("2024-05-05T12:00:00Z")
    private val fixedClock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        val jdbcUrl = "jdbc:h2:mem:guest-list-db;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"

        val migrationDataSource =
            HikariDataSource(
                HikariConfig().apply {
                    this.jdbcUrl = jdbcUrl
                    driverClassName = "org.h2.Driver"
                    username = "sa"
                    password = ""
                    maximumPoolSize = 3
                },
            )

        val flyway =
            Flyway
                .configure()
                .dataSource(migrationDataSource)
                .locations(
                    "filesystem:${Paths.get("core-data/src/main/resources/db/migration/common").toAbsolutePath()}",
                    "filesystem:${prepareH2Migrations()}",
                )
                .cleanDisabled(false)
                .load()

        flyway.clean()
        flyway.migrate()
        migrationDataSource.close()

        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    this.jdbcUrl = jdbcUrl
                    driverClassName = "org.h2.Driver"
                    username = "sa"
                    password = ""
                    maximumPoolSize = 2
                },
            )

        verifyGuestListMigrationsApplied()

        database = Database.connect(dataSource)
    }

    private fun prepareH2Migrations(): String {
        val sourceDir =
            Paths.get(
                checkNotNull(javaClass.classLoader.getResource("db/migration/h2")) {
                    "H2 migration resources not found"
                }.toURI(),
            )
        val targetDir = Files.createTempDirectory("flyway-h2-migrations")

        Files.list(sourceDir).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                val fileName = path.fileName.toString()
                val content = Files.readString(path)
                val patched =
                    when (fileName) {
                        "V017__guest_list_invites_checkins.sql" -> patchGuestListMigration(content)
                        "V11__webhook_security.sql",
                        "V12__promo_schema.sql",
                        "V13__payments_actions.sql",
                        "V016__admin_tables.sql",
                        -> patchAutoIncrement(content)
                        else -> content
                    }

                Files.writeString(targetDir.resolve(fileName), patched)
            }
        }

        return targetDir.toAbsolutePath().toString()
    }

    private fun patchGuestListMigration(content: String): String =
        content
            .replace(
                """
                ALTER TABLE guest_lists
                    ADD COLUMN IF NOT EXISTS promoter_id BIGINT NULL REFERENCES users(id) ON DELETE SET NULL,
                    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                    ADD COLUMN IF NOT EXISTS "limit" INT NULL;
                """.trimIndent(),
                """
                ALTER TABLE guest_lists ADD COLUMN IF NOT EXISTS promoter_id BIGINT NULL REFERENCES users(id) ON DELETE SET NULL;
                ALTER TABLE guest_lists ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
                ALTER TABLE guest_lists ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
                ALTER TABLE guest_lists ADD COLUMN IF NOT EXISTS "limit" INT NULL;
                """.trimIndent(),
            )
            .replace(
                """
                ALTER TABLE guest_list_entries
                    ADD COLUMN IF NOT EXISTS display_name TEXT NOT NULL DEFAULT '',
                    ADD COLUMN IF NOT EXISTS telegram_user_id BIGINT NULL,
                    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
                """.trimIndent(),
                """
                ALTER TABLE guest_list_entries ADD COLUMN IF NOT EXISTS display_name TEXT NOT NULL DEFAULT '';
                ALTER TABLE guest_list_entries ADD COLUMN IF NOT EXISTS telegram_user_id BIGINT NULL;
                ALTER TABLE guest_list_entries ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
                ALTER TABLE guest_list_entries ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
                """.trimIndent(),
            )
            .replace(
                """
                ALTER TABLE guest_list_entries
                    DROP CONSTRAINT IF EXISTS guest_list_entries_status_check;
                """.trimIndent(),
                """
                ALTER TABLE guest_list_entries
                    DROP CONSTRAINT IF EXISTS guest_list_entries_status_check;
                ALTER TABLE guest_list_entries
                    DROP CONSTRAINT IF EXISTS chk_guest_list_entries_status;
                """.trimIndent(),
            )

    private fun patchAutoIncrement(content: String): String =
        content.replace("AUTO_INCREMENT", "GENERATED BY DEFAULT AS IDENTITY")

    private fun verifyGuestListMigrationsApplied() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                val versions =
                    statement
                        .executeQuery("SELECT version FROM flyway_schema_history")
                        .use { resultSet ->
                            buildList {
                                while (resultSet.next()) {
                                    add(resultSet.getString("version"))
                                }
                            }
                        }

                val normalizedVersions = versions.filterNotNull().map { it.trimStart('0') }.toSet()

                assertTrue(normalizedVersions.containsAll(setOf("17", "18")), "Unexpected migration versions: $versions")
            }
        }
    }

    @AfterEach
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `insertMany returns stable entries`() =
        runBlocking {
            val ownerId = insertUser(username = "owner", displayName = "Owner")
            val clubId = insertClub(name = "Nebula")
            val eventId =
                insertEvent(
                    clubId = clubId,
                    title = "Opening",
                    startAt = fixedInstant,
                    endAt = fixedInstant.plusSeconds(3600),
                )

            val guestListRepo = GuestListDbRepository(database, fixedClock)
            val entryRepo = GuestListEntryDbRepository(database, fixedClock)

            val guestList =
                guestListRepo.create(
                    NewGuestList(
                        clubId = clubId,
                        eventId = eventId,
                        promoterId = ownerId,
                        ownerType = GuestListOwnerType.PROMOTER,
                        ownerUserId = ownerId,
                        title = "VIP",
                        capacity = 10,
                        arrivalWindowStart = fixedInstant,
                        arrivalWindowEnd = fixedInstant.plusSeconds(1800),
                        status = GuestListStatus.ACTIVE,
                    ),
                )

            val entries =
                entryRepo.insertMany(
                    guestList.id,
                    listOf(
                        NewGuestListEntry(displayName = "Alice", telegramUserId = null),
                        NewGuestListEntry(displayName = "Bob", telegramUserId = null),
                        NewGuestListEntry(displayName = "Charlie", telegramUserId = null),
                    ),
                )

            assertEquals(3, entries.size)
            assertEquals(3, entries.count { it.id > 0 })

            assertEquals(fixedInstant, guestList.createdAt)
            assertEquals(fixedInstant, guestList.updatedAt)
            entries.forEach { entry ->
                assertEquals(fixedInstant, entry.createdAt)
                assertEquals(fixedInstant, entry.updatedAt)
            }

            val first = entries.first()
            assertEquals(first, entryRepo.findById(first.id))

            val byList = entryRepo.listByGuestList(guestList.id)
            assertEquals(entries.toSet(), byList.toSet())
            assertNotNull(guestList)
        }

    private fun insertClub(
        name: String,
        timezone: String = "Europe/Moscow",
    ): Long =
        transaction(database) {
            TestClubsTable
                .insert {
                    it[TestClubsTable.name] = name
                    it[TestClubsTable.description] = null
                    it[TestClubsTable.timezone] = timezone
                    it[TestClubsTable.adminChannelId] = null
                    it[TestClubsTable.bookingsTopicId] = null
                    it[TestClubsTable.checkinTopicId] = null
                    it[TestClubsTable.qaTopicId] = null
                } get TestClubsTable.id
        }

    private fun insertEvent(
        clubId: Long,
        title: String,
        startAt: Instant,
        endAt: Instant,
    ): Long =
        transaction(database) {
            EventsTable
                .insert {
                    it[EventsTable.clubId] = clubId
                    it[EventsTable.title] = title
                    it[EventsTable.startAt] = startAt.atOffset(ZoneOffset.UTC)
                    it[EventsTable.endAt] = endAt.atOffset(ZoneOffset.UTC)
                    it[EventsTable.isSpecial] = false
                    it[EventsTable.posterUrl] = null
                } get EventsTable.id
        }

    private fun insertUser(
        username: String,
        displayName: String,
    ): Long =
        transaction(database) {
            TestUsersTable
                .insert {
                    it[TestUsersTable.telegramUserId] = null
                    it[TestUsersTable.username] = username
                    it[TestUsersTable.displayName] = displayName
                    it[TestUsersTable.phoneE164] = null
                } get TestUsersTable.id
        }
}

private object TestClubsTable : Table("clubs") {
    val id = long("id").autoIncrement()
    val name = text("name")
    val description = text("description").nullable()
    val timezone = text("timezone")
    val adminChannelId = long("admin_channel_id").nullable()
    val bookingsTopicId = integer("bookings_topic_id").nullable()
    val checkinTopicId = integer("checkin_topic_id").nullable()
    val qaTopicId = integer("qa_topic_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

private object TestUsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val telegramUserId = long("telegram_user_id").nullable()
    val username = text("username").nullable()
    val displayName = text("display_name").nullable()
    val phoneE164 = text("phone_e164").nullable()
    override val primaryKey = PrimaryKey(id)
}
