package com.example.bot.music

import com.example.bot.data.music.MusicItemRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import testing.RequiresDocker
import java.time.Instant

@RequiresDocker
@Tag("it")
class RepositoryTest {
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
    fun `create and list`() =
        runBlocking {
            PostgreSQLContainer<Nothing>("postgres:15-alpine").use { pg ->
                pg.start()
                Flyway
                    .configure()
                    .dataSource(pg.jdbcUrl, pg.username, pg.password)
                    .load()
                    .migrate()
                val db =
                    Database.connect(
                        pg.jdbcUrl,
                        driver = "org.postgresql.Driver",
                        user = pg.username,
                        password = pg.password,
                    )
                val repo = MusicItemRepositoryImpl(db)
                val created =
                    repo.create(
                        MusicItemCreate(
                            null, "Test", "DJ", MusicSource.YOUTUBE, "http://x", 60, null,
                            listOf(
                                "tag",
                            ),
                            Instant.EPOCH,
                        ),
                        actor = 1,
                    )
                val list = repo.listActive(null, 10, 0, null, null)
                assertEquals(1, list.size)
                assertEquals(created.id, list.first().id)
            }
        }
}
