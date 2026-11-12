package com.example.bot.data.client.assignments

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** Only synthetic callers/providers; this fixture never installs an application authority adapter. */
internal class AssignmentLifecycleFixture(
    val database: AssignmentTestDatabase,
) : AutoCloseable {
    val clock = AssignmentLifecycleClock()
    val managerScope = StaffScope(CLIENT, CLUB, MANAGER)
    val targetScope = StaffScope(CLIENT, CLUB, TARGET)
    val floorPolicy = RolePolicy(StaffRole.FLOOR_MANAGER, 1, setOf(NIGHT_READ, FLOOR_READ))
    val entryPolicy = RolePolicy(StaffRole.ENTRANCE_MANAGER, 1, setOf(NIGHT_READ, ENTRY_WRITE))
    val financePolicy =
        RolePolicy(
            StaffRole.FINANCE_MANAGER,
            1,
            setOf(FINANCE_READ),
            setOf(SelectorKind.SHIFT_SET, SelectorKind.ALL_SHIFTS_OF_THIS_CLUB),
        )
    val managerPolicy =
        RolePolicy(
            StaffRole.CLUB_MANAGER,
            1,
            setOf(
                CALENDAR_READ,
                CALENDAR_WRITE,
                PROFILE_READ,
                PROFILE_WRITE,
                "client.staff.delegate.calendar",
                "client.staff.delegate.profile",
                "client.staff.assign.manager",
                "client.staff.assign.entry_manager",
                "client.staff.assign.finance_manager",
                "client.staff.assign.promoter",
                "client.staff.revoke.manager",
                "client.staff.revoke.entry_manager",
                "client.staff.revoke.finance_manager",
                "client.staff.revoke.promoter",
                "client.staff.disable.club",
            ),
        )
    val promoterPolicy =
        RolePolicy(StaffRole.PROMOTER, 1, setOf("booking.promoter.create", "booking.promoter.own.read"))
    val delegationPolicy = DelegationPolicy(CALENDAR_WRITE, 1)
    val catalog =
        AssignmentPolicyCatalog(
            listOf(floorPolicy, entryPolicy, financePolicy, managerPolicy, promoterPolicy),
            listOf(delegationPolicy),
        )
    val identity =
        AssignmentIdentityVerifier { evidence, now ->
            evidence
                .removePrefix("synthetic-signed:")
                .toLongOrNull()
                ?.takeIf { evidence.startsWith("synthetic-signed:") && it in USERS }
                ?.let {
                    VerifiedAssignmentIdentity(
                        telegram(it),
                        "platform",
                        now.minusSeconds(1),
                        now.plusSeconds(120),
                    )
                }
        }
    val ownership =
        object : AssignmentOwnershipProvider {
            override fun ownerReference(
                connection: Connection,
                actorId: Long,
                clientId: Long,
                clubId: Long,
            ): String? =
                if (actorId == OWNER &&
                    clientId == CLIENT &&
                    clubId in setOf(CLUB, OTHER_CLUB, THIRD_CLUB, FOURTH_CLUB, FIFTH_CLUB)
                ) {
                    "synthetic-owner:$clientId:$clubId"
                } else {
                    null
                }

            override fun protectedSubject(
                connection: Connection,
                scope: StaffScope,
            ): Boolean = scope.userId == OWNER || scope.userId == PLATFORM

            override fun canDisable(
                connection: Connection,
                actorId: Long,
                scope: StaffScope,
            ): Boolean = ownerReference(connection, actorId, scope.clientId, scope.clubId) != null
        }
    private val confirmations = mutableMapOf<String, TargetAdmissionConfirmation>()
    private var sequence = 0L
    val admissions =
        object : TargetAdmissionVerifier {
            override fun verify(
                connection: Connection,
                reference: String,
                now: Instant,
            ): TargetAdmissionConfirmation? = confirmations[reference]

            override fun latestIssuedInvitationId(
                connection: Connection,
                clientId: Long,
                clubId: Long,
            ): Long =
                confirmations.values
                    .filter { it.scope.clientId == clientId && it.scope.clubId == clubId }
                    .maxOfOrNull { it.invitationId } ?: 0
        }
    val repository get() = repository()

    init {
        database.execute(
            "INSERT INTO clients(id,display_name,is_active) VALUES ($CLIENT,'Synthetic assignment client',TRUE)," +
                "($OTHER_CLIENT,'Synthetic unrelated client',TRUE)",
            "INSERT INTO clubs(id,name,timezone,is_active) VALUES ($CLUB,'Synthetic assignment club','UTC',FALSE)," +
                "($OTHER_CLUB,'Synthetic neighbouring club','UTC',FALSE)," +
                "($THIRD_CLUB,'Synthetic third club','UTC',FALSE)," +
                "($FOURTH_CLUB,'Synthetic fourth club','UTC',FALSE)," +
                "($FIFTH_CLUB,'Synthetic fifth club','UTC',FALSE)," +
                "($FOREIGN_CLUB,'Synthetic foreign club','UTC',FALSE)",
            "INSERT INTO client_clubs(client_id,club_id) VALUES " +
                "($CLIENT,$CLUB),($CLIENT,$OTHER_CLUB),($CLIENT,$THIRD_CLUB)," +
                "($CLIENT,$FOURTH_CLUB),($CLIENT,$FIFTH_CLUB),($OTHER_CLIENT,$FOREIGN_CLUB)",
        )
        USERS.forEach { user ->
            database.execute("INSERT INTO users(id,telegram_user_id) VALUES ($user,${telegram(user)})")
            database.execute("INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($CLIENT,$user,TRUE)")
        }
        database.execute(
            "INSERT INTO client_memberships(client_id,user_id,is_active) VALUES " +
                "($OTHER_CLIENT,$TARGET,TRUE),($OTHER_CLIENT,$LEGACY,TRUE)",
            "INSERT INTO user_roles(user_id,role_code,scope_type,scope_club_id) VALUES " +
                "($LEGACY,'MANAGER','CLUB',$FOREIGN_CLUB)",
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES " +
                "($OTHER_CLIENT,$LEGACY,$FOREIGN_CLUB,'client.club.read',TRUE)," +
                "($OTHER_CLIENT,$LEGACY,$FOREIGN_CLUB,'client.club.profile.write',FALSE)",
        )
        val permissions =
            listOf(floorPolicy, entryPolicy, financePolicy, managerPolicy, promoterPolicy)
                .flatMap { it.permissions }
                .toSet()
        permissions
            .filter { code -> database.scalar("SELECT COUNT(*) FROM permissions WHERE code='$code'") == "0" }
            .forEach { code -> database.execute("INSERT INTO permissions(code) VALUES ('$code')") }
    }

    fun repository(
        source: DataSource = database.dataSource,
        policies: AssignmentPolicyCatalog = catalog,
    ): JdbcRoleAssignmentFoundation =
        JdbcRoleAssignmentFoundation(
            dataSource = source,
            policies = policies,
            identityVerifier = identity,
            ownership = ownership,
            admissionVerifier = admissions,
            configuredPlatformOwnerId = telegram(PLATFORM),
            clock = clock,
        )

    suspend fun prepare() = prepareManager(CLUB)

    suspend fun prepareManager(clubId: Long) {
        val scope = StaffScope(CLIENT, clubId, MANAGER)
        val result = admit(scope, StaffRole.CLUB_MANAGER, OWNER)
        repository.mutate(
            evidence(OWNER),
            AssignmentCommand.IssueRole(
                scope,
                requireNotNull(result.current.admission),
                if (clubId ==
                    CLUB
                ) {
                    MANAGER_SOURCE
                } else {
                    UUID.nameUUIDFromBytes("synthetic-manager:$clubId".toByteArray())
                },
                StaffRole.CLUB_MANAGER,
                managerPolicy.reference,
                reason = REASON,
                confirmed = true,
            ),
            commandKey(),
        )
    }

    suspend fun admit(
        scope: StaffScope = targetScope,
        role: StaffRole = StaffRole.FLOOR_MANAGER,
        actor: Long = MANAGER,
        expected: AdmissionVersion? = fence(scope),
    ): AssignmentResult {
        val reference = confirmation(scope, role, actor, expected?.epoch ?: 0)
        return repository.mutate(
            evidence(actor),
            AssignmentCommand.Admit(scope, expected, role, reference, REASON, true),
            commandKey(),
        )
    }

    fun confirmation(
        scope: StaffScope = targetScope,
        role: StaffRole = StaffRole.FLOOR_MANAGER,
        actor: Long = MANAGER,
        expectedEpoch: Long = fence(scope)?.epoch ?: 0,
    ): String {
        val reference = commandKey()
        confirmations[reference] =
            TargetAdmissionConfirmation(
                1_000_000 + sequence,
                scope,
                role,
                expectedEpoch,
                telegram(actor),
                clock.instant().plusSeconds(300),
            )
        return reference
    }

    fun issue(
        role: StaffRole = StaffRole.FLOOR_MANAGER,
        sourceId: UUID = FLOOR_SOURCE,
        scope: StaffScope = targetScope,
    ): AssignmentCommand.IssueRole {
        val policy =
            when (role) {
                StaffRole.FLOOR_MANAGER -> floorPolicy
                StaffRole.ENTRANCE_MANAGER -> entryPolicy
                StaffRole.FINANCE_MANAGER -> financePolicy
                StaffRole.PROMOTER -> promoterPolicy
                StaffRole.CLUB_MANAGER -> managerPolicy
                StaffRole.NETWORK_MANAGER -> error("No network policy installed by this fixture")
            }
        return AssignmentCommand.IssueRole(
            scope,
            requireNotNull(fence(scope)),
            sourceId,
            role,
            policy.reference,
            selector =
                if (role ==
                    StaffRole.FINANCE_MANAGER
                ) {
                    PermissionSelector(SelectorKind.ALL_SHIFTS_OF_THIS_CLUB)
                } else {
                    PermissionSelector()
                },
            reason = REASON,
            confirmed = true,
        )
    }

    suspend fun grant(
        command: AssignmentCommand.IssueRole = issue(),
        actor: Long = MANAGER,
    ): AssignmentResult = repository.mutate(evidence(actor), command, commandKey())

    suspend fun revoke(
        sourceId: UUID,
        actor: Long = MANAGER,
        scope: StaffScope = targetScope,
    ): AssignmentResult =
        repository.mutate(
            evidence(actor),
            AssignmentCommand.RevokeRole(
                scope,
                requireNotNull(fence(scope)),
                sourceId,
                sourceVersion(sourceId),
                REASON,
                true,
            ),
            commandKey(),
        )

    fun delegation(sourceId: UUID = DELEGATION_SOURCE): AssignmentCommand.IssueDelegation =
        AssignmentCommand.IssueDelegation(
            targetScope,
            requireNotNull(fence(targetScope)),
            sourceId,
            FLOOR_SOURCE,
            sourceVersion(FLOOR_SOURCE),
            MANAGER_SOURCE,
            sourceVersion(MANAGER_SOURCE),
            requireNotNull(fence(managerScope)),
            CALENDAR_WRITE,
            delegationPolicy.reference,
            clock.instant().plusSeconds(3600),
            reason = REASON,
            confirmed = true,
        )

    fun fence(scope: StaffScope = targetScope): AdmissionVersion? =
        database
            .rows(
                "SELECT admission_epoch,version FROM client_club_access_state WHERE " +
                    "client_id=${scope.clientId} AND club_id=${scope.clubId} AND user_id=${scope.userId}",
            ).singleOrNull()
            ?.let { AdmissionVersion(requireNotNull(it[0]).toLong(), requireNotNull(it[1]).toLong()) }

    fun sourceVersion(id: UUID): Long =
        requireNotNull(database.scalar("SELECT version FROM client_role_assignments WHERE id='$id'")).toLong()

    suspend fun view(scope: StaffScope = targetScope): AssignmentView =
        repository.effective(evidence(scope.userId), scope.clientId, scope.clubId)

    fun snapshot(): Map<String, List<List<String?>>> =
        TRACKED.associateWith { database.rows("SELECT * FROM $it ORDER BY 1,2") }

    fun legacySnapshot(): Map<String, List<List<String?>>> =
        LEGACY_TABLES.associateWith { database.rows("SELECT * FROM $it ORDER BY 1,2") }

    fun commandKey(): String = "synthetic-command-${++sequence}"

    override fun close() = database.close()

    companion object {
        const val CLIENT = 8801L
        const val OTHER_CLIENT = 8802L
        const val CLUB = 88011L
        const val OTHER_CLUB = 88012L
        const val THIRD_CLUB = 88013L
        const val FOURTH_CLUB = 88014L
        const val FIFTH_CLUB = 88015L
        const val FOREIGN_CLUB = 88021L
        const val OWNER = 88101L
        const val MANAGER = 88102L
        const val TARGET = 88107L
        const val LEGACY = 88108L
        const val PLATFORM = 88199L
        const val NIGHT_READ = "night.read"
        const val FLOOR_READ = "floor.tables.read"
        const val ENTRY_WRITE = "entrance.arrival.record"
        const val FINANCE_READ = "finance.report.read"
        const val CALENDAR_READ = "client.club.calendar.read"
        const val CALENDAR_WRITE = "client.club.calendar.write"
        const val PROFILE_READ = "client.club.profile.read"
        const val PROFILE_WRITE = "client.club.profile.write"
        const val REASON = "Synthetic lifecycle test"
        val MANAGER_SOURCE: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val FLOOR_SOURCE: UUID = UUID.fromString("00000000-0000-4000-8000-000000000002")
        val ENTRY_SOURCE: UUID = UUID.fromString("00000000-0000-4000-8000-000000000003")
        val DELEGATION_SOURCE: UUID = UUID.fromString("00000000-0000-4000-8000-000000000004")
        val SECOND_SOURCE: UUID = UUID.fromString("00000000-0000-4000-8000-000000000005")
        private val USERS = setOf(OWNER, MANAGER, TARGET, LEGACY, PLATFORM)
        private val LEGACY_TABLES =
            listOf(
                "user_roles",
                "user_role_permissions",
                "client_club_grants",
                "client_membership_grants",
                "client_club_staff",
            )
        private val TRACKED =
            LEGACY_TABLES +
                listOf(
                    "client_memberships",
                    "client_club_access_state",
                    "client_role_assignments",
                    "client_permission_delegations",
                    "client_commands",
                    "audit_log",
                )

        fun evidence(user: Long): String = "synthetic-signed:$user"

        fun telegram(user: Long): Long = user + 1_000_000
    }
}

