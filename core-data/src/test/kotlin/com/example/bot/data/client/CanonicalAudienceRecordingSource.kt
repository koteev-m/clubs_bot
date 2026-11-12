package com.example.bot.data.client

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import javax.sql.DataSource

internal class CanonicalAudienceRecordingSource(
    private val source: DataSource,
) : DataSource by source {
    var connections = 0
    var rollbacks = 0
    var afterQuery: () -> Unit = {}
    var queryFailure: Exception? = null
    var rollbackFailure: Exception? = null
    val queries = mutableListOf<String>()
    val readOnly = mutableListOf<Boolean>()
    val autoCommit = mutableListOf<Boolean>()
    val isolation = mutableListOf<Int>()

    override fun getConnection(): Connection {
        connections++
        val connection = source.connection
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "setReadOnly" -> readOnly += args!![0] as Boolean
                "setAutoCommit" -> autoCommit += args!![0] as Boolean
                "setTransactionIsolation" -> isolation += args!![0] as Int
                "rollback" -> {
                    rollbacks++
                    rollbackFailure?.let { throw it }
                }
                "prepareStatement" -> {
                    queries += args!![0] as String
                    return@newProxyInstance statement(invoke(connection, method, args) as PreparedStatement)
                }
            }
            invoke(connection, method, args)
        } as Connection
    }

    private fun statement(statement: PreparedStatement): PreparedStatement =
        Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
        ) { _, method, args ->
            if (method.name == "executeQuery") queryFailure?.let { throw it }
            val result = invoke(statement, method, args)
            if (method.name == "executeQuery") afterQuery()
            result
        } as PreparedStatement

    private fun invoke(
        target: Any,
        method: Method,
        args: Array<out Any?>?,
    ): Any? =
        try {
            method.invoke(target, *args.orEmpty())
        } catch (exception: InvocationTargetException) {
            throw exception.targetException
        }
}
