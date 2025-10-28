package com.example.bot.security

import com.example.bot.plugins.withMiniAppAuth
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

class InitDataAuthPluginTest : StringSpec({

    "401 when initData missing" {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    route("/api/miniapp") {
                        withMiniAppAuth { "test-bot-token" }
                        get("/me") { call.respondText("ok") }
                    }
                }
            }
            val res = client.get("/api/miniapp/me")
            res.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "401 when initData invalid" {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    route("/api/miniapp") {
                        withMiniAppAuth { "test-bot-token" }
                        get("/me") { call.respondText("ok") }
                    }
                }
            }
            val res = client.get("/api/miniapp/me?initData=bad")
            res.status shouldBe HttpStatusCode.Unauthorized
        }
    }
})
