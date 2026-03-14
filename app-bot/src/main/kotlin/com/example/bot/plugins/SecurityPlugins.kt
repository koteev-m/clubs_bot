package com.example.bot.plugins

import com.example.bot.audit.AuditLogRepository
import com.example.bot.data.audit.AuditLogRepositoryImpl
import com.example.bot.data.security.ExposedUserRepository
import com.example.bot.data.security.ExposedUserRoleRepository
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.request.header
import org.jetbrains.exposed.sql.Database

/** Installs RBAC security. API error contract is handled by JsonErrorPages. */
fun Application.configureSecurity() {
    val dataSource = DataSourceHolder.dataSource ?: error("DataSource is not initialised")
    val database = Database.connect(dataSource)
    val userRepository = ExposedUserRepository(database)
    val userRoleRepository = ExposedUserRoleRepository(database)
    val auditLogRepository: AuditLogRepository = AuditLogRepositoryImpl(database)

    install(RbacPlugin) {
        this.userRepository = userRepository
        this.userRoleRepository = userRoleRepository
        this.auditLogRepository = auditLogRepository
        principalExtractor = { call ->
            if (call.attributes.contains(MiniAppUserKey)) {
                val principal = call.attributes[MiniAppUserKey]
                TelegramPrincipal(principal.id, principal.username)
            } else {
                call.request.header("X-Telegram-Id")?.toLongOrNull()?.let { id ->
                    TelegramPrincipal(id, call.request.header("X-Telegram-Username"))
                }
            }
        }
    }

}
