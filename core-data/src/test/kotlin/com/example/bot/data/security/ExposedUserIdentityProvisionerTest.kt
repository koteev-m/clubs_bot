package com.example.bot.data.security

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class ExposedUserIdentityProvisionerTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var repository: ExposedUserRepository

    @BeforeEach
    fun setUp() {
        val jdbcUrl =
            "jdbc:h2:mem:user-identity-${UUID.randomUUID()};" +
                "MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    this.jdbcUrl = jdbcUrl
                    driverClassName = "org.h2.Driver"
                    username = "sa"
                    password = ""
                    maximumPoolSize = 4
                },
            )
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/common", "classpath:db/migration/h2")
            .cleanDisabled(false)
            .load()
            .also { flyway ->
                flyway.clean()
                flyway.migrate()
            }
        database = Database.connect(dataSource)
        repository = ExposedUserRepository(database)
    }

    @AfterEach
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `first ensure creates one minimal identity and sequential retry reuses it`() =
        runBlocking {
            val first = repository.ensureMinimalIdentity(TELEGRAM_USER_ID)
            val second = repository.ensureMinimalIdentity(TELEGRAM_USER_ID)

            assertEquals(first, second)
            assertEquals(1L, userCount(TELEGRAM_USER_ID))
            val row = userRow(TELEGRAM_USER_ID)
            assertNull(row[UsersTable.username])
            assertNull(row[UsersTable.displayName])
            assertNull(row[UsersTable.phoneE164])
            assertEquals(0L, roleCount(first.id))
        }

    @Test
    fun `non-positive telegram ids are rejected without persistence`() {
        listOf(0L, -1L).forEach { telegramUserId ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.ensureMinimalIdentity(telegramUserId) }
            }
            assertEquals(0L, userCount(telegramUserId))
        }
    }

    @Test
    fun `ensure returns existing identity without changing profile fields`() =
        runBlocking {
            val existingId =
                transaction(database) {
                    UsersTable.insert {
                        it[telegramUserId] = TELEGRAM_USER_ID
                        it[username] = "kept_username"
                        it[displayName] = "Kept Display Name"
                        it[phoneE164] = "+79990000000"
                    } get UsersTable.id
                }

            val ensured = repository.ensureMinimalIdentity(TELEGRAM_USER_ID)

            assertEquals(existingId, ensured.id)
            assertEquals("kept_username", ensured.username)
            assertEquals(1L, userCount(TELEGRAM_USER_ID))
            val row = userRow(TELEGRAM_USER_ID)
            assertEquals("Kept Display Name", row[UsersTable.displayName])
            assertEquals("+79990000000", row[UsersTable.phoneE164])
        }

    private fun userCount(telegramUserId: Long): Long =
        transaction(database) {
            UsersTable
                .selectAll()
                .where { UsersTable.telegramUserId eq telegramUserId }
                .count()
        }

    private fun userRow(telegramUserId: Long) =
        transaction(database) {
            UsersTable
                .selectAll()
                .where { UsersTable.telegramUserId eq telegramUserId }
                .single()
        }

    private fun roleCount(userId: Long): Long =
        transaction(database) {
            UserRolesTable
                .selectAll()
                .where { UserRolesTable.userId eq userId }
                .count()
        }

    private companion object {
        const val TELEGRAM_USER_ID = 8_800_100L
    }
}
