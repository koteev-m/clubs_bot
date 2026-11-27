package com.example.bot.routes

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Tag
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.net.URI
import java.util.UUID
import javax.sql.DataSource

@Tag("it")
class ClientManagerPostgresRoutesIT : ClientManagerRoutesDataTest() {
    override fun databaseAt(
        target: String?,
        block: (DataSource) -> Unit,
    ) = withClientManagerPostgres(target, block)
}

internal fun withClientManagerPostgres(
    target: String?,
    block: (DataSource) -> Unit,
) {
    val local = System.getenv("CLB_CLIENT_TEST_PG_URL")
    if (local != null && System.getenv("GITHUB_ACTIONS") != "true") {
        localDatabase(local, target, block)
        return
    }
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

private fun localDatabase(
    url: String,
    target: String?,
    block: (DataSource) -> Unit,
) {
    val parsed = URI(url.removePrefix("jdbc:"))
    require(parsed.scheme == "postgresql" && parsed.host == "127.0.0.1" && parsed.path == "/clb_client_test")
    require(parsed.query == null && parsed.userInfo == null && url.startsWith("jdbc:"))
    val schema = "manager_http_" + UUID.randomUUID().toString().replace("-", "")
    val source =
        PGSimpleDataSource().apply {
            setURL(url)
            user = "clb_client_test"
            password = ""
        }
    source.connection.use { connection ->
        check(connection.metaData.databaseProductName == "PostgreSQL")
        connection.createStatement().use { statement ->
            statement.execute("CREATE SCHEMA $schema")
            listOf("pgcrypto", "pg_trgm", "uuid-ossp").forEach {
                statement.execute("CREATE EXTENSION IF NOT EXISTS \"$it\" WITH SCHEMA public")
            }
        }
    }
    source.currentSchema = "$schema,public"
    try {
        migrateBookingRulesFixture(source, "postgresql", target)
        block(source)
    } finally {
        source.connection.use { connection ->
            connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
        }
    }
}
