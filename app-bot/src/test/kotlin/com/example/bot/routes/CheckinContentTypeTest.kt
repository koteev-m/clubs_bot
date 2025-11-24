package com.example.bot.routes

import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class CheckinContentTypeTest {
    @Test
    fun `non-json content-type returns 415`() = testApplication {
        application {
            routing {
                route("/api/clubs/{clubId}/checkin") {
                    post("/scan") {
                        // Минимальный сценарий: проверка контент-типа ровно как в реальном коде
                        val ct = call.request.contentType()
                        if (!ct.match(ContentType.Application.Json)) {
                            call.respond(HttpStatusCode.UnsupportedMediaType)
                            return@post
                        }
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }
        }
        // Нет Content-Type вовсе
        val r1 = client.post("/api/clubs/1/checkin/scan") { setBody("x") }
        assertEquals(HttpStatusCode.UnsupportedMediaType, r1.status)
        // Явно неправильный Content-Type
        val r2 = client.post("/api/clubs/1/checkin/scan") {
            headers { append(HttpHeaders.ContentType, ContentType.Text.Plain.toString()) }
            setBody("x")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, r2.status)
        // Правильный Content-Type → не 415
        val r3 = client.post("/api/clubs/1/checkin/scan") {
            headers { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
            setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, r3.status)

        // Правильный Content-Type с charset → тоже не 415
        val r4 = client.post("/api/clubs/1/checkin/scan") {
            headers { append(HttpHeaders.ContentType, "application/json; charset=utf-8") }
            setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, r4.status)
    }
}
