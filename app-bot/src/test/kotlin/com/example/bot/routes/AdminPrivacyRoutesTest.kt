package com.example.bot.routes

import com.example.bot.data.privacy.PrivacyAnonymizeResult
import com.example.bot.data.privacy.PrivacyService
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class AdminPrivacyRoutesTest {
    @Test
    fun `anonymize endpoint returns service result`() = testApplication {
        val service = mockk<PrivacyService>()
        coEvery { service.anonymizeUser(5L, any(), "cleanup") } returns PrivacyAnonymizeResult(1, 2, 3)

        application {
            install(ContentNegotiation) { json() }
            adminPrivacyRoutes(service)
        }

        val response = client.post("/api/admin/privacy/users/5/anonymize") {
            contentType(ContentType.Application.Json)
            setBody("""{"actorUserId":7,"actorRole":"OWNER","reason":"cleanup"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }
}
