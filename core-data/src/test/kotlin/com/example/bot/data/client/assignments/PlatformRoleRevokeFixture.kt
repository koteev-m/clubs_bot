package com.example.bot.data.client.assignments

import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.CLIENT
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.CLUB
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.FOREIGN_CLUB
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.LEGACY
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.MANAGER
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.OTHER_CLIENT
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.OTHER_CLUB
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.OWNER
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.PLATFORM
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.PROFILE_WRITE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.REASON
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.TARGET
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.evidence
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.telegram
import com.example.bot.data.client.onboarding.JdbcClientOnboardingRepository
import com.example.bot.data.client.onboarding.OnboardingActor
import com.example.bot.data.client.onboarding.OnboardingFailure
import com.example.bot.data.client.onboarding.OnboardingMutation
import com.example.bot.data.client.onboarding.OnboardingRead
import com.example.bot.data.client.onboarding.OnboardingSql
import com.example.bot.data.client.onboarding.digest
import com.example.bot.data.client.onboarding.id
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/** Composes the existing synthetic foundation providers with the real platform membership API. */
internal class PlatformRoleRevokeFixture(
    val database: AssignmentTestDatabase,
) : AutoCloseable {
    val base = AssignmentLifecycleFixture(database)
    val profileDelegation = DelegationPolicy(PROFILE_WRITE, 1)
    private val policies =
        AssignmentPolicyCatalog(
            listOf(base.floorPolicy, base.entryPolicy, base.financePolicy, base.managerPolicy, base.promoterPolicy),
            listOf(base.delegationPolicy, profileDelegation),
        )
    lateinit var originalRoleCommand: AssignmentCommand.IssueRole
        private set
    lateinit var originalRoleKey: String
        private set

    fun foundation(source: DataSource = database.dataSource): JdbcRoleAssignmentFoundation =
        base.repository(source, policies)

    fun platform(source: DataSource = database.dataSource): JdbcClientOnboardingRepository =
        JdbcClientOnboardingRepository(source, telegram(PLATFORM), base.clock)

    fun actor(user: Long = PLATFORM): OnboardingActor =
        OnboardingActor(telegram(user), "platform", "Synthetic platform adapter", "synthetic_adapter")

    fun memberVersion(
        user: Long = TARGET,
        client: Long = CLIENT,
    ): Long =
        requireNotNull(
            database.scalar("SELECT version FROM client_memberships WHERE client_id=$client AND user_id=$user"),
        ).toLong()

    fun memberState(
        user: Long = TARGET,
        client: Long = CLIENT,
    ): String? =
        database.scalar(
            "SELECT CASE WHEN is_active THEN 'active' ELSE 'inactive' END FROM client_memberships " +
                "WHERE client_id=$client AND user_id=$user",
        )

    fun body(
        restore: Boolean,
        user: Long = TARGET,
        expectedVersion: Long = memberVersion(user),
        grants: List<Pair<Long, String>> = emptyList(),
        permissions: List<String> = emptyList(),
    ): JsonObject =
        buildJsonObject {
            put("userId", user.toString())
            put("expectedVersion", expectedVersion.toString())
            if (restore) {
                put("permissions", JsonArray(permissions.map(::JsonPrimitive)))
                put(
                    "clubGrants",
                    JsonArray(
                        grants.map { (club, permission) ->
                            buildJsonObject {
                                put("clubId", club.toString())
                                put("permission", permission)
                            }
                        },
                    ),
                )
            }
        }

    suspend fun change(
        restore: Boolean,
        user: Long = TARGET,
        request: JsonObject = body(restore, user),
        key: String = base.commandKey(),
        source: DataSource = database.dataSource,
        caller: OnboardingActor = actor(),
        client: Long = CLIENT,
    ): JsonObject =
        platform(source).mutate(
            caller,
            if (restore) OnboardingMutation.MEMBER_RESTORE else OnboardingMutation.MEMBER_REVOKE,
            client,
            request,
            key,
        )

    suspend fun recipient(withDelegation: Boolean = false) {
        base.prepare()
        base.admit()
        originalRoleCommand = base.issue()
        originalRoleKey = base.commandKey()
        foundation().mutate(evidence(MANAGER), originalRoleCommand, originalRoleKey)
        if (withDelegation) foundation().mutate(evidence(MANAGER), base.delegation(), base.commandKey())
    }

    /** Subject MANAGER has two clubs, an incoming delegation and an outgoing delegation to TARGET. */
    suspend fun mixedIssuer() {
        recipient(withDelegation = true)
        base.prepareManager(OTHER_CLUB)
        val otherManager = StaffScope(CLIENT, CLUB, LEGACY)
        base.admit(otherManager, StaffRole.CLUB_MANAGER, OWNER)
        val issuerId = UUID.randomUUID()
        base.grant(base.issue(StaffRole.CLUB_MANAGER, issuerId, otherManager), OWNER)
        val recipientId = UUID.randomUUID()
        base.grant(base.issue(StaffRole.FLOOR_MANAGER, recipientId, base.managerScope), LEGACY)
        foundation().mutate(
            evidence(LEGACY),
            AssignmentCommand.IssueDelegation(
                base.managerScope,
                requireNotNull(base.fence(base.managerScope)),
                UUID.randomUUID(),
                recipientId,
                base.sourceVersion(recipientId),
                issuerId,
                base.sourceVersion(issuerId),
                requireNotNull(base.fence(otherManager)),
                PROFILE_WRITE,
                profileDelegation.reference,
                base.clock.instant().plusSeconds(3600),
                reason = REASON,
                confirmed = true,
            ),
            base.commandKey(),
        )
        foreignSubject(MANAGER)
    }

    /** Pre-existing valid synthetic state in another client; the adapter must leave every row intact. */
    fun foreignSubject(user: Long = TARGET) {
        if (database.scalar(
                "SELECT COUNT(*) FROM client_memberships WHERE client_id=$OTHER_CLIENT AND user_id=$user",
            ) == "0"
        ) {
            database.execute(
                "INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($OTHER_CLIENT,$user,TRUE)",
            )
        }
        val keyHash = digest("synthetic-foreign-subject:$user")
        database.execute(
            "INSERT INTO client_commands(actor_user_id,connection_key,key_hash,request_hash,result_type," +
                "result_id,client_id) VALUES ($OWNER,'foreign-fixture','$keyHash','$keyHash'," +
                "'role_foundation',$user,$OTHER_CLIENT)",
        )
        val audit =
            database.dataSource.connection.use { connection ->
                OnboardingSql(connection).insert(
                    "INSERT INTO audit_log(actor_user_id,subject_user_id,club_id,entity_type,entity_id," +
                        "action,fingerprint) VALUES (?,?,?,'CLIENT_ONBOARDING',?,'synthetic.foreign',?)",
                    OWNER,
                    user,
                    FOREIGN_CLUB,
                    FOREIGN_CLUB,
                    "foreign-foundation-$user",
                )
            }
        database.execute(
            "INSERT INTO client_club_access_state(client_id,club_id,user_id,status,mode,membership_version) " +
                "VALUES ($OTHER_CLIENT,$FOREIGN_CLUB,$user,'ACTIVE','SHADOW',1)",
            "INSERT INTO client_role_assignments(id,client_id,club_id,user_id,role_code,policy_version," +
                "policy_hash,selector_kind,selector_json,admission_epoch,issuer_authority_ref,created_by," +
                "command_connection_key,command_key_hash,created_audit_id,last_audit_id) VALUES " +
                "('${UUID.randomUUID()}',$OTHER_CLIENT,$FOREIGN_CLUB,$user,'FLOOR_MANAGER',1," +
                "'${base.floorPolicy.reference.hash}','CLUB','[]',1,'synthetic-foreign',$OWNER," +
                "'foreign-fixture','$keyHash',$audit,$audit)",
        )
    }

    /** Authorizes the existing legacy path positively before the new controlled-source fence is introduced. */
    suspend fun legacyStaff() {
        val hash = "c".repeat(64)
        database.execute(
            "INSERT INTO client_direct_club_profiles(club_id,client_id,owner_user_id,venue_fingerprint," +
                "country_code,city,address,legacy_fingerprint) VALUES " +
                "($CLUB,$CLIENT,$OWNER,'$hash','RU','Synthetic city','Synthetic address','$hash')",
        )
        listOf(
            "read",
            "profile.write",
            "layout.write",
            "calendar.write",
            "booking_rules.write",
            "finance.read",
            "finance.write",
            "modules.read",
            "modules.write",
            "team.manage",
        ).forEach {
            database.execute(
                "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) " +
                    "VALUES ($CLIENT,$OWNER,$CLUB,'client.club.$it',TRUE)",
            )
        }
        val repository = platform()
        val team = repository.read(actor(OWNER), OnboardingRead.CLUB_TEAM_GET, CLUB)
        val issued =
            repository.mutate(
                actor(OWNER),
                OnboardingMutation.CLUB_TEAM_UPDATE,
                CLUB,
                buildJsonObject {
                    put("action", "invite")
                    put("expectedVersion", team.getValue("version"))
                    put("recipientLabel", "Synthetic pre-existing staff")
                    put("role", "MANAGER")
                },
                base.commandKey(),
            )
        val invitation = issued.id("createdInvitationId")
        val claimed =
            repository.mutate(
                actor(TARGET),
                OnboardingMutation.INVITATION_CLAIM,
                invitation,
                buildJsonObject {
                    put("token", issued.getValue("token"))
                    put("expectedVersion", "1")
                },
                base.commandKey(),
            )
        val fresh = repository.read(actor(OWNER), OnboardingRead.CLUB_TEAM_GET, CLUB)
        repository.mutate(
            actor(OWNER),
            OnboardingMutation.CLUB_TEAM_UPDATE,
            CLUB,
            buildJsonObject {
                put("action", "confirm")
                put("expectedVersion", fresh.getValue("version"))
                put("invitationId", invitation.toString())
                put("expectedInvitationVersion", claimed.getValue("version"))
                put("verificationCode", claimed.getValue("verificationCode"))
                put("confirmed", true)
            },
            base.commandKey(),
        )
    }

    fun legacyChangeBody(): JsonObject =
        buildJsonObject {
            put("action", "change_role")
            put("expectedVersion", database.scalar("SELECT team_version FROM clubs WHERE id=$CLUB"))
            put("userId", TARGET.toString())
            put(
                "expectedMemberVersion",
                database.scalar("SELECT version FROM client_club_staff WHERE client_id=$CLIENT AND user_id=$TARGET"),
            )
            put("role", "ENTRY_MANAGER")
            put("reason", REASON)
            put("confirmed", true)
        }

    fun sourceSnapshot(client: Long = CLIENT): Map<String, List<List<String?>>> =
        FOUNDATION.associateWith { table ->
            database
                .rows(
                    "SELECT * FROM $table WHERE client_id=$client",
                ).sortedBy { row -> row.toString() }
        }

    fun clientSnapshot(client: Long): Map<String, List<List<String?>>> =
        (
            FOUNDATION +
                listOf("client_memberships", "client_club_grants", "client_membership_grants", "client_club_staff")
        ).associateWith { table ->
            database.rows("SELECT * FROM $table WHERE client_id=$client").sortedBy { row -> row.toString() }
        }

    fun snapshot(): Map<String, List<List<String?>>> =
        (
            FOUNDATION +
                listOf(
                    "users",
                    "client_memberships",
                    "client_club_grants",
                    "client_membership_grants",
                    "client_club_staff",
                    "client_invitations",
                    "user_roles",
                    "user_role_permissions",
                    "client_commands",
                    "audit_log",
                )
        ).associateWith { table -> database.rows("SELECT * FROM $table").sortedBy { row -> row.toString() } }

    override fun close() = base.close()

    companion object {
        val FOUNDATION = listOf("client_club_access_state", "client_role_assignments", "client_permission_delegations")
    }
}

