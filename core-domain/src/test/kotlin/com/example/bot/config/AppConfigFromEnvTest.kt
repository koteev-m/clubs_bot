package com.example.bot.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class AppConfigFromEnvTest :
    StringSpec({
        val baseEnv =
            mapOf(
                "DATABASE_URL" to "jdbc:postgresql://localhost:5432/db",
                "DATABASE_USER" to "botuser",
                "DATABASE_PASSWORD" to "botpass",
                "OWNER_TELEGRAM_ID" to "1",
                "HQ_CHAT_ID" to "100",
                "CLUB1_CHAT_ID" to "101",
                "CLUB2_CHAT_ID" to "102",
                "CLUB3_CHAT_ID" to "103",
                "CLUB4_CHAT_ID" to "104",
            )

        "missing token fails fast" {
            val error =
                shouldThrow<IllegalStateException> {
                    withEnv(baseEnv + ("TELEGRAM_BOT_TOKEN" to null)) {
                        AppConfig.fromEnv()
                    }
                }
            error.message shouldBe "ENV TELEGRAM_BOT_TOKEN is required"
        }

        "loads bot token from TELEGRAM_BOT_TOKEN" {
            val token = "123456:ABC"
            val config =
                withEnv(baseEnv + ("TELEGRAM_BOT_TOKEN" to token)) {
                    AppConfig.fromEnv()
                }
            config.bot.token shouldBe token
        }

        "supports webhook and polling run modes" {
            val env = baseEnv + ("TELEGRAM_BOT_TOKEN" to "987:XYZ")

            val webhookConfig =
                withEnv(env) {
                    AppConfig.fromEnv()
                }
            webhookConfig.runMode shouldBe BotRunMode.WEBHOOK

            val pollingConfig =
                withEnv(env + ("TELEGRAM_USE_POLLING" to "true")) {
                    AppConfig.fromEnv()
                }
            pollingConfig.runMode shouldBe BotRunMode.POLLING
        }
    })
