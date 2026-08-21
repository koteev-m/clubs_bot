package com.example.bot.data.security

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

class UserRolePermissionMigrationH2Test {
    @Test
    fun `V058 preserves roles and assignments and creates empty explicit grants`() {
        val jdbcUrl =
            "jdbc:h2:mem:user-role-permission-upgrade-${UUID.randomUUID()};" +
                "MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
        val locations = arrayOf("classpath:db/migration/common", "classpath:db/migration/h2")
        val legacyFlyway =
            Flyway
                .configure()
                .dataSource(jdbcUrl, H2_USER, H2_PASSWORD)
                .locations(*locations)
                .target(LEGACY_VERSION)
                .cleanDisabled(false)
                .load()
        legacyFlyway.clean()
        legacyFlyway.migrate()

        DriverManager.getConnection(jdbcUrl, H2_USER, H2_PASSWORD).use { connection ->
            val rolesBefore = readRoles(connection)
            val assignmentsBefore = readAssignments(connection)
            assertTrue(assignmentsBefore.isNotEmpty())

            val latestFlyway =
                Flyway
                    .configure()
                    .dataSource(jdbcUrl, H2_USER, H2_PASSWORD)
                    .locations(*locations)
                    .load()
            assertEquals(1, latestFlyway.migrate().migrationsExecuted)
            assertEquals(
                EXPECTED_VERSION,
                latestFlyway
                    .info()
                    .current()
                    ?.version
                    ?.toString(),
            )

            assertEquals(rolesBefore, readRoles(connection))
            assertEquals(assignmentsBefore, readAssignments(connection))
            assertEquals(EXPECTED_PERMISSIONS, readPermissions(connection))
            assertEquals(0L, grantCount(connection))

            val assignmentId = assignmentsBefore.single().id
            insertGrant(connection, assignmentId, PermissionCodes.SUPPORT_VIEW)
            assertEquals(1L, grantCount(connection))
            assertThrows(SQLException::class.java) {
                insertGrant(connection, assignmentId, PermissionCodes.SUPPORT_VIEW)
            }

            connection.prepareStatement("DELETE FROM user_roles WHERE id = ?").use { statement ->
                statement.setLong(1, assignmentId)
                assertEquals(1, statement.executeUpdate())
            }
            assertEquals(0L, grantCount(connection))
        }
    }

    private fun readRoles(connection: Connection): List<String> =
        connection.prepareStatement("SELECT code FROM roles ORDER BY code").use { statement ->
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) add(resultSet.getString("code"))
                }
            }
        }

    private fun readPermissions(connection: Connection): Set<String> =
        connection.prepareStatement("SELECT code FROM permissions ORDER BY code").use { statement ->
            statement.executeQuery().use { resultSet ->
                buildSet {
                    while (resultSet.next()) add(resultSet.getString("code"))
                }
            }
        }

    private fun readAssignments(connection: Connection): List<PersistedAssignment> =
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

    private fun insertGrant(
        connection: Connection,
        assignmentId: Long,
        permission: PermissionCode,
    ) {
        connection
            .prepareStatement(
                "INSERT INTO user_role_permissions (user_role_id, permission_code) VALUES (?, ?)",
            ).use { statement ->
                statement.setLong(1, assignmentId)
                statement.setString(2, permission.value)
                statement.executeUpdate()
            }
    }

    private fun grantCount(connection: Connection): Long =
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

    private companion object {
        const val H2_USER = "sa"
        const val H2_PASSWORD = ""
        const val LEGACY_VERSION = "57"
        const val EXPECTED_VERSION = "058"
        val EXPECTED_PERMISSIONS =
            setOf(
                PermissionCodes.SUPPORT_VIEW.value,
                PermissionCodes.SUPPORT_REPLY.value,
                PermissionCodes.SUPPORT_STATUS_MANAGE.value,
            )
    }
}
