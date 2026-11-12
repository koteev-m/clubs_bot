package com.example.bot.plugins.target

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.postgresql.ds.PGSimpleDataSource
import java.net.URI
import java.sql.DriverManager
import java.util.UUID
import javax.sql.DataSource

/** Never uses application DB configuration: only an explicit local disposable DB or the fixed CI service. */
@Tag("it")
class CanonicalTargetAudiencePostgresIT : CanonicalTargetAudienceDatabaseTest() {
    override fun database(block: (DataSource) -> Unit) {
        val source = disposableSource()
        val schema = "canonical_target_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(source.getURL(), source.user, source.password).use { connection ->
            check(connection.metaData.databaseMajorVersion == 16)
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
                listOf("pgcrypto", "pg_trgm", "uuid-ossp").forEach {
                    statement.execute("CREATE EXTENSION IF NOT EXISTS \"$it\" WITH SCHEMA public")
                }
            }
        }
        source.currentSchema = "$schema,public"
        try {
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
        } finally {
            DriverManager.getConnection(source.getURL(), source.user, source.password).use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    private fun disposableSource(): PGSimpleDataSource {
        val supplied = System.getenv("CLB_CLIENT_TEST_PG_URL")
        val ci = supplied == null && System.getenv("CI") == "true" && System.getenv("GITHUB_ACTIONS") == "true"
        val url = if (ci) "jdbc:postgresql://127.0.0.1:5432/botdb" else checkNotNull(supplied)
        val parsed = URI(url.removePrefix("jdbc:"))
        require(
            parsed.scheme == "postgresql" &&
                parsed.host == "127.0.0.1" &&
                parsed.query == null &&
                parsed.userInfo == null,
        )
        require(parsed.path == if (ci) "/botdb" else "/clb_client_test")
        return PGSimpleDataSource().apply {
            setURL(url)
            user = if (ci) "botuser" else "clb_client_test"
            password = if (ci) "botpass" else ""
        }
    }
}
