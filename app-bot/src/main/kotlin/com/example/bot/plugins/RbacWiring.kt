package com.example.bot.plugins

import com.example.bot.data.security.Role
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.security.rbac.authorize
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.pluginOrNull
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

private val rbacLogger = LoggerFactory.getLogger("MiniAppRbac")

fun Application.installRbacIfEnabled() {
    val enabled = envBool("RBAC_ENABLED", default = false)
    if (!enabled) {
        return
    }
    if (pluginOrNull(RbacPlugin) == null) {
        rbacLogger.warn("RBAC_ENABLED=true but RbacPlugin is not installed; RBAC routes skipped")
        return
    }
    rbacLogger.info("RBAC feature flag enabled")
    environment.log.info("RBAC enabled")
}

fun Application.rbacSampleRoute() {
    val enabled = envBool("RBAC_ENABLED", default = false)
    if (!enabled) {
        return
    }
    if (pluginOrNull(RbacPlugin) == null) {
        return
    }
    routing {
        authorize(Role.MANAGER) {
            get("/api/secure/ping") {
                call.respond(HttpStatusCode.OK, mapOf("ok" to true))
            }
        }
    }
}
