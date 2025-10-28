package com.example.bot.data.security

/**
 * Repository responsible for loading user roles and club scopes.
 */
interface UserRoleRepository {
    /** Returns all roles assigned to the user. */
    suspend fun listRoles(userId: Long): Set<Role>

    /** Returns club identifiers the user has scoped access to. */
    suspend fun listClubIdsFor(userId: Long): Set<Long>
}
