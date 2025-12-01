package com.example.bot.di

import org.koin.core.module.Module

fun appModules(): List<Module> =
    buildList {
        add(bookingModule)
        add(availabilityModule)
        add(clubsModule)
        add(layoutModule)
        add(healthModule)
        add(musicModule)
        add(notifyModule)
        add(outboxAdminModule)
        add(paymentsModule)
        add(refundWorkerModule)
        add(securityModule)
        add(webAppModule)
    }
