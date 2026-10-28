package com.example.bot.data.client.onboarding

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

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
                        "'client_club_applications','client_membership_grants','client_commands')",
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
