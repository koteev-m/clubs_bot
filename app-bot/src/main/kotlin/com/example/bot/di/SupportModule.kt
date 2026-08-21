package com.example.bot.di

import com.example.bot.data.support.SupportRepository
import com.example.bot.data.support.SupportServiceImpl
import com.example.bot.support.StaffSupportReadService
import com.example.bot.support.SupportService
import org.koin.dsl.module

val supportModule =
    module {
        single { SupportRepository(get()) }
        single { SupportServiceImpl(get()) }
        single<SupportService> { get<SupportServiceImpl>() }
        single<StaffSupportReadService> { get<SupportServiceImpl>() }
    }
