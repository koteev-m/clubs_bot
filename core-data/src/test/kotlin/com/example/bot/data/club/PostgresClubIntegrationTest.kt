package com.example.bot.data.club

import com.example.bot.data.booking.EventsTable
import com.example.bot.data.booking.TablesTable
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import testing.RequiresDocker
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

@RequiresDocker
@Tag("it")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PostgresClubIntegrationTest {
    protected lateinit var database: Database

    @BeforeAll
    fun startContainer() {
        postgres.start()
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations(
                "classpath:db/migration/common",
                "classpath:db/migration/postgresql",
            )
            .baselineOnMigrate(true)
            .load()
            .migrate()
        database =
            Database.connect(
                url = postgres.jdbcUrl,
                driver = postgres.driverClassName,
                user = postgres.username,
                password = postgres.password,
            )
    }

    @BeforeEach
    fun cleanDatabase() {
        transaction(database) {
            exec(
                """
                TRUNCATE TABLE
                    guest_list_entries,
                    guest_lists,
                    bookings,
                    booking_holds,
                    events,
                    tables,
                    zones,
                    clubs,
                    users
                RESTART IDENTITY CASCADE
                """
                    .trimIndent(),
            )
        }
    }

    protected fun insertClub(
        name: String,
        timezone: String = "Europe/Moscow",
    ): Long {
        return transaction(database) {
            ClubsTable
                .insert {
                    it[ClubsTable.name] = name
                    it[ClubsTable.description] = null
                    it[ClubsTable.timezone] = timezone
                    it[ClubsTable.adminChannelId] = null
                    it[ClubsTable.bookingsTopicId] = null
                    it[ClubsTable.checkinTopicId] = null
                    it[ClubsTable.qaTopicId] = null
                }
                .resultedValues!!
                .single()[ClubsTable.id]
        }
    }

    protected fun insertEvent(
        clubId: Long,
        title: String,
        startAt: Instant,
        endAt: Instant,
    ): Long {
        return transaction(database) {
            EventsTable
                .insert {
                    it[EventsTable.clubId] = clubId
                    it[EventsTable.title] = title
                    it[EventsTable.startAt] = startAt.atOffset(ZoneOffset.UTC)
                    it[EventsTable.endAt] = endAt.atOffset(ZoneOffset.UTC)
                    it[EventsTable.isSpecial] = false
                    it[EventsTable.posterUrl] = null
                }
                .resultedValues!!
                .single()[EventsTable.id]
        }
    }

    protected fun insertTable(
        clubId: Long,
        tableNumber: Int,
        capacity: Int,
        minDeposit: BigDecimal,
        active: Boolean = true,
    ): Long {
        return transaction(database) {
            TablesTable
                .insert {
                    it[TablesTable.clubId] = clubId
                    it[TablesTable.zoneId] = null
                    it[TablesTable.tableNumber] = tableNumber
                    it[TablesTable.capacity] = capacity
                    it[TablesTable.minDeposit] = minDeposit
                    it[TablesTable.active] = active
                }
                .resultedValues!!
                .single()[TablesTable.id]
        }
    }

    protected fun insertUser(
        username: String,
        displayName: String,
    ): Long {
        return transaction(database) {
            UsersTable
                .insert {
                    it[UsersTable.telegramUserId] = null
                    it[UsersTable.username] = username
                    it[UsersTable.displayName] = displayName
                    it[UsersTable.phoneE164] = null
                }
                .resultedValues!!
                .single()[UsersTable.id]
        }
    }

    @AfterAll
    fun stopContainer() {
        postgres.stop()
    }

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }
}

private object ClubsTable : Table("clubs") {
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

private object UsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val telegramUserId = long("telegram_user_id").nullable()
    val username = text("username").nullable()
    val displayName = text("display_name").nullable()
    val phoneE164 = text("phone_e164").nullable()
    override val primaryKey = PrimaryKey(id)
}
