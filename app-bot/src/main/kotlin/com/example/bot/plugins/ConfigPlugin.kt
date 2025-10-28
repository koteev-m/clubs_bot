package com.example.bot.plugins

import com.example.bot.config.AppConfig
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory

object ConfigProvider {
    @Volatile
    private var cfg: AppConfig? = null

    fun current(): AppConfig = cfg ?: error("AppConfig not initialized")

    internal fun set(appConfig: AppConfig) {
        cfg = appConfig
    }

    internal fun clear() {
        cfg = null
    }
}

private val AppConfigAttributeKey = AttributeKey<AppConfig>("AppConfig")

val AppConfigPlugin =
    createApplicationPlugin(name = "AppConfigPlugin") {
        val log = LoggerFactory.getLogger("AppConfig")
        val appConfig = AppConfig.fromEnv()
        application.attributes.put(AppConfigAttributeKey, appConfig)
        ConfigProvider.set(appConfig)
        log.info("\n{}", appConfig.toSafeString())

        on(MonitoringEvent(ApplicationStopped)) {
            ConfigProvider.clear()
        }
    }

val Application.appConfig: AppConfig
    get() = attributes[AppConfigAttributeKey]

fun Application.installAppConfig() {
    if (pluginOrNull(AppConfigPlugin) == null) {
        install(AppConfigPlugin)
    }
}
