package com.example.bot.di

import com.example.bot.promoter.invites.InMemoryPromoterInviteRepository
import com.example.bot.promoter.invites.PromoterInviteRepository
import com.example.bot.promoter.invites.PromoterInviteService
import com.example.bot.promoter.rating.PromoterRatingService
import com.example.bot.promoter.quotas.InMemoryPromoterQuotaRepository
import com.example.bot.promoter.quotas.PromoterQuotaRepository
import com.example.bot.promoter.quotas.PromoterQuotaService
import org.koin.dsl.module
import java.time.Clock

val promoterModule =
    module {
        single<Clock> { Clock.systemUTC() }

        single<PromoterInviteRepository> { InMemoryPromoterInviteRepository() }
        single { PromoterInviteService(get(), get()) }
        single { PromoterRatingService(get(), get(), get()) }

        single<PromoterQuotaRepository> { InMemoryPromoterQuotaRepository() }
        single { PromoterQuotaService(get(), get()) }
    }
