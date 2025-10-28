package com.example.bot.telegram

import com.example.bot.di.bookingModule
import com.example.bot.routes.notifyHealthRoute
import com.example.bot.testing.applicationDev
import com.example.bot.workers.SendOutcome
import com.example.bot.workers.SendPort
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.koin.core.context.stopKoin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.assertEquals

class NotifyDefaultWiringTest : StringSpec({

    val json = Json { ignoreUnknownKeys = true }

    afterSpec {
        // На всякий случай, если где-то поднялся глобальный Koin
        runCatching { stopKoin() }
    }

    fun koinApp(
        config: NotificationsDispatchConfig,
        sender: NotifySender = mockk(relaxed = true),
    ) = koinApplication {
        // ключ: включаем разрешение переопределений
        allowOverride(true)
        // порядок важен: сначала «прод» модуль, потом наши тестовые биндинги, которые его переопределят
        modules(
            bookingModule,
            module { single { sender } },
            module { single { config } },
        )
    }

    "binds NotifySenderSendPort when notifications enabled" {
        val sender = mockk<NotifySender>(relaxed = true)
        val app = koinApp(NotificationsDispatchConfig(flagEnabled = true, botToken = "token"), sender)

        try {
            val port = app.koin.get<SendPort>()
            port.shouldBeInstanceOf<NotifySenderSendPort>()

            val health = app.koin.get<NotifyDispatchHealth>()
            health shouldBe NotifyDispatchHealth(enabled = true, endpoints = 1, impl = "NotifySenderSendPort")
        } finally {
            app.close()
        }
    }

    "binds DummySendPort when notifications disabled" {
        val sender = mockk<NotifySender>(relaxed = true)
        val app = koinApp(NotificationsDispatchConfig(flagEnabled = false, botToken = "token"), sender)

        try {
            val port = app.koin.get<SendPort>()
            port::class.simpleName shouldBe "DummySendPort"

            val health = app.koin.get<NotifyDispatchHealth>()
            health shouldBe NotifyDispatchHealth(enabled = false, endpoints = 0, impl = "DummySendPort")
        } finally {
            app.close()
        }
    }

    "notify health route exposes dispatch status" {
        testApplication {
            applicationDev {
                install(ContentNegotiation) { json() }
                routing {
                    val health = NotifyDispatchHealth(enabled = true, endpoints = 1, impl = "NotifySenderSendPort")
                    notifyHealthRoute { health }
                }
            }

            val response = client.get("/notify/health")
            assertEquals(HttpStatusCode.OK, response.status)

            val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
            payload["enabled"]?.jsonPrimitive?.booleanOrNull shouldBe true
            payload["endpoints"]?.jsonPrimitive?.intOrNull shouldBe 1
            payload["impl"]?.jsonPrimitive?.content shouldBe "NotifySenderSendPort"
        }
    }

    "notify sender adapter maps results to SendOutcome.Ok" {
        val sender = mockk<NotifySender>()
        val app = koinApp(NotificationsDispatchConfig(flagEnabled = true, botToken = "token"), sender)

        try {
            val port = app.koin.get<SendPort>().shouldBeInstanceOf<NotifySenderSendPort>()
            val payload =
                buildJsonObject {
                    put("chatId", 42L)
                    put("text", "ping")
                }

            runTest {
                coEvery { sender.sendMessage(any(), any(), any(), any()) } returns SendResult.Ok(messageId = 1)
                port.send("topic", payload) shouldBe SendOutcome.Ok

                coEvery { sender.sendMessage(any(), any(), any(), any()) } returns SendResult.RetryAfter(retryAfterMs = 500)
                port.send("topic", payload) shouldBe SendOutcome.Ok
            }
        } finally {
            app.close()
        }
    }
})
