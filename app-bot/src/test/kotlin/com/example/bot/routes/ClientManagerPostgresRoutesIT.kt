package com.example.bot.routes

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Tag
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

@Tag("it")
class ClientManagerPostgresRoutesIT : ClientManagerRoutesDataTest() {
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
                check(source.connection.use { it.metaData.databaseProductName == "PostgreSQL" })
                block(source)
            }
        }
    }
}
