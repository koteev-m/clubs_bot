package com.example.bot.di

import org.koin.core.module.Module

fun appModules(): List<Module> =
    buildList {
        add(bookingModule)
        add(availabilityModule)
        add(coreDataModule)
        add(clubsModule)
        add(layoutModule)
        add(healthModule)
        add(musicModule)
        add(notifyModule)
        add(opsNotificationsModule)
        add(outboxAdminModule)
        add(paymentsModule)
        add(refundWorkerModule)
        add(securityModule)
        add(promoterModule)
        add(ownerModule)
        add(supportModule)
        add(webAppModule)
    }
