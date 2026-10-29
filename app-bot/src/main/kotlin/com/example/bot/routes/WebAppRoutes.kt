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
            call.respondMiniAppIndex(classLoader)
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
    val isReactMode =
        when (request.queryParameters["mode"]) {
            "support", "guest-support", "platform-owner", "client", "cabinet" -> true
            else ->
                listOf("tgWebAppStartParam", "startapp").any { key ->
                    request.queryParameters
                        .getAll(key)
                        ?.singleOrNull()
                        ?.let(CLIENT_LAUNCH::matches) == true
                }
        }
    val resourcePath = if (isReactMode) "webapp/app/react/index.html" else "webapp/app/index.html"
    response.headers.append("Cache-Control", "no-store")
    response.headers.append("Referrer-Policy", "no-referrer")
    respondMiniAppResource(classLoader, resourcePath, ContentType.Text.Html)
}

private val CLIENT_LAUNCH = Regex("ci_[A-Za-z0-9_-]{43}|client")

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
