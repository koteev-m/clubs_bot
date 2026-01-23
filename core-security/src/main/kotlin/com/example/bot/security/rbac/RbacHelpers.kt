package com.example.bot.security.rbac

import com.example.bot.data.security.Role

val GLOBAL_ROLES: Set<Role> =
    setOf(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER)

fun RbacContext.canAccessClub(clubId: Long): Boolean {
    if (roles.any { it in GLOBAL_ROLES }) return true
    return clubId in clubIds
}
