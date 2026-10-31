package com.example.bot.data.client.onboarding

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicit disposable native PostgreSQL only; no Docker or application database discovery. */
@Tag("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class ClientOnboardingPostgresIT : OnboardingContract() {
    private lateinit var url: String
    private var user = "clb_client_test"
    private var password = ""
    private val schemas = mutableListOf<String>()
    private val extensions = "onboarding_extensions_" + UUID.randomUUID().toString().replace("-", "")

    @BeforeAll
    fun selectDatabase() {
        val supplied = System.getenv("CLB_CLIENT_TEST_PG_URL")
        if (supplied == null && System.getenv("CI") == "true" && System.getenv("GITHUB_ACTIONS") == "true") {
            url = "jdbc:postgresql://127.0.0.1:5432/botdb"
            user = "botuser"
            password = "botpass"
        } else {
            checkNotNull(supplied) { "Explicit disposable CLB_CLIENT_TEST_PG_URL is required" }
            val parsed = URI(supplied.removePrefix("jdbc:"))
            require(parsed.scheme == "postgresql" && parsed.host == "127.0.0.1")
            require(parsed.path == "/clb_client_test" && parsed.query == null && parsed.userInfo == null)
            url = supplied
        }
        DriverManager.getConnection(url, user, password).use { connection ->
            assertEquals(
                16,
                connection.metaData.databaseMajorVersion,
                "Onboarding concurrency gate uses CI PostgreSQL major",
            )
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $extensions")
                listOf("pgcrypto", "pg_trgm", "uuid-ossp").forEach {
                    statement.execute("CREATE EXTENSION IF NOT EXISTS \"$it\" WITH SCHEMA $extensions")
                }
            }
        }
    }

    override fun newFixture(target: String?): OnboardingFixture {
        val schema = "onboarding_test_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        schemas += schema
        return OnboardingFixture(
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = url
                    username = user
                    password = this@ClientOnboardingPostgresIT.password
                    connectionInitSql = "SET search_path TO $schema, $extensions, public"
                    maximumPoolSize = 8
                },
            ),
            "postgresql",
            target,
        )
    }

    @Test
    fun `claim waiting for the client lock cannot use a pre expiry timestamp`() =
        runBlocking<Unit> {
            assertExpiryAfterLock(OnboardingMutation.INVITATION_CLAIM, "not_found")
        }

    @Test
    fun `platform confirmation waiting for the client lock cannot accept after expiry`() =
        runBlocking<Unit> {
            assertExpiryAfterLock(OnboardingMutation.INVITATION_CONFIRM, "invalid_transition")
        }

    @Test
    fun `create waiting for client lock rechecks committed revocation before any persistence`() =
        runBlocking<Unit> {
            val approved = fixture.approved(fixture.accept().id("clientId"))
            fixture.source.connection.use { blocker ->
                blocker.autoCommit = false
                val sql = OnboardingSql(blocker)
                val blockerId = checkNotNull(sql.row("SELECT pg_backend_pid() AS pid")).long("pid").toInt()
                sql.row("SELECT id FROM clients WHERE id=? FOR UPDATE", approved.id("clientId"))
                coroutineScope {
                    val outcome =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_CREATE_CLUB, approved)
                                "unexpected_success"
                            } catch (failure: OnboardingFailure) {
                                failure.code
                            }
                        }
                    try {
                        awaitBlockedMutation(blockerId)
                        sql.update(
                            "UPDATE client_memberships SET is_active=FALSE WHERE client_id=?",
                            approved.id("clientId"),
                        )
                        sql.update(
                            "UPDATE client_membership_grants SET is_active=FALSE WHERE client_id=?",
                            approved.id("clientId"),
                        )
                        blocker.commit()
                    } finally {
                        blocker.rollback()
                    }
                    assertEquals("not_found", outcome.await())
                }
            }
            assertEquals(0, fixture.count("client_club_onboarding"))
            assertEquals(0, fixture.count("client_clubs"))
            assertEquals(0, fixture.count("client_club_grants"))
        }

    @Test
    fun `layout mutation waiting on client lock rechecks exact grant revocation before persistence`() =
        runBlocking<Unit> {
            val club = create(approved())
            val hall =
                fixture.repository
                    .mutate(
                        fixture.alice,
                        OnboardingMutation.CLUB_HALL_CREATE,
                        club.id("id"),
                        buildJsonObject { put("name", "Waiting hall") },
                        "layout-waiting-create",
                    ).firstHall()
            val before = fixture.rows("SELECT * FROM hall_tables WHERE hall_id=${hall.id("id")}")
            fixture.source.connection.use { blocker ->
                blocker.autoCommit = false
                val sql = OnboardingSql(blocker)
                val blockerId = checkNotNull(sql.row("SELECT pg_backend_pid() AS pid")).long("pid").toInt()
                sql.row("SELECT id FROM clients WHERE id=? FOR UPDATE", club.id("clientId"))
                coroutineScope {
                    val outcome =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                fixture.repository.mutate(
                                    fixture.alice,
                                    OnboardingMutation.CLUB_TABLE_CREATE,
                                    club.id("id"),
                                    tableBody(hall),
                                    "layout-waiting-table",
                                )
                                "unexpected_success"
                            } catch (failure: OnboardingFailure) {
                                failure.code
                            }
                        }
                    try {
                        awaitBlockedMutation(blockerId)
                        sql.update(
                            "UPDATE client_club_grants SET is_active=FALSE WHERE club_id=? AND permission_code=?",
                            club.id("id"),
                            WRITE_LAYOUT,
                        )
                        blocker.commit()
                    } finally {
                        blocker.rollback()
                    }
                    assertEquals("not_found", outcome.await())
                }
            }
            assertEquals(before, fixture.rows("SELECT * FROM hall_tables WHERE hall_id=${hall.id("id")}"))
            assertEquals("1", layout(club).firstHall().string("layoutRevision"))
        }

    @Test
    fun `layout and image reads waiting on client lock see committed read grant revocation`() =
        runBlocking<Unit> {
            val club = create(approved())
            val hall =
                fixture.repository
                    .mutate(
                        fixture.alice,
                        OnboardingMutation.CLUB_HALL_CREATE,
                        club.id("id"),
                        buildJsonObject { put("name", "Waiting read") },
                        "layout-waiting-read-hall",
                    ).firstHall()
            listOf(OnboardingRead.CLUB_LAYOUT_GET, OnboardingRead.CLUB_PLAN_GET).forEach { operation ->
                fixture.execute(
                    "UPDATE client_club_grants SET is_active=TRUE WHERE club_id=${club.id(
                        "id",
                    )} AND permission_code='$READ_CLUB'",
                )
                fixture.source.connection.use { blocker ->
                    blocker.autoCommit = false
                    val sql = OnboardingSql(blocker)
                    val blockerId = checkNotNull(sql.row("SELECT pg_backend_pid() AS pid")).long("pid").toInt()
                    sql.row("SELECT id FROM clients WHERE id=? FOR UPDATE", club.id("clientId"))
                    coroutineScope {
                        val outcome =
                            async(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    fixture.repository.read(
                                        fixture.alice,
                                        operation,
                                        club.id("id"),
                                        if (operation ==
                                            OnboardingRead.CLUB_PLAN_GET
                                        ) {
                                            buildJsonObject { put("hallId", hall.getValue("id")) }
                                        } else {
                                            JsonObject(emptyMap())
                                        },
                                    )
                                    "unexpected_success"
                                } catch (failure: OnboardingFailure) {
                                    failure.code
                                }
                            }
                        try {
                            awaitBlockedMutation(blockerId)
                            sql.update(
                                "UPDATE client_club_grants SET is_active=FALSE WHERE club_id=? AND permission_code=?",
                                club.id("id"),
                                READ_CLUB,
                            )
                            blocker.commit()
                        } finally {
                            blocker.rollback()
                        }
                        assertEquals("not_found", outcome.await())
                    }
                }
            }
        }

    @Test
    fun `club profile and exact ownership survive independent JVM restarts`() =
        runBlocking<Unit> {
            val approved = fixture.approved(fixture.accept().id("clientId"))
            val club = fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_CREATE_CLUB, approved)
            val saved =
                fixture.mutate(
                    fixture.alice,
                    OnboardingMutation.CLUB_PROFILE_UPDATE,
                    club,
                    buildJsonObject {
                        put("name", "Persisted synthetic club")
                        put("description", "Persisted synthetic profile")
                        put("timezone", "Europe/Paris")
                    },
                )
            repeat(2) {
                assertEquals("CLUB_PROFILE=$saved", runProfileProcess(club.id("id")))
            }
        }

    private fun runProfileProcess(clubId: Long): String {
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
                ClubProfileProcessProbe::class.java.name,
                url,
                user,
                fixture.rows("SELECT current_schema() AS name").single().getValue("name"),
                extensions,
                fixture.owner.telegramUserId.toString(),
                fixture.alice.telegramUserId.toString(),
                clubId.toString(),
            ).redirectErrorStream(true).apply { environment()["CLB_SYNTHETIC_PG_PASSWORD"] = password }.start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Fresh profile JVM did not finish")
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), output)
            return output.lineSequence().single { it.startsWith("CLUB_PROFILE=") }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private suspend fun assertExpiryAfterLock(
        operation: OnboardingMutation,
        expected: String,
    ) = coroutineScope {
        val issued = fixture.issue()
        val resource = if (operation == OnboardingMutation.INVITATION_CONFIRM) fixture.claim(issued) else issued
        val expiresAt = Instant.parse(checkNotNull(issued.string("expiresAt")))
        fixture.clock.current = expiresAt.minusSeconds(1)
        val tables =
            listOf(
                "users",
                "clients",
                "client_invitations",
                "client_memberships",
                "client_membership_grants",
                "client_commands",
                "audit_log",
            )
        val before = tables.associateWith { fixture.rows("SELECT * FROM $it ORDER BY 1") }
        fixture.source.connection.use { blocker ->
            blocker.autoCommit = false
            val sql = OnboardingSql(blocker)
            val blockerId = checkNotNull(sql.row("SELECT pg_backend_pid() AS pid")).long("pid").toInt()
            sql.row("SELECT id FROM clients WHERE id=? FOR UPDATE", issued.id("clientId"))
            val outcome =
                async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        if (operation == OnboardingMutation.INVITATION_CLAIM) {
                            fixture.claim(issued)
                        } else {
                            fixture.mutate(fixture.owner, operation, resource)
                        }
                        "unexpected_success"
                    } catch (failure: OnboardingFailure) {
                        failure.code
                    }
                }
            try {
                awaitBlockedMutation(blockerId)
                fixture.clock.current = expiresAt
            } finally {
                blocker.rollback()
            }
            assertEquals(expected, outcome.await())
        }
        assertEquals(before, tables.associateWith { fixture.rows("SELECT * FROM $it ORDER BY 1") })
        assertEquals(
            "expired",
            fixture.repository
                .read(
                    fixture.owner,
                    OnboardingRead.PLATFORM_INVITATION_GET,
                    issued.id("id"),
                ).string("status"),
        )
    }

    private suspend fun awaitBlockedMutation(blockerId: Int) {
        withTimeout(10_000) {
            while (true) {
                val waiting =
                    fixture.source.connection.use { connection ->
                        OnboardingSql(connection)
                            .row(
                                "SELECT COUNT(*) AS waiting FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                                blockerId,
                            )?.long("waiting")
                    }
                if (waiting != null && waiting > 0) break
                delay(10)
            }
        }
    }

    @Test
    fun `migration foreign keys retain exact membership and invitation client boundaries`() {
        val keys =
            fixture
                .rows(
                    "SELECT pg_get_constraintdef(oid) AS definition FROM pg_constraint " +
                        "WHERE contype='f' AND connamespace=current_schema()::regnamespace " +
                        "AND conrelid::regclass::text IN ('client_invitations'," +
                        "'client_club_applications','client_membership_grants'," +
                        "'client_commands','client_club_onboarding')",
                ).map { it.getValue("definition") }
        assertTrue(
            keys.any {
                it?.contains("FOREIGN KEY (client_id, author_user_id) REFERENCES client_memberships") ==
                    true
            },
        )
        assertTrue(
            keys.any {
                it?.contains("FOREIGN KEY (client_id, replaces_invitation_id) REFERENCES client_invitations") ==
                    true
            },
        )
        assertTrue(keys.all { it?.contains("ON UPDATE RESTRICT ON DELETE RESTRICT") == true })
        assertTrue(keys.any { it?.contains("FOREIGN KEY (client_id, club_id) REFERENCES client_clubs") == true })
        assertTrue(
            keys.any {
                it?.contains(
                    "FOREIGN KEY (application_id, client_id, owner_user_id, application_status, " +
                        "approved_snapshot_hash, approved_snapshot_version)",
                ) ==
                    true
            },
        )
    }

    @AfterAll
    fun removeSchemas() {
        if (!::url.isInitialized) return
        DriverManager.getConnection(url, user, password).use { connection ->
            (schemas.asReversed() + extensions).forEach { schema ->
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }
}

internal object ClubProfileProcessProbe {
    @JvmStatic
    fun main(args: Array<String>) =
        runBlocking<Unit> {
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = args[0]
                    username = args[1]
                    password = System.getenv("CLB_SYNTHETIC_PG_PASSWORD").orEmpty()
                    connectionInitSql = "SET search_path TO ${args[2]}, ${args[3]}, public"
                    maximumPoolSize = 1
                },
            ).use { source ->
                val repository = JdbcClientOnboardingRepository(source, args[4].toLong())
                val profile =
                    repository.read(
                        OnboardingActor(args[5].toLong(), "platform"),
                        OnboardingRead.CLUB_PROFILE_GET,
                        args[6].toLong(),
                    )
                println("CLUB_PROFILE=$profile")
            }
        }
}
