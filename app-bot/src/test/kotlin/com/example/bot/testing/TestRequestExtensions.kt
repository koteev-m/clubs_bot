package com.example.bot.testing

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.path

/**
 * Тестовые экстеншены для совместимости с существующим кодом.
 * Если в тестах уже есть import io.ktor.client.request.header, этот header не помешает;
 * при отсутствии импорта — даст компиляцию.
 */
public fun HttpRequestBuilder.header(
    name: String,
    value: String,
) {
    headers.append(name, value)
}

/**
 * Упрощённая установка пути запроса в тестах.
 */
public fun HttpRequestBuilder.route(pathValue: String) {
    url {
        val normalized = pathValue.trimStart('/')
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            path("")
        } else {
            path(*segments.toTypedArray())
        }
    }
}
