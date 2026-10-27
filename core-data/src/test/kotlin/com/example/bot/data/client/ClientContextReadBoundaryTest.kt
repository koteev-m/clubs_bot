package com.example.bot.data.client

import com.example.bot.data.TestDatabase
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

internal class ClientContextReadBoundaryTest {
    @Test
    fun `each read executes exactly one parameterized SELECT in an explicit readonly read committed transaction`() =
        runBlocking<Unit> {
            ClientContextFixture.h2().use { fixture ->
                fixture.seed()
                val source = RecordingClientDataSource(fixture.dataSource)
                val repository = JdbcClientContextRepository(source)
                repository.context(880061001, ClientAudience.Client(101, setOf(1001)))
                assertEquals(1, source.preparedQueries.size)
                assertEquals(1, source.connectionCount)
                assertTrue(source.preparedQueries.single().startsWith("SELECT"))
                assertTrue(source.preparedQueries.single().contains("k.id IN (?)"))
                assertTrue(source.preparedQueries.none { it.contains("880061001") || it.contains("1001") })
                assertEquals(listOf(true), source.readOnlyValues)
                assertEquals(listOf(Connection.TRANSACTION_READ_COMMITTED), source.isolationValues)
                assertEquals(listOf(false), source.autoCommitValues)
                repository.club(880061001, ClientAudience.Platform, 1001)
                assertEquals(2, source.preparedQueries.size)
                assertEquals(2, source.connectionCount)
            }
        }

    @Test
    fun `database failure and cancellation propagate without returning previous authority`() =
        runBlocking<Unit> {
            ClientContextFixture.h2().use { fixture ->
                fixture.seed()
                var failure: Exception? = null
                val source =
                    object : DataSource by fixture.dataSource {
                        override fun getConnection(): Connection {
                            failure?.let { throw it }
                            return fixture.dataSource.connection
                        }
                    }
                val repository = JdbcClientContextRepository(source)
                assertEquals(
                    2,
                    repository
                        .context(880061001, ClientAudience.Platform)
                        .single()
                        .clubs.size,
                )
                failure = SQLException("synthetic database unavailable")
                assertThrows(
                    SQLException::class.java,
                ) { runBlocking { repository.context(880061001, ClientAudience.Platform) } }
                failure = CancellationException("synthetic cancellation")
                assertThrows(CancellationException::class.java) {
                    runBlocking { repository.club(880061001, ClientAudience.Platform, 1001) }
                }
            }
        }

    @Test
    fun `fresh latest migrations create empty authority and no implicit grants`() {
        TestDatabase().use { fixture ->
            fixture.dataSource.connection.use { connection ->
                ClientContextFixture.NEW_TABLES.forEach { table ->
                    assertEquals(
                        listOf(listOf("0")),
                        ClientContextFixture.snapshotQuery(connection, "SELECT COUNT(*) FROM $table"),
                    )
                }
                assertEquals(
                    listOf(listOf("0")),
                    ClientContextFixture.snapshotQuery(
                        connection,
                        "SELECT COUNT(*) FROM user_role_permissions WHERE permission_code='client.club.read'",
                    ),
                )
            }
        }
    }

    @Test
    fun `schema drift is rejected instead of silently adopting an existing client table`() {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:client-drift-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_UPPER=false"
                username = "sa"
                password = ""
                maximumPoolSize = 3
            },
        ).use { source ->
            fun configured(target: String? = null): Flyway =
                Flyway
                    .configure()
                    .dataSource(source)
                    .locations("classpath:db/migration/common", "classpath:db/migration/h2")
                    .apply { target?.let { target(it) } }
                    .load()
            configured("60").migrate()
            source.connection.use { connection ->
                connection.createStatement().use { it.execute("CREATE TABLE clients (id BIGINT PRIMARY KEY)") }
            }
            assertThrows(FlywayException::class.java) { configured().migrate() }
        }
    }
}

private class RecordingClientDataSource(
    private val source: DataSource,
) : DataSource by source {
    val preparedQueries = mutableListOf<String>()
    val readOnlyValues = mutableListOf<Boolean>()
    val isolationValues = mutableListOf<Int>()
    val autoCommitValues = mutableListOf<Boolean>()
    var connectionCount = 0

    override fun getConnection(): Connection {
        connectionCount++
        val connection = source.connection
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "prepareStatement" -> preparedQueries += args!![0] as String
                "setReadOnly" -> readOnlyValues += args!![0] as Boolean
                "setTransactionIsolation" -> isolationValues += args!![0] as Int
                "setAutoCommit" -> autoCommitValues += args!![0] as Boolean
            }
            try {
                method.invoke(connection, *args.orEmpty())
            } catch (exception: InvocationTargetException) {
                throw exception.targetException
            }
        } as Connection
    }
}
