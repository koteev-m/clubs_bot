@file:Suppress("ktlint:standard:filename")

package com.example.bot.di

import com.example.bot.data.music.MusicAssetRepositoryImpl
import com.example.bot.data.music.MusicItemRepositoryImpl
import com.example.bot.data.music.MusicBattleRepositoryImpl
import com.example.bot.data.music.MusicBattleVoteRepositoryImpl
import com.example.bot.data.music.MusicStemsRepositoryImpl
import com.example.bot.data.music.MusicLikesRepositoryImpl
import com.example.bot.data.music.MusicPlaylistRepositoryImpl
import com.example.bot.data.music.TrackOfNightRepositoryImpl
import com.example.bot.music.MusicAssetRepository
import com.example.bot.music.MusicItemRepository
import com.example.bot.music.MusicBattleRepository
import com.example.bot.music.MusicBattleService
import com.example.bot.music.MusicBattleVoteRepository
import com.example.bot.music.MusicStemsRepository
import com.example.bot.music.MusicPlaylistRepository
import com.example.bot.music.MusicService
import com.example.bot.music.MixtapeService
import com.example.bot.music.MusicLikesRepository
import com.example.bot.music.TrackOfNightRepository
import org.koin.dsl.module

val musicModule =
    module {
        single<MusicItemRepository> { MusicItemRepositoryImpl(get()) }
        single<MusicAssetRepository> { MusicAssetRepositoryImpl(get()) }
        single<MusicPlaylistRepository> { MusicPlaylistRepositoryImpl(get()) }
        single<MusicLikesRepository> { MusicLikesRepositoryImpl(get()) }
        single<TrackOfNightRepository> { TrackOfNightRepositoryImpl(get()) }
        single<MusicBattleRepository> { MusicBattleRepositoryImpl(get()) }
        single<MusicBattleVoteRepository> { MusicBattleVoteRepositoryImpl(get()) }
        single<MusicStemsRepository> { MusicStemsRepositoryImpl(get()) }
        single {
            MusicService(
                get(),
                get(),
                get(),
                get(),
                get(),
            )
        }
        single {
            MusicBattleService(
                battlesRepository = get(),
                votesRepository = get(),
                itemsRepository = get(),
                likesRepository = get(),
                clock = get(),
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
