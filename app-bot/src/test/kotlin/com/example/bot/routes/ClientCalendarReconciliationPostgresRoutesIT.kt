package com.example.bot.routes

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.util.UUID
import javax.sql.DataSource

/** Keep calendar reconciliation tests isolated from the shared PostgreSQL service used by other test modules. */
@Tag("it")
@Testcontainers
class ClientCalendarReconciliationPostgresRoutesIT : ClientCalendarReconciliationRoutesDataTest() {
    override fun databaseAt(
        target: String?,
        block: (DataSource) -> Unit,
    ) {
        val schema = "client_calendar_reconciliation_" + UUID.randomUUID().toString().replace("-", "")
        setupSchema(schema)
        try {
            val configuration =
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    connectionInitSql = "SET search_path TO $schema, public"
                    maximumPoolSize = 4
                }
            HikariDataSource(configuration).use { source ->
                migrate(source, target)
                block(source)
            }
        } finally {
            jdbc { it.execute("DROP SCHEMA $schema CASCADE") }
        }
    }

    private fun migrate(
        source: DataSource,
        target: String?,
    ) {
        val flyway =
            Flyway
                .configure()
                .dataSource(source)
                .locations("classpath:db/migration/common", "classpath:db/migration/postgresql")
                .apply { target?.let { target(it) } }
                .load()
        flyway.migrate()
        flyway.validate()
    }

    private fun setupSchema(schema: String) {
        jdbc { statement ->
            statement.execute("CREATE SCHEMA $schema")
            listOf("pgcrypto", "pg_trgm", "uuid-ossp").forEach {
                statement.execute("CREATE EXTENSION IF NOT EXISTS \"$it\" WITH SCHEMA public")
            }
        }
    }

    private fun jdbc(block: (java.sql.Statement) -> Unit) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use(block)
        }
    }

    companion object {
        @Container
        @JvmField
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }
}
