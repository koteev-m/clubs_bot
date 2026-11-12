package com.example.bot.data.client.bindings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

internal data class HostileControlContext(
    val role: String,
    val path: String,
)

/** Only the fixture owner provisions privileges. The attack itself has no privileged DDL or superuser access. */
internal fun hostileControlOperator(
    f: ControlHeadFixture,
    body: String,
    block: (Connection, HostileControlContext) -> Unit,
) {
    val role = "control_operator_" + UUID.randomUUID().toString().replace("-", "")
    val evil = "control_evil_" + UUID.randomUUID().toString().replace("-", "")
    val db = f.binding.database
    val schema = f.schema.quoted
    val context = HostileControlContext(role, "$evil, pg_catalog, $schema")
    db.execute(
        "CREATE ROLE $role NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE",
        "CREATE SCHEMA $evil AUTHORIZATION $role",
        "GRANT USAGE ON SCHEMA $schema TO $role",
        "GRANT SELECT,INSERT,UPDATE ON $schema.target_maintenance_control_heads TO $role",
        "GRANT SELECT,UPDATE ON $schema.target_bot_connections TO $role",
        "GRANT SELECT,INSERT ON $schema.target_maintenance_publications,$schema.audit_log TO $role",
        "GRANT USAGE ON ALL SEQUENCES IN SCHEMA $schema TO $role",
    )
    try {
        db.dataSource.connection.use { c ->
            try {
                installHostileControlOperator(c, context, evil, body)
                block(c, context)
            } finally {
                resetHostileControlConnection(c, schema)
            }
        }
    } finally {
        db.execute("DROP OWNED BY $role CASCADE", "DROP ROLE $role")
    }
}

private fun resetHostileControlConnection(
    c: Connection,
    schema: String,
) {
    if (!c.autoCommit) c.rollback()
    c.autoCommit = true
    c.createStatement().use { s ->
        s.execute("RESET ROLE")
        s.execute("SET search_path=$schema,public")
        s.execute("DISCARD TEMP")
    }
}

private fun installHostileControlOperator(
    c: Connection,
    context: HostileControlContext,
    evil: String,
    body: String,
) {
    c.createStatement().use { s ->
        s.execute("SET ROLE ${context.role}")
        s.execute(
            "CREATE FUNCTION $evil.hostile_equal(lhs pg_catalog.uuid,rhs pg_catalog.uuid) " +
                "RETURNS BOOLEAN VOLATILE LANGUAGE plpgsql AS $$ BEGIN $body END $$",
        )
        s.execute(
            "CREATE OPERATOR $evil.= (LEFTARG=pg_catalog.uuid,RIGHTARG=pg_catalog.uuid," +
                "FUNCTION=$evil.hostile_equal)",
        )
        s.execute("SET search_path=${context.path}")
        s
            .executeQuery(
                "SELECT rolsuper,rolcreaterole,rolcreatedb FROM pg_catalog.pg_roles " +
                    "WHERE rolname=current_user",
            ).use { r ->
                assertTrue(r.next())
                listOf("rolsuper", "rolcreaterole", "rolcreatedb").forEach { assertFalse(r.getBoolean(it)) }
            }
    }
}

/** Driver-boundary fault proves independent persisted/RETURNING identity checks and transaction rollback. */
internal class ControlIdentityProbe(
    private val source: DataSource,
    private val updateOnly: Boolean,
) : DataSource by source {
    override fun getConnection(): Connection {
        val c = source.connection
        return controlProxy(c, Connection::class.java) { method, args ->
            val result = invokeControlProbe(c, method, args)
            if (method.name == "prepareStatement" &&
                (args!![0] as String).startsWith(if (updateOnly) "UPDATE " else "SELECT id,")
            ) {
                val statement = result as PreparedStatement
                controlProxy(statement, PreparedStatement::class.java) { call, values ->
                    val rows = invokeControlProbe(statement, call, values)
                    if (call.name == "executeQuery") corruptIdentity(rows as ResultSet) else rows
                }
            } else {
                result
            }
        }
    }
}

private fun corruptIdentity(rows: ResultSet): ResultSet =
    controlProxy(rows, ResultSet::class.java) { method, args ->
        if (method.name == "getObject" && args?.firstOrNull() == "id") {
            UUID.randomUUID()
        } else {
            invokeControlProbe(rows, method, args)
        }
    }

private fun <T> controlProxy(
    target: T,
    type: Class<T>,
    call: (Method, Array<out Any?>?) -> Any?,
): T =
    type.cast(
        Proxy.newProxyInstance(target!!::class.java.classLoader, arrayOf(type)) { _, method, args ->
            call(method, args)
        },
    )

private fun invokeControlProbe(
    target: Any,
    method: Method,
    args: Array<out Any?>?,
): Any? =
    try {
        method.invoke(target, *args.orEmpty())
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }
