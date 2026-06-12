package com.example.bot.deprecated.legacy.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LegacyBookingConfigTest {
    @Test
    fun `enabled legacy flow fails fast when bot token is missing`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                LegacyBookingConfig.fromEnvForEnabled(envProvider(mapOf("LEGACY_HQ_CHAT_ID" to "1000")))
            }

        assertTrue(error.message.orEmpty().contains("TELEGRAM_BOT_TOKEN or BOT_TOKEN"))
    }

    @Test
    fun `enabled legacy flow fails fast when hq chat id is missing`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                LegacyBookingConfig.fromEnvForEnabled(envProvider(mapOf("TELEGRAM_BOT_TOKEN" to "111111:token")))
            }

        assertTrue(error.message.orEmpty().contains("LEGACY_HQ_CHAT_ID"))
    }

    @Test
    fun `legacy bot token reader uses BOT_TOKEN when telegram token is blank`() {
        val token =
            LegacyBookingConfig.readLegacyBotToken(
                envProvider(
                    mapOf(
                        "TELEGRAM_BOT_TOKEN" to "   ",
                        "BOT_TOKEN" to " 222222:fallback ",
                    ),
                ),
            )

        assertEquals("222222:fallback", token)
    }

    @Test
    fun `enabled legacy flow builds config from valid environment`() {
        val config =
            LegacyBookingConfig.fromEnvForEnabled(
                envProvider(
                    mapOf(
                        "TELEGRAM_BOT_TOKEN" to " 111111:telegram ",
                        "LEGACY_HQ_CHAT_ID" to " 1000 ",
                    ),
                ),
            )

        assertEquals("111111:telegram", config.botToken)
        assertEquals("1000", config.hqChatId)
        assertEquals("111111:telegram", config.botTokenProvider())
    }

    private fun envProvider(values: Map<String, String>): (String) -> String? = { key -> values[key] }
}
