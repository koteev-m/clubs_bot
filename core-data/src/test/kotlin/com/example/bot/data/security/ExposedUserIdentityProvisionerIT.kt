package com.example.bot.data.security

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import testing.RequiresDocker
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RequiresDocker
@Tag("it")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedUserIdentityProvisionerIT {
    private lateinit var database: Database

    @BeforeAll
    fun startContainer() {
        postgres.start()
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration/common", "classpath:db/migration/postgresql")
            .baselineOnMigrate(true)
            .load()
            .migrate()
        database =
            Database.connect(
                url = postgres.jdbcUrl,
                driver = postgres.driverClassName,
                user = postgres.username,
                password = postgres.password,
            )
    }

    @BeforeEach
    fun cleanDatabase() {
        transaction(database) {
            exec("TRUNCATE TABLE users RESTART IDENTITY CASCADE")
        }
    }

    @AfterAll
    fun stopContainer() {
        postgres.stop()
    }

    @Test
    fun `concurrent provisioning across repository instances converges to one identity`() =
        runBlocking {
            val repositories = listOf(ExposedUserRepository(database), ExposedUserRepository(database))
            val callersReady = CountDownLatch(CALL_COUNT)
            val start = CompletableDeferred<Unit>()

            val identities =
                Executors.newFixedThreadPool(CALL_COUNT).asCoroutineDispatcher().use { dispatcher ->
                    coroutineScope {
                        val calls =
                            List(CALL_COUNT) { index ->
                                async(dispatcher) {
                                    callersReady.countDown()
                                    start.await()
                                    repositories[index % repositories.size].ensureMinimalIdentity(TELEGRAM_USER_ID)
                                }
                            }
                        assertTrue(callersReady.await(10, TimeUnit.SECONDS))
                        start.complete(Unit)
                        calls.awaitAll()
                    }
                }

            assertEquals(1, identities.map { it.id }.distinct().size)
            assertEquals(1L, userCount(TELEGRAM_USER_ID))
            assertTrue(identities.all { it.telegramId == TELEGRAM_USER_ID })
        }

    @Test
    fun `failed identity insert rolls back without partial row`() {
        transaction(database) {
            exec(
                """
                CREATE OR REPLACE FUNCTION reject_user_identity_insert()
                RETURNS trigger AS ${'$'}${'$'}
                BEGIN
                    RAISE EXCEPTION 'forced identity persistence failure';
                END;
                ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            exec(
                """
                CREATE TRIGGER reject_user_identity_insert_trigger
                AFTER INSERT ON users
                FOR EACH ROW EXECUTE FUNCTION reject_user_identity_insert()
                """.trimIndent(),
            )
        }
        val repository = ExposedUserRepository(database)

        assertThrows(Throwable::class.java) {
            runBlocking { repository.ensureMinimalIdentity(TELEGRAM_USER_ID) }
        }

        transaction(database) {
            exec("DROP TRIGGER reject_user_identity_insert_trigger ON users")
            exec("DROP FUNCTION reject_user_identity_insert()")
        }
        assertEquals(0L, userCount(TELEGRAM_USER_ID))
    }

    private fun userCount(telegramUserId: Long): Long =
        transaction(database) {
            UsersTable
                .selectAll()
                .where { UsersTable.telegramUserId eq telegramUserId }
                .count()
        }

    companion object {
        private const val CALL_COUNT = 24
        private const val TELEGRAM_USER_ID = 8_800_200L

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

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }
}
