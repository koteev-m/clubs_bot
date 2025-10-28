package com.example.testing.support

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

/**
 * Utility to reset database state and apply migrations before each test.
 */
object DbSupport {
    private const val LOCATION = "classpath:db/migration"

    fun resetAndMigrate(dataSource: DataSource): Database {
        val flyway =
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations(LOCATION)
                .load()
        flyway.clean()
        flyway.migrate()
        return Database.connect(dataSource)
    }
}
