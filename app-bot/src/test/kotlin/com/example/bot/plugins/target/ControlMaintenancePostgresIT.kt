package com.example.bot.plugins.target

import com.example.bot.data.client.bindings.InactiveMaintenanceAction
import com.example.bot.data.client.bindings.MaintenanceMutationStatus
import com.example.bot.data.client.onboarding.OnboardingFailure
import kotlinx.coroutines.async
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.postgresql.ds.PGSimpleDataSource
import java.net.URI
import java.sql.DriverManager
import java.util.UUID
import javax.sql.DataSource

/** Never uses application DB configuration: only an explicit local disposable DB or the fixed CI service. */
@Tag("it")
class ControlMaintenancePostgresIT : ControlMaintenanceDatabaseTest() {
    @org.junit.jupiter.api.Test
    fun `both controlled disable revoke orders serialize on independent DB connections`() {
        for (first in listOf(
            InactiveMaintenanceAction.DISABLE,
            InactiveMaintenanceAction.REVOKE,
        )) {
            database { source -> kotlinx.coroutines.runBlocking { concurrentPair(source, first) } }
        }
    }

    private suspend fun concurrentPair(
        source: DataSource,
        first: InactiveMaintenanceAction,
    ) = kotlinx.coroutines.coroutineScope {
        val f = MaintenanceDatabaseFixture(source)
        val gate = ControlMaintenanceFixture(f)
        val pin = gate.pin()
        val second =
            if (first == InactiveMaintenanceAction.DISABLE) {
                InactiveMaintenanceAction.REVOKE
            } else {
                InactiveMaintenanceAction.DISABLE
            }
        source.connection.use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.executeQuery("SELECT id FROM target_bot_connections FOR UPDATE").close() }
            val name = "control-pair-" + UUID.randomUUID()
            (source as PGSimpleDataSource).applicationName = name
            val earlier =
                async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    gate.adapter().execute(pin, first, "first-pair", f.identity.signing.headers())
                }
            awaitControlledWait(source, name, 1)
            val later =
                async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    org.junit.jupiter.api.assertThrows<OnboardingFailure> {
                        gate.adapter().execute(pin, second, "second-pair", f.identity.signing.headers())
                    }
                }
            awaitControlledWait(source, name, 2)
            holder.rollback()
            kotlin.test.assertEquals(MaintenanceMutationStatus.APPLIED, earlier.await().status)
            kotlin.test.assertEquals("version_conflict", later.await().code)
        }
    }

    private suspend fun awaitControlledWait(
        source: DataSource,
        name: String,
        expected: Int,
    ) {
        val deadline =
            System.nanoTime() +
                java.util.concurrent.TimeUnit.SECONDS
                    .toNanos(2)
        while (true) {
            val count = controlledWaitCount(source, name)
            if (count == expected) return
            check(System.nanoTime() < deadline)
            kotlinx.coroutines.delay(10)
        }
    }

    private fun controlledWaitCount(
        source: DataSource,
        name: String,
    ): Int =
        source.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT COUNT(*) FROM pg_stat_activity " +
                            "WHERE application_name='$name' AND wait_event_type='Lock'",
                    ).use { rows ->
                        check(rows.next())
                        rows.getInt(1)
                    }
            }
        }

    @org.junit.jupiter.api.Test
    fun `actual actor remapping during signed head lease denies all binding writes`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            val gate = ControlMaintenanceFixture(f)
            kotlinx.coroutines.runBlocking {
                val pin = gate.pin()
                source.connection.use { holder ->
                    holder.autoCommit = false
                    holder.createStatement().use {
                        it.executeUpdate("UPDATE users SET telegram_user_id=9 WHERE id=978001")
                    }
                    val name = "control-actor-" + UUID.randomUUID()
                    (source as PGSimpleDataSource).applicationName = name
                    val pending =
                        async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                            org.junit.jupiter.api
                                .assertThrows<OnboardingFailure> {
                                    gate.adapter().execute(
                                        pin,
                                        InactiveMaintenanceAction.REVOKE,
                                        "remapped-actor",
                                        f.identity.signing.headers(),
                                    )
                                }
                        }
                    val deadline =
                        System.nanoTime() +
                            java.util.concurrent.TimeUnit.SECONDS
                                .toNanos(2)
                    while (true) {
                        val count =
                            source.connection.use { connection ->
                                connection.createStatement().use { statement ->
                                    statement
                                        .executeQuery(
                                            "SELECT COUNT(*) FROM pg_stat_activity " +
                                                "WHERE application_name='$name' AND wait_event_type='Lock'",
                                        ).use { rows ->
                                            check(rows.next())
                                            rows.getInt(1)
                                        }
                                }
                            }
                        if (count == 1) break
                        check(System.nanoTime() < deadline)
                        kotlinx.coroutines.delay(10)
                    }
                    holder.commit()
                    val committed = f.snapshot()
                    kotlin.test.assertEquals("forbidden", pending.await().code)
                    kotlin.test.assertEquals(committed, f.snapshot())
                }
            }
        }

    override fun database(block: (DataSource) -> Unit) {
        val source = disposableSource()
        val schema = "control_target_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(source.getURL(), source.user, source.password).use { connection ->
            check(connection.metaData.databaseMajorVersion == 16)
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
                listOf("pgcrypto", "pg_trgm", "uuid-ossp").forEach {
                    statement.execute("CREATE EXTENSION IF NOT EXISTS \"$it\" WITH SCHEMA public")
                }
            }
        }
        source.currentSchema = "$schema,public"
        try {
            Flyway
                .configure()
                .dataSource(source)
                .locations("classpath:db/migration/common", "classpath:db/migration/postgresql")
                .load()
                .also {
                    it.migrate()
                    it.validate()
                }
            block(source)
        } finally {
            DriverManager.getConnection(source.getURL(), source.user, source.password).use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    private fun disposableSource(): PGSimpleDataSource {
        val supplied = System.getenv("CLB_CLIENT_TEST_PG_URL")
        val ci = supplied == null && System.getenv("CI") == "true" && System.getenv("GITHUB_ACTIONS") == "true"
        val url = if (ci) "jdbc:postgresql://127.0.0.1:5432/botdb" else checkNotNull(supplied)
        val parsed = URI(url.removePrefix("jdbc:"))
        require(
            parsed.scheme == "postgresql" &&
                parsed.host == "127.0.0.1" &&
                parsed.query == null &&
                parsed.userInfo == null,
        )
        require(parsed.path == if (ci) "/botdb" else "/clb_client_test")
        return PGSimpleDataSource().apply {
            setURL(url)
            user = if (ci) "botuser" else "clb_client_test"
            password = if (ci) "botpass" else ""
        }
    }
}
