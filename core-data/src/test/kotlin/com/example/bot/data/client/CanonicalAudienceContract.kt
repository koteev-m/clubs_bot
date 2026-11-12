package com.example.bot.data.client

import com.example.bot.data.client.assignments.AssignmentTestDatabase
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

internal abstract class CanonicalAudienceContract {
    private lateinit var database: AssignmentTestDatabase
    private lateinit var source: CanonicalAudienceRecordingSource
    private lateinit var repository: JdbcCanonicalClientAudienceRepository

    protected abstract fun createDatabase(): AssignmentTestDatabase

    @BeforeEach
    fun prepare() {
        database = createDatabase()
        database.execute(
            "INSERT INTO clients(id,display_name,is_active) VALUES (101,'Same name',TRUE),(102,'Same name',TRUE)",
            "INSERT INTO clubs(id,name,timezone,is_active) VALUES " +
                "(1001,'Same name','UTC',FALSE),(1002,'Same name','UTC',TRUE)," +
                "(1003,'Unmapped','UTC',FALSE),(2001,'Same name','UTC',FALSE)",
            "INSERT INTO client_clubs(client_id,club_id,created_at) VALUES " +
                "(101,1001,'2026-11-11T00:00:00Z'),(101,1002,'2026-11-11T00:00:00Z')," +
                "(102,2001,'2026-11-11T00:00:00Z')",
        )
        source = CanonicalAudienceRecordingSource(database.dataSource)
        repository = JdbcCanonicalClientAudienceRepository(source)
    }

    @AfterEach
    fun close() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun `exact finite subset permits private clubs without staff users or permissions`() {
        val single = checkNotNull(repository.read(audience(1001)))
        assertEquals(setOf(1001L), single.mappingCreatedAt.keys)
        assertEquals(101L, single.clientId)
        assertEquals(1L, single.lifecycleVersion)
        val multiple = checkNotNull(repository.read(audience(1001, 1002)))
        assertEquals(setOf(1001L, 1002L), multiple.mappingCreatedAt.keys)
        assertEquals("0", database.scalar("SELECT COUNT(*) FROM users WHERE telegram_user_id=7"))
        for (table in listOf("client_memberships", "client_club_grants", "client_role_assignments")) {
            assertEquals("0", database.scalar("SELECT COUNT(*) FROM $table"))
        }
    }

    @Test
    fun `foreign missing and partially valid mapping sets fail closed without name matching`() {
        for (clubs in listOf(longArrayOf(2001), longArrayOf(1003), longArrayOf(9999), longArrayOf(1001, 2001))) {
            assertNull(repository.read(audience(*clubs)))
        }
        assertNull(repository.read(ClientAudience.Client(102, setOf(1001))))
        assertNull(repository.read(ClientAudience.Client(999, setOf(1001))))
        assertNull(repository.read(audience()))
    }

    @Test
    fun `new club is never added to an existing finite snapshot`() {
        val first = checkNotNull(repository.read(audience(1001)))
        database.execute("INSERT INTO client_clubs(client_id,club_id) VALUES (101,1003)")
        val current = checkNotNull(repository.read(audience(1001)))
        assertTrue(first.sameSnapshot(current))
        assertEquals(setOf(1001L), current.mappingCreatedAt.keys)
    }

    @Test
    fun `inactive archived and archive restore lifecycle changes are observed afresh`() {
        val first = checkNotNull(repository.read(audience(1001)))
        database.execute("UPDATE clients SET is_active=FALSE WHERE id=101")
        assertNull(repository.read(audience(1001)))
        database.execute(
            "UPDATE clients SET is_active=TRUE,archived_at=CURRENT_TIMESTAMP,lifecycle_version=2 WHERE id=101",
        )
        assertNull(repository.read(audience(1001)))
        database.execute("UPDATE clients SET archived_at=NULL,lifecycle_version=3 WHERE id=101")
        val restored = checkNotNull(repository.read(audience(1001)))
        assertEquals(3L, restored.lifecycleVersion)
        assertFalse(first.sameSnapshot(restored))
    }

