package com.example.bot.data.client.bindings

import com.example.bot.data.client.assignments.AssignmentTestDatabase
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import java.util.UUID
import javax.sql.DataSource

internal class BindingTestFixture(
    val database: AssignmentTestDatabase,
) : AutoCloseable {
    val clock = BindingTestClock()
    val source = BindingTestSource(database.dataSource)
    val authorityConnection: UUID = UUID.randomUUID()
    val authorityRef: UUID = UUID.randomUUID()

    @Volatile var identityAllowed = true
    var botAllowed = true
    var referencesAllowed = true
    var identityChecks = 0
    var onIdentity: () -> Unit = {}
    val providers =
        object : BindingEvidenceProvider {
            override fun maintenance(
                evidence: String,
                now: Instant,
            ): MaintenanceIdentity? {
                identityChecks++
                onIdentity()
                if (!identityAllowed || evidence != EVIDENCE) return null
                return MaintenanceIdentity(TELEGRAM, authorityConnection, authorityRef, START, START.plusSeconds(240))
            }

            override fun bot(
                command: BindingCommand.Create,
                now: Instant,
            ): BotIdentityEvidence? =
                if (botAllowed) {
                    BotIdentityEvidence(
                        command.rootId,
                        command.botId,
                        command.provenanceId,
                        START,
                        START.plusSeconds(240),
                    )
                } else {
                    null
                }

            override fun credential(
                command: BindingCommand.AttachReference,
                now: Instant,
            ): CredentialReferenceEvidence? =
                if (referencesAllowed) {
                    CredentialReferenceEvidence(
                        command.rootId,
                        BOT,
                        command.referenceId,
                        command.revision,
                        command.materialId,
                        command.attestationId,
                        START,
                        START.plusSeconds(240),
                    )
                } else {
                    null
                }
        }
    val repository =
        JdbcInactiveBindingFoundation(
            source,
            TELEGRAM,
            providers,
            clock,
            storageSchema =
                BindingStorageSchema.fromServerConfiguration(
                    database.dataSource.connection.use { it.schema },
                ),
        )

    init {
        database.execute(
            "INSERT INTO users(id,telegram_user_id) VALUES ($ACTOR,$TELEGRAM),($FOREIGN,878002)",
            "INSERT INTO clients(id,display_name,is_active) VALUES (101,'Synthetic A',TRUE),(102,'Synthetic B',TRUE)",
            "INSERT INTO clubs(id,name,timezone,is_active) VALUES (1001,'Private A','UTC',FALSE),(1002,'Private B','UTC',FALSE),(1003,'Unmapped','UTC',FALSE),(2001,'Foreign','UTC',FALSE)",
            "INSERT INTO client_clubs(client_id,club_id,created_at) VALUES " +
                "(101,1001,'2026-11-11T00:00:00Z'),(101,1002,'2026-11-11T00:00:00Z'),(102,2001,'2026-11-11T00:00:00Z')",
        )
    }

    fun create(): BindingCommand.Create =
        BindingCommand.Create(UUID.randomUUID(), UUID.randomUUID(), BOT, UUID.randomUUID())

    suspend fun issue(
        command: BindingCommand = create(),
        key: String = UUID.randomUUID().toString(),
    ): BindingView = repository.mutate(EVIDENCE, command, key).current

    fun attach(
        view: BindingView,
        material: UUID = UUID.randomUUID(),
        revision: Long = 1,
    ): BindingCommand.AttachReference =
        BindingCommand.AttachReference(
            view.rootId,
            view.fence,
            UUID.randomUUID(),
            revision,
            material,
            UUID.randomUUID(),
        )

    fun epoch(client: Long = 101): Long =
        checkNotNull(database.scalar("SELECT target_mapping_epoch FROM clients WHERE id=$client")).toLong()

    fun snapshot(): Map<String, List<List<String?>>> =
        (TABLES + listOf("audit_log")).associateWith {
            database.rows("SELECT * FROM $it ORDER BY 1").sortedBy { row -> row.toString() }
        }

    fun legacy(): Map<String, List<List<String?>>> =
        listOf(
            "users",
            "clubs",
            "client_memberships",
            "client_club_grants",
            "client_membership_grants",
            "client_club_staff",
            "client_commands",
            "user_roles",
            "user_role_permissions",
            "client_role_assignments",
            "client_permission_delegations",
            "client_club_access_state",
        ).associateWith { database.rows("SELECT * FROM $it ORDER BY 1").sortedBy { row -> row.toString() } }

    override fun close() = database.close()

    companion object {
        const val ACTOR = 78001L
        const val FOREIGN = 78002L
        const val TELEGRAM = 878001L
        const val BOT = 780001L
        const val EVIDENCE = "fixture-maintenance"
        val START: Instant = Instant.parse("2026-11-11T12:00:00Z")
        val TABLES =
            listOf(
                "target_bot_connections",
                "target_bot_bindings",
                "target_credential_materials",
                "target_credential_revisions",
                "target_binding_clubs",
                "target_binding_commands",
            )
    }
}

internal class BindingTestClock : Clock() {
    @Volatile var now = BindingTestFixture.START

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)

    override fun instant(): Instant = now
}

internal class BindingTestSource(
    private val source: DataSource,
) : DataSource by source {
    @Volatile var beforeSql: (String, Connection) -> Unit = { _, _ -> }
    val statements: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile var applicationName: String? = null

    override fun getConnection(): Connection {
        val connection = source.connection
        if (applicationName != null && connection.metaData.databaseProductName == "PostgreSQL") {
            connection.prepareStatement("SELECT set_config('application_name',?,false)").use {
                it.setString(1, applicationName)
                it.execute()
            }
        }
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            if (method.name == "prepareStatement") {
                val sql = args!![0] as String
                statements += sql
                val statement = invoke(connection, method, args) as PreparedStatement
                return@newProxyInstance Proxy.newProxyInstance(
                    PreparedStatement::class.java.classLoader,
                    arrayOf(PreparedStatement::class.java),
                ) { _, call, values ->
                    if (call.name.startsWith("execute")) beforeSql(sql, connection)
                    invoke(statement, call, values)
                }
            }
            invoke(connection, method, args)
        } as Connection
    }

    private fun invoke(
        target: Any,
        method: Method,
        args: Array<out Any?>?,
    ): Any? =
        try {
            method.invoke(target, *args.orEmpty())
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
}
