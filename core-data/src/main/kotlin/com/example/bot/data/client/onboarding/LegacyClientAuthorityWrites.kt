package com.example.bot.data.client.onboarding

import java.util.Locale

/** Old flat-grant writers must never modify a subject controlled by the additive role model. */
internal fun OnboardingSession.requireLegacyClubAuthorityWrite(
    clientId: Long,
    clubId: Long,
    targetUserId: Long,
) {
    client(clientId, lock = true)
    if (sql.blocksLegacyClubAuthorityWrite(clientId, clubId, targetUserId)) fail("invalid_transition")
}

/** A membership mutation affects every club and every outgoing delegation of this client/user. */
internal fun OnboardingSession.requireLegacyClientAuthorityWrite(
    clientId: Long,
    targetUserId: Long,
) {
    client(clientId, lock = true)
    if (!sql.roleFoundationSchemaPresent()) return
    if (sql.row(
            "SELECT 1 AS blocked WHERE " +
                "EXISTS (SELECT 1 FROM client_club_access_state WHERE client_id=? AND user_id=? " +
                "AND (mode='TARGET' OR status='DISABLED')) OR " +
                "EXISTS (SELECT 1 FROM client_role_assignments WHERE client_id=? AND user_id=?) OR " +
                "EXISTS (SELECT 1 FROM client_permission_delegations WHERE client_id=? " +
                "AND (user_id=? OR issuer_user_id=?))",
            clientId,
            targetUserId,
            clientId,
            targetUserId,
            clientId,
            targetUserId,
            targetUserId,
        ) != null
    ) {
        fail("invalid_transition")
    }
}

/** Caller holds the mapped client row lock in the same transaction, including legacy promoter writes. */
internal fun OnboardingSql.blocksLegacyClubAuthorityWrite(
    clientId: Long,
    clubId: Long,
    targetUserId: Long,
): Boolean {
    if (!roleFoundationSchemaPresent()) return false
    return row(
        "SELECT 1 AS blocked WHERE " +
            "EXISTS (SELECT 1 FROM client_club_access_state WHERE client_id=? AND club_id=? AND user_id=? " +
            "AND (mode='TARGET' OR status='DISABLED')) OR " +
            "EXISTS (SELECT 1 FROM client_role_assignments WHERE client_id=? AND club_id=? AND user_id=?) OR " +
            "EXISTS (SELECT 1 FROM client_permission_delegations WHERE client_id=? AND club_id=? " +
            "AND (user_id=? OR issuer_user_id=?))",
        clientId,
        clubId,
        targetUserId,
        clientId,
        clubId,
        targetUserId,
        clientId,
        clubId,
        targetUserId,
        targetUserId,
    ) != null
}

/**
 * Historical migration fixtures may run the old contract before V077. Never infer that state from
 * a missing table alone: partial schema or a successful V077 followed by missing tables is an error.
 * Inspect only the connection's current schema; tables in another schema are not authority evidence.
 */
internal fun OnboardingSql.roleFoundationSchemaPresent(): Boolean {
    val schema = connection.schema ?: fail("invalid_transition")
    val metadata = connection.metaData

    fun identifier(value: String): String =
        when {
            metadata.storesUpperCaseIdentifiers() -> value.uppercase(Locale.ROOT)
            metadata.storesLowerCaseIdentifiers() -> value.lowercase(Locale.ROOT)
            else -> value
        }
    val expected = ROLE_FOUNDATION_TABLES.map(::identifier).toSet()
    val history = identifier("flyway_schema_history")
    val present = mutableSetOf<String>()
    metadata.getTables(connection.catalog, null, "%", arrayOf("TABLE", "BASE TABLE")).use { tables ->
        while (tables.next()) {
            if (tables.getString("TABLE_SCHEM") == schema) present += tables.getString("TABLE_NAME")
        }
    }
    val count = expected.count { it in present }
    if (count == expected.size) return true
    if (count != 0 || history !in present) fail("invalid_transition")
    val versions = rows("SELECT version FROM flyway_schema_history WHERE success=TRUE AND version IS NOT NULL")
    if (versions.isEmpty() ||
        versions.any {
            (it["version"]?.substringBefore('.')?.toIntOrNull() ?: Int.MAX_VALUE) >= ROLE_FOUNDATION_VERSION
        }
    ) {
        fail("invalid_transition")
    }
    return false
}

private const val ROLE_FOUNDATION_VERSION = 77
private val ROLE_FOUNDATION_TABLES =
    setOf("client_club_access_state", "client_role_assignments", "client_permission_delegations")
