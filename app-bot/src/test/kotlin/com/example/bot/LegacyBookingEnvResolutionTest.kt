package com.example.bot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LegacyBookingEnvResolutionTest {
    @Test
    fun `legacy env resolver uses process value only when config key is absent`() {
        assertEquals(
            "from-process",
            resolveLegacyBookingEnvValue(
                configValue = null,
                hasConfigValue = false,
                processValue = "from-process",
            ),
        )
    }

    @Test
    fun `legacy env resolver treats blank config as explicit absent override`() {
        assertNull(
            resolveLegacyBookingEnvValue(
                configValue = "   ",
                hasConfigValue = true,
                processValue = "from-process",
            ),
        )
    }

    @Test
    fun `legacy env resolver trims configured values`() {
        assertEquals(
            "from-config",
            resolveLegacyBookingEnvValue(
                configValue = " from-config ",
                hasConfigValue = true,
                processValue = "from-process",
            ),
        )
    }
}
