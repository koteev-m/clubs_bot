package com.example.bot.di

import com.example.bot.data.security.webhook.SuspiciousIpRepository
import com.example.bot.data.security.webhook.WebhookUpdateDedupRepository
import org.koin.dsl.module

val securityModule =
    module {
        single { SuspiciousIpRepository(get()) }
        single { WebhookUpdateDedupRepository(get()) }
    }
