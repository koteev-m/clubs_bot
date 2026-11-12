package com.example.bot.routes

import javax.sql.DataSource

/** Disposable SQL provenance only: no role evaluator or production provider is installed. */
internal class ClientMemberRoleRevokeRouteFixture(
    private val source: DataSource,
    platformTelegramId: Long,
    targetTelegramId: Long,
    private val foundation: Boolean = true,
) {
    init {
        execute(
            "INSERT INTO users(id,telegram_user_id) VALUES ($PLATFORM,$platformTelegramId)," +
                "($TARGET,$targetTelegramId),($ISSUER,998104),($DEPENDENT,998105)",
            "INSERT INTO clients(id,display_name,is_active,allowed_clubs) VALUES " +
                "($CLIENT,'Synthetic member role client',TRUE,2),($OTHER_CLIENT,'Synthetic unrelated client',TRUE,1)",
            "INSERT INTO clubs(id,name,timezone,is_active) VALUES ($CLUB,'Synthetic first club','UTC',FALSE)," +
                "($SECOND_CLUB,'Synthetic second club','UTC',FALSE),($OTHER_CLUB,'Synthetic foreign club','UTC',FALSE)",
            "INSERT INTO client_clubs(client_id,club_id) VALUES " +
                "($CLIENT,$CLUB),($CLIENT,$SECOND_CLUB),($OTHER_CLIENT,$OTHER_CLUB)",
            "INSERT INTO client_memberships(client_id,user_id,is_active) VALUES " +
                "($CLIENT,$TARGET,TRUE),($CLIENT,$ISSUER,TRUE),($CLIENT,$DEPENDENT,TRUE)," +
                "($OTHER_CLIENT,$TARGET,TRUE)",
            "INSERT INTO client_membership_grants(client_id,user_id,permission_code,is_active) VALUES " +
                "($CLIENT,$TARGET,'client.application.manage',TRUE)",
            "INSERT INTO client_club_grants(client_id,club_id,user_id,permission_code,is_active) VALUES " +
                "($CLIENT,$CLUB,$TARGET,'client.club.read',TRUE)," +
                "($CLIENT,$CLUB,$TARGET,'client.club.calendar.write',TRUE)," +
                "($CLIENT,$SECOND_CLUB,$TARGET,'client.club.read',TRUE)," +
                "($OTHER_CLIENT,$OTHER_CLUB,$TARGET,'client.club.read',TRUE)",
        )
        val invitationHash = "c".repeat(64)
        execute(
            "INSERT INTO client_invitations(id,client_id,recipient_label,token_hash,status,expires_at," +
                "claimant_user_id,claimed_at,accepted_at,purpose,club_id,staff_role," +
                "inviter_user_id,invitation_scope) " +
                "VALUES ($INVITATION,$CLIENT,'Synthetic staff','$invitationHash','accepted'," +
                "'2099-01-01T00:00:00Z'," +
                "$TARGET,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'staff',$CLUB,'MANAGER',$PLATFORM,'$invitationHash')",
            "INSERT INTO client_club_staff(client_id,club_id,user_id,role_code,is_active,invitation_id) VALUES " +
                "($CLIENT,$CLUB,$TARGET,'MANAGER',TRUE,$INVITATION)",
        )
        if (foundation) seedFoundation()
    }

    fun snapshot(): Map<String, List<List<String?>>> =
        (LEGACY_TABLES + if (foundation) FOUNDATION_TABLES else emptyList())
            .associateWith { rows("SELECT * FROM $it ORDER BY 1,2") }

    fun foundationSnapshot(): Map<String, List<List<String?>>> =
        FOUNDATION_TABLES.associateWith { rows("SELECT * FROM $it ORDER BY 1,2") }

    fun unrelatedClientSnapshot(): Map<String, List<List<String?>>> =
        listOf("client_memberships", "client_club_grants", "client_club_access_state", "client_role_assignments")
            .associateWith { rows("SELECT * FROM $it WHERE client_id=$OTHER_CLIENT ORDER BY 1,2") }

    fun rows(sql: String): List<List<String?>> =
        source.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    buildList {
                        while (result.next()) {
                            add((1..result.metaData.columnCount).map { result.getString(it) })
                        }
                    }
                }
            }
        }

    private fun execute(vararg statements: String) {
        source.connection.use { connection ->
            connection.createStatement().use { statement -> statements.forEach { statement.execute(it) } }
        }
    }

    private fun seedFoundation() {
        val policyHash = "a".repeat(64)
        val keyHash = "b".repeat(64)
        execute(
            "INSERT INTO client_commands(actor_user_id,connection_key,key_hash,request_hash,result_type," +
                "result_id,client_id) VALUES ($PLATFORM,'synthetic-route-foundation','$keyHash','$policyHash'," +
                "'role_foundation',$TARGET,$CLIENT)",
            "INSERT INTO audit_log(id,created_at,actor_user_id,subject_user_id,club_id,entity_type,entity_id," +
                "action,fingerprint,metadata_json) VALUES ($AUDIT,CURRENT_TIMESTAMP,$PLATFORM,$TARGET,$CLUB," +
                "'CLIENT_ONBOARDING',$CLUB,'synthetic.route.seed','synthetic-route-foundation','{}')",
            "INSERT INTO client_club_access_state(client_id,club_id,user_id,status,mode,admission_epoch," +
                "membership_version,version) VALUES " +
                "($CLIENT,$CLUB,$TARGET,'ACTIVE','SHADOW',5,1,8)," +
                "($CLIENT,$SECOND_CLUB,$TARGET,'ACTIVE','SHADOW',7,1,9)," +
                "($CLIENT,$CLUB,$ISSUER,'ACTIVE','SHADOW',1,1,2)," +
                "($CLIENT,$CLUB,$DEPENDENT,'ACTIVE','SHADOW',1,1,3)," +
                "($OTHER_CLIENT,$OTHER_CLUB,$TARGET,'ACTIVE','SHADOW',1,1,2)",
        )
        role(TARGET_MANAGER, CLIENT, CLUB, TARGET, "CLUB_MANAGER", 5, policyHash, keyHash)
        role(TARGET_FLOOR, CLIENT, CLUB, TARGET, "FLOOR_MANAGER", 5, policyHash, keyHash)
        role(TARGET_SECOND, CLIENT, SECOND_CLUB, TARGET, "FLOOR_MANAGER", 7, policyHash, keyHash)
        role(ISSUER_MANAGER, CLIENT, CLUB, ISSUER, "CLUB_MANAGER", 1, policyHash, keyHash)
        role(DEPENDENT_FLOOR, CLIENT, CLUB, DEPENDENT, "FLOOR_MANAGER", 1, policyHash, keyHash)
        role(OTHER_ROLE, OTHER_CLIENT, OTHER_CLUB, TARGET, "FLOOR_MANAGER", 1, policyHash, keyHash)
        delegation(INCOMING, TARGET, TARGET_FLOOR, ISSUER, ISSUER_MANAGER, 5, 1, policyHash, keyHash)
        delegation(OUTGOING, DEPENDENT, DEPENDENT_FLOOR, TARGET, TARGET_MANAGER, 1, 5, policyHash, keyHash)
        delegation(INDEPENDENT, DEPENDENT, DEPENDENT_FLOOR, ISSUER, ISSUER_MANAGER, 1, 1, policyHash, keyHash)
    }

    private fun role(
        id: String,
        client: Long,
        club: Long,
        user: Long,
        code: String,
        epoch: Long,
        policyHash: String,
        keyHash: String,
    ) {
        execute(
            "INSERT INTO client_role_assignments(id,client_id,club_id,user_id,role_code,policy_version,policy_hash," +
                "selector_kind,selector_json,admission_epoch,issuer_authority_ref,created_by,command_connection_key," +
                "command_key_hash,created_audit_id,last_audit_id) VALUES ('$id',$client,$club,$user,'$code',1," +
                "'$policyHash','CLUB','[]',$epoch,'synthetic-route-authority',$PLATFORM," +
                "'synthetic-route-foundation','$keyHash',$AUDIT,$AUDIT)",
        )
    }

    private fun delegation(
        id: String,
        user: Long,
        recipient: String,
        issuerUser: Long,
        issuer: String,
        epoch: Long,
        issuerEpoch: Long,
        policyHash: String,
        keyHash: String,
    ) {
        execute(
            "INSERT INTO client_permission_delegations(id,client_id,club_id,user_id,permission_code," +
                "recipient_assignment_id,issuer_assignment_id,issuer_user_id,policy_version,policy_hash," +
                "admission_epoch,issuer_admission_epoch,issuer_authority_ref,created_by,command_connection_key," +
                "command_key_hash,created_audit_id,last_audit_id,expires_at) VALUES ('$id',$CLIENT,$CLUB,$user," +
                "'client.club.calendar.write','$recipient','$issuer',$issuerUser,1,'$policyHash',$epoch,$issuerEpoch," +
                "'synthetic-route-authority',$PLATFORM,'synthetic-route-foundation','$keyHash',$AUDIT,$AUDIT," +
                "'2099-01-01T00:00:00Z')",
        )
    }

    companion object {
        const val CLIENT = 9411L
        const val OTHER_CLIENT = 9412L
        const val CLUB = 94111L
        const val SECOND_CLUB = 94112L
        const val OTHER_CLUB = 94121L
        const val PLATFORM = 94201L
        const val TARGET = 94207L
        const val ISSUER = 94202L
        const val DEPENDENT = 94208L
        const val INVITATION = 94301L
        private const val AUDIT = 94302L
        const val TARGET_MANAGER = "00000000-0000-4000-8000-000000009411"
        const val TARGET_FLOOR = "00000000-0000-4000-8000-000000009412"
        const val TARGET_SECOND = "00000000-0000-4000-8000-000000009413"
        const val ISSUER_MANAGER = "00000000-0000-4000-8000-000000009414"
        const val DEPENDENT_FLOOR = "00000000-0000-4000-8000-000000009415"
        const val OTHER_ROLE = "00000000-0000-4000-8000-000000009416"
        const val INCOMING = "00000000-0000-4000-8000-000000009421"
        const val OUTGOING = "00000000-0000-4000-8000-000000009422"
        const val INDEPENDENT = "00000000-0000-4000-8000-000000009423"
        private val FOUNDATION_TABLES =
            listOf("client_club_access_state", "client_role_assignments", "client_permission_delegations")
        private val LEGACY_TABLES =
            listOf(
                "users",
                "clients",
                "clubs",
                "client_memberships",
                "client_membership_grants",
                "client_club_grants",
                "client_club_staff",
                "client_invitations",
                "client_commands",
                "audit_log",
            )
    }
}
