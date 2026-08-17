package com.example.bot.routes

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Раздаёт Mini App из ресурсов по пути /app.
 *
 * Общие HTTP-плагины устанавливаются основным application module.
 */
fun Application.webAppRoutes() {
    val classLoader = environment.classLoader

    routing {
        get("/app") {
            val bytes =
                classLoader
                    .getResource("webapp/app/index.html")
                    ?.readBytes()
                    ?: error("Mini App index.html not found at resources/webapp/app/index.html")
            call.respondBytes(bytes, ContentType.Text.Html)
        }

        staticResources("/app", "webapp/app")
    }
}
