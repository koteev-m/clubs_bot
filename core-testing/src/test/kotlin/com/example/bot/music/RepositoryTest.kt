package com.example.bot.music

import com.example.bot.data.music.MusicAssetRepositoryImpl
import com.example.bot.data.music.MusicItemRepositoryImpl
import com.example.bot.music.MusicAssetKind
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
                            clubId = null,
                            title = "Test",
                            dj = "DJ",
                            description = null,
                            itemType = MusicItemType.TRACK,
                            source = MusicSource.YOUTUBE,
                            sourceUrl = "http://x",
                            durationSec = 60,
                            coverUrl = null,
                            tags = listOf("tag"),
                            publishedAt = Instant.EPOCH,
                        ),
                        actor = 1,
                    )
                val list = repo.listActive(null, 10, 0, null, null)
                assertEquals(1, list.size)
                assertEquals(created.id, list.first().id)
            }
        }

    @Test
    fun `attach assets after migration`() =
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
                val itemsRepo = MusicItemRepositoryImpl(db)
                val assetsRepo = MusicAssetRepositoryImpl(db)
                val created =
                    itemsRepo.create(
                        MusicItemCreate(
                            clubId = null,
                            title = "Test",
                            dj = "DJ",
                            description = null,
                            itemType = MusicItemType.SET,
                            source = MusicSource.YOUTUBE,
                            sourceUrl = "http://x",
                            durationSec = 60,
                            coverUrl = null,
                            tags = listOf("tag"),
                            publishedAt = Instant.EPOCH,
                        ),
                        actor = 1,
                    )
                val audio =
                    assetsRepo.createAsset(
                        kind = MusicAssetKind.AUDIO,
                        bytes = "audio".toByteArray(),
                        contentType = "audio/mpeg",
                        sha256 = "audio-sha",
                        sizeBytes = 5L,
                    )
                val cover =
                    assetsRepo.createAsset(
                        kind = MusicAssetKind.COVER,
                        bytes = "cover".toByteArray(),
                        contentType = "image/jpeg",
                        sha256 = "cover-sha",
                        sizeBytes = 5L,
                    )
                val withAudio = itemsRepo.attachAudioAsset(created.id, audio.id, actor = 1)
                val withCover = itemsRepo.attachCoverAsset(created.id, cover.id, actor = 1)
                assertEquals(audio.id, withAudio?.audioAssetId)
                assertEquals(cover.id, withCover?.coverAssetId)
            }
        }
}
