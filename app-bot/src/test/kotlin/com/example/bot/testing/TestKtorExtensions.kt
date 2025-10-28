package com.example.bot.testing

import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.server.testing.ApplicationTestBuilder

/**
 * Совместимость со старым DSL: создаёт клиент с "default headers".
 * Пример:
 *   val authed = defaultRequest { withInitData(createInitData()) }
 *   val resp = authed.get("/api/...")
 */
public fun ApplicationTestBuilder.defaultRequest(block: HttpRequestBuilder.() -> Unit): HttpClient =
    createClient {
        defaultRequest {
            val requestBuilder = HttpRequestBuilder().apply(block)
            headers.appendAll(requestBuilder.headers.build())
        }
    }
