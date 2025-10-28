package com.example.bot.telegram

import kotlinx.serialization.Serializable
import kotlin.text.toBooleanStrictOrNull

fun interface EnvValueProvider {
    fun get(name: String): String?

    companion object {
        val system = EnvValueProvider { name ->
            System.getenv(name) ?: System.getProperty(name)
        }
    }
}

internal data class NotificationsDispatchConfig(
    val flagEnabled: Boolean,
    val botToken: String?,
    val endpointsWhenEnabled: Int = 1,
) {
    val isDispatchActive: Boolean
        get() = flagEnabled && !botToken.isNullOrBlank()

    fun toHealth(implementation: String): NotifyDispatchHealth {
        val enabled = isDispatchActive
        val endpoints = if (enabled) endpointsWhenEnabled else 0
        return NotifyDispatchHealth(
            enabled = enabled,
            endpoints = endpoints,
            impl = implementation,
        )
    }

    companion object {
        fun fromEnvironment(provider: EnvValueProvider = EnvValueProvider.system): NotificationsDispatchConfig {
            val enabled =
                provider.get("NOTIFICATIONS_ENABLED")?.toBooleanStrictOrNull()
                    ?: true
            val token = provider.get("TELEGRAM_BOT_TOKEN")
            return NotificationsDispatchConfig(flagEnabled = enabled, botToken = token)
        }
    }
}

@Serializable
data class NotifyDispatchHealth(
    val enabled: Boolean,
    val endpoints: Int,
    val impl: String,
)
