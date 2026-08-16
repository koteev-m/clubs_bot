package com.example.bot.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class AppConfigFromEnvTest :
    StringSpec({
        val baseEnv =
            mapOf<String, String?>(
                "APP_PROFILE" to "DEV",
                "TELEGRAM_USE_POLLING" to "false",
                "MINI_APP_URL" to null,
                "WEBHOOK_SECRET_TOKEN" to null,
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

        "stage polling is rejected with a stable secret-safe diagnostic" {
            val botToken = "123456:stage-bot-secret"
            val webhookSecret = "stage-webhook-secret"
            val error =
                shouldThrow<IllegalStateException> {
                    withEnv(
                        baseEnv +
                            mapOf(
                                "APP_PROFILE" to "STAGE",
                                "TELEGRAM_USE_POLLING" to "true",
                                "MINI_APP_URL" to "https://mini.example/club/",
                                "TELEGRAM_BOT_TOKEN" to botToken,
                                "WEBHOOK_SECRET_TOKEN" to webhookSecret,
                            ),
                    ) {
                        AppConfig.fromEnv()
                    }
                }
            val diagnostic = requireNotNull(error.message)
            diagnostic shouldBe "Telegram polling is unsupported for STAGE/PROD; configure webhook mode"
            diagnostic shouldNotContain botToken
            diagnostic shouldNotContain webhookSecret
            diagnostic shouldNotContain "botpass"
        }

        "prod polling is rejected with the same stable diagnostic" {
            val error =
                shouldThrow<IllegalStateException> {
                    withEnv(
                        baseEnv +
                            mapOf(
                                "APP_PROFILE" to "PROD",
                                "TELEGRAM_USE_POLLING" to "true",
                                "MINI_APP_URL" to "https://mini.example/",
                                "TELEGRAM_BOT_TOKEN" to "987:XYZ",
                            ),
                    ) {
                        AppConfig.fromEnv()
                    }
                }
            error.message shouldBe "Telegram polling is unsupported for STAGE/PROD; configure webhook mode"
        }

        "dev polling remains accepted without a mini app url" {
            val config =
                withEnv(
                    baseEnv +
                        mapOf(
                            "TELEGRAM_USE_POLLING" to "true",
                            "TELEGRAM_BOT_TOKEN" to "987:XYZ",
                        ),
                ) {
                    AppConfig.fromEnv()
                }

            config.profile shouldBe AppProfile.DEV
            config.runMode shouldBe BotRunMode.POLLING
            config.miniAppUrl shouldBe null
        }

        "dev accepts an explicit local http mini app url" {
            val miniAppUrl = "http://127.0.0.1:8080/app"
            val config =
                withEnv(
                    baseEnv +
                        mapOf(
                            "MINI_APP_URL" to miniAppUrl,
                            "TELEGRAM_BOT_TOKEN" to "987:XYZ",
                        ),
                ) {
                    AppConfig.fromEnv()
                }

            config.miniAppUrl shouldBe miniAppUrl
        }

        "stage webhook mode accepts and preserves the canonical mini app url" {
            val miniAppUrl = "https://stage.example/app/"
            val config =
                withEnv(
                    baseEnv +
                        mapOf(
                            "APP_PROFILE" to "STAGE",
                            "TELEGRAM_USE_POLLING" to "false",
                            "MINI_APP_URL" to miniAppUrl,
                            "TELEGRAM_BOT_TOKEN" to "987:XYZ",
                        ),
                ) {
                    AppConfig.fromEnv()
                }

            config.profile shouldBe AppProfile.STAGE
            config.runMode shouldBe BotRunMode.WEBHOOK
            config.miniAppUrl shouldBe miniAppUrl
        }

        "prod webhook mode accepts and preserves the canonical mini app url" {
            val miniAppUrl = "https://prod.example/app"
            val config =
                withEnv(
                    baseEnv +
                        mapOf(
                            "APP_PROFILE" to "PROD",
                            "TELEGRAM_USE_POLLING" to "false",
                            "MINI_APP_URL" to miniAppUrl,
                            "TELEGRAM_BOT_TOKEN" to "987:XYZ",
                        ),
                ) {
                    AppConfig.fromEnv()
                }

            config.profile shouldBe AppProfile.PROD
            config.runMode shouldBe BotRunMode.WEBHOOK
            config.miniAppUrl shouldBe miniAppUrl
        }

        "absent and valid explicit mini app ports are accepted" {
            listOf(
                "https://example.com/app",
                "https://example.com:443/app",
                "https://example.com:8443/app",
                "https://example.com:65535/app",
            ).forEach { miniAppUrl ->
                val config =
                    withEnv(
                        baseEnv +
                            mapOf(
                                "APP_PROFILE" to "PROD",
                                "MINI_APP_URL" to miniAppUrl,
                                "TELEGRAM_BOT_TOKEN" to "987:XYZ",
                            ),
                    ) {
                        AppConfig.fromEnv()
                    }

                config.miniAppUrl shouldBe miniAppUrl
            }
        }

        "stage defaults to webhook when TELEGRAM_USE_POLLING is absent" {
            val miniAppUrl = "https://stage.example/app"
            val config =
                withEnv(
                    baseEnv +
                        mapOf(
                            "APP_PROFILE" to "STAGE",
                            "TELEGRAM_USE_POLLING" to null,
                            "MINI_APP_URL" to miniAppUrl,
                            "TELEGRAM_BOT_TOKEN" to "987:XYZ",
                        ),
                ) {
                    AppConfig.fromEnv()
                }

            config.profile shouldBe AppProfile.STAGE
            config.runMode shouldBe BotRunMode.WEBHOOK
            config.miniAppUrl shouldBe miniAppUrl
        }

        "prod defaults to webhook when TELEGRAM_USE_POLLING is absent" {
            val miniAppUrl = "https://prod.example/app"
            val config =
                withEnv(
                    baseEnv +
                        mapOf(
                            "APP_PROFILE" to "PROD",
                            "TELEGRAM_USE_POLLING" to null,
                            "MINI_APP_URL" to miniAppUrl,
                            "TELEGRAM_BOT_TOKEN" to "987:XYZ",
                        ),
                ) {
                    AppConfig.fromEnv()
                }

            config.profile shouldBe AppProfile.PROD
            config.runMode shouldBe BotRunMode.WEBHOOK
            config.miniAppUrl shouldBe miniAppUrl
        }

        "stage and prod require a mini app url" {
            listOf("STAGE", "PROD").forEach { profile ->
                val error =
                    shouldThrow<IllegalStateException> {
                        withEnv(
                            baseEnv +
                                mapOf(
                                    "APP_PROFILE" to profile,
                                    "TELEGRAM_BOT_TOKEN" to "987:XYZ",
                                ),
                        ) {
                            AppConfig.fromEnv()
                        }
                    }
                error.message shouldBe "MINI_APP_URL is required for APP_PROFILE=STAGE/PROD"
            }
        }

        "mini app url must be absolute exact https with a host and no unsafe components" {
            val invalidUrls =
                listOf(
                    "mini.example/app",
                    "http://mini.example/app",
                    "HTTPS://mini.example/app",
                    "https:///app",
                    "https://user:password@mini.example/app",
                    "https://mini.example/app?token=secret",
                    "https://mini.example/app#section",
                    "https://",
                )

            invalidUrls.forEach { invalidUrl ->
                val error =
                    shouldThrow<IllegalStateException> {
                        withEnv(
                            baseEnv +
                                mapOf(
                                    "APP_PROFILE" to "STAGE",
                                    "MINI_APP_URL" to invalidUrl,
                                    "TELEGRAM_BOT_TOKEN" to "987:XYZ",
                                ),
                        ) {
                            AppConfig.fromEnv()
                        }
                    }
                val diagnostic = requireNotNull(error.message)
                diagnostic shouldBe
                    "MINI_APP_URL must be absolute with a non-empty host, https in STAGE/PROD, " +
                    "and no userinfo, query, or fragment"
                diagnostic shouldNotContain invalidUrl
            }
        }

        "protocol-relative mini app url is rejected" {
            val error =
                shouldThrow<IllegalStateException> {
                    withEnv(
                        baseEnv +
                            mapOf(
                                "APP_PROFILE" to "STAGE",
                                "MINI_APP_URL" to "//example.com/app",
                                "TELEGRAM_BOT_TOKEN" to "987:XYZ",
                            ),
                    ) {
                        AppConfig.fromEnv()
                    }
                }

            error.message shouldBe
                "MINI_APP_URL must be absolute with a non-empty host, https in STAGE/PROD, " +
                "and no userinfo, query, or fragment"
        }

        "whitespace-only mini app url is rejected as missing" {
            val error =
                shouldThrow<IllegalStateException> {
                    withEnv(
                        baseEnv +
                            mapOf(
                                "APP_PROFILE" to "STAGE",
                                "MINI_APP_URL" to " \t\n ",
                                "TELEGRAM_BOT_TOKEN" to "987:XYZ",
                            ),
                    ) {
                        AppConfig.fromEnv()
                    }
                }

            error.message shouldBe "MINI_APP_URL is required for APP_PROFILE=STAGE/PROD"
        }

        "surrounding whitespace in mini app url is rejected without trimming" {
            val error =
                shouldThrow<IllegalStateException> {
                    withEnv(
                        baseEnv +
                            mapOf(
                                "APP_PROFILE" to "STAGE",
                                "MINI_APP_URL" to " https://example.com/app ",
                                "TELEGRAM_BOT_TOKEN" to "987:XYZ",
                            ),
                    ) {
                        AppConfig.fromEnv()
                    }
                }

            error.message shouldBe
                "MINI_APP_URL must be absolute with a non-empty host, https in STAGE/PROD, " +
                "and no userinfo, query, or fragment"
        }

        "encoded mini app authority is rejected" {
            val error =
                shouldThrow<IllegalStateException> {
                    withEnv(
                        baseEnv +
                            mapOf(
                                "APP_PROFILE" to "STAGE",
                                "MINI_APP_URL" to "https://%65xample.com/app",
                                "TELEGRAM_BOT_TOKEN" to "987:XYZ",
                            ),
                    ) {
                        AppConfig.fromEnv()
                    }
                }

            error.message shouldBe
                "MINI_APP_URL must be absolute with a non-empty host, https in STAGE/PROD, " +
                "and no userinfo, query, or fragment"
        }

        "empty malformed and out-of-range mini app ports are rejected safely" {
            val botToken = "987:port-test-secret"
            val webhookSecret = "port-webhook-secret"
            listOf(
                "https://example.com:0/app",
                "https://example.com:65536/app",
                "https://example.com:99999/app",
                "https://example.com:/app",
                "https://example.com:bad/app",
            ).forEach { invalidUrl ->
                val error =
                    shouldThrow<IllegalStateException> {
                        withEnv(
                            baseEnv +
                                mapOf(
                                    "APP_PROFILE" to "STAGE",
                                    "MINI_APP_URL" to invalidUrl,
                                    "TELEGRAM_BOT_TOKEN" to botToken,
                                    "WEBHOOK_SECRET_TOKEN" to webhookSecret,
                                ),
                        ) {
                            AppConfig.fromEnv()
                        }
                    }
                val diagnostic = requireNotNull(error.message)
                diagnostic shouldBe
                    "MINI_APP_URL must be absolute with a non-empty host, https in STAGE/PROD, " +
                    "and no userinfo, query, or fragment"
                diagnostic shouldNotContain invalidUrl
                diagnostic shouldNotContain botToken
                diagnostic shouldNotContain webhookSecret
                diagnostic shouldNotContain "botpass"
            }
        }

        listOf("BASE_URL", "WEBAPP_ORIGIN", "PUBLIC_URL").forEach { legacyVariable ->
            "missing mini app url does not fall back to $legacyVariable" {
                val error =
                    shouldThrow<IllegalStateException> {
                        withEnv(
                            baseEnv +
                                mapOf(
                                    "APP_PROFILE" to "STAGE",
                                    "MINI_APP_URL" to null,
                                    legacyVariable to "https://legacy.example/app",
                                    "TELEGRAM_BOT_TOKEN" to "987:XYZ",
                                ),
                        ) {
                            AppConfig.fromEnv()
                        }
                    }

                error.message shouldBe "MINI_APP_URL is required for APP_PROFILE=STAGE/PROD"
            }
        }

        "safe config output reports mini app presence without secrets or url contents" {
            val botToken = "123456:bot-secret-value"
            val webhookSecret = "webhook-secret-value"
            val miniAppUrl = "https://mini.example/club/"
            val config =
                withEnv(
                    baseEnv +
                        mapOf(
                            "APP_PROFILE" to "STAGE",
                            "MINI_APP_URL" to miniAppUrl,
                            "TELEGRAM_BOT_TOKEN" to botToken,
                            "WEBHOOK_SECRET_TOKEN" to webhookSecret,
                        ),
                ) {
                    AppConfig.fromEnv()
                }
            val safeOutput = config.toSafeString()

            safeOutput shouldContain "MiniApp(urlConfigured=true)"
            safeOutput shouldNotContain botToken
            safeOutput shouldNotContain webhookSecret
            safeOutput shouldNotContain miniAppUrl
        }
    })
