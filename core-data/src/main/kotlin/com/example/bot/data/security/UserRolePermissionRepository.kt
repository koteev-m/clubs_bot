package com.example.bot.data.security

/**
 * Reads explicit permissions attached to concrete scoped role assignments.
 *
 * A permission never broadens a role assignment: callers supply the roles accepted for their
 * operation, and CLUB scope is always required by this contract.
 */
interface UserRolePermissionRepository {
    /** Returns whether one exact CLUB role assignment carries [permission]. */
    suspend fun hasClubPermission(
        userId: Long,
        clubId: Long,
        allowedRoles: Set<Role>,
        permission: PermissionCode,
    ): Boolean

    /** Returns CLUB scopes whose exact accepted assignment carries [permission]. */
    suspend fun listClubIdsForPermission(
        userId: Long,
        allowedRoles: Set<Role>,
        permission: PermissionCode,
    ): Set<Long>
}
