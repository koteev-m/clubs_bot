package com.example.bot.plugins

import io.ktor.server.application.application
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RbacStartupGuardTest {
    @Test
    fun `prod profile with disabled rbac fails on startup`() {
        assertFailsWith<IllegalStateException> {
            testApplication {
                application {
                    enforceRbacStartupGuard { key ->
                        when (key) {
                            "APP_PROFILE" -> "prod"
                            "RBAC_ENABLED" -> "false"
                            else -> null
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `dev profile with disabled rbac and explicit insecure flag starts with warning policy`() {
        testApplication {
            application {
                val policy =
                    enforceRbacStartupGuard { key ->
                        when (key) {
                            "APP_PROFILE" -> "dev"
                            "RBAC_ENABLED" -> "false"
                            "ALLOW_INSECURE_DEV" -> "true"
                            else -> null
                        }
                    }

                assertEquals("DEV", policy.profile)
                assertEquals(false, policy.rbacEnabled)
                assertTrue(policy.allowInsecureDev)
                assertTrue(policy.warnings.any { it.contains("RBAC отключён") })
            }
        }
    }

    @Test
    fun `production profile with disabled rbac fails on startup`() {
        assertFailsWith<IllegalStateException> {
            testApplication {
                application {
                    enforceRbacStartupGuard { key ->
                        when (key) {
                            "APP_PROFILE" -> "production"
                            "RBAC_ENABLED" -> "false"
                            else -> null
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `unknown profile fails closed on startup`() {
        assertFailsWith<IllegalArgumentException> {
            testApplication {
                application {
                    enforceRbacStartupGuard { key ->
                        when (key) {
                            "APP_PROFILE" -> "qa"
                            else -> null
                        }
                    }
                }
            }
        }
    }

}
