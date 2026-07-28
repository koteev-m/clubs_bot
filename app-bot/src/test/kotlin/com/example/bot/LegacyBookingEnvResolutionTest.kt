package com.example.bot

import com.example.bot.plugins.BlankConfigSemantics
import com.example.bot.plugins.resolveEnvValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LegacyBookingEnvResolutionTest {
    @Test
    fun `legacy env resolver uses process value only when config key is absent`() {
        assertEquals(
            "from-process",
            resolveEnvValue(
                configValue = null,
                hasConfigValue = false,
                processValue = "from-process",
                blankConfigSemantics = BlankConfigSemantics.EXPLICIT_ABSENT_NO_FALLBACK,
            ),
        )
    }

    @Test
    fun `legacy env resolver treats blank config as explicit absent override`() {
        assertNull(
            resolveEnvValue(
                configValue = "   ",
                hasConfigValue = true,
                processValue = "from-process",
                blankConfigSemantics = BlankConfigSemantics.EXPLICIT_ABSENT_NO_FALLBACK,
            ),
        )
    }

    @Test
    fun `legacy env resolver trims configured values`() {
        assertEquals(
            "from-config",
            resolveEnvValue(
                configValue = " from-config ",
                hasConfigValue = true,
                processValue = "from-process",
                blankConfigSemantics = BlankConfigSemantics.EXPLICIT_ABSENT_NO_FALLBACK,
            ),
        )
    }
}
