package com.example.bot.plugins

import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.security.ExposedUserRepository
import com.example.bot.data.security.ExposedUserRoleRepository
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.webapp.InitDataPrincipalKey
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.response.respond
import org.jetbrains.exposed.sql.Database

/**
 * Installs RBAC security and basic error handling.
 * StatusPages ставим один раз, с обработчиками:
 * - BadRequestException -> 400
 * - Throwable -> 500
 * - MiniAppAuthAbort -> проглатываем (401 уже отдан в InitDataAuth)
 */
fun Application.configureSecurity() {
    val dataSource = DataSourceHolder.dataSource ?: error("DataSource is not initialised")
    val database = Database.connect(dataSource)
    val userRepository = ExposedUserRepository(database)
    val userRoleRepository = ExposedUserRoleRepository(database)
    val auditLogRepository = AuditLogRepository(database)

    install(RbacPlugin) {
        this.userRepository = userRepository
        this.userRoleRepository = userRoleRepository
        this.auditLogRepository = auditLogRepository
        principalExtractor = { call ->
            if (call.attributes.contains(InitDataPrincipalKey)) {
                val principal = call.attributes[InitDataPrincipalKey]
                TelegramPrincipal(principal.userId, principal.username)
            } else {
                call.request.header("X-Telegram-Id")?.toLongOrNull()?.let { id ->
                    TelegramPrincipal(id, call.request.header("X-Telegram-Username"))
                }
            }
        }
    }

    if (pluginOrNull(StatusPages) == null) {
        install(StatusPages) {
            exception<BadRequestException> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad_request")))
            }
            exception<MiniAppAuthAbort> { _, _ ->
                // 401 уже отправлен в InitDataAuth
            }
            exception<Throwable> { call, cause ->
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "error")))
            }
        }
    }
}
