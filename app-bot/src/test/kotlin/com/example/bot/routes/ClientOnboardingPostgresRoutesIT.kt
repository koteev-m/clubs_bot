package com.example.bot.routes

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import java.net.URI
import java.sql.DriverManager
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals

/** The same real Ktor/JDBC contract as H2, in task-owned PostgreSQL schemas only. */
@Tag("it")
class ClientOnboardingPostgresRoutesIT : ClientOnboardingRoutesDataTest() {
    override fun database(block: (DataSource) -> Unit) {
        val supplied = System.getenv("CLB_CLIENT_TEST_PG_URL")
        val ci = supplied == null && System.getenv("CI") == "true" && System.getenv("GITHUB_ACTIONS") == "true"
        val url = if (ci) "jdbc:postgresql://127.0.0.1:5432/botdb" else checkNotNull(supplied)
        val user = if (ci) "botuser" else "clb_client_test"
        val syntheticPassword = if (ci) "botpass" else ""
        if (!ci) {
            val parsed = URI(url.removePrefix("jdbc:"))
            require(parsed.scheme == "postgresql" && parsed.host == "127.0.0.1")
            require(parsed.path == "/clb_client_test" && parsed.query == null && parsed.userInfo == null)
        }
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val schema = "owner_http_$suffix"
        val extensions = "owner_http_ext_$suffix"
        DriverManager.getConnection(url, user, syntheticPassword).use { connection ->
            assertEquals(16, connection.metaData.databaseMajorVersion)
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
                statement.execute("CREATE SCHEMA $extensions")
                listOf("pgcrypto", "pg_trgm", "uuid-ossp").forEach {
                    statement.execute("CREATE EXTENSION IF NOT EXISTS \"$it\" WITH SCHEMA $extensions")
                }
            }
        }
        try {
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = url
                    username = user
                    password = syntheticPassword
                    connectionInitSql = "SET search_path TO $schema, $extensions, public"
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
            DriverManager.getConnection(url, user, syntheticPassword).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA $schema CASCADE")
                    statement.execute("DROP SCHEMA $extensions CASCADE")
                }
            }
        }
    }
}
