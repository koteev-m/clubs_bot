package com.example.bot.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnvironmentFlagsTest {
    @Test
    fun `resolveEnvValue falls back to process environment when config value is blank`() {
        assertEquals(
            "from-process",
            resolveEnvValue(configValue = "   ", processValue = "from-process"),
        )
    }

    @Test
    fun `resolveEnvValue prefers non blank config over process environment`() {
        assertEquals(
            "from-config",
            resolveEnvValue(configValue = "from-config", processValue = "from-process"),
        )
    }

    @Test
    fun `resolveEnvValue preserves blank as absent when no process fallback exists`() {
        assertNull(resolveEnvValue(configValue = "", processValue = null))
    }
}
