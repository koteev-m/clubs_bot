package com.example.bot.data.security

import com.example.bot.data.TestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UserRolePermissionRepositoryTest {
    private lateinit var testDatabase: TestDatabase
    private lateinit var repository: UserRolePermissionRepository

    @BeforeEach
    fun setUp() {
        testDatabase = TestDatabase()
        repository = ExposedUserRolePermissionRepository(testDatabase.database)
    }

    @AfterEach
    fun tearDown() {
        testDatabase.close()
    }

    @Test
    fun `only exact accepted CLUB assignments grant their attached permission`() =
        runBlocking {
            val userId = insertUser(8_800_580_001L)
            val otherUserId = insertUser(8_800_580_002L)
            val clubA = insertClub("Permission club A")
            val clubB = insertClub("Permission club B")
            val clubC = insertClub("Permission club C")
            val clubD = insertClub("Permission club D")

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

            assertFalse(can(userId, clubA, PermissionCodes.SUPPORT_VIEW))
            assertFalse(can(userId, clubB, PermissionCodes.SUPPORT_VIEW))
            assertTrue(can(userId, clubB, PermissionCodes.SUPPORT_REPLY))
            assertFalse(can(userId, clubB, PermissionCodes.SUPPORT_STATUS_MANAGE))
            assertTrue(can(userId, clubC, PermissionCodes.SUPPORT_VIEW))
            assertFalse(can(userId, clubD, PermissionCodes.SUPPORT_VIEW))
            assertFalse(can(otherUserId, clubB, PermissionCodes.SUPPORT_REPLY))
            assertEquals(setOf(clubC), permittedClubs(userId, PermissionCodes.SUPPORT_VIEW))

            insertGrant(managerA, PermissionCodes.SUPPORT_STATUS_MANAGE)
            assertTrue(can(userId, clubA, PermissionCodes.SUPPORT_STATUS_MANAGE))
            assertFalse(can(userId, clubA, PermissionCodes.SUPPORT_VIEW))
            assertFalse(can(userId, clubA, PermissionCodes.SUPPORT_REPLY))

            insertGrant(managerA, PermissionCodes.SUPPORT_VIEW)

            val freshRepository = ExposedUserRolePermissionRepository(testDatabase.database)
            assertTrue(
                freshRepository.hasClubPermission(
                    userId = userId,
                    clubId = clubA,
                    allowedRoles = OPERATIONAL_SUPPORT_ROLES,
                    permission = PermissionCodes.SUPPORT_VIEW,
                ),
            )
            assertEquals(
                linkedSetOf(clubA, clubC),
                freshRepository.listClubIdsForPermission(
                    userId = userId,
                    allowedRoles = OPERATIONAL_SUPPORT_ROLES,
                    permission = PermissionCodes.SUPPORT_VIEW,
                ),
            )
        }

    @Test
    fun `permission grant is unique and cascades with its exact assignment`() =
        runBlocking {
            val userId = insertUser(8_800_580_003L)
            val clubId = insertClub("Permission cascade club")
            val assignmentId = insertAssignment(userId, Role.MANAGER, "CLUB", clubId)
            insertGrant(assignmentId, PermissionCodes.SUPPORT_VIEW)

            assertThrows(ExposedSQLException::class.java) {
                insertGrant(assignmentId, PermissionCodes.SUPPORT_VIEW)
            }
            assertEquals(1L, grantCount(assignmentId))
            assertTrue(can(userId, clubId, PermissionCodes.SUPPORT_VIEW))

            transaction(testDatabase.database) {
                assertEquals(
                    1,
                    UserRolesTable.deleteWhere { UserRolesTable.id eq assignmentId },
                )
            }

            assertEquals(0L, grantCount(assignmentId))
            assertFalse(can(userId, clubId, PermissionCodes.SUPPORT_VIEW))
        }

    @Test
    fun `invalid identifiers and empty accepted roles fail closed`() =
        runBlocking {
            assertFalse(
                repository.hasClubPermission(
                    userId = 0L,
                    clubId = 1L,
                    allowedRoles = OPERATIONAL_SUPPORT_ROLES,
                    permission = PermissionCodes.SUPPORT_VIEW,
                ),
            )
            assertFalse(
                repository.hasClubPermission(
                    userId = 1L,
                    clubId = -1L,
                    allowedRoles = OPERATIONAL_SUPPORT_ROLES,
                    permission = PermissionCodes.SUPPORT_VIEW,
                ),
            )
            assertFalse(
                repository.hasClubPermission(
                    userId = 1L,
                    clubId = 1L,
                    allowedRoles = emptySet(),
                    permission = PermissionCodes.SUPPORT_VIEW,
                ),
            )
            assertEquals(
                emptySet<Long>(),
                repository.listClubIdsForPermission(
                    userId = 0L,
                    allowedRoles = OPERATIONAL_SUPPORT_ROLES,
                    permission = PermissionCodes.SUPPORT_VIEW,
                ),
            )
        }

    private suspend fun can(
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

    private suspend fun permittedClubs(
        userId: Long,
        permission: PermissionCode,
    ): Set<Long> =
        repository.listClubIdsForPermission(
            userId = userId,
            allowedRoles = OPERATIONAL_SUPPORT_ROLES,
            permission = permission,
        )

    private fun insertClub(name: String): Long =
        transaction(testDatabase.database) {
            PermissionTestClubsTable.insert {
                it[PermissionTestClubsTable.name] = name
                it[PermissionTestClubsTable.timezone] = "Europe/Moscow"
            } get PermissionTestClubsTable.id
        }

    private fun insertUser(telegramUserId: Long): Long =
        transaction(testDatabase.database) {
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
        transaction(testDatabase.database) {
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
        transaction(testDatabase.database) {
            UserRolePermissionsTable.insert {
                it[UserRolePermissionsTable.userRoleId] = assignmentId
                it[UserRolePermissionsTable.permissionCode] = permission.value
            }
        }
    }

    private fun grantCount(assignmentId: Long): Long =
        transaction(testDatabase.database) {
            UserRolePermissionsTable
                .selectAll()
                .where { UserRolePermissionsTable.userRoleId eq assignmentId }
                .count()
        }

    private companion object {
        val OPERATIONAL_SUPPORT_ROLES = setOf(Role.MANAGER, Role.CLUB_ADMIN)
    }
}

private object PermissionTestClubsTable : Table("clubs") {
    val id = long("id").autoIncrement()
    val name = text("name")
    val timezone = text("timezone")

    override val primaryKey = PrimaryKey(id)
}
