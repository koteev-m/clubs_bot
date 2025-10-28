package com.example.bot.routes

import com.example.bot.di.notifyModule
import com.example.bot.notifications.NotifyMessage
import com.example.bot.notifications.NotifyMethod
import com.example.bot.notifications.ParseMode
import com.example.bot.plugins.resolveFlag
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin

class NotifyRoutesWiringTest : StringSpec({
    val payload =
        Json.encodeToString(
            NotifyMessage(
                chatId = 1,
                messageThreadId = null,
                method = NotifyMethod.TEXT,
                text = "hello",
                parseMode = ParseMode.MARKDOWNV2,
                photoUrl = null,
                album = null,
                buttons = null,
                dedupKey = null,
            ),
        )

    "notify routes enabled by default" {
        lateinit var txService: TxNotifyService

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(Koin) { modules(notifyModule) }

                val notifyEnabled = resolveFlag("NOTIFY_ROUTES_ENABLED", default = true)
                if (notifyEnabled) {
                    notifyRoutes(
                        tx = get<TxNotifyService>().also { txService = it },
                        campaigns = get(),
                    )
                }
            }

            val response =
                client.post("/api/notify/tx") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }

            response.status shouldBe HttpStatusCode.Accepted
            txService.size() shouldBe 1
        }
    }

    "notify routes disabled when NOTIFY_ROUTES_ENABLED=false" {
        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "app.flags.NOTIFY_ROUTES_ENABLED" to "false",
                    )
            }

            application {
                install(ContentNegotiation) { json() }
                install(Koin) { modules(notifyModule) }

                val notifyEnabled = resolveFlag("NOTIFY_ROUTES_ENABLED", default = true)
                if (notifyEnabled) {
                    notifyRoutes(
                        tx = get(),
                        campaigns = get(),
                    )
                }
            }

            val response =
                client.post("/api/notify/tx") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }

            response.status shouldBe HttpStatusCode.NotFound
        }
    }
})
