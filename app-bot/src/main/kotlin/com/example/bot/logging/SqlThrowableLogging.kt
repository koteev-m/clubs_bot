package com.example.bot.logging

import mu.KLogger
import org.slf4j.Logger
import java.sql.SQLException
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

internal data class SafeSqlFailure(
    val sqlState: String,
    val exceptionClass: String,
)

internal fun Throwable.safeSqlFailureOrNull(): SafeSqlFailure? {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val pending = ArrayDeque<Throwable>()
    pending.add(this)

    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!visited.add(current)) continue

        if (current is SQLException) {
            return SafeSqlFailure(
                sqlState = current.sqlState.safeSqlState(),
                exceptionClass = current.javaClass.name.safeExceptionClass(),
            )
        }

        current.cause?.let(pending::addLast)
        current.suppressed.forEach(pending::addLast)
    }

    return null
}

internal fun KLogger.warnSqlSafe(
    throwable: Throwable,
    message: () -> String,
) {
    val sqlFailure = throwable.safeSqlFailureOrNull()
    if (sqlFailure == null) {
        warn(throwable, message)
    } else {
        warn {
            "${message()} sqlState=${sqlFailure.sqlState} cause=${sqlFailure.exceptionClass}"
        }
    }
}

internal fun KLogger.errorSqlSafe(
    throwable: Throwable,
    message: () -> String,
) {
    val sqlFailure = throwable.safeSqlFailureOrNull()
    if (sqlFailure == null) {
        error(throwable, message)
    } else {
        error {
            "${message()} sqlState=${sqlFailure.sqlState} cause=${sqlFailure.exceptionClass}"
        }
    }
}

internal fun Logger.errorSqlSafe(
    message: String,
    argument: Any,
    throwable: Throwable,
) {
    val sqlFailure = throwable.safeSqlFailureOrNull()
    if (sqlFailure == null) {
        error(message, argument, throwable)
    } else {
        error(
            "$message sqlState={} cause={}",
            argument,
            sqlFailure.sqlState,
            sqlFailure.exceptionClass,
        )
    }
}

private fun String?.safeSqlState(): String =
    this
        ?.takeIf { state -> SQL_STATE_PATTERN.matches(state) }
        ?: "unknown"

private fun String.safeExceptionClass(): String =
    takeIf { className ->
        className.length <= MAX_EXCEPTION_CLASS_LENGTH && EXCEPTION_CLASS_PATTERN.matches(className)
    } ?: SQLException::class.java.name

private const val MAX_EXCEPTION_CLASS_LENGTH = 160
private val SQL_STATE_PATTERN = Regex("[0-9A-Z]{5}")
private val EXCEPTION_CLASS_PATTERN = Regex("[A-Za-z0-9_.$]+")
