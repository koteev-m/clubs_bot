package com.example.bot.testing

import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder

/**
 * Запускает Ktor-приложение в профиле DEV (обходит TEST-guard),
 * не затрагивая глобальные ENV и прочие тесты.
 */
fun ApplicationTestBuilder.applicationDev(block: Application.() -> Unit) {
    environment {
        config = MapApplicationConfig("app.profile" to "DEV")
    }
    application(block)
}
