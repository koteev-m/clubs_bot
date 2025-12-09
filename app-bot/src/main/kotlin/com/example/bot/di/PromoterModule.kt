package com.example.bot.di

import com.example.bot.promoter.invites.InMemoryPromoterInviteRepository
import com.example.bot.promoter.invites.PromoterInviteRepository
import com.example.bot.promoter.invites.PromoterInviteService
import org.koin.dsl.module
import java.time.Clock

val promoterModule =
    module {
        single<PromoterInviteRepository> { InMemoryPromoterInviteRepository() }
        single { PromoterInviteService(get(), Clock.systemUTC()) }
    }
