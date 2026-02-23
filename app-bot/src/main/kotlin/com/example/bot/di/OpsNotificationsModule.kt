package com.example.bot.di

import com.example.bot.notifications.OpsNotificationServiceConfig
import com.example.bot.notifications.TelegramOperationalNotificationService
import com.example.bot.opschat.OpsNotificationPublisher
import com.example.bot.plugins.ConfigProvider
import com.example.bot.telegram.TelegramClient
import com.pengrad.telegrambot.TelegramBot
import org.koin.dsl.module

val opsNotificationsModule =
    module {
        single { OpsNotificationServiceConfig.fromEnv() }
        single<TelegramBot> {
            val config = ConfigProvider.current()
            TelegramBot
                .Builder(config.bot.token)
                .apply {
                    config.localApi.baseUrl.takeIf { config.localApi.enabled }?.let { apiUrl(it) }
                }.build()
        }
        single {
            TelegramClient(
                bot = get(),
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
        single<OpsNotificationPublisher> { get<TelegramOperationalNotificationService>() }
    }
