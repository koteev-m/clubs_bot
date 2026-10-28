package com.example.bot.data.client

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import java.sql.Connection
import java.util.UUID

internal class ClientContextFixture(
    val dataSource: HikariDataSource,
    private val vendor: String,
) : AutoCloseable {
    val repository = JdbcClientContextRepository(dataSource)
    val legacyBefore: Map<String, List<List<String?>>>
    val migrationCount: Int

    init {
        flyway("60").migrate()
        execute(
            "INSERT INTO users(id, telegram_user_id) VALUES " +
                "(5001,880061001),(5002,880061002),(5003,880061003),(5004,880061004)," +
                "(5005,880061005),(5006,880061006),(5007,880061007)",
            "INSERT INTO clubs(id,name,timezone) VALUES " +
                "(1001,'A1','UTC'),(1002,'A2','UTC'),(2001,'B1','UTC'),(3001,'Unmapped','UTC')," +
                "(9007199254740993,'Large club','UTC')",
            "INSERT INTO user_roles(id,user_id,role_code,scope_type,scope_club_id) VALUES " +
                "(8001,5005,'OWNER','GLOBAL',NULL),(8002,5005,'GLOBAL_ADMIN','GLOBAL',NULL)," +
                "(8003,5002,'MANAGER','CLUB',1002)",
            "INSERT INTO user_role_permissions(user_role_id,permission_code) VALUES (8003,'support.view')",
        )
        legacyBefore = legacySnapshot()
        migrationCount = flyway().migrate().migrationsExecuted
        flyway().validate()
        assertEquals(legacyBefore, legacySnapshot())
        NEW_TABLES.forEach { assertEquals("0", rows("SELECT COUNT(*) FROM $it").single().single()) }
    }

    fun seed() {
        execute(
            "INSERT INTO clients(id,display_name,is_active) VALUES " +
                "(101,'Client A',TRUE),(102,'Client B',TRUE),(9007199254740993,'Large client',TRUE)",
            "INSERT INTO client_memberships(client_id,user_id,is_active) VALUES " +
                "(101,5001,TRUE),(101,5002,TRUE),(102,5003,TRUE),(101,5004,TRUE)," +
                "(101,5006,TRUE),(102,5006,TRUE),(9007199254740993,5007,TRUE)",
            "INSERT INTO client_clubs(client_id,club_id) VALUES " +
                "(101,1001),(101,1002),(102,2001),(9007199254740993,9007199254740993)",
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES " +
                "(101,5001,1001,'client.club.read',TRUE),(101,5001,1002,'client.club.read',TRUE)," +
                "(101,5002,1001,'client.club.read',TRUE),(102,5003,2001,'client.club.read',TRUE)," +
                "(101,5006,1001,'client.club.read',TRUE),(102,5006,2001,'client.club.read',TRUE)," +
                "(9007199254740993,5007,9007199254740993,'client.club.read',TRUE)",
        )
    }

    fun flyway(target: String? = null): Flyway =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/common", "classpath:db/migration/$vendor")
            // This contract intentionally verifies the V061 boundary independently of later migrations.
            .target(target ?: "61")
            .load()

    fun execute(vararg sql: String) {
        dataSource.connection.use { connection ->
            sql.forEach { connection.createStatement().use { statement -> statement.execute(it) } }
        }
    }

    fun rows(sql: String): List<List<String?>> =
        dataSource.connection.use { connection -> snapshotQuery(connection, sql) }

    fun legacySnapshot(): Map<String, List<List<String?>>> =
        LEGACY_TABLES.associateWith { table -> rows("SELECT * FROM $table ORDER BY 1") }

    fun allSnapshot(): Map<String, List<List<String?>>> =
        (LEGACY_TABLES + NEW_TABLES).associateWith { table ->
            rows("SELECT * FROM $table ORDER BY 1").sortedBy { it.toString() }
        }

    override fun close() = dataSource.close()

    companion object {
        val LEGACY_TABLES = listOf("users", "clubs", "roles", "user_roles", "user_role_permissions")
        val NEW_TABLES = listOf("clients", "client_memberships", "client_clubs", "client_club_grants")

        fun h2(): ClientContextFixture =
            ClientContextFixture(
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = "jdbc:h2:mem:client-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_UPPER=false"
                        username = "sa"
                        password = ""
                        maximumPoolSize = 3
                    },
                ),
                "h2",
            )

        fun snapshotQuery(
            connection: Connection,
            sql: String,
        ): List<List<String?>> =
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
}
