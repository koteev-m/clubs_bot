package com.example.bot.http

import com.example.bot.plugins.configureLoggingAndRequestId
import com.example.bot.routes.errorCodesRoutes
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.head
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import kotlin.text.Charsets
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.testing.testApplication
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorCodesRouteTest {
    private val jsonContentType = ContentType.Application.Json.withCharset(Charsets.UTF_8).toString()

    @Test
    fun `publishes versioned error codes registry`() = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            configureLoggingAndRequestId()
            errorCodesRoutes()
        }

        val response = client.config {
            install(ContentNegotiation) { json() }
        }.get("/api/.well-known/errors")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ErrorRegistry.cacheControl, response.headers[HttpHeaders.CacheControl])
        assertEquals(ErrorRegistry.etag, response.headers[HttpHeaders.ETag])
        val payload = response.body<ErrorCodesPayload>()
        assertEquals(ErrorRegistry.version, payload.version)

        val codes: Map<String, ErrorCodeInfo> = payload.codes.associateBy { it.code }
        val expected = mapOf(
            ErrorCodes.invalid_json to HttpStatusCode.BadRequest,
            ErrorCodes.unsupported_media_type to HttpStatusCode.UnsupportedMediaType,
            ErrorCodes.request_timeout to HttpStatusCode.RequestTimeout,
            ErrorCodes.outside_arrival_window to HttpStatusCode.Conflict,
            ErrorCodes.club_scope_mismatch to HttpStatusCode.Forbidden,
            ErrorCodes.rate_limited to HttpStatusCode.TooManyRequests,
            ErrorCodes.internal_error to HttpStatusCode.InternalServerError,
        )

        expected.forEach { (code, status) ->
            val info = codes[code] ?: error("Missing code $code")
            assertEquals(status.value, info.http)
            assertTrue(info.stable)
            assertFalse(info.deprecated)
        }

        val codeValues = payload.codes.map { it.code }
        assertEquals(codeValues.size, codeValues.toSet().size)
    }

    @Test
    fun `registry includes all declared error codes`() = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            configureLoggingAndRequestId()
            errorCodesRoutes()
        }

        val response = client.config {
            install(ContentNegotiation) { json() }
        }.get("/api/.well-known/errors")

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = response.body<ErrorCodesPayload>()
        val registryCodes = payload.codes.map { it.code }.toSet()

        val declaredCodes = ErrorCodes::class.java.fields
            .filter { field ->
                Modifier.isStatic(field.modifiers) &&
                    Modifier.isFinal(field.modifiers) &&
                    field.type == String::class.java
            }
            .map { it.get(null) as String }
            .toSet()

        declaredCodes.forEach { code ->
            assertTrue(code in registryCodes, "Missing code in registry: $code")
        }

        val extraInRegistry = registryCodes - declaredCodes
        assertTrue(extraInRegistry.isEmpty(), "Unexpected codes in registry: $extraInRegistry")
    }

    @Test
    fun `errors registry supports 304 via If-None-Match`() = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            configureLoggingAndRequestId()
            errorCodesRoutes()
        }

        val response = client.config {
            install(ContentNegotiation) { json() }
        }.get("/api/.well-known/errors") {
            header(HttpHeaders.IfNoneMatch, ErrorRegistry.etag)
        }

        assertEquals(HttpStatusCode.NotModified, response.status)
        assertEquals(ErrorRegistry.etag, response.headers[HttpHeaders.ETag])
        assertEquals(ErrorRegistry.cacheControl, response.headers[HttpHeaders.CacheControl])
        assertEquals(jsonContentType, response.headers[HttpHeaders.ContentType])
    }

    @Test
    fun `errors registry matches weak and wildcard If-None-Match`() = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            configureLoggingAndRequestId()
            errorCodesRoutes()
        }

        val clientWithJson = client.config {
            install(ContentNegotiation) { json() }
        }

        val weakMatch = clientWithJson.get("/api/.well-known/errors") {
            header(HttpHeaders.IfNoneMatch, "W/${ErrorRegistry.etag}")
        }

        assertEquals(HttpStatusCode.NotModified, weakMatch.status)

        val wildcardMatch = clientWithJson.get("/api/.well-known/errors") {
            header(HttpHeaders.IfNoneMatch, "\"other\", *")
        }

        assertEquals(HttpStatusCode.NotModified, wildcardMatch.status)
    }

    @Test
    fun `errors registry supports If-None-Match from latest ETag`() = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            configureLoggingAndRequestId()
            errorCodesRoutes()
        }

        val clientWithJson = client.config {
            install(ContentNegotiation) { json() }
        }

        val first = clientWithJson.get("/api/.well-known/errors")
        val etag = first.headers[HttpHeaders.ETag] ?: error("Missing ETag from registry")

        val cached = clientWithJson.get("/api/.well-known/errors") {
            header(HttpHeaders.IfNoneMatch, etag)
        }

        assertEquals(HttpStatusCode.NotModified, cached.status)
        assertEquals(etag, cached.headers[HttpHeaders.ETag])
    }

    @Test
    fun `errors registry exposes headers on HEAD`() = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            configureLoggingAndRequestId()
            errorCodesRoutes()
        }

        val response = client.head("/api/.well-known/errors")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ErrorRegistry.etag, response.headers[HttpHeaders.ETag])
        assertEquals(ErrorRegistry.cacheControl, response.headers[HttpHeaders.CacheControl])
        val contentType = response.headers[HttpHeaders.ContentType] ?: error("Missing Content-Type header")
        assertEquals(jsonContentType, contentType)
    }
}
