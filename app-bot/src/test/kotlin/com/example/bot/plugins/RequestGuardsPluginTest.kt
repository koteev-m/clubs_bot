package com.example.bot.plugins

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.call
import io.ktor.server.plugins.requestsize.RequestSizeLimit
import io.ktor.server.plugins.requestsize.maxRequestSize
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestGuardsPluginTest {
    @Test
    fun `body over limit returns 413`() = testApplication {
        val env = mapOf("CHECKIN_MAX_BODY_BYTES" to "32")

        application {
            routing {
                route("/api/clubs/{clubId}/checkin") {
                    val maxBytes: Long = (env["CHECKIN_MAX_BODY_BYTES"]?.toLongOrNull()
                        ?.coerceIn(MIN_CHECKIN_MAX_BYTES, MAX_CHECKIN_MAX_BYTES)) ?: DEFAULT_CHECKIN_MAX_BYTES
                    install(RequestSizeLimit) {
                        maxRequestSize = maxBytes
                    }

                    post("/scan") {
                        call.receiveText()
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }
        }

        val response = client.post("/api/clubs/1/checkin/scan") {
            setBody("x".repeat(600))
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun `body over limit without content length returns 413`() = testApplication {
        val env = mapOf("CHECKIN_MAX_BODY_BYTES" to "32")

        application {
            routing {
                route("/api/clubs/{clubId}/checkin") {
                    val maxBytes: Long = (env["CHECKIN_MAX_BODY_BYTES"]?.toLongOrNull()
                        ?.coerceIn(MIN_CHECKIN_MAX_BYTES, MAX_CHECKIN_MAX_BYTES)) ?: DEFAULT_CHECKIN_MAX_BYTES
                    install(RequestSizeLimit) {
                        maxRequestSize = maxBytes
                    }

                    post("/scan") {
                        call.receiveText()
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }
        }

        val bodyContent = "y".repeat(600).toByteArray()
        val response = client.post("/api/clubs/1/checkin/scan") {
            setBody(object : OutgoingContent.ReadChannelContent() {
                override val contentType: ContentType = ContentType.Application.Json
                override val contentLength: Long? = null
                override fun readFrom(): ByteReadChannel = ByteReadChannel(bodyContent)
            })
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun `request timeout returns 408`() = testApplication {
        val env = mapOf("HTTP_REQUEST_TIMEOUT_MS" to "100")

        application {
            installRequestGuardsFromEnv { key -> env[key] }
            routing {
                post("/api/clubs/{clubId}/checkin/scan") {
                    delay(300)
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        val response = client.post("/api/clubs/1/checkin/scan") {
            setBody("{}")
        }

        assertEquals(HttpStatusCode.RequestTimeout, response.status)
    }
}
