package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.security.rbac.rbacContext
import io.ktor.server.application.ApplicationCall

internal fun ApplicationCall.isAdminClubAllowed(clubId: Long): Boolean {
    val context = rbacContext()
    val elevated = context.roles.any { it in setOf(Role.OWNER, Role.GLOBAL_ADMIN) }
    return elevated || clubId in context.clubIds
}

internal fun ApplicationCall.hasGlobalAdminAccess(): Boolean {
    val context = rbacContext()
    return context.roles.any { it in setOf(Role.OWNER, Role.GLOBAL_ADMIN) }
}
