package com.example.bot.plugins

import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LimiterProdLikeFailFastTest {
    @Test
    fun `hot path limiter fail-fast when production profile and explicit empty prefixes`() {
        assertFailsWith<IllegalStateException> {
            testApplication {
                environment {
                    config =
                        MapApplicationConfig(
                            "app.env.APP_PROFILE" to " production ",
                            "app.env.HOT_PATH_PREFIXES" to "",
                        )
                }
                application {
                    installHotPathLimiterDefaults()
                }
            }
        }
    }

    @Test
    fun `hot path limiter fail-fast when staging profile from app env and explicit empty prefixes`() {
        assertFailsWith<IllegalStateException> {
            testApplication {
                environment {
                    config =
                        MapApplicationConfig(
                            "app.env.APP_ENV" to " staging ",
                            "app.env.HOT_PATH_PREFIXES" to "",
                        )
                }
                application {
                    installHotPathLimiterDefaults()
                }
            }
        }
    }

    @Test
    fun `rate limiter fail-fast when production profile and both rule groups disabled`() {
        assertFailsWith<IllegalStateException> {
            testApplication {
                environment {
                    config =
                        MapApplicationConfig(
                            "app.env.APP_PROFILE" to " production ",
                            "app.env.RL_IP_ENABLED" to "false",
                            "app.env.RL_SUBJECT_ENABLED" to "false",
                        )
                }
                application {
                    installRateLimitPluginDefaults()
                }
            }
        }
    }

    @Test
    fun `rate limiter fail-fast when staging profile and enabled subject with explicit empty prefixes`() {
        assertFailsWith<IllegalStateException> {
            testApplication {
                environment {
                    config =
                        MapApplicationConfig(
                            "app.env.APP_PROFILE" to " staging ",
                            "app.env.RL_IP_ENABLED" to "false",
                            "app.env.RL_SUBJECT_ENABLED" to "true",
                            "app.env.RL_SUBJECT_PATH_PREFIXES" to "",
                        )
                }
                application {
                    installRateLimitPluginDefaults()
                }
            }
        }
    }
}
