package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** All authority writers share the client row lock, including claim, grant restore and club creation. */
internal class ClientLifecycleOperations(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql

    fun change(
        clientId: Long,
        operation: OnboardingMutation,
        body: JsonObject,
    ): MutationResult {
        session.owner()
        body.only(listOf("expectedVersion"))
        val client = session.client(clientId, lock = true)
        if (body.id("expectedVersion") != client.long("lifecycle_version")) fail("version_conflict")
        val archive = operation == OnboardingMutation.CLIENT_ARCHIVE
        if (archive == (client["archived_at"] != null)) fail("invalid_transition")
        if (archive && blockers(snapshot(clientId)).isNotEmpty()) fail("client_not_empty")
        sql.update(
            "UPDATE clients SET archived_at=?,lifecycle_version=lifecycle_version+1,updated_at=? WHERE id=?",
            if (archive) session.now else null,
            session.now,
            clientId,
        )
        // Restoring the container never restores memberships, grants or invitation expiry/status.
        session.audit(
            if (archive) "client.archive" else "client.restore",
            clientId,
            clientId,
            client.long("lifecycle_version") + 1,
        )
        return MutationResult("client_lifecycle", clientId, clientId, ClientAccessOperations(session).get(clientId))
    }

    fun view(client: DbRow): JsonObject {
        val clientId = client.long("id")
        val archived = client["archived_at"] != null
        val snapshot = snapshot(clientId)
        val blockers = blockers(snapshot)
        return buildJsonObject {
            put("archived", archived)
            put(
                "archivedAt",
                client["archived_at"]?.let { JsonPrimitive(client.instant("archived_at").toString()) }
                    ?: JsonNull,
            )
            put("version", client.getValue("lifecycle_version"))
            put("activeMembers", snapshot.long("memberships"))
            put("archiveBlockers", JsonArray(blockers.map(::JsonPrimitive)))
            put(
                "availableActions",
                JsonArray(
                    (
                        if (archived) {
                            listOf("restore")
                        } else if (blockers.isEmpty()) {
                            listOf("archive")
                        } else {
                            emptyList()
                        }
                    ).map(::JsonPrimitive),
                ),
            )
        }
    }

    private fun blockers(snapshot: DbRow): List<String> =
        buildList {
            if (snapshot.long("clubs") > 0) add("clubs")
            if (snapshot.long("memberships") > 0) add("active_memberships")
            if (snapshot.long("membership_grants") > 0 || snapshot.long("club_grants") > 0) add("active_grants")
            if (snapshot.long("invitations") > 0) add("active_invitations")
        }

    private fun snapshot(clientId: Long): DbRow =
        checkNotNull(
            sql.row(
                "SELECT (SELECT COUNT(*) FROM client_clubs WHERE client_id=?) AS clubs," +
                    "(SELECT COUNT(*) FROM client_memberships WHERE client_id=? AND is_active=TRUE) AS memberships," +
                    "(SELECT COUNT(*) FROM client_membership_grants WHERE client_id=? AND is_active=TRUE) " +
                    "AS membership_grants," +
                    "(SELECT COUNT(*) FROM client_club_grants WHERE client_id=? AND is_active=TRUE) AS club_grants," +
                    "(SELECT COUNT(*) FROM client_invitations WHERE client_id=? " +
                    "AND status IN ('issued','claimed') AND expires_at>?) AS invitations",
                clientId,
                clientId,
                clientId,
                clientId,
                clientId,
                session.now,
            ),
        )
}
