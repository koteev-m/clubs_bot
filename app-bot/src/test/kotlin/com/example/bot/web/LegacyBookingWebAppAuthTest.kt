package com.example.bot.deprecated.legacy.web

import com.example.bot.data.privacy.PrivacyConfig
import com.example.bot.isLegacyBookingEnabled
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LegacyBookingWebAppAuthTest {
    @AfterTest
    fun cleanup() {
        resetMiniAppValidator()
    }

    @Test
    fun `legacy api is fail-closed when auth missing`() = testApplication {
        application {
            installLegacyBookingWebApp(privacyConfig())
        }

        val response = client.get("/api/bookings/my") {
            header("Accept", "application/json")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `legacy api rejects invalid init data`() = testApplication {
        overrideMiniAppValidatorForTesting { _, _ -> null }
        application {
            installLegacyBookingWebApp(privacyConfig())
        }

        val response = client.get("/api/bookings/my?tgUserId=123") {
            header("Accept", "application/json")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `legacy feature flag defaults to disabled`() = testApplication {
        application {
            routing {
                if (isLegacyBookingEnabled()) {
                    get("/legacy-enabled") {}
                }
            }
        }

        val response = client.get("/legacy-enabled")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    private fun privacyConfig(): PrivacyConfig =
        PrivacyConfig.fromEnv(mapOf("PHONE_ENCRYPTION_KEY" to "0123456789abcdef0123456789abcdef"))
}
