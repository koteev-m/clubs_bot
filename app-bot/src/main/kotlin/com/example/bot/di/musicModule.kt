@file:Suppress("ktlint:standard:filename")

package com.example.bot.di

import com.example.bot.data.music.MusicItemRepositoryImpl
import com.example.bot.data.music.MusicPlaylistRepositoryImpl
import com.example.bot.music.MusicItemRepository
import com.example.bot.music.MusicPlaylistRepository
import com.example.bot.music.MusicService
import org.koin.dsl.module

val musicModule =
    module {
        single<MusicItemRepository> { MusicItemRepositoryImpl(get()) }
        single<MusicPlaylistRepository> { MusicPlaylistRepositoryImpl(get()) }
        single {
            MusicService(
                get(),
                get(),
                get(),
            )
        }
    }
