package com.example.bot.plugins.target

import com.example.bot.data.client.JdbcCanonicalClientAudienceRepository
import com.example.bot.routes.ClientOnboardingRoutesTestBase
import org.junit.jupiter.api.Test
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Full-migration disposable DB + real canonical reader + provider + HMAC; no production routes installed. */
open class CanonicalTargetAudienceDatabaseTest : ClientOnboardingRoutesTestBase() {
    @Test
    fun `real database audience authenticates a new recipient and rejects current archive or mapping changes`() =
        database { source ->
            execute(
                source,
                "INSERT INTO clients(id,display_name,is_active) VALUES (101,'Synthetic A',TRUE),(102,'Synthetic B',TRUE)",
                "INSERT INTO clubs(id,name,timezone,is_active) VALUES " +
                    "(1001,'Private A','UTC',FALSE),(1002,'Private B','UTC',FALSE)",
                "INSERT INTO client_clubs(client_id,club_id) VALUES (101,1001),(101,1002)",
            )
            val fixture = CanonicalTargetAudienceFixture()
            val provider =
                CanonicalTargetAudienceProvider(
                    fixture.pinned,
                    JdbcCanonicalClientAudienceRepository(source),
                    fixture.bindings,
                )
            val verifier = TargetIdentityVerifier(fixture.connection, provider, fixture.identity.clock)
            val before = authority(source)
            repeat(2) {
                val identity = assertNotNull(verifier.verify(fixture.identity.headers(), "", fixture.context))
                assertEquals(7L, identity.telegramUserId)
            }
            assertEquals(before, authority(source))
            assertEquals("0", source.scalar("SELECT COUNT(*) FROM users WHERE telegram_user_id=7"))
            assertEquals("0", source.scalar("SELECT COUNT(*) FROM client_memberships"))
            execute(source, "UPDATE clients SET archived_at=CURRENT_TIMESTAMP,lifecycle_version=2 WHERE id=101")
            assertNull(verifier.verify(fixture.identity.headers(), "", fixture.context))
            execute(source, "UPDATE clients SET archived_at=NULL,lifecycle_version=3 WHERE id=101")
            assertNotNull(verifier.verify(fixture.identity.headers(), "", fixture.context))
            execute(source, "UPDATE client_clubs SET client_id=102 WHERE club_id=1002")
            assertNull(verifier.verify(fixture.identity.headers(), "", fixture.context))
            assertEquals(before, authority(source))
        }

    private fun execute(
        source: DataSource,
        vararg sql: String,
    ) {
        source.connection.use { connection ->
            connection.createStatement().use { statement -> sql.forEach { statement.execute(it) } }
        }
    }

    private fun authority(source: DataSource): Map<String, String> =
        listOf(
            "client_memberships",
            "client_club_grants",
            "client_club_staff",
            "client_club_access_state",
            "client_role_assignments",
            "client_permission_delegations",
            "client_invitations",
            "client_commands",
            "audit_log",
        ).associateWith { source.scalar("SELECT COUNT(*) FROM $it") }
}
