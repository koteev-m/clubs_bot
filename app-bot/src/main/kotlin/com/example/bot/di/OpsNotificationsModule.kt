package com.example.bot.di

import com.example.bot.notifications.OpsNotificationServiceConfig
import com.example.bot.notifications.TelegramOperationalNotificationService
import com.example.bot.plugins.ConfigProvider
import com.example.bot.telegram.TelegramClient
import org.koin.dsl.module

val opsNotificationsModule =
    module {
        single { OpsNotificationServiceConfig.fromEnv() }
        single {
            val config = ConfigProvider.current()
            TelegramClient(
                token = config.bot.token,
                apiUrl = config.localApi.baseUrl.takeIf { config.localApi.enabled },
            )
        }
        single {
            val opsConfig = get<OpsNotificationServiceConfig>()
            val config = ConfigProvider.current()
            TelegramOperationalNotificationService(
                telegramClient =
                    if (opsConfig.enabled && config.bot.token.isNotBlank()) {
                        get()
                    } else {
                        null
                    },
                configRepository = get(),
                config = opsConfig,
            )
        }
    }
