package com.example.bot.di

import com.example.bot.observability.DefaultHealthService
import org.koin.core.scope.Scope
import org.koin.dsl.module

val healthModule =
    module {
        single { DefaultHealthService(dataSource = getOrNull()) }
    }

private inline fun <reified T : Any> Scope.getOrNull(): T? = runCatching { get<T>() }.getOrNull()
