package com.example.bot.data.client.assignments

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.net.URI
import java.sql.DriverManager
import java.util.UUID

/** Owns only an in-memory H2 database or a schema in an explicit disposable local PostgreSQL database. */
internal class AssignmentTestDatabase private constructor(
    val dataSource: HikariDataSource,
    val dialect: String,
    private val cleanup: () -> Unit,
) : AutoCloseable {
    fun migrate(target: String? = null): Int {
        val configured =
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/common", "classpath:db/migration/$dialect")
        if (target != null) configured.target(target)
        val flyway = configured.load()
        val result = flyway.migrate()
        flyway.validate()
        return result.migrationsExecuted
    }

    fun execute(vararg statements: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statements.forEach { statement.execute(it) } }
        }
    }

    fun rows(sql: String): List<List<String?>> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    buildList {
                        while (result.next()) {
                            add((1..result.metaData.columnCount).map { result.getString(it) })
                        }
                    }
                }
            }
        }

    fun scalar(sql: String): String? = rows(sql).single().single()

    override fun close() {
        try {
            dataSource.close()
        } finally {
            cleanup()
        }
    }

    companion object {
        fun h2(target: String? = null): AssignmentTestDatabase {
            val url =
                "jdbc:h2:mem:role-assignment-${UUID.randomUUID()};" +
                    "MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
            val source =
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = url
                        username = "sa"
                        password = ""
                        maximumPoolSize = 4
                    },
                )
            val database =
                AssignmentTestDatabase(source, "h2") {
                    DriverManager.getConnection(url, "sa", "").use { connection ->
                        connection.createStatement().use { it.execute("SHUTDOWN") }
                    }
                }
            return initialized(database, target)
        }

        fun postgres(target: String? = null): AssignmentTestDatabase {
            val supplied = System.getenv("CLB_CLIENT_TEST_PG_URL")
            val isCi = System.getenv("CI") == "true" && System.getenv("GITHUB_ACTIONS") == "true"
            val url: String
            val user: String
            val password: String
            if (supplied == null && isCi) {
                url = "jdbc:postgresql://127.0.0.1:5432/botdb"
                user = "botuser"
                password = "botpass"
            } else {
                checkNotNull(supplied) { "Explicit disposable CLB_CLIENT_TEST_PG_URL is required" }
                val parsed = URI(supplied.removePrefix("jdbc:"))
                require(parsed.scheme == "postgresql" && parsed.host == "127.0.0.1")
                require(parsed.path == "/clb_client_test" && parsed.query == null && parsed.userInfo == null)
                url = supplied
                user = "clb_client_test"
                password = ""
            }
            val schema = "role_assignment_" + UUID.randomUUID().toString().replace("-", "")
            DriverManager.getConnection(url, user, password).use { connection ->
                check(connection.metaData.databaseMajorVersion == 16) { "PostgreSQL 16 parity is required" }
                connection.createStatement().use { statement ->
                    statement.execute("CREATE SCHEMA $schema")
                    listOf("pgcrypto", "pg_trgm", "uuid-ossp").forEach {
                        statement.execute("CREATE EXTENSION IF NOT EXISTS \"$it\" WITH SCHEMA public")
                    }
                }
            }
            val source =
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = url
                        username = user
                        this.password = password
                        connectionInitSql = "SET search_path TO $schema, public"
                        maximumPoolSize = 4
                    },
                )
            val database =
                AssignmentTestDatabase(source, "postgresql") {
                    DriverManager.getConnection(url, user, password).use { connection ->
                        connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
                    }
                }
            return initialized(database, target)
        }

        private fun initialized(
            database: AssignmentTestDatabase,
            target: String?,
        ): AssignmentTestDatabase {
            var completed = false
            return try {
                database.migrate(target)
                completed = true
                database
            } finally {
                if (!completed) database.close()
            }
        }
    }
}
