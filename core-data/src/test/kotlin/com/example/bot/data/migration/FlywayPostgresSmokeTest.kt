package com.example.bot.data.migration

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import testing.RequiresDocker
import java.time.OffsetDateTime

@RequiresDocker
@Tag("it")
class FlywayPostgresSmokeTest {
    private val resourcesToClose = mutableListOf<AutoCloseable>()
    private val baseTime = OffsetDateTime.parse("2024-01-01T12:00:00+00:00")

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
    fun `postgres migrations run with jsonb defaults`() {
        val container = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        container.start()
        resourcesToClose += AutoCloseable { container.stop() }

        migrateAndTrack(container.jdbcUrl, container.username, container.password, container.driverClassName, "postgresql", resourcesToClose)
        withConnection(resourcesToClose) { connection ->
            assertUuidDefault(connection)
            assertJsonColumnType(connection, expectedType = "jsonb")
            assertGuestListLimitRemovedPostgres(connection)
            assertCheckinsSchemaPostgres(connection)
            assertCheckinsConstraintEnforcedPostgres(connection)
            assertGuestListStatusConstraintPostgres(connection)
            assertGuestListStatuses(connection, baseTime)
        }
    }
}
