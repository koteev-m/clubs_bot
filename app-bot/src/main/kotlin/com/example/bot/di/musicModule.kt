@file:Suppress("ktlint:standard:filename")

package com.example.bot.di

import com.example.bot.data.music.MusicItemRepositoryImpl
import com.example.bot.data.music.MusicLikesRepositoryImpl
import com.example.bot.data.music.MusicPlaylistRepositoryImpl
import com.example.bot.data.music.TrackOfNightRepositoryImpl
import com.example.bot.music.MusicItemRepository
import com.example.bot.music.MusicPlaylistRepository
import com.example.bot.music.MusicService
import com.example.bot.music.MixtapeService
import com.example.bot.music.MusicLikesRepository
import com.example.bot.music.TrackOfNightRepository
import org.koin.dsl.module

val musicModule =
    module {
        single<MusicItemRepository> { MusicItemRepositoryImpl(get()) }
        single<MusicPlaylistRepository> { MusicPlaylistRepositoryImpl(get()) }
        single<MusicLikesRepository> { MusicLikesRepositoryImpl(get()) }
        single<TrackOfNightRepository> { TrackOfNightRepositoryImpl(get()) }
        single {
            MusicService(
                get(),
                get(),
                get(),
                get(),
            )
        }
        single {
            MixtapeService(
                likesRepository = get(),
                musicService = get(),
                clock = get(),
            )
        }
    }
