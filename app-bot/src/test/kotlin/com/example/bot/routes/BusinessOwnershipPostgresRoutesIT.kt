package com.example.bot.routes

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.assertEquals

@Tag("it")
class BusinessOwnershipPostgresRoutesIT : BusinessOwnershipRoutesTest() {
    @Test
    fun `existing prepared reads survive ownership and manager migrations on the same connection`() =
        databaseAt("79") { source ->
            source.connection.use { legacy ->
                legacy.createStatement().use { statement ->
                    val tables = listOf("clubs", "client_memberships", "client_club_access_state")
                    val originalColumns =
                        tables.associateWith { table ->
                            statement.execute("PREPARE legacy_$table AS SELECT * FROM $table LIMIT 0")
                            statement.executeQuery("EXECUTE legacy_$table").use { it.metaData.columnCount }
                        }
                    upgradeClubFixture(source)
                    originalColumns.forEach { (table, count) ->
                        statement.executeQuery("EXECUTE legacy_$table").use { rows ->
                            assertEquals(count, rows.metaData.columnCount)
                        }
                    }
                }
            }
        }

    override fun databaseAt(
        target: String?,
        block: (DataSource) -> Unit,
    ) {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { postgres ->
            postgres.start()
            val config =
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 4
                }
            HikariDataSource(config).use { source ->
                migrateBookingRulesFixture(source, "postgresql", target)
                block(source)
            }
        }
    }
}
