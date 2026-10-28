package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal class OnboardingSql(
    val connection: Connection,
) {
    fun rows(
        sql: String,
        vararg args: Any?,
    ): List<DbRow> = rowsWithArguments(sql, args.asList())

    fun rowsWithArguments(
        sql: String,
        args: List<Any?>,
    ): List<DbRow> =
        connection.prepareStatement(sql).use { statement ->
            statement.bind(args)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            (1..result.metaData.columnCount).associate {
                                result.metaData.getColumnLabel(it) to
                                    result.getString(it)
                            },
                        )
                    }
                }
            }
        }

    fun row(
        sql: String,
        vararg args: Any?,
    ): DbRow? = rows(sql, *args).singleOrNull()

    fun update(
        sql: String,
        vararg args: Any?,
    ): Int = updateWithArguments(sql, args.asList())

    fun updateWithArguments(
        sql: String,
        args: List<Any?>,
    ): Int =
        connection.prepareStatement(sql).use { statement ->
            statement.bind(args)
            statement.executeUpdate()
        }

    fun insert(
        sql: String,
        vararg args: Any?,
    ): Long = insertWithArguments(sql, args.asList())

    fun insertWithArguments(
        sql: String,
        args: List<Any?>,
    ): Long =
        connection.prepareStatement(sql, arrayOf("id")).use { statement ->
            statement.bind(args)
            statement.executeUpdate()
            statement.generatedKeys.use { result ->
                check(result.next()) { "Missing generated resource identity" }
                result.getLong(1)
            }
        }

    fun user(telegramId: Long): DbRow? = row("SELECT * FROM users WHERE telegram_user_id=?", telegramId)

    fun lockActor(userId: Long) {
        // PostgreSQL FK checks take KEY SHARE on users.
        // NO KEY UPDATE serializes commands without blocking those checks.
        val lock = if (connection.metaData.databaseProductName == "PostgreSQL") " FOR NO KEY UPDATE" else " FOR UPDATE"
        row("SELECT id FROM users WHERE id=?" + lock, userId) ?: fail("not_found")
    }

    fun provision(actor: OnboardingActor): Long {
        user(actor.telegramUserId)?.let { return it.long("id") }
        val savepoint = connection.setSavepoint()
        return try {
            insert(
                "INSERT INTO users(telegram_user_id,username,display_name) VALUES (?,?,?)",
                actor.telegramUserId,
                actor.username?.take(IDENTITY_DISPLAY_LIMIT),
                actor.displayName?.take(IDENTITY_DISPLAY_LIMIT),
            )
        } catch (failure: java.sql.SQLException) {
            connection.rollback(savepoint)
            if (failure.sqlState != "23505") throw failure
            user(actor.telegramUserId)?.long("id") ?: throw failure
        } finally {
            connection.releaseSavepoint(savepoint)
        }
    }

    fun audit(
        actorId: Long,
        action: String,
        clientId: Long,
        resourceId: Long,
        version: Long,
        now: Instant,
        correlation: String,
    ) {
        val metadata =
            buildJsonObject {
                put("clientId", clientId.toString())
                put("version", version.toString())
                put("commandCorrelation", correlation)
            }
        update(
            "INSERT INTO audit_log(created_at,actor_user_id,entity_type,entity_id,action,fingerprint,metadata_json) " +
                "VALUES (?,?,?,?,?,?,?)",
            now,
            actorId,
            "CLIENT_ONBOARDING",
            resourceId,
            action,
            "client-" + UUID.randomUUID(),
            metadata.toString(),
        )
    }

    private fun PreparedStatement.bind(args: List<Any?>) {
        args.forEachIndexed { index, value ->
            setObject(index + 1, if (value is Instant) value.atOffset(ZoneOffset.UTC) else value)
        }
    }
}

internal class OnboardingSession(
    val sql: OnboardingSql,
    val actor: OnboardingActor,
    val userId: Long?,
    val ownerId: Long?,
    private val clock: Clock,
    val correlation: String = "",
) {
    // Authorization may wait for database locks. Read time at the check, never before the wait.
    val now: Instant get() = clock.instant()

    fun owner() {
        if (ownerId == null || ownerId <= 0 || actor.telegramUserId != ownerId) fail("forbidden")
    }

    fun client(
        id: Long,
        lock: Boolean = false,
    ): DbRow = sql.row("SELECT * FROM clients WHERE id=?" + if (lock) " FOR UPDATE" else "", id) ?: fail("not_found")

    fun member(
        clientId: Long,
        memberId: Long = userId ?: fail("not_found"),
        manage: Boolean = true,
    ): DbRow {
        if (!client(clientId).active()) fail("not_found")
        val member =
            sql
                .row("SELECT * FROM client_memberships WHERE client_id=? AND user_id=?", clientId, memberId)
                ?.takeIf { it.active() } ?: fail("not_found")
        if (manage &&
            sql.row(
                "SELECT * FROM client_membership_grants WHERE client_id=? AND user_id=? AND " +
                    "permission_code=? AND is_active=TRUE",
                clientId,
                memberId,
                MANAGE,
            ) == null
        ) {
            fail("not_found")
        }
        return member
    }

    fun version(
        row: DbRow,
        body: JsonObject,
    ) {
        if (body.id("expectedVersion") != row.long("version")) fail("version_conflict")
    }

    fun audit(
        action: String,
        clientId: Long,
        resourceId: Long,
        version: Long,
    ) = sql.audit(checkNotNull(userId), action, clientId, resourceId, version, now, correlation)
}

private const val IDENTITY_DISPLAY_LIMIT = 255
