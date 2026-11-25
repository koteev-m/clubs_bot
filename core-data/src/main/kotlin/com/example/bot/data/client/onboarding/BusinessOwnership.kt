package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val OWNER_JOIN =
    " FROM client_business_owners o JOIN client_memberships m ON m.client_id=o.client_id AND m.user_id=o.user_id " +
        "JOIN clients c ON c.id=o.client_id "
private const val ACTIVE_OWNER =
    "m.is_active=TRUE AND m.version=o.member_version AND c.is_active=TRUE AND c.archived_at IS NULL"

internal fun OnboardingSession.isBusinessOwner(
    clientId: Long,
    userId: Long,
): Boolean =
    sql.businessOwnershipSchemaPresent() &&
        sql.row(
            "SELECT o.user_id" + OWNER_JOIN + "WHERE o.client_id=? AND o.user_id=? AND " + ACTIVE_OWNER,
            clientId,
            userId,
        ) !=
        null

internal fun OnboardingSession.businessOwnerRef(
    clientId: Long,
    clubId: Long,
    actorId: Long? = userId,
): String? {
    if (!sql.businessOwnershipSchemaPresent()) return null
    val record =
        sql.row(
            "SELECT o.member_version" + OWNER_JOIN +
                "JOIN client_business_clubs b ON b.client_id=o.client_id AND b.user_id=o.user_id " +
                "WHERE b.client_id=? AND b.club_id=? AND o.user_id=? AND " + ACTIVE_OWNER,
            clientId,
            clubId,
            actorId,
        )
    return record?.let { "business:$clientId:$clubId:$actorId:" + it.getValue("member_version") }
}

internal fun OnboardingSession.requireClubCreationAuthority(clientId: Long): Boolean {
    if (sql.businessOwnershipSchemaPresent()) {
        requireBusinessIdentity()
        if (!isBusinessOwner(clientId, userId ?: fail("not_found"))) fail("not_found")
        return true
    }
    member(clientId)
    return false
}

internal fun OnboardingSession.recordBusinessClub(
    clientId: Long,
    clubId: Long,
    owner: Long,
    evidence: String,
) {
    sql.update(
        "INSERT INTO client_business_clubs(client_id,club_id,user_id,evidence,confirmed_by,confirmed_at) " +
            "VALUES (?,?,?,?,?,?)",
        clientId,
        clubId,
        owner,
        evidence,
        userId,
        now,
    )
    sql.audit(
        checkNotNull(userId),
        "client.business.club.confirm",
        clientId,
        clubId,
        1,
        now,
        correlation,
        buildJsonObject {
            put("ownerUserId", owner.toString())
            put("evidence", evidence)
        },
    )
}

