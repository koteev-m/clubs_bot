package com.example.bot.data.client.bindings

import com.example.bot.data.client.assignments.AssignmentTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

@Tag("it")
internal class BindingSearchPathPostgresIT {
    @Test fun `TEMP clients cannot intercept canonical epoch changes`() {
        BindingTestFixture(AssignmentTestDatabase.postgres()).use { f ->
            val before = f.epoch()
            f.database.dataSource.connection.use { connection ->
                val schema = connection.schemaName()
                connection.autoCommit = false
                connection.createStatement().use { s ->
                    s.execute(
                        "CREATE TEMP TABLE clients(id BIGINT PRIMARY KEY,target_mapping_epoch BIGINT NOT NULL) ON COMMIT DROP",
                    )
                    s.execute("INSERT INTO pg_temp.clients VALUES(101,1000)")
                    s.execute("DELETE FROM \"$schema\".client_clubs WHERE club_id=1001")
                    s.execute(
                        "INSERT INTO \"$schema\".client_clubs(client_id,club_id,created_at) VALUES(101,1001,'2026-11-11T00:00:00Z')",
                    )
                    assertEquals(
                        1000L,
                        connection.number("SELECT target_mapping_epoch FROM pg_temp.clients WHERE id=101"),
                    )
                }
                connection.commit()
            }
            println(
                "EPOCH_SHADOW before=$before projection=${f.epoch()} head=${f.database.scalar(
                    "SELECT epoch FROM target_client_mapping_epochs WHERE client_id=101",
                )}",
            )
            assertEquals(before + 2, f.epoch())
            assertEquals(
                (before + 2).toString(),
                f.database.scalar("SELECT epoch FROM target_client_mapping_epochs WHERE client_id=101"),
            )
        }
    }

    @Test fun `all V078 V079 functions bind trusted schema and ignore hostile objects`() =
        runBlocking {
            BindingTestFixture(AssignmentTestDatabase.postgres()).use { f ->
                val create = f.create()
                val root = f.issue(create, "path-root")
                val evil = "binding_evil_" + UUID.randomUUID().toString().replace("-", "")
                f.database.dataSource.connection.use { c ->
                    val schema = c.schemaName()
                    c.createStatement().use { s -> s.execute("CREATE SCHEMA $evil") }
                    try {
                        c.autoCommit = false
                        c.createStatement().use { s ->
                            val tableNames =
                                listOf(
                                    "clients",
                                    "target_client_mapping_epochs",
                                    "target_bot_connections",
                                    "target_bot_bindings",
                                    "target_credential_materials",
                                )
                            for (table in tableNames) {
                                s.execute(
                                    "CREATE TEMP TABLE $table (LIKE \"$schema\".$table INCLUDING DEFAULTS) ON COMMIT DROP",
                                )
                                s.execute("INSERT INTO pg_temp.$table SELECT * FROM \"$schema\".$table")
                                s.execute("CREATE TABLE $evil.$table (LIKE \"$schema\".$table INCLUDING DEFAULTS)")
                                s.execute("INSERT INTO $evil.$table SELECT * FROM \"$schema\".$table")
                            }
                            for (name in listOf(
                                "target_epoch_next",
                                "target_child_root_lock",
                                "target_child_root_bump",
                            )) {
                                val signature = if (name == "target_epoch_next") "BIGINT,BIGINT" else "UUID"
                                val returns =
                                    when (name) {
                                        "target_epoch_next" -> "BIGINT"
                                        "target_child_root_lock" -> "UUID"
                                        else -> "VOID"
                                    }
                                s.execute(
                                    "CREATE FUNCTION $evil.$name($signature) RETURNS $returns LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''shadow_called''; END'",
                                )
                            }
                            s.execute(
                                "CREATE FUNCTION $evil.unnest(BIGINT[]) RETURNS SETOF BIGINT LANGUAGE SQL AS 'SELECT 999::BIGINT'",
                            )
                            s.execute(
                                "CREATE FUNCTION $evil.bad_add(BIGINT,BIGINT) RETURNS BIGINT LANGUAGE SQL AS 'SELECT 999::BIGINT'",
                            )
                            s.execute("CREATE OPERATOR $evil.+ (FUNCTION=$evil.bad_add,LEFTARG=BIGINT,RIGHTARG=BIGINT)")
                            s.execute("SET LOCAL search_path TO $evil, pg_temp, pg_catalog")
                            s.execute("DELETE FROM \"$schema\".client_clubs WHERE club_id=1001")
                            s.execute("INSERT INTO \"$schema\".client_clubs(client_id,club_id) VALUES (101,1001)")
                            s.execute(
                                "UPDATE \"$schema\".target_bot_bindings SET status='DISABLED' WHERE id='${root.fence.bindingId}'",
                            )
                            for (prefix in listOf("pg_temp", evil)) {
                                assertEquals(
                                    3L,
                                    c.number("SELECT target_mapping_epoch FROM $prefix.clients WHERE id=101"),
                                )
                                assertEquals(
                                    3L,
                                    c.number(
                                        "SELECT epoch FROM $prefix.target_client_mapping_epochs WHERE client_id=101",
                                    ),
                                )
                                assertEquals(
                                    1L,
                                    c.number(
                                        "SELECT generation FROM $prefix.target_bot_connections WHERE id='${root.rootId}'",
                                    ),
                                )
                            }
                            assertEquals(
                                5L,
                                c.number("SELECT target_mapping_epoch FROM \"$schema\".clients WHERE id=101"),
                            )
                            assertEquals(
                                5L,
                                c.number(
                                    "SELECT epoch FROM \"$schema\".target_client_mapping_epochs WHERE client_id=101",
                                ),
                            )
                            s
                                .executeQuery(
                                    "SELECT p.proname,p.prosecdef,p.proconfig::text FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='$schema' AND p.proname LIKE 'target_%'",
                                ).use { r ->
                                    var count = 0
                                    while (r.next()) {
                                        count++
                                        assertFalse(r.getBoolean(2))
                                        assertEquals("{\"search_path=pg_catalog, $schema, pg_temp\"}", r.getString(3))
                                    }
                                    assertEquals(8, count)
                                }
                        }
                        c.commit()
                    } finally {
                        c.rollback()
                        c.autoCommit = true
                        c.createStatement().use { it.execute("DROP SCHEMA $evil CASCADE") }
                    }
                }
                assertEquals(5L, f.epoch())
                assertEquals(
                    BindingResolutionStatus.COMPLETED_STALE,
                    f.repository.resolve(BindingTestFixture.EVIDENCE, create, "path-root").status,
                )
                assertEquals(
                    root.fence.generation + 1,
                    f.repository
                        .read(BindingTestFixture.EVIDENCE, root.rootId)
                        ?.fence
                        ?.generation,
                )
            }
        }

    @Test fun `material and incarnation guards ignore TEMP status lies`() =
        runBlocking {
            BindingTestFixture(AssignmentTestDatabase.postgres()).use { f ->
                val first = f.issue()
                val reference = f.attach(first)
                val selected = f.issue(reference)
                val retired = f.issue(BindingCommand.RetireMaterial(first.rootId, selected.fence, reference.materialId))
                val revoked = f.issue(BindingCommand.State(first.rootId, retired.fence, BindingState.REVOKED))
                val next =
                    f.issue(
                        BindingCommand.Replace(first.rootId, revoked.fence, UUID.randomUUID(), UUID.randomUUID()),
                    )
                val terminal = f.issue(BindingCommand.State(first.rootId, next.fence, BindingState.REVOKED))
                val audit = f.database.scalar("SELECT MIN(id) FROM audit_log")
                val before = f.snapshot()
                f.database.dataSource.connection.use { c ->
                    val schema = c.schemaName()
                    c.autoCommit = false
                    try {
                        c.createStatement().use { s ->
                            for (table in listOf("target_credential_materials", "target_bot_bindings")) {
                                s.execute(
                                    "CREATE TEMP TABLE $table (LIKE \"$schema\".$table INCLUDING DEFAULTS) ON COMMIT DROP",
                                )
                                s.execute("INSERT INTO pg_temp.$table SELECT * FROM \"$schema\".$table")
                            }
                            s.execute("UPDATE pg_temp.target_credential_materials SET status='AVAILABLE'")
                            s.execute("UPDATE pg_temp.target_bot_bindings SET status='PENDING'")
                            val statements =
                                listOf(
                                    "INSERT INTO \"$schema\".target_credential_revisions(id,root_id,material_id," +
                                        "revision," +
                                        "verified_bot_id,attestation_id,created_audit_id) " +
                                        "VALUES ('${UUID.randomUUID()}','${first.rootId}','${reference.materialId}',2,${first.botId},'${UUID.randomUUID()}',$audit)",
                                    "UPDATE \"$schema\".target_bot_connections SET current_binding_id='${first.fence.bindingId}',version=version+1,generation=generation+1 WHERE id='${first.rootId}'",
                                )
                            for (sql in statements) {
                                val point = c.setSavepoint()
                                assertThrows<SQLException> { s.execute(sql) }
                                c.rollback(point)
                            }
                        }
                        c.commit()
                    } finally {
                        c.rollback()
                    }
                }
                assertEquals(before, f.snapshot())
                assertEquals(terminal, f.repository.read(BindingTestFixture.EVIDENCE, first.rootId))
            }
        }

    @Test fun `TEMP attack is fenced for an ordinary invoker after populated upgrade`() {
        AssignmentTestDatabase.postgres("77").use { db ->
            db.execute(
                "INSERT INTO users(id,telegram_user_id) VALUES (78001,878001)",
                "INSERT INTO clients(id,display_name,is_active) VALUES (101,'Populated',TRUE)",
                "INSERT INTO clubs(id,name,timezone) VALUES (1001,'Populated','UTC')",
                "INSERT INTO client_clubs(client_id,club_id) VALUES (101,1001)",
                "INSERT INTO client_memberships(client_id,user_id,is_active) VALUES (101,78001,FALSE)",
            )
            assertEquals(1, db.migrate("78"))
            val role = "binding_dml_" + UUID.randomUUID().toString().replace("-", "")
            db.dataSource.connection.use { admin ->
                val schema = admin.schemaName()
                admin.createStatement().use { s ->
                    s.execute("CREATE ROLE $role NOSUPERUSER NOCREATEDB NOCREATEROLE NOLOGIN")
                    s.execute("GRANT USAGE ON SCHEMA \"$schema\" TO $role")
                    s.execute(
                        "GRANT SELECT,INSERT,UPDATE,DELETE ON \"$schema\".clients,\"$schema\".client_clubs,\"$schema\".target_client_mapping_epochs TO $role",
                    )
                }
                try {
                    db.invokerMappingAttack(schema, role)
                } finally {
                    admin.dropSyntheticRole(role)
                }
            }
            assertEquals(
                "false",
                db.scalar("SELECT is_active::text FROM client_memberships WHERE client_id=101 AND user_id=78001"),
            )
        }
    }
}

