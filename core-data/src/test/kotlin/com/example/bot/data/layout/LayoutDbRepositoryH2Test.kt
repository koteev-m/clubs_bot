package com.example.bot.data.layout

import com.example.bot.data.db.Clubs
import com.example.bot.layout.AdminTableUpdate
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class LayoutDbRepositoryH2Test {
    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var repository: LayoutDbRepository
    private lateinit var clock: Clock
    private var seedClubId: Long = 0

    @BeforeEach
    fun setUp() {
        val dbName = "layout-db-${UUID.randomUUID()}"
        val jdbcUrl = "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    this.jdbcUrl = jdbcUrl
                    driverClassName = "org.h2.Driver"
                    username = "sa"
                    password = ""
                    maximumPoolSize = 3
                },
            )

        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/common", "classpath:db/migration/h2")
            .cleanDisabled(false)
            .load()
            .also { flyway ->
                flyway.clean()
                flyway.migrate()
            }

        database = Database.connect(dataSource)
        clock = Clock.fixed(Instant.parse("2024-06-01T00:00:00Z"), ZoneOffset.UTC)
        repository = LayoutDbRepository(database, clock)

        seedLayout()
    }

    @AfterEach
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `layout watermark bumps when hall revision changes`() = runBlocking {
        val before = requireNotNull(repository.lastUpdatedAt(seedClubId, null))

        val updated =
            repository.update(
                AdminTableUpdate(
                    id = 1,
                    clubId = seedClubId,
                    label = "VIP-1A",
                    minDeposit = null,
                    capacity = null,
                    zone = null,
                    arrivalWindow = null,
                    mysteryEligible = null,
                ),
            )
        assertNotNull(updated)

        val after = requireNotNull(repository.lastUpdatedAt(seedClubId, null))
        assertTrue(after.isAfter(before))
    }

    @Test
    fun `layout returns zones and tables`() = runBlocking {
        val layout = requireNotNull(repository.getLayout(seedClubId, null))
        assertEquals(2, layout.zones.size)
        assertEquals(2, layout.tables.size)
    }

    private fun seedLayout() {
        val now = OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC)
        transaction(database) {
            val clubEntity =
                Clubs.insert {
                it[name] = "Club 1"
                it[description] = null
                it[timezone] = "Europe/Moscow"
                it[adminChatId] = null
                it[city] = "Moscow"
                it[genres] = "[]"
                it[tags] = "[]"
                it[logoUrl] = null
                it[isActive] = true
                it[createdAt] = now
                it[updatedAt] = now
            } get Clubs.id
            seedClubId = clubEntity.value.toLong()
            val hallEntity =
                HallsTable.insert {
                    it[HallsTable.clubId] = seedClubId
                    it[HallsTable.name] = "Main Hall"
                    it[HallsTable.isActive] = true
                    it[HallsTable.layoutRevision] = 1
                    it[HallsTable.geometryJson] = "{}"
                    it[HallsTable.geometryFingerprint] = "fp"
                    it[HallsTable.createdAt] = now
                    it[HallsTable.updatedAt] = now
                } get HallsTable.id
            val hallId = hallEntity.value
            HallZonesTable.insert {
                it[HallZonesTable.hallId] = hallId
                it[HallZonesTable.zoneId] = "vip"
                it[HallZonesTable.name] = "VIP"
                it[HallZonesTable.tags] = "[]"
                it[HallZonesTable.sortOrder] = 1
                it[HallZonesTable.createdAt] = now
                it[HallZonesTable.updatedAt] = now
            }
            HallZonesTable.insert {
                it[HallZonesTable.hallId] = hallId
                it[HallZonesTable.zoneId] = "main"
                it[HallZonesTable.name] = "Main"
                it[HallZonesTable.tags] = "[]"
                it[HallZonesTable.sortOrder] = 2
                it[HallZonesTable.createdAt] = now
                it[HallZonesTable.updatedAt] = now
            }
            HallTablesTable.insert {
                it[HallTablesTable.hallId] = hallId
                it[HallTablesTable.tableNumber] = 1
                it[HallTablesTable.label] = "VIP-1"
                it[HallTablesTable.capacity] = 4
                it[HallTablesTable.minimumTier] = "vip"
                it[HallTablesTable.minDeposit] = 0
                it[HallTablesTable.zoneId] = "vip"
                it[HallTablesTable.zone] = "vip"
                it[HallTablesTable.arrivalWindow] = null
                it[HallTablesTable.mysteryEligible] = false
                it[HallTablesTable.x] = 0.2
                it[HallTablesTable.y] = 0.2
                it[HallTablesTable.isActive] = true
                it[HallTablesTable.createdAt] = now
                it[HallTablesTable.updatedAt] = now
            }
            HallTablesTable.insert {
                it[HallTablesTable.hallId] = hallId
                it[HallTablesTable.tableNumber] = 2
                it[HallTablesTable.label] = "M-1"
                it[HallTablesTable.capacity] = 6
                it[HallTablesTable.minimumTier] = "standard"
                it[HallTablesTable.minDeposit] = 0
                it[HallTablesTable.zoneId] = "main"
                it[HallTablesTable.zone] = "main"
                it[HallTablesTable.arrivalWindow] = null
                it[HallTablesTable.mysteryEligible] = false
                it[HallTablesTable.x] = 0.6
                it[HallTablesTable.y] = 0.6
                it[HallTablesTable.isActive] = true
                it[HallTablesTable.createdAt] = now
                it[HallTablesTable.updatedAt] = now
            }
        }
    }
}
