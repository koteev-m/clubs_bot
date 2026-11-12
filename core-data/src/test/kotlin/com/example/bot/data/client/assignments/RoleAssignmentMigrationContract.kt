package com.example.bot.data.client.assignments

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID

/** Shared assertions are executed against both actual migration dialects. */
internal abstract class RoleAssignmentMigrationContract {
    protected abstract fun database(target: String? = null): AssignmentTestDatabase

    @Test
    fun `populated V076 upgrade preserves every existing row and creates no authority`() {
        database("76").use { database ->
            seedExisting(database)
            database.execute(
                "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) " +
                    "VALUES (101,77002,1001,'client.club.read',TRUE)," +
                    "(101,77002,1001,'client.club.profile.write',FALSE)",
                "INSERT INTO user_roles(user_id,role_code,scope_type,scope_club_id) " +
                    "VALUES (77002,'ENTRY_MANAGER','CLUB',1001)",
            )
            val before = snapshot(database)
            assertEquals(1, database.migrate("77"))
            assertEquals(before, snapshot(database).filterKeys { it in before })
            assertEquals(NEW_TABLES, snapshot(database).keys - before.keys)
            NEW_TABLES.forEach { assertEquals("0", database.scalar("SELECT COUNT(*) FROM $it")) }
            assertEquals(0, database.migrate("77"))
            assertEquals(before, snapshot(database).filterKeys { it in before })
        }
    }

    @Test
    fun `pending subject can precede membership but must reference an exact user and club mapping`() {
        database().use { database ->
            seedExisting(database)
            database.execute("INSERT INTO client_club_access_state(client_id,club_id,user_id) VALUES (101,1001,77003)")
            assertEquals("0", database.scalar("SELECT COUNT(*) FROM client_memberships WHERE user_id=77003"))
            assertEquals(
                listOf(listOf("PENDING", "SHADOW", "1", "1", null)),
                database.rows(
                    "SELECT status,mode,admission_epoch,version,membership_version FROM client_club_access_state",
                ),
            )
            reject(database, "INSERT INTO client_club_access_state(client_id,club_id,user_id) VALUES (102,1001,77002)")
            reject(database, "INSERT INTO client_club_access_state(client_id,club_id,user_id) VALUES (101,1001,999999)")
            reject(database, "UPDATE client_club_access_state SET status='ACTIVE'")
            reject(database, "UPDATE client_club_access_state SET admission_epoch=0")
            reject(database, "UPDATE client_club_access_state SET membership_version=0")
            reject(database, "UPDATE client_club_access_state SET version=0")
            reject(database, "UPDATE client_club_access_state SET mode='GLOBAL'")
            reject(database, "UPDATE client_club_access_state SET revoked_through_invitation_id=-1")
            assertEquals("0", database.scalar("SELECT COUNT(*) FROM client_role_assignments"))
        }
    }

