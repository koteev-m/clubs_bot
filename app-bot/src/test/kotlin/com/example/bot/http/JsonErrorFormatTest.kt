package com.example.bot.http

import com.example.bot.plugins.configureLoggingAndRequestId
import com.example.bot.plugins.installJsonErrorPages
import com.example.bot.plugins.installRequestGuardsFromEnv
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.plugins.requestsize.RequestSizeLimit
import io.ktor.server.plugins.requestsize.maxRequestSize
import io.ktor.server.request.contentType
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonErrorFormatTest {
    @Test
    fun `unsupported media type returns JSON`() = testApplication {
        application {
            val app = this
            app.install(ServerContentNegotiation) { json() }
            app.configureLoggingAndRequestId()
            app.installRequestGuardsFromEnv()
            routing {
                route("/api/clubs/{clubId}/checkin") {
                    post("/scan") {
                        val ct = call.request.contentType()
                        if (!ct.match(ContentType.Application.Json)) {
                            call.respondError(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type")
                            return@post
                        }
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }
        }

        val response = client.config {
            install(ContentNegotiation) { json() }
        }.post("/api/clubs/1/checkin/scan") {
            headers {
                append(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                append("X-Request-Id", "test-rid-1")
            }
            setBody("hello")
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        val contentType = response.headers[HttpHeaders.ContentType]
        assertTrue(contentType?.startsWith(ContentType.Application.Json.toString()) == true)
        val error = Json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("unsupported_media_type", error.code)
        assertEquals("test-rid-1", error.requestId)
    }

    @Test
    fun `request timeout returns JSON`() = testApplication {
        application {
            val app = this
            app.install(ServerContentNegotiation) { json() }
            app.configureLoggingAndRequestId()
            app.installRequestGuardsFromEnv { key ->
                when (key) {
                    "HTTP_REQUEST_TIMEOUT_MS" -> "100"
                    else -> null
                }
            }
            routing {
                post("/slow") {
                    delay(300)
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        val response = client.config {
            install(ContentNegotiation) { json() }
        }.post("/slow") {
            headers { append("X-Request-Id", "test-rid-2") }
            setBody("{}")
        }

        assertEquals(HttpStatusCode.RequestTimeout, response.status)
        val contentType = response.headers[HttpHeaders.ContentType]
        assertTrue(contentType?.startsWith(ContentType.Application.Json.toString()) == true)
        val error = Json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("request_timeout", error.code)
        assertEquals("test-rid-2", error.requestId)
    }

    @Test
    fun `payload too large returns JSON`() = testApplication {
        application {
            val app = this
            app.install(ServerContentNegotiation) { json() }
            app.configureLoggingAndRequestId()
            app.installJsonErrorPages()
            routing {
                route("/api/clubs/{clubId}/checkin") {
                    install(RequestSizeLimit) { maxRequestSize = 32 }
                    post("/scan") {
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }
        }

        val response = client.config {
            install(ContentNegotiation) { json() }
        }.post("/api/clubs/1/checkin/scan") {
            headers {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                append("X-Request-Id", "rid-413-0001")
            }
            setBody("""{"text":"${"x".repeat(40)}"}""")
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        val contentType = response.headers[HttpHeaders.ContentType]
        assertTrue(contentType?.startsWith(ContentType.Application.Json.toString()) == true)
        val error = Json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("payload_too_large", error.code)
        assertEquals(HttpStatusCode.PayloadTooLarge.value, error.status)
        assertEquals("rid-413-0001", error.requestId)
    }

    @Test
    fun `domain payload too large is not overwritten`() = testApplication {
        application {
            val app = this
            app.install(ServerContentNegotiation) { json() }
            app.configureLoggingAndRequestId()
            app.installJsonErrorPages()
            routing {
                route("/api") {
                    get("/domain-413") {
                        call.respondError(HttpStatusCode.PayloadTooLarge, ErrorCodes.bulk_parse_too_large)
                    }
                }
            }
        }

        val response = client.config {
            install(ContentNegotiation) { json() }
        }.get("/api/domain-413") {
            headers { append("X-Request-Id", "rid-413-domain") }
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        val contentType = response.headers[HttpHeaders.ContentType]
        assertTrue(contentType?.startsWith(ContentType.Application.Json.toString()) == true)
        val error = Json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("bulk_parse_too_large", error.code)
        assertEquals(HttpStatusCode.PayloadTooLarge.value, error.status)
        assertEquals("rid-413-domain", error.requestId)
    }

    @Test
    fun `unauthorized returns JSON`() = testApplication {
        application {
            val app = this
            app.install(ServerContentNegotiation) { json() }
            app.configureLoggingAndRequestId()
            app.installJsonErrorPages()
            routing {
                route("/api") {
                    post("/secure") {
                        call.respond(HttpStatusCode.Unauthorized)
                    }
                }
            }
        }

        val response = client.config {
            install(ContentNegotiation) { json() }
        }.post("/api/secure") {
            headers { append("X-Request-Id", "rid-401-0001") }
            setBody("{}")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val contentType = response.headers[HttpHeaders.ContentType]
        assertTrue(contentType?.startsWith(ContentType.Application.Json.toString()) == true)
        val error = Json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("unauthorized", error.code)
        assertEquals(HttpStatusCode.Unauthorized.value, error.status)
        assertEquals("rid-401-0001", error.requestId)
    }

    @Test
    fun `unauthorized preserves WWW-Authenticate`() = testApplication {
        application {
            val app = this
            app.install(ServerContentNegotiation) { json() }
            app.configureLoggingAndRequestId()
            app.installJsonErrorPages()
            routing {
                route("/api") {
                    get("/auth") {
                        call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
                        call.respond(HttpStatusCode.Unauthorized)
                    }
                }
            }
        }

        val response = client.config {
            install(ContentNegotiation) { json() }
        }.get("/api/auth") {
            headers { append("X-Request-Id", "rid-401-header") }
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("Bearer", response.headers[HttpHeaders.WWWAuthenticate])
        val contentType = response.headers[HttpHeaders.ContentType]
        assertTrue(contentType?.startsWith(ContentType.Application.Json.toString()) == true)
        val error = Json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("unauthorized", error.code)
        assertEquals(HttpStatusCode.Unauthorized.value, error.status)
        assertEquals("rid-401-header", error.requestId)
    }

    @Test
    fun `unauthorized on non api is not converted to JSON`() = testApplication {
        application {
            val app = this
            app.install(ServerContentNegotiation) { json() }
            app.configureLoggingAndRequestId()
            app.installJsonErrorPages()
            routing {
                post("/secure-non-api") {
                    call.respond(HttpStatusCode.Unauthorized)
                }
            }
        }

        val response = client.config {
            install(ContentNegotiation) { json() }
        }.post("/secure-non-api") {
            headers { append("X-Request-Id", "rid-401-non-api") }
            setBody("{}")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val contentType = response.headers[HttpHeaders.ContentType]
        assertTrue(contentType?.startsWith(ContentType.Application.Json.toString()) != true)
    }

    @Test
    fun `too many requests returns JSON with retry after`() = testApplication {
        application {
            val app = this
            app.install(ServerContentNegotiation) { json() }
            app.configureLoggingAndRequestId()
            app.installJsonErrorPages()
            routing {
                route("/api") {
                    post("/limited") {
                        call.response.headers.append(HttpHeaders.RetryAfter, "2")
                        call.respond(HttpStatusCode.TooManyRequests)
                    }
                }
            }
        }

        val response = client.config {
            install(ContentNegotiation) { json() }
        }.post("/api/limited") {
            headers { append("X-Request-Id", "rid-429-0001") }
            setBody("{}")
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        val contentType = response.headers[HttpHeaders.ContentType]
        assertTrue(contentType?.startsWith(ContentType.Application.Json.toString()) == true)
        assertEquals("2", response.headers[HttpHeaders.RetryAfter])
        val error = Json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("rate_limited", error.code)
        assertEquals(HttpStatusCode.TooManyRequests.value, error.status)
        assertEquals("rid-429-0001", error.requestId)
    }

    @Test
    fun `too many requests on non api is not converted to JSON`() = testApplication {
        application {
            val app = this
            app.install(ServerContentNegotiation) { json() }
            app.configureLoggingAndRequestId()
            app.installJsonErrorPages()
            routing {
                post("/rate-non-api") {
                    call.respond(HttpStatusCode.TooManyRequests)
                }
            }
        }

        val response = client.config {
            install(ContentNegotiation) { json() }
        }.post("/rate-non-api") {
            headers { append("X-Request-Id", "rid-429-non-api") }
            setBody("{}")
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        val contentType = response.headers[HttpHeaders.ContentType]
        assertTrue(contentType?.startsWith(ContentType.Application.Json.toString()) != true)
    }

    @Test
    fun `forbidden returns JSON on api`() = testApplication {
        application {
            val app = this
            app.install(ServerContentNegotiation) { json() }
            app.configureLoggingAndRequestId()
            app.installJsonErrorPages()
            routing {
                route("/api") {
                    get("/secure") {
                        call.respond(HttpStatusCode.Forbidden)
                    }
                }
            }
        }

        val response = client.config {
            install(ContentNegotiation) { json() }
        }.get("/api/secure") {
            headers { append("X-Request-Id", "rid-403-0001") }
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val contentType = response.headers[HttpHeaders.ContentType]
        assertTrue(contentType?.startsWith(ContentType.Application.Json.toString()) == true)
        val error = Json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("forbidden", error.code)
        assertEquals(HttpStatusCode.Forbidden.value, error.status)
        assertEquals("rid-403-0001", error.requestId)
    }

    @Test
    fun `domain forbidden is not overwritten`() = testApplication {
        application {
            val app = this
            app.install(ServerContentNegotiation) { json() }
            app.configureLoggingAndRequestId()
            app.installJsonErrorPages()
            routing {
                route("/api") {
                    get("/domain-403") {
                        call.respondError(HttpStatusCode.Forbidden, ErrorCodes.checkin_forbidden)
                    }
                }
            }
        }

        val response = client.config {
            install(ContentNegotiation) { json() }
        }.get("/api/domain-403") {
            headers { append("X-Request-Id", "rid-403-domain") }
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val contentType = response.headers[HttpHeaders.ContentType]
        assertTrue(contentType?.startsWith(ContentType.Application.Json.toString()) == true)
        val error = Json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("checkin_forbidden", error.code)
        assertEquals(HttpStatusCode.Forbidden.value, error.status)
        assertEquals("rid-403-domain", error.requestId)
    }

    @Test
    fun `unsupported media type returns JSON on api`() = testApplication {
        application {
            val app = this
            app.install(ServerContentNegotiation) { json() }
            app.configureLoggingAndRequestId()
            app.installJsonErrorPages()
            routing {
                route("/api") {
                    post("/other") {
                        call.respond(HttpStatusCode.UnsupportedMediaType)
                    }
                }
            }
        }

        val response = client.config {
            install(ContentNegotiation) { json() }
        }.post("/api/other") {
            headers { append("X-Request-Id", "rid-415-0001") }
            setBody("{}")
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        val contentType = response.headers[HttpHeaders.ContentType]
        assertTrue(contentType?.startsWith(ContentType.Application.Json.toString()) == true)
        val error = Json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("unsupported_media_type", error.code)
        assertEquals(HttpStatusCode.UnsupportedMediaType.value, error.status)
        assertEquals("rid-415-0001", error.requestId)
    }

    @Test
    fun `domain not found is not overwritten`() = testApplication {
        application {
            val app = this
            app.install(ServerContentNegotiation) { json() }
            app.configureLoggingAndRequestId()
            app.installJsonErrorPages()
            routing {
                route("/api") {
                    get("/domain-404") {
                        call.respondError(HttpStatusCode.NotFound, ErrorCodes.entry_not_found)
                    }
                }
            }
        }

        val response = client.config {
            install(ContentNegotiation) { json() }
        }.get("/api/domain-404") {
            headers { append("X-Request-Id", "rid-404-domain") }
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val contentType = response.headers[HttpHeaders.ContentType]
        assertTrue(contentType?.startsWith(ContentType.Application.Json.toString()) == true)
        val error = Json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("entry_not_found", error.code)
        assertEquals(HttpStatusCode.NotFound.value, error.status)
        assertEquals("rid-404-domain", error.requestId)
    }

    @Test
    fun `api not found returns JSON`() = testApplication {
        application {
            val app = this
            app.install(ServerContentNegotiation) { json() }
            app.configureLoggingAndRequestId()
            app.installJsonErrorPages()
        }

        val response = client.config {
            install(ContentNegotiation) { json() }
        }.get("/api/unknown/path") {
            headers { append("X-Request-Id", "rid-404-0001") }
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val contentType = response.headers[HttpHeaders.ContentType]
        assertTrue(contentType?.startsWith(ContentType.Application.Json.toString()) == true)
        val error = Json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("not_found", error.code)
        assertEquals(HttpStatusCode.NotFound.value, error.status)
        assertEquals("rid-404-0001", error.requestId)
    }

    @Test
    fun `api unhandled exception returns JSON 500`() = testApplication {
        application {
            val app = this
            app.install(ServerContentNegotiation) { json() }
            app.configureLoggingAndRequestId()
            app.installJsonErrorPages()
            routing {
                route("/api") {
                    get("/boom") {
                        error("boom")
                    }
                }
            }
        }

        val response = client.config {
            install(ContentNegotiation) { json() }
        }.get("/api/boom") {
            headers { append("X-Request-Id", "rid-500-0001") }
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val contentType = response.headers[HttpHeaders.ContentType]
        assertTrue(contentType?.startsWith(ContentType.Application.Json.toString()) == true)
        val error = Json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("internal_error", error.code)
        assertEquals(HttpStatusCode.InternalServerError.value, error.status)
        assertEquals("rid-500-0001", error.requestId)
    }
}
