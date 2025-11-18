@file:Suppress("ktlint:standard:no-multi-spaces")

package com.example.bot.dev

import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Диагностические эндпоинты для локальной разработки.
 * НИЧЕГО не устанавливаем (никаких CallId/CallLogging/Metrics),
 * только роуты — чтобы не дублировать плагины из основного модуля.
 */
fun Application.devProbes() {
    routing {
        get("/ping")   { call.respondText("pong") }
        get("/health") { call.respondText("ok") }
        get("/ready")  { call.respondText("ready") }
    }
}