internal class AssignmentLifecycleClock(
    var current: Instant = Instant.parse("2026-11-10T12:00:00Z"),
) : Clock() {
    override fun instant(): Instant = current

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
}

/** Faults occur on the repository's own transaction, without replacing its transaction handling. */
internal fun failingAssignmentInsert(
    source: DataSource,
    table: String,
): DataSource = failingAssignmentStatement(source, "INSERT INTO $table")

internal fun failingAssignmentStatement(
    source: DataSource,
    statementPrefix: String,
    beforeFailure: (Connection) -> Unit = {},
): DataSource =
    object : DataSource by source {
        override fun getConnection(): Connection = wrap(source.connection)

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = wrap(source.getConnection(username, password))

        private fun wrap(connection: Connection): Connection =
            Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, args ->
                try {
                    val result = method.invoke(connection, *(args.orEmpty()))
                    if (method.name == "prepareStatement" &&
                        (args?.firstOrNull() as? String)?.uppercase()?.startsWith(statementPrefix.uppercase()) ==
                        true
                    ) {
                        val statement = result as PreparedStatement
                        Proxy.newProxyInstance(
                            PreparedStatement::class.java.classLoader,
                            arrayOf(PreparedStatement::class.java),
                        ) { _, call, values ->
                            if (call.name.startsWith(
                                    "execute",
                                )
                            ) {
                                beforeFailure(connection)
                                throw SQLException("Synthetic assignment transaction failure", "HY000")
                            }
                            try {
                                call.invoke(statement, *(values.orEmpty()))
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
