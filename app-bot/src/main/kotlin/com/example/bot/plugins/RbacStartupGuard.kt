package com.example.bot.plugins

import com.example.bot.security.rbac.RbacPlugin
import io.ktor.server.application.Application
import io.ktor.server.application.pluginOrNull
import mu.KotlinLogging

private val logger = KotlinLogging.logger("RbacStartupGuard")

private val knownProdLikeProfiles = setOf("PROD", "PRODUCTION", "STAGE", "STAGING")
private val knownDevLikeProfiles = setOf("DEV", "DEVELOPMENT", "TEST", "LOCAL")
private val knownProfiles = knownProdLikeProfiles + knownDevLikeProfiles
private val allowedProfilesMessage = knownProfiles.sorted().joinToString(", ")

data class RbacStartupPolicy(
    val profile: String,
    val rbacEnabled: Boolean,
    val allowInsecureDev: Boolean,
    val warnings: List<String>,
) {
    val insecureMode: Boolean = !rbacEnabled
}

internal fun resolveRbacStartupPolicy(readValue: (String) -> String?): RbacStartupPolicy {
    val profile = (readValue("APP_PROFILE") ?: readValue("APP_ENV") ?: "dev").trim().uppercase()
    val rbacEnabled = parseBooleanEnv(readValue("RBAC_ENABLED"), default = false)
    val allowInsecureDev = parseBooleanEnv(readValue("ALLOW_INSECURE_DEV"), default = false)

    require(profile in knownProfiles) {
        "Неизвестный APP_PROFILE/APP_ENV: '$profile'. Допустимые значения: $allowedProfilesMessage"
    }

    require(profile !in knownProdLikeProfiles || !allowInsecureDev) {
        "ALLOW_INSECURE_DEV=true запрещён для профиля $profile. " +
            "Для прод-профилей RBAC должен быть включён без исключений."
    }

    if (profile in knownProdLikeProfiles && !rbacEnabled) {
        error("RBAC_ENABLED=false запрещён для профиля $profile. Приложение запускается только с RBAC.")
    }

    if (profile in knownDevLikeProfiles && !rbacEnabled && !allowInsecureDev) {
        error(
            "RBAC_ENABLED=false требует ALLOW_INSECURE_DEV=true для профиля $profile. " +
                "Fail-closed guard остановил запуск.",
        )
    }

    val warnings =
        if (profile in knownDevLikeProfiles && !rbacEnabled && allowInsecureDev) {
            listOf(
                "RBAC отключён для $profile (ALLOW_INSECURE_DEV=true). " +
                    "Админские/write маршруты будут работать без authorize — только для локальной разработки.",
            )
        } else {
            emptyList()
        }

    return RbacStartupPolicy(
        profile = profile,
        rbacEnabled = rbacEnabled,
        allowInsecureDev = allowInsecureDev,
        warnings = warnings,
    )
}

fun Application.enforceRbacStartupGuard(readValue: (String) -> String? = { envString(it) }): RbacStartupPolicy {
    val policy = resolveRbacStartupPolicy(readValue)
    policy.warnings.forEach { warning ->
        logger.warn { warning }
        environment.log.warn(warning)
    }

    if (policy.rbacEnabled && pluginOrNull(RbacPlugin) == null) {
        error("RBAC_ENABLED=true, но RbacPlugin не установлен. Приложение остановлено (fail-closed).")
    }

    environment.log.info(
        "RBAC startup policy: profile=${policy.profile}, rbacEnabled=${policy.rbacEnabled}, " +
            "allowInsecureDev=${policy.allowInsecureDev}",
    )
    return policy
}

private fun parseBooleanEnv(raw: String?, default: Boolean): Boolean =
    when (raw?.trim()?.lowercase()) {
        null, "" -> default
        "true" -> true
        "false" -> false
        else -> default
    }
