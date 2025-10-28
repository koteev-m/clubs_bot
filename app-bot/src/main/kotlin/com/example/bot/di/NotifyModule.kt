package com.example.bot.di

import com.example.bot.notifications.DefaultRatePolicy
import com.example.bot.notifications.RatePolicy
import com.example.bot.routes.CampaignService
import com.example.bot.routes.TxNotifyService
import com.example.bot.telegram.NotifySender
import com.pengrad.telegrambot.TelegramBot
import org.koin.dsl.module

/**
 * Нотификации: биндим TxNotifyService/CampaignService, RatePolicy и NotifySender.
 * Важно: сигнатура NotifySender должна совпадать с вашей реализацией.
 * Здесь предполагается конструктор NotifySender(bot: TelegramBot, ratePolicy: RatePolicy).
 */
val notifyModule =
    module {
        single { TxNotifyService() }
        single { CampaignService() }

        single<RatePolicy> { DefaultRatePolicy() }
        single { NotifySender(get<TelegramBot>(), get<RatePolicy>()) }
    }
