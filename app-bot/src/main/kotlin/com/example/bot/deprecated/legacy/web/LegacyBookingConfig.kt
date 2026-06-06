package com.example.bot.deprecated.legacy.web

internal data class LegacyBookingConfig(
    val botToken: String,
    val hqChatId: String?,
) {
    fun botTokenProvider(): String = botToken

    fun buildHqNotifier(): LegacyHqNotifier =
        hqChatId
            ?.takeIf { it.isNotBlank() }
            ?.let { TelegramLegacyHqNotifier(token = botToken, chatId = it) }
            ?: error("LEGACY_HQ_CHAT_ID is required when LEGACY_BOOKING_WEBAPP_ENABLED=true")

    companion object {
        fun fromEnvForEnabled(envProvider: (String) -> String? = System::getenv): LegacyBookingConfig {
            val token = readLegacyBotToken(envProvider)
            require(token.isNotBlank()) {
                "TELEGRAM_BOT_TOKEN or BOT_TOKEN is required when LEGACY_BOOKING_WEBAPP_ENABLED=true"
            }

            val hqChatId = envProvider("LEGACY_HQ_CHAT_ID")?.trim()?.takeIf { it.isNotBlank() }
            require(!hqChatId.isNullOrBlank()) {
                "LEGACY_HQ_CHAT_ID is required when LEGACY_BOOKING_WEBAPP_ENABLED=true"
            }

            return LegacyBookingConfig(
                botToken = token,
                hqChatId = hqChatId,
            )
        }

        fun readLegacyBotToken(envProvider: (String) -> String? = System::getenv): String =
            envProvider("TELEGRAM_BOT_TOKEN")?.trim()?.takeIf { it.isNotBlank() }
                ?: envProvider("BOT_TOKEN")?.trim()?.takeIf { it.isNotBlank() }
                ?: ""
    }
}
