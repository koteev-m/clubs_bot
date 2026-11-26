package com.example.bot.data.client.onboarding

import com.example.bot.data.client.assignments.ASSIGN_CLUB_MANAGER
import com.example.bot.data.client.assignments.READ_REPORTS
import com.example.bot.data.client.assignments.REVOKE_CLUB_MANAGER
import com.example.bot.data.client.assignments.managerPermissions
import com.example.bot.data.client.assignments.managerRoleCodes
import com.example.bot.data.client.assignments.targetManagerTuple
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal class OnboardingSql(
    val connection: Connection,
) {
    private val hasBusinessOwnershipSchema by lazy {
        val names =
            rows(
                "SELECT LOWER(TABLE_NAME) AS name FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=CURRENT_SCHEMA " +
                    "AND LOWER(TABLE_NAME) IN ('client_business_owners','client_business_clubs'," +
                    "'flyway_schema_history')",
            ).map { it["name"] }.toSet()
        when (names.count { it == "client_business_owners" || it == "client_business_clubs" }) {
            2 -> true
            1 -> fail("invalid_transition")
            else -> {
                // Read-only historical fixtures can predate Flyway or V080. Missing migrated tables are an error.
                if ("flyway_schema_history" in names &&
                    rows("SELECT version FROM flyway_schema_history WHERE success=TRUE AND version IS NOT NULL")
                        .any { (it["version"]?.substringBefore('.')?.toIntOrNull() ?: Int.MAX_VALUE) >= 80 }
                ) {
                    fail("invalid_transition")
                }
                false
            }
        }
    }

    fun businessOwnershipSchemaPresent(): Boolean = hasBusinessOwnershipSchema

    val managerSchemaPresent: Boolean by lazy {
        if (!businessOwnershipSchemaPresent()) {
            false
        } else {
            val names =
                rows(
                    "SELECT LOWER(TABLE_NAME) AS name FROM INFORMATION_SCHEMA.TABLES " +
                        "WHERE TABLE_SCHEMA=CURRENT_SCHEMA " +
                        "AND LOWER(TABLE_NAME) IN ('client_manager_invitations','client_role_assignments'," +
                        "'client_club_access_state','client_permission_delegations'," +
                        "'client_manager_invitation_fences')",
                ).map { it["name"] }.toSet()
            if ("client_manager_invitations" in names) {
                if (!names.containsAll(MANAGER_FOUNDATION_TABLES)) fail("invalid_transition")
                true
            } else {
                val versions =
                    rows("SELECT version FROM flyway_schema_history WHERE success=TRUE AND version IS NOT NULL")
                if (versions.any { (it["version"]?.substringBefore('.')?.toIntOrNull() ?: Int.MAX_VALUE) >= 81 }) {
                    fail("invalid_transition")
                }
                false
            }
        }
    }

    // Historical fixtures and old pending commands retain their original issuance protocol.
    val directServiceInvitations: Boolean by lazy {
        val present =
            row(
                "SELECT 1 AS present FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=CURRENT_SCHEMA " +
                    "AND LOWER(TABLE_NAME)='client_manager_invitations' AND LOWER(COLUMN_NAME)='acceptance_policy'",
            ) != null
        if (!present &&
            rows("SELECT version FROM flyway_schema_history WHERE success=TRUE AND version IS NOT NULL")
                .any {
                    (it["version"]?.substringBefore('.')?.toIntOrNull() ?: Int.MAX_VALUE) >=
                        DIRECT_INVITATIONS_VERSION
                }
        ) {
            fail("invalid_transition")
        }
        present
    }

    // PostgreSQL retains prepared statements per physical connection. The SELECT text and result
    // types must stay paired across an in-place migration on that connection.
    val clientInvitationSelect: String by lazy {
        val extra =
            if (directServiceInvitations) {
                ",acceptance_policy,purpose,club_id,staff_role,inviter_user_id,bound_user_id"
            } else {
                // V062–V081 fixtures have different optional fields. Select only known, existing names.
                val available =
                    rows(
                        "SELECT LOWER(COLUMN_NAME) AS name FROM INFORMATION_SCHEMA.COLUMNS " +
                            "WHERE TABLE_SCHEMA=CURRENT_SCHEMA AND LOWER(TABLE_NAME)='client_invitations'",
                    ).map { it["name"] }.toSet()
                val optional = listOf("acceptance_policy", "purpose", "club_id", "staff_role", "inviter_user_id")
                optional.filter(available::contains).joinToString("") { ",$it" }
            }
        "SELECT id,client_id,recipient_label,token_hash,status,expires_at,claimant_user_id,verification_code,version" +
            "$extra FROM client_invitations"
    }

    val managerInvitationSelect: String by lazy {
        val extra = if (directServiceInvitations) ",parent_invitation_id,acceptance_policy" else ""
        "SELECT id,client_id,club_id,role_code,policy_version,policy_hash,inviter_user_id,issuer_ref," +
            "recipient_label,token_hash,claimant_user_id,verification_code,claim_valid_until,source_id,status," +
            "version,expires_at$extra FROM client_manager_invitations"
    }

    val networkSettingsAmendmentPresent: Boolean by lazy {
        row(
            "SELECT 1 AS present FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=CURRENT_SCHEMA " +
                "AND LOWER(TABLE_NAME)='client_role_assignments' AND LOWER(COLUMN_NAME)='network_settings_policy_hash'",
        ) != null
    }

    var usedTargetIdentity = false

    // A consumed invitation and its proof must still be live after any blocking database write.
    var invitationDeadline: Instant? = null

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

    /** Preserve a previously acquired client lock when a non-waiting actor probe cannot complete. */
    fun tryLockActor(userId: Long): Boolean {
        val savepoint = connection.setSavepoint()
        val vendor = connection.metaData.databaseProductName
        return try {
            val lock = if (vendor == "PostgreSQL") " FOR NO KEY UPDATE NOWAIT" else " FOR UPDATE NOWAIT"
            row("SELECT id FROM users WHERE id=?" + lock, userId) ?: fail("not_found")
            true
        } catch (failure: SQLException) {
            connection.rollback(savepoint)
            val actorBusy =
                when (vendor) {
                    "PostgreSQL" -> failure.sqlState == "55P03"
                    "H2" -> failure.sqlState == "HYT00" && failure.errorCode == H2_LOCK_TIMEOUT
                    else -> false
                }
            if (actorBusy) {
                false
            } else {
                throw failure
            }
        } finally {
            connection.releaseSavepoint(savepoint)
        }
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
        additionalMetadata: JsonObject = JsonObject(emptyMap()),
    ) {
        val metadata =
            buildJsonObject {
                put("clientId", clientId.toString())
                put("version", version.toString())
                put("commandCorrelation", correlation)
                additionalMetadata.forEach { (key, value) -> put(key, value) }
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

    fun requireUnarchived(clientId: Long) {
        if (client(clientId)["archived_at"] != null) fail("not_found")
    }

    fun member(
        clientId: Long,
        memberId: Long = userId ?: fail("not_found"),
        manage: Boolean = true,
    ): DbRow {
        requireUnarchived(clientId)
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

    fun requireClubGrant(
        clubId: Long,
        writePermission: String? = null,
        lockClient: Boolean = true,
    ): Long {
        val mapping = sql.row("SELECT client_id FROM client_clubs WHERE club_id=?", clubId) ?: fail("not_found")
        val clientId = mapping.long("client_id")
        if (lockClient) client(clientId, lock = true)
        member(clientId, manage = false)
        val permissions = clubPermissions(clientId, clubId)
        if (READ_CLUB !in permissions || (writePermission != null && writePermission !in permissions)) {
            fail("not_found")
        }
        return clientId
    }

    fun hasClubGrant(
        clientId: Long,
        clubId: Long,
        permission: String,
    ): Boolean = permission in clubPermissions(clientId, clubId)

    /** Flat grants retained after explicit compatibility review remain independent sources. */
    fun clubPermissions(
        clientId: Long,
        clubId: Long,
    ): Set<String> {
        val registeredOwner = registeredClubOwner(clientId, clubId)
        val ownerSubject = registeredOwner != null && registeredOwner == userId
        val owner = ownerSubject && isBusinessOwner(clientId, checkNotNull(registeredOwner))
        val target = targetManagerTuple(clientId, clubId)
        if (ownerSubject || target) requireBusinessIdentity()
        val legacy =
            if (target) {
                emptyList()
            } else {
                sql
                    .rows(
                        "SELECT g.permission_code FROM client_club_grants g JOIN client_clubs c " +
                            "ON c.client_id=g.client_id AND c.club_id=g.club_id " +
                            "WHERE g.client_id=? AND g.club_id=? AND g.user_id=? AND g.is_active=TRUE",
                        clientId,
                        clubId,
                        userId,
                    ).map { checkNotNull(it["permission_code"]) }
            }
        return buildSet {
            addAll(legacy)
            if (owner) {
                addAll(listOf(READ_CLUB, READ_MODULES, READ_FINANCE, READ_REPORTS))
                if (sql.managerSchemaPresent) addAll(listOf(ASSIGN_CLUB_MANAGER, REVOKE_CLUB_MANAGER))
            }
            addAll(managerPermissions(clientId, clubId))
        }
    }

    fun requireBusinessIdentity() {
        actor.requireFreshManagerIdentity(now)
        sql.usedTargetIdentity = true
    }

    fun businessRole(
        clientId: Long,
        clubId: Long,
    ): String? {
        if (businessOwnerRef(clientId, clubId) != null) return "BUSINESS_OWNER"
        val roles = managerRoleCodes(clientId, clubId)
        return listOf("NETWORK_MANAGER", "CLUB_MANAGER").firstOrNull(roles::contains)
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

private const val DIRECT_INVITATIONS_VERSION = 82

private const val H2_LOCK_TIMEOUT = 50200

private val MANAGER_FOUNDATION_TABLES =
    setOf(
        "client_role_assignments",
        "client_club_access_state",
        "client_permission_delegations",
        "client_manager_invitation_fences",
    )
