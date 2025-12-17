package com.example.bot.di

import com.example.bot.owner.OwnerHealthService
import com.example.bot.owner.OwnerHealthServiceImpl
import java.time.Clock
import org.koin.core.scope.Scope
import org.koin.dsl.module

val ownerModule =
    module {
        single<OwnerHealthService> { OwnerHealthServiceImpl(get(), get(), get(), get(), getOrNull() ?: Clock.systemUTC()) }
    }

private inline fun <reified T : Any> Scope.getOrNull(): T? = runCatching { get<T>() }.getOrNull()
