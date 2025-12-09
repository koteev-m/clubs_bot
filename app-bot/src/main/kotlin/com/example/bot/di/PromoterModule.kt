package com.example.bot.di

import com.example.bot.promoter.invites.InMemoryPromoterInviteRepository
import com.example.bot.promoter.invites.PromoterInviteRepository
import com.example.bot.promoter.invites.PromoterInviteService
import com.example.bot.promoter.quotas.InMemoryPromoterQuotaRepository
import com.example.bot.promoter.quotas.PromoterQuotaRepository
import com.example.bot.promoter.quotas.PromoterQuotaService
import org.koin.dsl.module
import java.time.Clock

val promoterModule =
    module {
        single<PromoterInviteRepository> { InMemoryPromoterInviteRepository() }
        single { PromoterInviteService(get(), Clock.systemUTC()) }

        single<PromoterQuotaRepository> { InMemoryPromoterQuotaRepository() }
        single { PromoterQuotaService(get(), Clock.systemUTC()) }
    }
