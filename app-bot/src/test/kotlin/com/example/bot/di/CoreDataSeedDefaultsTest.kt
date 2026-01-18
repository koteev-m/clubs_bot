package com.example.bot.di

import kotlin.test.Test
import kotlin.test.assertTrue

class CoreDataSeedDefaultsTest {
    @Test
    fun `default core data seed uses normalized hall table coordinates`() {
        val seed = defaultCoreDataSeed()
        val invalidTables =
            seed.halls.flatMap { hall ->
                hall.tables.filter { table ->
                    table.x !in 0.0..1.0 || table.y !in 0.0..1.0
                }.map { table ->
                    "clubId=${hall.clubId} hallId=${hall.id} tableId=${table.id} x=${table.x} y=${table.y}"
                }
            }

        assertTrue(
            invalidTables.isEmpty(),
            "Hall table coordinates must be normalized within [0..1]. Invalid entries: ${invalidTables.joinToString()}"
        )
    }
}
