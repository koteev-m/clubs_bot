package com.example.bot.test

import com.example.bot.data.repo.ClubDto
import com.example.bot.data.repo.ClubRepository
import org.koin.core.module.Module
import org.koin.dsl.module

object TestModules {
    val clubs: Module =
        module {
            single<ClubRepository> { FakeClubRepository() }
        }
}

private class FakeClubRepository : ClubRepository {
    override suspend fun listClubs(limit: Int): List<ClubDto> =
        listOf(
            ClubDto(id = 1, name = "Club One", shortDescription = null),
            ClubDto(id = 2, name = "Club Two", shortDescription = null),
            ClubDto(id = 3, name = "Club Three", shortDescription = null),
            ClubDto(id = 4, name = "Club Four", shortDescription = null),
        ).take(limit)
}