    @Test
    fun `fence rejects partial manifests and missing disable history`() {
        database().use { database ->
            seedExisting(database)
            activeFences(database)
            val subject = " WHERE client_id=101 AND club_id=1001 AND user_id=77002"
            reject(database, "UPDATE client_club_access_state SET compatibility_manifest='[]'$subject")
            reject(database, "UPDATE client_club_access_state SET compatibility_hash='$HASH'$subject")
            reject(
                database,
                "UPDATE client_club_access_state SET compatibility_manifest='[]',compatibility_hash='${"X".repeat(
                    64,
                )}'$subject",
            )
            database.execute(
                "UPDATE client_club_access_state SET compatibility_manifest='[]',compatibility_hash='$HASH'$subject",
            )
            reject(database, "UPDATE client_club_access_state SET status='DISABLED'$subject")
            database.execute(
                "UPDATE client_club_access_state SET status='DISABLED',admission_epoch=2,version=2," +
                    "revoked_at=CURRENT_TIMESTAMP,revoked_by=77001,revoke_reason='Synthetic disable'$subject",
            )
            assertEquals(
                listOf(listOf("DISABLED", "2", "2", "1", "Synthetic disable")),
                database.rows(
                    "SELECT status,admission_epoch,version,membership_version,revoke_reason " +
                        "FROM client_club_access_state$subject",
                ),
            )
            reject(database, "UPDATE client_club_access_state SET status='ACTIVE'$subject")
            assertEquals("0", database.scalar("SELECT COUNT(*) FROM client_club_grants"))
        }
    }

    @Test
    fun `assignments bind membership subject fence receipt and audit without altering old role catalogue`() {
        database().use { database ->
            prepared(database)
            val valid = UUID.randomUUID()
            database.execute(assignment(valid))
            assertEquals("1", database.scalar("SELECT COUNT(*) FROM client_role_assignments"))
            reject(database, assignment(valid))
            reject(database, assignment(overrides = mapOf("client_id" to "102")))
            reject(database, assignment(overrides = mapOf("user_id" to "77003")))
            reject(database, assignment(overrides = mapOf("created_by" to "77002")))
            reject(database, assignment(overrides = mapOf("created_audit_id" to "999999")))
            reject(database, assignment(overrides = mapOf("last_audit_id" to "999999")))
            reject(database, assignment(overrides = mapOf("command_key_hash" to "'${"b".repeat(64)}'")))
            listOf("BUSINESS_OWNER", "GUEST", "OWNER", "MANAGER").forEach { forbidden ->
                reject(database, assignment(overrides = mapOf("role_code" to "'$forbidden'")))
            }
            assertEquals("0", database.scalar("SELECT COUNT(*) FROM roles WHERE code='BUSINESS_OWNER'"))
            assertEquals("0", database.scalar("SELECT COUNT(*) FROM user_roles WHERE user_id IN (77001,77002)"))
            assertEquals("0", database.scalar("SELECT COUNT(*) FROM client_club_grants"))
            reject(database, "DELETE FROM client_memberships WHERE client_id=101 AND user_id=77002")
            reject(database, "DELETE FROM client_commands WHERE actor_user_id=77001")
            reject(database, "DELETE FROM audit_log WHERE id=77001")
        }
    }

    @Test
    fun `policy selector epoch and revoked-source metadata constraints agree across dialects`() {
        database().use { database ->
            prepared(database)
            listOf(
                mapOf("policy_version" to "0"),
                mapOf("policy_hash" to "'${"g".repeat(64)}'"),
                mapOf("admission_epoch" to "0"),
                mapOf("version" to "0"),
                mapOf("selector_kind" to "'GLOBAL'"),
                mapOf("selector_kind" to "'SHIFT_SET'", "selector_json" to "'[]'"),
                mapOf("selector_kind" to "'CLUB'", "selector_json" to "'[1]'"),
                mapOf("status" to "'REVOKED'"),
                mapOf("revoked_at" to "CURRENT_TIMESTAMP"),
                mapOf("issuer_authority_ref" to "' '"),
                mapOf("expires_at" to "CURRENT_TIMESTAMP - INTERVAL '1' DAY"),
            ).forEach { invalid -> reject(database, assignment(overrides = invalid)) }
            val id = UUID.randomUUID()
            database.execute(assignment(id, mapOf("selector_kind" to "'SHIFT_SET'", "selector_json" to "'[11,12]'")))
            database.execute(
                "UPDATE client_role_assignments SET status='REVOKED',version=2,revoked_at=CURRENT_TIMESTAMP," +
                    "revoked_by=77001,revoke_reason='Synthetic revoke' WHERE id='$id'",
            )
            assertEquals(
                listOf(listOf("REVOKED", "2", "1", HASH, "[11,12]", "Synthetic revoke")),
                database.rows(
                    "SELECT status,version,policy_version,policy_hash,selector_json,revoke_reason " +
                        "FROM client_role_assignments WHERE id='$id'",
                ),
            )
            reject(database, "UPDATE client_role_assignments SET status='ACTIVE' WHERE id='$id'")
            // Terminal-state and immutable-policy UPDATE protection belongs to the transactional CAS writer.
            // This migration verifies intact revocation history, not a standalone trigger implementation.
        }
    }

    @Test
    fun `delegations require exact same-club recipient issuer registry and complete revocation history`() {
        database().use { database ->
            prepared(database)
            val recipient = UUID.randomUUID()
            val issuer = UUID.randomUUID()
            database.execute(
                assignment(recipient),
                assignment(issuer, mapOf("user_id" to "77001", "role_code" to "'CLUB_MANAGER'")),
            )
            val valid = UUID.randomUUID()
            database.execute(delegation(valid, recipient, issuer))
            listOf(
                mapOf("client_id" to "102"),
                mapOf("club_id" to "1002"),
                mapOf("user_id" to "77001"),
                mapOf("issuer_user_id" to "77002"),
                mapOf("recipient_assignment_id" to "'${UUID.randomUUID()}'"),
                mapOf("issuer_assignment_id" to "'${UUID.randomUUID()}'"),
                mapOf("permission_code" to "'not.registered'"),
                mapOf("admission_epoch" to "0"),
                mapOf("issuer_admission_epoch" to "0"),
                mapOf("policy_version" to "0"),
                mapOf("policy_hash" to "'invalid'"),
                mapOf("version" to "0"),
                mapOf("expires_at" to "NULL"),
                mapOf("expires_at" to "CURRENT_TIMESTAMP - INTERVAL '1' DAY"),
                mapOf("status" to "'REVOKED'"),
            ).forEach { invalid -> reject(database, delegation(UUID.randomUUID(), recipient, issuer, invalid)) }
            reject(database, "DELETE FROM client_role_assignments WHERE id='$issuer'")
            reject(database, "DELETE FROM client_role_assignments WHERE id='$recipient'")
            database.execute(
                "UPDATE client_permission_delegations SET status='REVOKED',version=2,revoked_at=CURRENT_TIMESTAMP," +
                    "revoked_by=77001,revoke_reason='Synthetic delegation revoke' WHERE id='$valid'",
            )
            assertEquals("REVOKED", database.scalar("SELECT status FROM client_permission_delegations"))
            reject(database, "UPDATE client_permission_delegations SET status='ACTIVE' WHERE id='$valid'")
            assertEquals("0", database.scalar("SELECT COUNT(*) FROM client_club_grants"))
        }
    }

    private fun prepared(database: AssignmentTestDatabase) {
        seedExisting(database)
        activeFences(database)
        database.execute(
            "INSERT INTO client_commands(actor_user_id,connection_key,key_hash,request_hash," +
                "result_type,result_id,client_id) VALUES (77001,'assignment-test','$HASH','$HASH'," +
                "'role_foundation',77002,101)",
        )
    }

    private fun seedExisting(database: AssignmentTestDatabase) {
        database.execute(
            "INSERT INTO users(id,telegram_user_id) VALUES (77001,880077001),(77002,880077002),(77003,880077003)",
            "INSERT INTO clubs(id,name,timezone) VALUES (1001,'Synthetic A','Europe/Moscow')," +
                "(1002,'Synthetic B','Europe/Moscow')",
            "INSERT INTO clients(id,display_name,is_active) VALUES (101,'Synthetic client A',TRUE)," +
                "(102,'Synthetic client B',TRUE)",
            "INSERT INTO client_clubs(client_id,club_id) VALUES (101,1001),(102,1002)",
            "INSERT INTO client_memberships(client_id,user_id,is_active) VALUES (101,77001,TRUE),(101,77002,TRUE)",
            "INSERT INTO audit_log(id,entity_type,entity_id,action,fingerprint) " +
                "VALUES (77001,'CLIENT_ONBOARDING',77002,'role.foundation.synthetic','role-foundation-synthetic')",
        )
    }

    private fun activeFences(database: AssignmentTestDatabase) {
        database.execute(
            "INSERT INTO client_club_access_state(client_id,club_id,user_id,status,mode,membership_version) " +
                "VALUES (101,1001,77001,'ACTIVE','TARGET',1),(101,1001,77002,'ACTIVE','TARGET',1)",
        )
    }

    private fun assignment(
        id: UUID = UUID.randomUUID(),
        overrides: Map<String, String> = emptyMap(),
    ): String =
        insert(
            "client_role_assignments",
            linkedMapOf(
                "id" to "'$id'",
                "client_id" to "101",
                "club_id" to "1001",
                "user_id" to "77002",
                "role_code" to "'FLOOR_MANAGER'",
                "policy_version" to "1",
                "policy_hash" to "'$HASH'",
                "selector_kind" to "'CLUB'",
                "selector_json" to "'[]'",
                "admission_epoch" to "1",
                "issuer_authority_ref" to "'synthetic-provider'",
                "created_by" to "77001",
                "command_connection_key" to "'assignment-test'",
                "command_key_hash" to "'$HASH'",
                "created_audit_id" to "77001",
                "last_audit_id" to "77001",
            ) + overrides,
        )

    private fun delegation(
        id: UUID,
        recipient: UUID,
        issuer: UUID,
        overrides: Map<String, String> = emptyMap(),
    ): String =
        insert(
            "client_permission_delegations",
            linkedMapOf(
                "id" to "'$id'",
                "client_id" to "101",
                "club_id" to "1001",
                "user_id" to "77002",
                "permission_code" to "'client.club.calendar.write'",
                "recipient_assignment_id" to "'$recipient'",
                "issuer_assignment_id" to "'$issuer'",
                "issuer_user_id" to "77001",
                "policy_version" to "1",
                "policy_hash" to "'$HASH'",
                "admission_epoch" to "1",
                "issuer_admission_epoch" to "1",
                "issuer_authority_ref" to "'synthetic-provider'",
                "created_by" to "77001",
                "command_connection_key" to "'assignment-test'",
                "command_key_hash" to "'$HASH'",
                "created_audit_id" to "77001",
                "last_audit_id" to "77001",
                "expires_at" to "CURRENT_TIMESTAMP + INTERVAL '1' DAY",
            ) + overrides,
        )

    private fun insert(
        table: String,
        fields: Map<String, String>,
    ): String = "INSERT INTO $table(${fields.keys.joinToString(",")}) VALUES (${fields.values.joinToString(",")})"

    private fun reject(
        database: AssignmentTestDatabase,
        statement: String,
    ) {
        assertThrows(SQLException::class.java) { database.execute(statement) }
    }

    private fun snapshot(database: AssignmentTestDatabase): Map<String, List<List<String?>>> {
        val tables =
            database.dataSource.connection
                .use { connection ->
                    connection.metaData.getTables(null, connection.schema, "%", null).use { result ->
                        buildList {
                            while (result.next()) {
                                if (result.getString("TABLE_TYPE") in setOf("TABLE", "BASE TABLE")) {
                                    add(result.getString("TABLE_NAME"))
                                }
                            }
                        }
                    }
                }.filterNot { it == "flyway_schema_history" }
        assertFalse(tables.isEmpty())
        return tables.associateWith { table ->
            assertTrue(table.matches(Regex("[A-Za-z0-9_]+")))
            database.rows("SELECT * FROM \"$table\"").sortedBy { it.toString() }
        }
    }

    private companion object {
        val HASH = "a".repeat(64)
        val NEW_TABLES = setOf("client_club_access_state", "client_role_assignments", "client_permission_delegations")
    }
}
