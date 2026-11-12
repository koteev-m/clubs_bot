package com.example.bot.data.client.bindings

import org.h2.api.Trigger
import java.sql.Connection
import java.sql.SQLException
import java.util.Locale

/** Optional H2 parity only; PostgreSQL runs the paired SQL functions. No authority is cached. */
abstract class BindingH2Trigger : Trigger {
    protected lateinit var table: String
    private lateinit var schema: String
    private var upperCaseIdentifiers = false
    private var columns: Map<String, Int> = emptyMap()

    override fun init(
        connection: Connection,
        schemaName: String,
        triggerName: String,
        tableName: String,
        before: Boolean,
        type: Int,
    ) {
        schema = schemaName
        upperCaseIdentifiers = connection.metaData.storesUpperCaseIdentifiers()
        table = tableName.lowercase(Locale.ROOT)
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM ${qualified(tableName)} WHERE 1=0").use { rows ->
                columns =
                    (1..rows.metaData.columnCount).associate {
                        rows.metaData.getColumnLabel(it).lowercase(Locale.ROOT) to it - 1
                    }
            }
        }
    }

    protected fun value(
        row: Array<Any?>,
        name: String,
    ): Any? = row[columns.getValue(name)]

    protected fun number(
        row: Array<Any?>,
        name: String,
    ): Long = (value(row, name) as Number).toLong()

    protected fun set(
        row: Array<Any?>,
        name: String,
        value: Any?,
    ) {
        row[columns.getValue(name)] = value
    }

    protected fun same(
        old: Array<Any?>,
        new: Array<Any?>,
        vararg names: String,
    ): Boolean = names.all { value(old, it) == value(new, it) }

    protected fun qualified(name: String): String {
        val identifier = if (upperCaseIdentifiers) name.uppercase(Locale.ROOT) else name
        return "\"${schema.replace("\"", "\"\"")}\".\"${identifier.replace("\"", "\"\"")}\""
    }

    protected fun status(
        connection: Connection,
        tableName: String,
        id: Any?,
    ): String? =
        connection.prepareStatement("SELECT status FROM ${qualified(tableName)} WHERE id=?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    protected fun reject(): Nothing = throw SQLException("target_binding_metadata_guard", "23514")
}
