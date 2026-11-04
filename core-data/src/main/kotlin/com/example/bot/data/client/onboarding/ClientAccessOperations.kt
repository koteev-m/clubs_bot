package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ClientAccessOperations(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql

    fun search(body: JsonObject): JsonObject {
        session.owner()
        body.only(listOf("cursor", "limit", "status"))
        val limit = pageLimit(body)
        val filter =
            when (body.string("status") ?: "active") {
                "active" -> " AND archived_at IS NULL"
                "archived" -> " AND archived_at IS NOT NULL"
                "all" -> ""
                else -> fail("validation_error")
            }
        val clients =
            sql.rows(
                "SELECT * FROM clients WHERE id>?" + filter + " ORDER BY id LIMIT ?",
                cursor(body),
                limit + 1,
            )
        return page(clients.map(::summary), limit)
    }

    fun platformSummary(): JsonObject {
        session.owner()
        val counts =
            checkNotNull(
                sql.row(
                    "SELECT (SELECT COUNT(*) FROM clients WHERE archived_at IS NULL) AS clients," +
                        "(SELECT COUNT(*) FROM clients WHERE archived_at IS NOT NULL) AS archived_clients," +
                        "(SELECT COUNT(*) FROM client_clubs) AS clubs," +
                        "(SELECT COUNT(*) FROM client_memberships m JOIN clients c ON c.id=m.client_id " +
                        "WHERE m.is_active=TRUE AND c.is_active=TRUE AND c.archived_at IS NULL) AS active_members," +
                        "(SELECT COUNT(*) FROM client_invitations i JOIN clients c ON c.id=i.client_id " +
                        "WHERE c.archived_at IS NULL AND i.status IN ('issued','claimed') AND i.expires_at>?) " +
                        "AS open_invitations",
                    session.now,
                ),
            )
        return buildJsonObject {
            put("clients", counts.long("clients"))
            put("archivedClients", counts.long("archived_clients"))
            put("clubs", counts.long("clubs"))
            put("activeMembers", counts.long("active_members"))
            put("openInvitations", counts.long("open_invitations"))
        }
    }

    fun searchClubs(body: JsonObject): JsonObject {
        session.owner()
        body.only(listOf("cursor", "limit"))
        val limit = pageLimit(body)
        val rows =
            sql.rows(
                "SELECT c.id,c.name,c.is_active,m.client_id,o.display_name," +
                    "o.is_active AS client_active,o.archived_at " +
                    "FROM clubs c JOIN client_clubs m ON m.club_id=c.id JOIN clients o ON o.id=m.client_id " +
                    "WHERE c.id>? ORDER BY c.id LIMIT ?",
                cursor(body),
                limit + 1,
            )
        return page(
            rows.map {
                buildJsonObject {
                    put("id", it.getValue("id"))
                    put("name", it.getValue("name"))
                    put("clientId", it.getValue("client_id"))
                    put("clientName", it.getValue("display_name"))
                    put("published", it.active())
                    put("clientActive", it.active("client_active"))
                    put("clientArchived", it["archived_at"] != null)
                }
            },
            limit,
        )
    }

    fun get(clientId: Long): JsonObject {
        session.owner()
        val client = session.client(clientId)
        return JsonObject(
            summary(client) +
                mapOf(
                    "members" to
                        JsonArray(
                            sql
                                .rows(
                                    "SELECT user_id FROM client_memberships WHERE client_id=? ORDER BY user_id",
                                    clientId,
                                ).map { memberView(clientId, it.long("user_id")) },
                        ),
                    "clubs" to JsonArray(clubs(clientId, null)),
                    "invitations" to InvitationOperations(session).forClient(clientId),
                ),
        )
    }

    fun onboarding(clientId: Long): JsonObject {
        session.member(clientId, manage = false)
        val permission =
            sql.row(
                "SELECT permission_code FROM client_membership_grants " +
                    "WHERE client_id=? AND user_id=? AND permission_code=? AND is_active=TRUE",
                clientId,
                session.userId,
                MANAGE,
            )
        return buildJsonObject {
            put("id", clientId.toString())
            put("name", session.client(clientId).getValue("display_name"))
            put("permissions", JsonArray(if (permission == null) emptyList() else listOf(JsonPrimitive(MANAGE))))
            put("clubs", JsonArray(clubs(clientId, session.userId)))
            ClientLimitOperations(session).view(clientId).forEach { (key, value) -> put(key, value) }
        }
    }

    fun change(
        clientId: Long,
        operation: OnboardingMutation,
        body: JsonObject,
    ): MutationResult {
        session.owner()
        body.only(listOf("userId", "expectedVersion", "permissions", "clubGrants"))
        session.client(clientId, lock = true)
        val userId = body.id("userId")
        val member =
            sql.row("SELECT * FROM client_memberships WHERE client_id=? AND user_id=?", clientId, userId)
                ?: fail("not_found")
        session.version(member, body)
        val restore = operation == OnboardingMutation.MEMBER_RESTORE
        if (restore == member.active()) fail("invalid_transition")
        if (restore) restoreScopes(clientId, userId, body) else disableScopes(clientId, userId)
        sql.update(
            "UPDATE client_memberships SET is_active=?,version=version+1,updated_at=? WHERE client_id=? AND user_id=?",
            restore,
            session.now,
            clientId,
            userId,
        )
        session.audit(
            if (restore) "client.member.restore" else "client.member.revoke",
            clientId,
            userId,
            member.long("version") + 1,
        )
        return MutationResult("member", userId, clientId, memberView(clientId, userId))
    }

    fun memberView(
        clientId: Long,
        userId: Long,
    ): JsonObject {
        session.owner()
        val member =
            sql.row(
                "SELECT m.*,u.telegram_user_id,u.display_name,u.username FROM client_memberships m " +
                    "JOIN users u ON u.id=m.user_id WHERE m.client_id=? AND m.user_id=?",
                clientId,
                userId,
            ) ?: fail("not_found")
        val permissions =
            sql.rows(
                "SELECT permission_code,is_active FROM client_membership_grants WHERE " +
                    "client_id=? AND user_id=? ORDER BY permission_code",
                clientId,
                userId,
            )
        val grants =
            sql.rows(
                "SELECT g.club_id,g.permission_code,g.is_active,c.name FROM client_club_grants g " +
                    "JOIN client_clubs mapping ON mapping.client_id=g.client_id AND mapping.club_id=g.club_id " +
                    "JOIN clubs c ON c.id=g.club_id WHERE g.client_id=? AND g.user_id=? ORDER BY g.club_id",
                clientId,
                userId,
            )
        return buildJsonObject {
            put("userId", userId.toString())
            put("telegramUserId", member.getValue("telegram_user_id"))
            put("displayName", jsonNullable(member["display_name"]))
            put("username", jsonNullable(member["username"]))
            put("active", member.active())
            put("version", member.getValue("version"))
            put(
                "permissions",
                JsonArray(
                    permissions
                        .filter {
                            it.active() && member.active()
                        }.map { JsonPrimitive(it.getValue("permission_code")) },
                ),
            )
            put("restorablePermissions", JsonArray(permissions.map { JsonPrimitive(it.getValue("permission_code")) }))
            put(
                "clubGrants",
                JsonArray(
                    grants.map {
                        buildJsonObject {
                            put("clubId", it.getValue("club_id"))
                            put("clubName", it.getValue("name"))
                            put("permission", it.getValue("permission_code"))
                            put("active", it.active())
                        }
                    },
                ),
            )
        }
    }

    private fun disableScopes(
        clientId: Long,
        userId: Long,
    ) {
        sql.update(
            "UPDATE client_membership_grants SET is_active=FALSE,version=version+1,updated_at=? " +
                "WHERE client_id=? AND user_id=?",
            session.now,
            clientId,
            userId,
        )
        sql.update(
            "UPDATE client_club_grants SET is_active=FALSE,updated_at=? WHERE client_id=? AND user_id=?",
            session.now,
            clientId,
            userId,
        )
    }

    private fun restoreScopes(
        clientId: Long,
        userId: Long,
        body: JsonObject,
    ) {
        session.requireUnarchived(clientId)
        if (!session.client(clientId).active()) fail("not_found")
        val permissions = body["permissions"] as? JsonArray ?: fail("validation_error")
        val grants = body["clubGrants"] as? JsonArray ?: fail("validation_error")
        if (permissions.size > 1 || grants.size > 100) fail("validation_error")
        val requestedPermissions =
            permissions
                .map {
                    (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: fail("validation_error")
                }.toSet()
        if (requestedPermissions.any { it != MANAGE }) fail("validation_error")
        disableScopes(clientId, userId)
        requestedPermissions.forEach { permission ->
            if (sql.update(
                    "UPDATE client_membership_grants SET is_active=TRUE,updated_at=? " +
                        "WHERE client_id=? AND user_id=? AND permission_code=?",
                    session.now,
                    clientId,
                    userId,
                    permission,
                ) != 1
            ) {
                fail("validation_error")
            }
        }
        val requestedGrants =
            grants.map {
                val grant = it as? JsonObject ?: fail("validation_error")
                grant.only(listOf("clubId", "permission"))
                val clubId = grant.id("clubId")
                val permission = grant.string("permission")
                if (permission !in
                    setOf(READ_CLUB, WRITE_PROFILE, WRITE_LAYOUT, WRITE_CALENDAR)
                ) {
                    fail("validation_error")
                }
                clubId to permission
            }
        if (requestedGrants.distinct().size != requestedGrants.size) fail("validation_error")
        requestedGrants.forEach { (clubId, permission) ->
            if (sql.update(
                    "UPDATE client_club_grants SET is_active=TRUE,updated_at=? " +
                        "WHERE client_id=? AND user_id=? AND club_id=? AND permission_code=?",
                    session.now,
                    clientId,
                    userId,
                    clubId,
                    permission,
                ) != 1
            ) {
                fail("validation_error")
            }
        }
    }

    private fun clubs(
        clientId: Long,
        userId: Long?,
    ): List<JsonObject> {
        val rows =
            if (userId == null) {
                sql.rows(
                    "SELECT c.id,c.name FROM client_clubs mapping JOIN clubs c ON c.id=mapping.club_id " +
                        "WHERE mapping.client_id=? ORDER BY c.id",
                    clientId,
                )
            } else {
                sql.rows(
                    "SELECT c.id,c.name FROM client_clubs mapping JOIN clubs c ON c.id=mapping.club_id " +
                        "JOIN client_club_grants g ON g.client_id=mapping.client_id AND g.club_id=mapping.club_id " +
                        "WHERE mapping.client_id=? AND g.user_id=? AND g.permission_code=? AND " +
                        "g.is_active=TRUE ORDER BY c.id",
                    clientId,
                    userId,
                    READ_CLUB,
                )
            }
        return rows.map {
            buildJsonObject {
                put("id", it.getValue("id"))
                put("name", it.getValue("name"))
                if (userId != null) put("permissions", JsonArray(listOf(JsonPrimitive(READ_CLUB))))
            }
        }
    }

    private fun summary(client: DbRow): JsonObject =
        buildJsonObject {
            put("id", client.getValue("id"))
            put("name", client.getValue("display_name"))
            put("active", client.active())
            ClientLifecycleOperations(session).view(client).forEach { (key, value) -> put(key, value) }
            ClientLimitOperations(session).view(client.long("id")).forEach { (key, value) -> put(key, value) }
        }
}