internal class BusinessOwnership(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql

    fun confirm(
        clientId: Long,
        body: JsonObject,
        oneClub: Boolean,
    ): MutationResult {
        session.owner()
        session.requireBusinessIdentity()
        body.only(
            if (oneClub) {
                listOf(
                    "expectedVersion",
                    "userId",
                    "clubId",
                    "reviewedGrants",
                )
            } else {
                listOf("expectedVersion", "userId")
            },
        )
        val owner = body.id("userId")
        if (owner == session.userId) fail("forbidden")
        // Lock the canonical account before the client, the same order as a subject's own commands.
        val candidate = sql.row("SELECT telegram_user_id FROM users WHERE id=?" + sql.updateLock(), owner)
        session.client(clientId, lock = true)
        val member = session.member(clientId, owner, manage = false)
        session.version(member, body)
        if (!eligibleTelegramIdentity(candidate?.get("telegram_user_id"))) fail("invalid_transition")
        if (oneClub) {
            if (!session.isBusinessOwner(clientId, owner)) fail("invalid_transition")
            confirmClub(clientId, body.id("clubId"), owner, body)
        } else {
            confirmOwner(clientId, owner, member.long("version"))
        }
        return MutationResult("member", owner, clientId, ClientAccessOperations(session).memberView(clientId, owner))
    }

    private fun confirmOwner(
        clientId: Long,
        owner: Long,
        version: Long,
    ) {
        val existing = sql.row("SELECT * FROM client_business_owners WHERE client_id=?", clientId)
        if (existing != null && existing.long("user_id") != owner) fail("invalid_transition")
        if (existing == null) {
            sql.update(
                "INSERT INTO client_business_owners(client_id,user_id,member_version,confirmed_by," +
                    "confirmed_at) VALUES (?,?,?,?,?)",
                clientId,
                owner,
                version,
                session.userId,
                session.now,
            )
        } else if (existing.long("member_version") != version) {
            sql.update(
                "UPDATE client_business_owners SET member_version=?,confirmed_by=?,confirmed_at=? " +
                    "WHERE client_id=? AND user_id=?",
                version,
                session.userId,
                session.now,
                clientId,
                owner,
            )
        } else {
            fail("invalid_transition")
        }
        session.audit("client.business.owner.confirm", clientId, owner, version)
        for (club in unboundClubs(clientId)) {
            val clubId = club.long("id")
            if (!verifiedCreation(clientId, clubId, owner)) continue
            val rights = classify(clientId, clubId, owner)
            if (rights.ambiguous.isEmpty()) attach(clientId, clubId, owner, "VERIFIED_CREATION", rights.bootstrap)
        }
    }

    fun fields(
        clientId: Long,
        owner: Long,
        member: DbRow,
    ): JsonObject =
        buildJsonObject {
            val active = session.isBusinessOwner(clientId, owner)
            put("businessOwner", active)
            if (sql.businessOwnershipSchemaPresent()) {
                val binding = sql.row("SELECT user_id FROM client_business_owners WHERE client_id=?", clientId)
                val available = binding == null || binding.long("user_id") == owner
                put(
                    "canConfirmBusinessOwner",
                    !active && member.active() && available && eligibleTelegramIdentity(member["telegram_user_id"]),
                )
            }
            if (active) {
                put(
                    "businessClubIds",
                    JsonArray(
                        sql
                            .rows(
                                "SELECT club_id FROM client_business_clubs WHERE client_id=? AND " +
                                    "user_id=? ORDER BY club_id",
                                clientId,
                                owner,
                            ).map { JsonPrimitive(it.getValue("club_id")) },
                    ),
                )
                put(
                    "businessClubsForReview",
                    JsonArray(
                        unboundClubs(clientId).map { club ->
                            buildJsonObject {
                                put("clubId", club.getValue("id"))
                                put("clubName", club.getValue("name"))
                                put(
                                    "grants",
                                    JsonArray(classify(clientId, club.long("id"), owner).ambiguous.map(::reviewEntry)),
                                )
                            }
                        },
                    ),
                )
            }
        }

    // Match the identity used for signed client sessions. Only the unique canonical Telegram
    // user id is an authority input; a membership, display name or username is insufficient.
    private fun eligibleTelegramIdentity(value: String?): Boolean =
        value?.toLongOrNull()?.let { it > 0 && it != session.ownerId } == true

    private fun unboundClubs(clientId: Long): List<DbRow> =
        sql.rows(
            "SELECT c.id,c.name FROM client_clubs m JOIN clubs c ON c.id=m.club_id " +
                "WHERE m.client_id=? AND NOT EXISTS (SELECT 1 FROM client_business_clubs b WHERE " +
                "b.club_id=m.club_id) ORDER BY c.id",
            clientId,
        )

    private fun verifiedCreation(
        clientId: Long,
        clubId: Long,
        owner: Long,
    ): Boolean = ClubStaffAccess(session).creator(clubId) == owner && creationCommand(clientId, clubId, owner) != null

    private fun creationCommand(
        clientId: Long,
        clubId: Long,
        owner: Long,
    ): DbRow? =
        sql.row(
            "SELECT a.created_at,m.created_at AS mapped_at FROM audit_log a " +
                "JOIN client_commands cc ON cc.actor_user_id=a.actor_user_id " +
                "AND cc.result_id=a.entity_id AND cc.result_type='club_create' " +
                "JOIN client_clubs m ON m.client_id=cc.client_id AND m.club_id=cc.result_id " +
                "WHERE cc.client_id=? AND a.entity_id=? AND a.actor_user_id=? AND a.entity_type='CLIENT_ONBOARDING' " +
                "AND a.action='client.club.create' ORDER BY a.id LIMIT 1",
            clientId,
            clubId,
            owner,
        )

    private fun confirmClub(
        clientId: Long,
        clubId: Long,
        owner: Long,
        body: JsonObject,
    ) {
        if (unboundClubs(clientId).none { it.long("id") == clubId }) fail("not_found")
        val rights = classify(clientId, clubId, owner)
        val reviewed =
            body["reviewedGrants"]?.let { it as? JsonArray ?: fail("validation_error") } ?: JsonArray(emptyList())
        val decisions =
            reviewed
                .map {
                    val row = it as? JsonObject ?: fail("validation_error")
                    row.only(listOf("permission", "userId", "updatedAt", "keep"))
                    val keep =
                        (row["keep"] as? JsonPrimitive)?.takeUnless { value -> value.isString }?.booleanOrNull
                            ?: fail("validation_error")
                    JsonObject(row - "keep") to keep
                }.toMap()
        if (reviewed.size != decisions.size ||
            decisions.keys != rights.ambiguous.map(::reviewEntry).toSet()
        ) {
            fail("version_conflict")
        }
        attach(
            clientId,
            clubId,
            owner,
            "PLATFORM_CONFIRMATION",
            rights.bootstrap + rights.ambiguous.filter { decisions[reviewEntry(it)] == false },
        )
        if (reviewed.isNotEmpty()) {
            sql.audit(
                checkNotNull(session.userId),
                "client.business.grants.review",
                clientId,
                clubId,
                1,
                session.now,
                session.correlation,
                buildJsonObject { put("decisions", reviewed) },
            )
        }
    }

    private fun attach(
        clientId: Long,
        clubId: Long,
        owner: Long,
        evidence: String,
        retire: List<DbRow>,
    ) {
        for (grant in retire) {
            check(
                sql.update(
                    "UPDATE client_club_grants SET is_active=FALSE,updated_at=? WHERE client_id=? AND club_id=? " +
                        "AND user_id=? AND permission_code=? AND is_active=TRUE",
                    session.now,
                    clientId,
                    clubId,
                    grant.long("user_id"),
                    grant.getValue("permission_code"),
                ) == 1,
            )
        }
        if (retire.isNotEmpty()) {
            sql.audit(
                checkNotNull(session.userId),
                "client.business.grants.retire",
                clientId,
                clubId,
                1,
                session.now,
                session.correlation,
                buildJsonObject { put("grants", JsonArray(retire.map(::reviewEntry))) },
            )
        }
        session.recordBusinessClub(clientId, clubId, owner, evidence)
    }

    private data class Rights(
        val bootstrap: List<DbRow>,
        val ambiguous: List<DbRow>,
    )

    private fun classify(
        clientId: Long,
        clubId: Long,
        owner: Long,
    ): Rights {
        val creators =
            sql
                .rows(
                    "SELECT owner_user_id FROM client_direct_club_profiles WHERE client_id=? AND club_id=? UNION " +
                        "SELECT owner_user_id FROM client_club_onboarding WHERE client_id=? AND club_id=?",
                    clientId,
                    clubId,
                    clientId,
                    clubId,
                ).map { it.long("owner_user_id") }
        val uniqueCreator = creators.singleOrNull()
        val subjects = if (uniqueCreator != null) listOf(uniqueCreator) else (creators + owner).distinct()
        val rights = subjects.map { subject -> creatorRights(clientId, clubId, subject, uniqueCreator == subject) }
        return Rights(rights.flatMap { it.bootstrap }, rights.flatMap { it.ambiguous })
    }

    private fun creatorRights(
        clientId: Long,
        clubId: Long,
        creator: Long,
        uniqueCreator: Boolean,
    ): Rights {
        val creation = if (uniqueCreator) creationCommand(clientId, clubId, creator) else null
        val bootstrap = mutableListOf<DbRow>()
        val ambiguous = mutableListOf<DbRow>()
        val staff =
            sql.row(
                "SELECT role_code FROM client_club_staff WHERE client_id=? AND club_id=? AND user_id=? " +
                    "AND is_active=TRUE",
                clientId,
                clubId,
                creator,
            )
        val independent =
            staffPackages.entries
                .find { it.key.name == staff?.get("role_code") }
                ?.value
                .orEmpty()
        val grants =
            sql.rows(
                "SELECT * FROM client_club_grants WHERE client_id=? AND club_id=? AND user_id=? AND " +
                    "is_active=TRUE ORDER BY permission_code",
                clientId,
                clubId,
                creator,
            )
        for (grant in grants) {
            val code = checkNotNull(grant["permission_code"])
            if (code in independent || code !in OWNER_BOOTSTRAP_WRITES) continue
            val updated = grant.instant("updated_at")
            val migration =
                sql.row(
                    "SELECT created_at FROM audit_log WHERE club_id=? AND subject_user_id=? AND action=? " +
                        "AND actor_user_id IS NULL " +
                        "AND entity_type='CLIENT_ONBOARDING' AND entity_id=? ORDER BY id LIMIT 1",
                    clubId,
                    creator,
                    code.removeSuffix(".write").let {
                        if (code == WRITE_FINANCE ||
                            code == WRITE_MODULES
                        ) {
                            code
                        } else {
                            it
                        }
                    } +
                        ".bootstrap",
                    clubId,
                )
            // Legacy creation inserted the club mapping, then its grants, then the audit in one transaction.
            val created = grant.instant("created_at")
            val fromCreation =
                creation != null &&
                    created >= creation.instant("mapped_at") &&
                    updated >= created &&
                    updated <= creation.instant("created_at")
            val fromMigration = created == updated && migration?.instant("created_at") == updated
            if (fromCreation || fromMigration) {
                bootstrap += grant
            } else {
                ambiguous += grant
            }
        }
        return Rights(bootstrap, ambiguous)
    }

    private fun reviewEntry(grant: DbRow): JsonObject =
        buildJsonObject {
            put("permission", grant.getValue("permission_code"))
            put("userId", grant.getValue("user_id"))
            put("updatedAt", grant.instant("updated_at").toString())
        }
}

private val OWNER_BOOTSTRAP_WRITES =
    setOf(WRITE_PROFILE, WRITE_LAYOUT, WRITE_CALENDAR, WRITE_BOOKING_RULES, WRITE_MODULES, WRITE_FINANCE, MANAGE_TEAM)

/** Historical creator contract for schemas before business ownership was introduced. */
internal fun OnboardingSession.bootstrapLegacyCreator(
    clientId: Long,
    clubId: Long,
) {
    for (permission in OWNER_BOOTSTRAP_WRITES + listOf(READ_CLUB, READ_FINANCE, READ_MODULES)) {
        sql.update(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active," +
                "created_at,updated_at) " +
                "VALUES (?,?,?,?,TRUE,?,?)",
            clientId,
            userId,
            clubId,
            permission,
            now,
            now,
        )
    }
}
