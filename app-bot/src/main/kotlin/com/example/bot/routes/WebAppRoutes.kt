package com.example.bot.routes

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
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
            call.respondMiniAppIndex(classLoader)
        }

        get("/app/") {
            call.respondMiniAppIndex(classLoader)
        }

        get("/app/index.html") {
            call.respondMiniAppResource(classLoader, "webapp/app/index.html", ContentType.Text.Html)
        }

        get("/app/layout.html") {
            call.respondMiniAppResource(classLoader, "webapp/app/layout.html", ContentType.Text.Html)
        }

        get("/app/robots.txt") {
            call.respondMiniAppResource(classLoader, "webapp/app/robots.txt", ContentType.Text.Plain)
        }

        staticResources("/app/assets", "webapp/app/assets")
        staticResources("/app/react/assets", "webapp/app/react/assets")
    }
}

private suspend fun ApplicationCall.respondMiniAppIndex(classLoader: ClassLoader) {
    val supportMode = request.queryParameters["mode"] == "support"
    val resourcePath = if (supportMode) "webapp/app/react/index.html" else "webapp/app/index.html"
    respondMiniAppResource(classLoader, resourcePath, ContentType.Text.Html)
}

private suspend fun ApplicationCall.respondMiniAppResource(
    classLoader: ClassLoader,
    resourcePath: String,
    contentType: ContentType,
) {
    val bytes =
        classLoader
            .getResource(resourcePath)
            ?.readBytes()
            ?: error("Mini App resource not found at resources/$resourcePath")
    respondBytes(bytes, contentType)
}
