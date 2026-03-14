package com.example.bot.plugins

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonErrorPagesTest {
    @Test
    fun `api bad request returns unified json envelope`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installJsonErrorPages()
                routing {
                    get("/api/test") {
                        throw BadRequestException("broken")
                    }
                }
            }

            val response = client.get("/api/test")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"code\":\"validation_error\""))
            assertTrue(body.contains("\"status\":400"))
            assertTrue(body.contains("\"message\":\"broken\""))
        }
}
