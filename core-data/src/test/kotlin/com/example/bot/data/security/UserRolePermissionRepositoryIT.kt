package com.example.bot.data.security

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import testing.RequiresDocker

@RequiresDocker
@Tag("it")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserRolePermissionRepositoryIT {
    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var rolesBeforeMigration: List<String>
    private lateinit var rolesAfterMigration: List<String>
    private lateinit var assignmentsBeforeMigration: List<PersistedAssignment>
    private lateinit var assignmentsAfterMigration: List<PersistedAssignment>
    private lateinit var permissionDefinitions: Set<String>
    private var migrationsExecuted = 0
    private var currentMigrationVersion = ""
    private var defaultGrantCount = -1L

    @BeforeAll
    fun migrateFromLegacySchema() {
        postgres.start()
        val locations = arrayOf("classpath:db/migration/common", "classpath:db/migration/postgresql")
        val legacyFlyway =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations(*locations)
                .target(LEGACY_VERSION)
                .cleanDisabled(false)
                .load()
        legacyFlyway.clean()
        legacyFlyway.migrate()

        postgres.createConnection("").use { connection ->
            rolesBeforeMigration = readRoles(connection)
            assignmentsBeforeMigration = readAssignments(connection)
        }

        val latestFlyway =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations(*locations)
                .load()
        migrationsExecuted = latestFlyway.migrate().migrationsExecuted
        currentMigrationVersion =
            latestFlyway
                .info()
                .current()
                ?.version
                ?.toString()
                .orEmpty()

        postgres.createConnection("").use { connection ->
            rolesAfterMigration = readRoles(connection)
            assignmentsAfterMigration = readAssignments(connection)
            permissionDefinitions = readPermissions(connection)
            defaultGrantCount = readGrantCount(connection)
        }

        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    driverClassName = postgres.driverClassName
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 3
                },
            )
        database = Database.connect(dataSource)
    }

    @AfterAll
    fun stopContainer() {
        if (::dataSource.isInitialized) dataSource.close()
        postgres.stop()
    }

    @Test
    fun `postgres V058 preserves roles and assignments and creates no grants`() {
        assertEquals(1, migrationsExecuted)
        assertEquals(EXPECTED_VERSION, currentMigrationVersion)
        assertTrue(assignmentsBeforeMigration.isNotEmpty())
        assertEquals(rolesBeforeMigration, rolesAfterMigration)
        assertEquals(assignmentsBeforeMigration, assignmentsAfterMigration)
        assertEquals(EXPECTED_PERMISSIONS, permissionDefinitions)
        assertEquals(0L, defaultGrantCount)
    }

    @Test
    fun `postgres evaluates role CLUB scope and exact persisted permission assignment`() =
        runBlocking {
            val repository = ExposedUserRolePermissionRepository(database)
            val userId = insertUser(8_800_581_001L)
            val otherUserId = insertUser(8_800_581_002L)
            val clubA = insertClub("Postgres permission club A")
            val clubB = insertClub("Postgres permission club B")
            val clubC = insertClub("Postgres permission club C")
            val clubD = insertClub("Postgres permission club D")

            val managerA = insertAssignment(userId, Role.MANAGER, "CLUB", clubA)
            val managerB = insertAssignment(userId, Role.MANAGER, "CLUB", clubB)
            val entryManagerB = insertAssignment(userId, Role.ENTRY_MANAGER, "CLUB", clubB)
            val clubAdminC = insertAssignment(userId, Role.CLUB_ADMIN, "CLUB", clubC)
            val ownerD = insertAssignment(userId, Role.OWNER, "CLUB", clubD)
            val globalManager = insertAssignment(userId, Role.MANAGER, "GLOBAL", null)
            val globalAdmin = insertAssignment(userId, Role.GLOBAL_ADMIN, "GLOBAL", null)
            val otherManagerA = insertAssignment(otherUserId, Role.MANAGER, "CLUB", clubA)

            insertGrant(entryManagerB, PermissionCodes.SUPPORT_VIEW)
            insertGrant(managerB, PermissionCodes.SUPPORT_REPLY)
            insertGrant(clubAdminC, PermissionCodes.SUPPORT_VIEW)
            insertGrant(ownerD, PermissionCodes.SUPPORT_VIEW)
            insertGrant(globalManager, PermissionCodes.SUPPORT_VIEW)
            insertGrant(globalAdmin, PermissionCodes.SUPPORT_VIEW)
            insertGrant(otherManagerA, PermissionCodes.SUPPORT_VIEW)

            assertFalse(can(repository, userId, clubA, PermissionCodes.SUPPORT_VIEW))
            assertFalse(can(repository, userId, clubB, PermissionCodes.SUPPORT_VIEW))
            assertTrue(can(repository, userId, clubB, PermissionCodes.SUPPORT_REPLY))
            assertFalse(can(repository, userId, clubB, PermissionCodes.SUPPORT_STATUS_MANAGE))
            assertTrue(can(repository, userId, clubC, PermissionCodes.SUPPORT_VIEW))
            assertFalse(can(repository, userId, clubD, PermissionCodes.SUPPORT_VIEW))
            assertFalse(can(repository, otherUserId, clubB, PermissionCodes.SUPPORT_REPLY))
            assertEquals(
                setOf(clubC),
                repository.listClubIdsForPermission(
                    userId = userId,
                    allowedRoles = OPERATIONAL_SUPPORT_ROLES,
                    permission = PermissionCodes.SUPPORT_VIEW,
                ),
            )

            insertGrant(managerA, PermissionCodes.SUPPORT_STATUS_MANAGE)
            assertTrue(can(repository, userId, clubA, PermissionCodes.SUPPORT_STATUS_MANAGE))
            assertFalse(can(repository, userId, clubA, PermissionCodes.SUPPORT_VIEW))
            assertFalse(can(repository, userId, clubA, PermissionCodes.SUPPORT_REPLY))

            insertGrant(managerA, PermissionCodes.SUPPORT_VIEW)
            assertThrows(ExposedSQLException::class.java) {
                insertGrant(managerA, PermissionCodes.SUPPORT_VIEW)
            }
            assertEquals(2L, grantCount(managerA))

            val freshRepository = ExposedUserRolePermissionRepository(database)
            assertEquals(
                linkedSetOf(clubA, clubC),
                freshRepository.listClubIdsForPermission(
                    userId = userId,
                    allowedRoles = OPERATIONAL_SUPPORT_ROLES,
                    permission = PermissionCodes.SUPPORT_VIEW,
                ),
            )

            transaction(database) {
                assertEquals(1, UserRolesTable.deleteWhere { UserRolesTable.id eq managerA })
            }
            assertEquals(0L, grantCount(managerA))
            assertFalse(can(freshRepository, userId, clubA, PermissionCodes.SUPPORT_VIEW))
        }

    private suspend fun can(
        repository: UserRolePermissionRepository,
        userId: Long,
        clubId: Long,
        permission: PermissionCode,
    ): Boolean =
        repository.hasClubPermission(
            userId = userId,
            clubId = clubId,
            allowedRoles = OPERATIONAL_SUPPORT_ROLES,
            permission = permission,
        )

    private fun insertClub(name: String): Long =
        transaction(database) {
            PermissionPostgresTestClubsTable.insert {
                it[PermissionPostgresTestClubsTable.name] = name
                it[PermissionPostgresTestClubsTable.timezone] = "Europe/Moscow"
            } get PermissionPostgresTestClubsTable.id
        }

    private fun insertUser(telegramUserId: Long): Long =
        transaction(database) {
            UsersTable.insert {
                it[UsersTable.telegramUserId] = telegramUserId
            } get UsersTable.id
        }

    private fun insertAssignment(
        userId: Long,
        role: Role,
        scopeType: String,
        clubId: Long?,
    ): Long =
        transaction(database) {
            UserRolesTable.insert {
                it[UserRolesTable.userId] = userId
                it[UserRolesTable.roleCode] = role.name
                it[UserRolesTable.scopeType] = scopeType
                it[UserRolesTable.scopeClubId] = clubId
            } get UserRolesTable.id
        }

    private fun insertGrant(
        assignmentId: Long,
        permission: PermissionCode,
    ) {
        transaction(database) {
            UserRolePermissionsTable.insert {
                it[UserRolePermissionsTable.userRoleId] = assignmentId
                it[UserRolePermissionsTable.permissionCode] = permission.value
            }
        }
    }

    private fun grantCount(assignmentId: Long): Long =
        transaction(database) {
            UserRolePermissionsTable
                .selectAll()
                .where { UserRolePermissionsTable.userRoleId eq assignmentId }
                .count()
        }

    private fun readRoles(connection: java.sql.Connection): List<String> =
        connection.prepareStatement("SELECT code FROM roles ORDER BY code").use { statement ->
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) add(resultSet.getString("code"))
                }
            }
        }

    private fun readPermissions(connection: java.sql.Connection): Set<String> =
        connection.prepareStatement("SELECT code FROM permissions ORDER BY code").use { statement ->
            statement.executeQuery().use { resultSet ->
                buildSet {
                    while (resultSet.next()) add(resultSet.getString("code"))
                }
            }
        }

    private fun readAssignments(connection: java.sql.Connection): List<PersistedAssignment> =
        connection
            .prepareStatement(
                """
                SELECT id, user_id, role_code, scope_type, scope_club_id
                FROM user_roles
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                PersistedAssignment(
                                    id = resultSet.getLong("id"),
                                    userId = resultSet.getLong("user_id"),
                                    roleCode = resultSet.getString("role_code"),
                                    scopeType = resultSet.getString("scope_type"),
                                    scopeClubId = resultSet.getLong("scope_club_id"),
                                ),
                            )
                        }
                    }
                }
            }

    private fun readGrantCount(connection: java.sql.Connection): Long =
        connection.prepareStatement("SELECT COUNT(*) FROM user_role_permissions").use { statement ->
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getLong(1).also { assertFalse(resultSet.next()) }
            }
        }

    private data class PersistedAssignment(
        val id: Long,
        val userId: Long,
        val roleCode: String,
        val scopeType: String,
        val scopeClubId: Long,
    )

    companion object {
        private const val LEGACY_VERSION = "57"
        private const val EXPECTED_VERSION = "058"
        private val OPERATIONAL_SUPPORT_ROLES = setOf(Role.MANAGER, Role.CLUB_ADMIN)
        private val EXPECTED_PERMISSIONS =
            setOf(
                PermissionCodes.SUPPORT_VIEW.value,
                PermissionCodes.SUPPORT_REPLY.value,
                PermissionCodes.SUPPORT_STATUS_MANAGE.value,
            )

        @JvmStatic
        @BeforeAll
        fun assumeDocker() {
            val dockerAvailable =
                try {
                    DockerClientFactory.instance().client()
                    true
                } catch (_: Throwable) {
                    false
                }
            assumeTrue(dockerAvailable, "Docker is not available on this host; skipping IT.")
        }

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }
}

private object PermissionPostgresTestClubsTable : Table("clubs") {
    val id = long("id").autoIncrement()
    val name = text("name")
    val timezone = text("timezone")

    override val primaryKey = PrimaryKey(id)
}
