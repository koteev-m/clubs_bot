package com.example.bot.music

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import testing.RequiresDocker

/** Ensures music migrations apply successfully. */
@RequiresDocker
@Tag("it")
class MigrationTest {
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

    @Test
    fun `migrations apply`() {
        PostgreSQLContainer<Nothing>("postgres:15-alpine").use { pg ->
            pg.start()
            val flyway = Flyway.configure().dataSource(pg.jdbcUrl, pg.username, pg.password).load()
            flyway.migrate()
            pg.createConnection("")!!.use { conn ->
                val rs = conn.createStatement().executeQuery("select to_regclass('music_items')")
                rs.next()
                assertTrue(rs.getString(1) != null)
            }
        }
    }
}