private fun Connection.schemaName(): String =
    createStatement().use { s ->
        s.executeQuery("SELECT pg_catalog.current_schema()").use {
            it.next()
            it.getString(1)
        }
    }

private fun Connection.number(query: String): Long =
    createStatement().use { s ->
        s.executeQuery(query).use {
            it.next()
            it.getLong(1)
        }
    }

private fun AssignmentTestDatabase.invokerMappingAttack(
    schema: String,
    role: String,
) {
    dataSource.connection.use { c ->
        c.autoCommit = false
        c.createStatement().use { s ->
            s.execute("SET LOCAL ROLE $role")
            assertEquals(
                0L,
                c.number(
                    "SELECT count(*) FROM pg_catalog.pg_roles WHERE rolname=current_user AND (rolsuper OR rolcreatedb OR rolcreaterole)",
                ),
            )
            s.execute("CREATE TEMP TABLE clients(id BIGINT,target_mapping_epoch BIGINT) ON COMMIT DROP")
            s.execute("INSERT INTO pg_temp.clients VALUES(101,900)")
            s.execute("SET LOCAL search_path TO pg_temp, pg_catalog")
            s.execute("DELETE FROM \"$schema\".client_clubs WHERE club_id=1001")
            s.execute("INSERT INTO \"$schema\".client_clubs(client_id,club_id) VALUES(101,1001)")
            assertEquals(
                900L,
                c.number("SELECT target_mapping_epoch FROM pg_temp.clients WHERE id=101"),
            )
            assertEquals(
                3L,
                c.number("SELECT target_mapping_epoch FROM \"$schema\".clients WHERE id=101"),
            )
            assertEquals(
                3L,
                c.number(
                    "SELECT epoch FROM \"$schema\".target_client_mapping_epochs WHERE client_id=101",
                ),
            )
        }
        c.commit()
    }
}

private fun Connection.dropSyntheticRole(role: String) {
    createStatement().use { s ->
        s.execute("DROP OWNED BY $role")
        s.execute("DROP ROLE $role")
    }
}
