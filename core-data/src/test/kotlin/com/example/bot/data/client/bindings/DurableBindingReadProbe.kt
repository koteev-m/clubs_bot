package com.example.bot.data.client.bindings

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource

/** Test-only statement boundary probe, never changes SQL or creates an ACTIVE record. */
internal class DurableBindingReadProbe(
    private val source: DataSource,
    private val held: Connection? = null,
    private val afterQuery: (ResultSet) -> Unit = {},
) : DataSource by source {
    override fun getConnection(): Connection {
        val connection = held ?: source.connection
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            when {
                method.name == "close" && held != null -> null
                method.name == "prepareStatement" ->
                    statement(
                        invokeReadProbe(connection, method, args) as PreparedStatement,
                    )
                else -> invokeReadProbe(connection, method, args)
            }
        } as Connection
    }

    private fun statement(statement: PreparedStatement): PreparedStatement =
        Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
        ) { _, method, args ->
            val result = invokeReadProbe(statement, method, args)
            if (method.name == "executeQuery") afterQuery(result as ResultSet)
            result
        } as PreparedStatement
}

private fun invokeReadProbe(
    target: Any,
    method: Method,
    args: Array<out Any?>?,
): Any? =
    try {
        method.invoke(target, *args.orEmpty())
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }
