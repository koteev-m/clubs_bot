package com.example.bot.data.security

import com.example.bot.data.db.withRetriedTx
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll

/** Exposed implementation of exact-assignment permission checks. */
class ExposedUserRolePermissionRepository(
    private val db: Database,
) : UserRolePermissionRepository {
    override suspend fun hasClubPermission(
        userId: Long,
        clubId: Long,
        allowedRoles: Set<Role>,
        permission: PermissionCode,
    ): Boolean {
        if (userId <= 0L || clubId <= 0L || allowedRoles.isEmpty()) return false

        return withRetriedTx(
            name = "userRolePermission.hasClub",
            readOnly = true,
            database = db,
        ) {
            assignmentsWithPermission(
                userId = userId,
                allowedRoles = allowedRoles,
                permission = permission,
            ).andWhere { UserRolesTable.scopeClubId eq clubId }
                .limit(1)
                .any()
        }
    }

    override suspend fun listClubIdsForPermission(
        userId: Long,
        allowedRoles: Set<Role>,
        permission: PermissionCode,
    ): Set<Long> {
        if (userId <= 0L || allowedRoles.isEmpty()) return emptySet()

        return withRetriedTx(
            name = "userRolePermission.listClubs",
            readOnly = true,
            database = db,
        ) {
            assignmentsWithPermission(
                userId = userId,
                allowedRoles = allowedRoles,
                permission = permission,
            ).orderBy(UserRolesTable.scopeClubId to SortOrder.ASC)
                .mapNotNullTo(linkedSetOf()) { row -> row[UserRolesTable.scopeClubId] }
        }
    }

    private fun assignmentsWithPermission(
        userId: Long,
        allowedRoles: Set<Role>,
        permission: PermissionCode,
    ) = UserRolesTable
        .join(
            UserRolePermissionsTable,
            JoinType.INNER,
            additionalConstraint = {
                UserRolesTable.id eq UserRolePermissionsTable.userRoleId
            },
        ).selectAll()
        .where {
            (UserRolesTable.userId eq userId) and
                (UserRolesTable.roleCode inList allowedRoles.map(Role::name)) and
                (UserRolesTable.scopeType eq CLUB_SCOPE_TYPE) and
                (UserRolePermissionsTable.permissionCode eq permission.value)
        }

    private companion object {
        const val CLUB_SCOPE_TYPE = "CLUB"
    }
}