    @Test
    fun `mapping move deletion and recreated row metadata cannot reuse old snapshot`() {
        val first = checkNotNull(repository.read(audience(1001)))
        database.execute("UPDATE client_clubs SET client_id=102 WHERE club_id=1001")
        assertNull(repository.read(audience(1001)))
        database.execute("DELETE FROM client_clubs WHERE club_id=1001")
        assertNull(repository.read(audience(1001)))
        database.execute(
            "INSERT INTO client_clubs(client_id,club_id,created_at) VALUES (101,1001,'2026-11-12T00:00:00Z')",
        )
        assertFalse(first.sameSnapshot(checkNotNull(repository.read(audience(1001)))))
    }

    @Test
    fun `read uses one readonly statement rollback and no membership or authority joins`() {
        val before = fingerprint()
        repeat(3) { checkNotNull(repository.read(audience(1001, 1002))) }
        assertEquals(before, fingerprint())
        assertEquals(3, source.connections)
        assertEquals(3, source.rollbacks)
        assertEquals(listOf(true, true, true), source.readOnly)
        assertEquals(listOf(false, false, false), source.autoCommit)
        assertEquals(List(3) { java.sql.Connection.TRANSACTION_READ_COMMITTED }, source.isolation)
        assertEquals(3, source.queries.size)
        assertTrue(source.queries.all { it.trimStart().startsWith("SELECT ") })
        assertTrue(source.queries.none { it.contains("grants") || it.contains("memberships") || it.contains("users") })
    }

    @Test
    fun `one statement retains its snapshot after another connection commits an archive`() {
        source.afterQuery = {
            database.execute("UPDATE clients SET archived_at=CURRENT_TIMESTAMP,lifecycle_version=2 WHERE id=101")
        }
        val snapshot = checkNotNull(repository.read(audience(1001, 1002)))
        assertEquals(1L, snapshot.lifecycleVersion)
        assertEquals(setOf(1001L, 1002L), snapshot.mappingCreatedAt.keys)
        source.afterQuery = {}
        assertNull(repository.read(audience(1001, 1002)))
    }

    @Test
    fun `snapshot metadata is immutable and is not an authority generation`() {
        val mutable = mutableMapOf(1001L to Instant.EPOCH)
        val snapshot = CanonicalClientAudienceSnapshot(101, 1, mutable)
        mutable[1002L] = Instant.EPOCH
        assertEquals(setOf(1001L), snapshot.mappingCreatedAt.keys)
        assertThrows<UnsupportedOperationException> {
            (snapshot.mappingCreatedAt as MutableMap)[1002L] = Instant.EPOCH
        }
        assertThrows<IllegalArgumentException> { CanonicalClientAudienceSnapshot(101, 0, mutable) }
    }

    @Test
    fun `query failure rolls back without partial writes`() {
        val before = fingerprint()
        source.queryFailure = java.sql.SQLException("synthetic DB failure")
        assertThrows<java.sql.SQLException> { repository.read(audience(1001)) }
        assertEquals(1, source.rollbacks)
        assertEquals(before, fingerprint())
    }

    @Test
    fun `cancellation survives a concurrent cleanup failure`() {
        source.queryFailure = CancellationException("synthetic cancellation")
        source.rollbackFailure = java.sql.SQLException("synthetic rollback failure")
        val failure = assertThrows<CancellationException> { repository.read(audience(1001)) }
        assertTrue(failure.suppressed.any { it.message == "synthetic rollback failure" })
        assertEquals(1, source.rollbacks)
    }

    private fun audience(vararg clubs: Long): ClientAudience.Client = ClientAudience.Client(101, clubs.toSet())

    private fun fingerprint(): Map<String, List<List<String?>>> =
        listOf(
            "clients",
            "client_clubs",
            "clubs",
            "users",
            "client_memberships",
            "client_club_grants",
            "client_club_staff",
            "client_club_access_state",
            "client_role_assignments",
            "client_permission_delegations",
            "client_commands",
            "client_invitations",
            "audit_log",
        ).associateWith { database.rows("SELECT * FROM $it ORDER BY 1").sortedBy { row -> row.toString() } }
}
