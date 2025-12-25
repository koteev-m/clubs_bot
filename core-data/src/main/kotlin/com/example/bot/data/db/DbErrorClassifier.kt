package com.example.bot.data.db

import org.postgresql.util.PSQLException
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.sql.SQLTransientException

private const val SQL_STATE_DEADLOCK = "40P01"
private const val SQL_STATE_SERIALIZATION = "40001"
private const val SQL_STATE_CONSTRAINT_PREFIX = "23"
private const val SQL_STATE_CONNECTION_PREFIX = "08"

enum class DbErrorReason(val label: String) {
    DEADLOCK("deadlock"),
    SERIALIZATION("serialization"),
    CONNECTION("connection"),
    CONSTRAINT("constraint"),
    OTHER("other"),
}

data class DbErrorClassification(
    val retryable: Boolean,
    val reason: DbErrorReason,
    val sqlState: String?,
    val isConnectionIssue: Boolean,
)

object DbErrorClassifier {
    fun classify(error: Throwable): DbErrorClassification {
        val sqlException = error.sqlException()
        val sqlState = sqlException?.sqlState

        val reason = reasonFor(error, sqlState)
        val retryable = when (reason) {
            DbErrorReason.DEADLOCK, DbErrorReason.SERIALIZATION, DbErrorReason.CONNECTION -> true
            else -> false
        }

        val isConnectionIssue = reason == DbErrorReason.CONNECTION

        return DbErrorClassification(
            retryable = retryable,
            reason = reason,
            sqlState = sqlState,
            isConnectionIssue = isConnectionIssue,
        )
    }

    private fun reasonFor(error: Throwable, sqlState: String?): DbErrorReason {
        if (sqlState == SQL_STATE_DEADLOCK) return DbErrorReason.DEADLOCK
        if (sqlState == SQL_STATE_SERIALIZATION) return DbErrorReason.SERIALIZATION
        if (sqlState?.startsWith(SQL_STATE_CONSTRAINT_PREFIX) == true) return DbErrorReason.CONSTRAINT
        if (sqlState?.startsWith(SQL_STATE_CONNECTION_PREFIX) == true) return DbErrorReason.CONNECTION

        return when (error) {
            is SQLTransientConnectionException, is SQLTransientException -> DbErrorReason.CONNECTION
            is PSQLException ->
                if (error.sqlState?.startsWith(SQL_STATE_CONNECTION_PREFIX) == true) DbErrorReason.CONNECTION
                else DbErrorReason.OTHER
            else -> DbErrorReason.OTHER
        }
    }
}

fun Throwable.isUniqueViolation(): Boolean =
    generateSequence(this) { it.cause }
        .filterIsInstance<SQLException>()
        .any { it.sqlState == "23505" }

fun Throwable.isRetryLimitExceeded(): Boolean {
    val state =
        generateSequence(this) { it.cause }
            .filterIsInstance<SQLException>()
            .firstOrNull()
            ?.sqlState
    return state == "40001" || state == "40P01"
}

private fun Throwable.sqlException(): SQLException? =
    generateSequence(this) { it.cause }
        .filterIsInstance<SQLException>()
        .firstOrNull()
