package com.example.bot.data.migration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import testing.RequiresDocker
import java.math.BigDecimal
import java.sql.Connection
import java.util.UUID

@RequiresDocker
@Tag("it")
class FlywayVendorSmokeTest {
    private val resourcesToClose = mutableListOf<AutoCloseable>()

    companion object {
        @JvmStatic
        @BeforeAll
        fun assumeDocker() {
            val dockerAvailable =
                try {
                    DockerClientFactory.instance().client()
                    true
                } catch (_: Throwable) {
                    false
                }
            assumeTrue(dockerAvailable, "Docker is not available on this host; skipping IT.")
        }
    }

    @AfterEach
    fun tearDown() {
        resourcesToClose.reversed().forEach { runCatching { it.close() } }
        resourcesToClose.clear()
    }

    @Test
    fun `h2 migrations run with json defaults`() {
        val jdbcUrl =
            "jdbc:h2:mem:flyway-h2;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
        migrate(jdbcUrl, "sa", "", "org.h2.Driver", "h2")
        withConnection { connection ->
            assertUuidDefault(connection)
            assertJsonColumnType(connection, expectedType = "json")
        }
    }

    @Test
    fun `postgres migrations run with jsonb defaults`() {
        val container = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        container.start()
        resourcesToClose += AutoCloseable { container.stop() }

        migrate(container.jdbcUrl, container.username, container.password, container.driverClassName, "postgresql")
        withConnection { connection ->
            assertUuidDefault(connection)
            assertJsonColumnType(connection, expectedType = "jsonb")
        }
    }

    private fun migrate(
        jdbcUrl: String,
        user: String,
        password: String,
        driver: String,
        vendor: String,
    ) {
        val flyway =
            Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .locations("classpath:db/migration/common", "classpath:db/migration/$vendor")
                .cleanDisabled(false)
                .load()
        flyway.clean()
        flyway.migrate()

        val dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    this.jdbcUrl = jdbcUrl
                    username = user
                    this.password = password
                    driverClassName = driver
                    maximumPoolSize = 2
                },
            )
        resourcesToClose += dataSource
    }

    private inline fun withConnection(block: (Connection) -> Unit) {
        val dataSource = resourcesToClose.filterIsInstance<HikariDataSource>().last()
        dataSource.connection.use(block)
    }

    private fun assertUuidDefault(connection: Connection) {
        val key = "smoke-" + UUID.randomUUID()
        connection.prepareStatement(
            "INSERT INTO payments (provider, currency, amount, status, idempotency_key) VALUES (?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, "stripe")
            statement.setString(2, "USD")
            statement.setBigDecimal(3, BigDecimal("10.00"))
            statement.setString(4, "INITIATED")
            statement.setString(5, key)
            statement.executeUpdate()
        }

        connection.prepareStatement("SELECT id FROM payments WHERE idempotency_key = ?").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { rs ->
                require(rs.next()) { "Payment row was not inserted" }
                val value = rs.getString(1)
                UUID.fromString(value)
            }
        }
    }

    private fun assertJsonColumnType(
        connection: Connection,
        expectedType: String,
    ) {
        val metadata = connection.metaData
        val schemaPattern =
            connection.schema ?: when (metadata.databaseProductName.lowercase()) {
                "postgresql" -> "public"
                else -> null
            }
        metadata.getColumns(connection.catalog, schemaPattern, "notifications_outbox", "payload").use { columns ->
            require(columns.next()) { "notifications_outbox.payload column not found" }
            val typeName = columns.getString("TYPE_NAME").lowercase()
            check(typeName == expectedType) {
                "Expected JSON column type $expectedType but was $typeName"
            }
        }
    }
}
