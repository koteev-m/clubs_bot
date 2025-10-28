package com.example.bot.di

import com.example.bot.data.repo.OutboxAdminRepository
import com.example.bot.data.repo.OutboxAdminRepositoryImpl
import org.koin.dsl.module

val outboxAdminModule =
    module {
        single<OutboxAdminRepository> { OutboxAdminRepositoryImpl(get()) }
    }