internal suspend fun platformFailure(block: suspend () -> Unit): String? =
    try {
        block()
        null
    } catch (failure: OnboardingFailure) {
        failure.code
    }

/** Executes the actual late statement first, then faults on the same connection. */
internal fun latePlatformFailure(
    source: DataSource,
    statementPrefix: String,
    sqlState: String = "HY000",
    shouldFail: (Connection) -> Boolean,
): DataSource =
    object : DataSource by source {
        override fun getConnection(): Connection {
            val connection = source.connection
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, args ->
                try {
                    val result = method.invoke(connection, *args.orEmpty())
                    if (method.name == "prepareStatement" &&
                        (args?.firstOrNull() as? String)?.startsWith(statementPrefix) == true
                    ) {
                        val statement = result as PreparedStatement
                        Proxy.newProxyInstance(
                            PreparedStatement::class.java.classLoader,
                            arrayOf(PreparedStatement::class.java),
                        ) { _, call, values ->
                            try {
                                val value = call.invoke(statement, *values.orEmpty())
                                if (call.name.startsWith("execute") && shouldFail(connection)) {
                                    throw SQLException("Synthetic late platform mutation failure", sqlState)
                                }
                                value
                            } catch (failure: InvocationTargetException) {
                                throw failure.targetException
                            }
                        }
                    } else {
                        result
                    }
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
            } as Connection
        }
    }
