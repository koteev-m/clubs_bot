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
import kotlin.test.assertEquals

/** Run the Ktor/JDBC contract without sharing PostgreSQL extensions across test modules. */
@Tag("it")
@Testcontainers
class ClientOnboardingPostgresRoutesIT : ClientOnboardingRoutesDataTest() {
    override fun database(block: (DataSource) -> Unit) {
        val schema = "owner_http_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            assertEquals(16, connection.metaData.databaseMajorVersion)
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
                listOf("pgcrypto", "pg_trgm", "uuid-ossp").forEach {
                    statement.execute("CREATE EXTENSION IF NOT EXISTS \"$it\" WITH SCHEMA public")
                }
            }
        }
        try {
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    connectionInitSql = "SET search_path TO $schema, public"
                    maximumPoolSize = 4
                },
            ).use { source ->
                Flyway
                    .configure()
                    .dataSource(source)
                    .locations("classpath:db/migration/common", "classpath:db/migration/postgresql")
                    .load()
                    .also {
                        it.migrate()
                        it.validate()
                    }
                block(source)
            }
        } finally {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA $schema CASCADE")
                }
            }
        }
    }

    companion object {
        @Container
        @JvmField
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }
}
