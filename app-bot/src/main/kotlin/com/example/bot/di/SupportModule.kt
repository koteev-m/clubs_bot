package com.example.bot.di

import com.example.bot.data.support.SupportRepository
import com.example.bot.data.support.SupportServiceImpl
import com.example.bot.plugins.ConfigProvider
import com.example.bot.support.StaffSupportReadService
import com.example.bot.support.SupportReplyDeliveryService
import com.example.bot.support.SupportReplyDeliveryServiceImpl
import com.example.bot.support.SupportReplyTelegramGateway
import com.example.bot.support.SupportService
import com.example.bot.support.TelegramSupportReplyGateway
import org.koin.dsl.module

val supportModule =
    module {
        single { SupportRepository(get()) }
        single { SupportServiceImpl(get()) }
        single<SupportService> { get<SupportServiceImpl>() }
        single<StaffSupportReadService> { get<SupportServiceImpl>() }
        single<SupportReplyTelegramGateway> {
            TelegramSupportReplyGateway(
                telegramClient = get(),
                isConfigured =
                    ConfigProvider
                        .current()
                        .bot
                        .token
                        .isNotBlank(),
            )
        }
        single<SupportReplyDeliveryService> {
            SupportReplyDeliveryServiceImpl(
                repository = get(),
                clubsRepository = get(),
                telegramGateway = get(),
            )
        }
    }
