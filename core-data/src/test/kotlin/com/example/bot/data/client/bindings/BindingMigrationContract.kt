package com.example.bot.data.client.bindings

import com.example.bot.data.client.assignments.AssignmentTestDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal abstract class BindingMigrationContract {
    protected abstract fun database(target: String?): AssignmentTestDatabase

    @Test fun `populated V077 upgrade preserves legacy grants and inactive memberships`() {
        database("77").use { db ->
            db.execute(
                "INSERT INTO users(id,telegram_user_id) VALUES (78901,878901)",
                "INSERT INTO clients(id,display_name,is_active) VALUES (101,'Old client',TRUE)",
                "INSERT INTO clubs(id,name,timezone) VALUES (1001,'Old club','UTC')",
                "INSERT INTO client_clubs(client_id,club_id) VALUES (101,1001)",
                "INSERT INTO client_memberships(client_id,user_id,is_active) VALUES (101,78901,FALSE)",
                "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES (101,78901,1001,'client.club.read',FALSE)",
            )
            val tables =
                listOf(
                    "users",
                    "clubs",
                    "client_clubs",
                    "client_memberships",
                    "client_club_grants",
                    "client_club_staff",
                    "client_role_assignments",
                    "client_permission_delegations",
                    "user_roles",
                    "audit_log",
                    "client_commands",
                )
            val before = tables.associateWith { db.rows("SELECT * FROM $it ORDER BY 1") }
            val client =
                db.rows(
                    "SELECT id,display_name,is_active,created_at,updated_at,lifecycle_version,archived_at FROM clients",
                )
            assertEquals(1, db.migrate("78"))
            assertEquals(before, tables.associateWith { db.rows("SELECT * FROM $it ORDER BY 1") })
            assertEquals(
                client,
                db.rows(
                    "SELECT id,display_name,is_active,created_at,updated_at,lifecycle_version,archived_at FROM clients",
                ),
            )
            assertEquals("1", db.scalar("SELECT target_mapping_epoch FROM clients WHERE id=101"))
            (BindingTestFixture.TABLES + "target_client_mapping_epochs").forEach {
                assertEquals("0", db.scalar("SELECT COUNT(*) FROM $it"))
            }
            assertEquals(0, db.migrate("78"))
            db.execute(
                "DELETE FROM client_club_grants",
                "DELETE FROM client_memberships",
                "DELETE FROM client_clubs",
                "DELETE FROM clients WHERE id=101",
                "INSERT INTO clients(id,display_name,is_active) VALUES (101,'Recreated',TRUE)",
            )
            assertEquals("4", db.scalar("SELECT target_mapping_epoch FROM clients WHERE id=101"))
        }
    }
}
