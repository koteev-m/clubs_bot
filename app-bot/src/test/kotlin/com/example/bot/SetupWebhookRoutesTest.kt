package com.example.bot

import com.example.bot.telegram.TelegramClient
import com.example.bot.telegram.telegramSetupRoutes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk

class SetupWebhookRoutesTest :
    StringSpec({
        val clientMock = mockk<TelegramClient>()
        coEvery { clientMock.setWebhook(any(), any(), any(), any()) } returns mockk { every { isOk } returns true }
        coEvery { clientMock.deleteWebhook(any()) } returns mockk { every { isOk } returns true }
        coEvery { clientMock.getWebhookInfo() } returns mockk(relaxed = true)
        val allowed = listOf("message")

        fun io.ktor.server.application.Application.testModule() {
            install(ContentNegotiation) { json() }
            routing { telegramSetupRoutes(clientMock, "https://example.com", "s", 40, allowed) }
        }

        "setup webhook" {
            testApplication {
                application { testModule() }
                val response = client.get("/telegram/setup-webhook")
                response.status shouldBe HttpStatusCode.OK
            }
        }

        "delete webhook" {
            testApplication {
                application { testModule() }
                val response = client.get("/telegram/delete-webhook")
                response.status shouldBe HttpStatusCode.OK
            }
        }

        "webhook info" {
            testApplication {
                application { testModule() }
                val response = client.get("/telegram/webhook-info")
                response.status shouldBe HttpStatusCode.OK
            }
        }
    })
