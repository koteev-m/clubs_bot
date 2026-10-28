package com.example.bot.data.client

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** Requires an explicit dedicated disposable local database; never opens a Docker or live-DB path. */
@Tag("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class ClientContextPostgresIT : ClientContextContract() {
    private lateinit var url: String
    private var user = "clb_client_test"
    private var password = ""
    private val schemas = mutableListOf<String>()
    private val extensionSchema = "client_extensions_" + UUID.randomUUID().toString().replace("-", "")

    @BeforeAll
    fun selectTestDatabase() {
        val supplied = System.getenv("CLB_CLIENT_TEST_PG_URL")
        if (supplied == null && System.getenv("CI") == "true" && System.getenv("GITHUB_ACTIONS") == "true") {
            // Fixed disposable service already provisioned by .github/workflows/tests.yml:217-224.
            // Never infer a database from application DB_URL or create/manage a container here.
            url = "jdbc:postgresql://127.0.0.1:5432/botdb"
            user = "botuser"
            password = "botpass"
        } else {
            checkNotNull(supplied) {
                "Client PostgreSQL IT requires CLB_CLIENT_TEST_PG_URL for a disposable clb_client_test database"
            }
            val parsed = URI(supplied.removePrefix("jdbc:"))
            require(parsed.scheme == "postgresql" && parsed.host == "127.0.0.1")
            require(parsed.path == "/clb_client_test" && parsed.query == null && parsed.userInfo == null)
            url = supplied
        }
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $extensionSchema")
                for (extension in listOf("pgcrypto", "pg_trgm", "uuid-ossp")) {
                    statement.execute("CREATE EXTENSION IF NOT EXISTS \"$extension\" WITH SCHEMA $extensionSchema")
                }
            }
        }
        schemas += extensionSchema
    }

    override fun createFixture(): ClientContextFixture = ClientContextFixture(newTestDataSource(), "postgresql")

    private fun newTestDataSource(): HikariDataSource {
        val schema = "client_test_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        schemas += schema
        return HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                username = user
                password = this@ClientContextPostgresIT.password
                connectionInitSql = "SET search_path TO $schema, $extensionSchema, public"
                maximumPoolSize = 4
            },
        )
    }

    @AfterAll
    fun removeFixtureSchemas() {
        if (!::url.isInitialized) return
        DriverManager.getConnection(url, user, password).use { connection ->
            schemas.asReversed().forEach { schema ->
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    @Test
    fun `postgres FK metadata has exact composite targets and immediate restricted updates`() {
        val rules =
            fixture.rows(
                "SELECT conrelid::regclass::text, pg_get_constraintdef(oid), condeferrable " +
                    "FROM pg_constraint WHERE contype='f' AND connamespace=current_schema()::regnamespace " +
                    "AND conrelid::regclass::text LIKE 'client_%' ORDER BY conrelid::regclass::text, conname",
            )
        assertEquals(7, rules.size)
        assertTrue(rules.all { it[2] == "f" })
        assertTrue(rules.all { it[1]?.contains("ON UPDATE RESTRICT") == true })
        assertTrue(
            rules.any {
                it[1]?.contains("FOREIGN KEY (client_id, user_id) REFERENCES client_memberships(client_id, user_id)") ==
                    true
            },
        )
        assertTrue(
            rules.any {
                it[1]?.contains("FOREIGN KEY (client_id, club_id) REFERENCES client_clubs(client_id, club_id)") ==
                    true
            },
        )
        assertTrue(rules.none { it[1]?.contains("user_roles") == true })
    }

    @Test
    fun `rollback keeps authority and commit is visible to the next SELECT`() =
        runBlocking<Unit> {
            fixture.dataSource.connection.use { writer ->
                writer.autoCommit = false
                writer.createStatement().use {
                    it.executeUpdate(
                        "UPDATE client_memberships SET is_active=FALSE WHERE user_id=5001",
                    )
                }
                assertEquals(
                    listOf(1001L, 1002L),
                    fixture.repository
                        .context(880061001, platform)
                        .single()
                        .clubs
                        .map { it.id },
                )
                writer.rollback()
                assertEquals(
                    listOf(1001L, 1002L),
                    fixture.repository
                        .context(880061001, platform)
                        .single()
                        .clubs
                        .map { it.id },
                )
                writer.createStatement().use {
                    it.executeUpdate(
                        "UPDATE client_memberships SET is_active=FALSE WHERE user_id=5001",
                    )
                }
                writer.commit()
                assertEquals(emptyList<ClientContext>(), fixture.repository.context(880061001, platform))
            }
        }

    @Test
    fun `in flight SELECT retains its snapshot while the next read sees committed revocation`() =
        runBlocking<Unit> {
            fixture.dataSource.connection.use { blocker ->
                blocker.autoCommit = false
                blocker.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(61001001)") }
                val repository = JdbcClientContextRepository(snapshotBarrier(fixture.dataSource))
                val pending = async(start = CoroutineStart.UNDISPATCHED) { repository.club(880061001, platform, 1001) }
                try {
                    awaitAdvisoryWait()
                    fixture.execute(
                        "UPDATE client_club_grants SET is_active=FALSE WHERE " +
                            "client_id=101 AND user_id=5001 AND club_id=1001",
                    )
                } finally {
                    blocker.rollback()
                }
                assertEquals(ClientClubDetail(101, 1001, "A1"), pending.await())
                assertNull(fixture.repository.club(880061001, platform, 1001))
                assertEquals(
                    listOf(1002L),
                    fixture.repository
                        .context(880061001, platform)
                        .single()
                        .clubs
                        .map { it.id },
                )
            }
        }

    @Test
    fun `independent JVM restarts retain persisted records and observe later revocation`() {
        assertEquals("CLIENT_CONTEXT=101:1001,1002", runReadProcess())
        assertEquals("CLIENT_CONTEXT=101:1001,1002", runReadProcess())
        fixture.execute(
            "UPDATE client_club_grants SET is_active=FALSE WHERE client_id=101 AND user_id=5001 AND club_id=1002",
        )
        assertEquals("CLIENT_CONTEXT=101:1001", runReadProcess())
    }

    @Test
    fun `GET leaves PostgreSQL rows and sequences unchanged`() =
        runBlocking<Unit> {
            val before = fixture.allSnapshot()
            val sequences =
                fixture.rows(
                    "SELECT sequencename, last_value FROM pg_sequences WHERE " +
                        "schemaname=current_schema() ORDER BY sequencename",
                )
            fixture.repository.context(880061001, platform)
            fixture.repository.context(880061099, platform)
            fixture.repository.club(880061001, platform, 1001)
            fixture.repository.club(880061001, platform, 2001)
            assertEquals(before, fixture.allSnapshot())
            assertEquals(
                sequences,
                fixture.rows(
                    "SELECT sequencename, last_value FROM pg_sequences WHERE " +
                        "schemaname=current_schema() ORDER BY sequencename",
                ),
            )
        }

    @Test
    fun `late migration drift fails and rolls back all earlier V061 changes`() {
        newTestDataSource().use { source ->
            fun configured(target: String? = null): Flyway =
                Flyway
                    .configure()
                    .dataSource(source)
                    .locations("classpath:db/migration/common", "classpath:db/migration/postgresql")
                    .target(target ?: "61")
                    .load()
            configured("60").migrate()
            source.connection.use { connection ->
                connection.createStatement().use {
                    it.execute(
                        "CREATE TABLE client_club_grants (id BIGINT PRIMARY KEY)",
                    )
                }
            }
            assertThrows(FlywayException::class.java) { configured().migrate() }
            source.connection.use { connection ->
                assertEquals(
                    listOf(listOf(null, null, null)),
                    ClientContextFixture.snapshotQuery(
                        connection,
                        "SELECT to_regclass('clients'), to_regclass('client_memberships'), to_regclass('client_clubs')",
                    ),
                )
                assertEquals(
                    listOf(listOf("0")),
                    ClientContextFixture.snapshotQuery(
                        connection,
                        "SELECT COUNT(*) FROM permissions WHERE code='client.club.read'",
                    ),
                )
            }
            assertEquals(
                "060",
                configured("60")
                    .info()
                    .current()
                    .version
                    .toString(),
            )
            configured("60").validate()
        }
    }

    private fun runReadProcess(): String {
        val paths = linkedSetOf<String>()
        paths += System.getProperty("java.class.path").split(java.io.File.pathSeparator)
        generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>().forEach { loader ->
            paths += loader.urLs.map { Path.of(it.toURI()).toString() }
        }
        val process =
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                paths.joinToString(java.io.File.pathSeparator),
                ClientContextProcessProbe::class.java.name,
                url,
                user,
                fixture.rows("SELECT current_schema()").single().single(),
                extensionSchema,
            ).redirectErrorStream(true).apply { environment()["CLB_SYNTHETIC_PG_PASSWORD"] = password }.start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Fresh JVM did not finish")
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), output)
            return output.lineSequence().single { it.startsWith("CLIENT_CONTEXT=") }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun awaitAdvisoryWait() {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        do {
            val waiting =
                fixture
                    .rows(
                        "SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() " +
                            "AND wait_event='advisory' AND query LIKE 'WITH snapshot_barrier AS MATERIALIZED%'",
                    ).single()
                    .single()
            if (waiting == "1") return
            Thread.yield()
        } while (System.nanoTime() < deadline)
        error("Repository SELECT never reached the database snapshot barrier")
    }

    private fun snapshotBarrier(source: DataSource): DataSource =
        object : DataSource by source {
            override fun getConnection(): Connection {
                val connection = source.connection
                return Proxy.newProxyInstance(
                    Connection::class.java.classLoader,
                    arrayOf(Connection::class.java),
                ) { _, method, args ->
                    val parameters = args?.clone()
                    if (method.name == "prepareStatement" && parameters?.firstOrNull() is String) {
                        val sql = parameters[0] as String
                        assertTrue(connection.isReadOnly)
                        assertFalse(connection.autoCommit)
                        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
                        parameters[0] =
                            "WITH snapshot_barrier AS MATERIALIZED (SELECT pg_advisory_xact_lock(61001001)) " +
                            sql.replace("FROM users u", "FROM users u CROSS JOIN snapshot_barrier")
                    }
                    try {
                        method.invoke(connection, *parameters.orEmpty())
                    } catch (exception: InvocationTargetException) {
                        throw exception.targetException
                    }
                } as Connection
            }
        }
}

internal object ClientContextProcessProbe {
    @JvmStatic
    fun main(args: Array<String>) =
        runBlocking<Unit> {
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = args[0]
                    username = args[1]
                    password = System.getenv("CLB_SYNTHETIC_PG_PASSWORD")
                    connectionInitSql = "SET search_path TO ${args[2]}, ${args[3]}, public"
                    maximumPoolSize = 1
                },
            ).use { source ->
                val context = JdbcClientContextRepository(source).context(880061001, ClientAudience.Platform)
                println(
                    "CLIENT_CONTEXT=" +
                        context.joinToString(
                            ";",
                        ) { "${it.id}:" + it.clubs.joinToString(",") { club -> club.id.toString() } },
                )
            }
        }
}
