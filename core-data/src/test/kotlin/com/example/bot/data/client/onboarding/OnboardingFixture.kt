package com.example.bot.data.client.onboarding

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

internal class MutableOnboardingClock(
    @Volatile
    var current: Instant = Instant.parse("2026-10-28T12:00:00Z"),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current
}

internal class OnboardingFixture(
    val source: HikariDataSource,
    val vendor: String,
    target: String? = null,
) : AutoCloseable {
    val clock = MutableOnboardingClock()
    val owner = OnboardingActor(880062001, "platform", "Synthetic platform", "synthetic_platform")
    val alice = OnboardingActor(880062002, "platform", "Synthetic A", "synthetic_a")
    val bob = OnboardingActor(880062003, "platform", "Synthetic B", "synthetic_b")
    val repository = JdbcClientOnboardingRepository(source, owner.telegramUserId, clock)
    val baseline: Map<String, List<DbRow>>

    init {
        migrate(target)
        baseline = listOf("users", "clubs", "user_roles").associateWith { rows("SELECT * FROM $it ORDER BY id") }
    }

    fun migrate(target: String? = null): Flyway =
        Flyway
            .configure()
            .dataSource(source)
            .locations("classpath:db/migration/common", "classpath:db/migration/$vendor")
            .apply { if (target != null) target(target) }
            .load()
            .also {
                it.migrate()
                it.validate()
            }

    suspend fun issue(key: String = UUID.randomUUID().toString()): JsonObject =
        repository.mutate(
            owner,
            OnboardingMutation.INVITATION_ISSUE,
            body =
                buildJsonObject {
                    put("clientName", "Synthetic client")
                    put("recipientLabel", "Synthetic recipient")
                },
            idempotencyKey = key,
        )

    suspend fun claim(
        invitation: JsonObject,
        actor: OnboardingActor = alice,
        key: String = UUID.randomUUID().toString(),
    ): JsonObject =
        repository.mutate(
            actor,
            OnboardingMutation.INVITATION_CLAIM,
            invitation.id("id"),
            buildJsonObject {
                put("token", invitation.getValue("token"))
                put("expectedVersion", invitation.getValue("version"))
            },
            key,
        )

    suspend fun accept(actor: OnboardingActor = alice): JsonObject {
        val issued = issue()
        val claimed = claim(issued, actor)
        return mutate(owner, OnboardingMutation.INVITATION_CONFIRM, claimed)
    }

    suspend fun draft(
        clientId: Long,
        actor: OnboardingActor = alice,
        venue: String = "Synthetic venue",
    ): JsonObject =
        repository.mutate(
            actor,
            OnboardingMutation.APPLICATION_CREATE,
            clientId,
            fields(venue),
            UUID.randomUUID().toString(),
        )

    suspend fun mutate(
        actor: OnboardingActor,
        operation: OnboardingMutation,
        resource: JsonObject,
        extra: JsonObject = JsonObject(emptyMap()),
        key: String = UUID.randomUUID().toString(),
    ): JsonObject =
        repository.mutate(
            actor,
            operation,
            resource.id("id"),
            JsonObject(extra + ("expectedVersion" to resource.getValue("version"))),
            key,
        )

    fun rows(sql: String): List<DbRow> = source.connection.use { OnboardingSql(it).rows(sql) }

    fun execute(sql: String) =
        source.connection.use { it.createStatement().use { statement -> statement.execute(sql) } }

    fun count(table: String): Long = rows("SELECT COUNT(*) AS total FROM $table").single().long("total")

    fun failingAuditRepository(): JdbcClientOnboardingRepository =
        JdbcClientOnboardingRepository(
            object : DataSource by source {
                override fun getConnection(): Connection {
                    val delegate = source.connection
                    return Proxy.newProxyInstance(
                        Connection::class.java.classLoader,
                        arrayOf(Connection::class.java),
                    ) { _, method, arguments ->
                        if (method.name == "prepareStatement" &&
                            arguments?.firstOrNull()?.toString()?.startsWith("INSERT INTO audit_log") == true
                        ) {
                            throw SQLException("Synthetic audit failure", "XX000")
                        }
                        try {
                            method.invoke(delegate, *arguments.orEmpty())
                        } catch (failure: InvocationTargetException) {
                            throw failure.targetException
                        }
                    } as Connection
                }
            },
            owner.telegramUserId,
            clock,
        )

    override fun close() = source.close()

    companion object {
        fun fields(venue: String = "Synthetic venue") =
            buildJsonObject {
                put("venueName", venue)
                put("countryCode", "RU")
                put("city", "Synthetic city")
                put("address", "Synthetic street 1")
                put("unit", "")
                put("timezone", "Europe/Moscow")
                put("comment", "Synthetic test application")
            }

        fun approval() =
            buildJsonObject {
                put("commercialReference", "SYNTHETIC-AGREEMENT-062")
                put("commercialAgreedAt", "2026-10-27")
                put("platformConfirmed", true)
                put("internalNote", "PRIVATE-SYNTHETIC-NOTE")
                put("reason", "Согласовано")
            }

        fun h2(target: String? = null): OnboardingFixture =
            OnboardingFixture(
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl =
                            "jdbc:h2:mem:onboarding-${UUID.randomUUID()};MODE=PostgreSQL;" +
                            "DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1"
                        username = "sa"
                        password = ""
                        maximumPoolSize = 6
                    },
                ),
                "h2",
                target,
            )
    }
}
