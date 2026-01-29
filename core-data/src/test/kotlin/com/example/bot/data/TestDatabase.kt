package com.example.bot.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import java.io.Closeable
import java.util.UUID

class TestDatabase : Closeable {
    val dataSource: HikariDataSource
    val database: Database

    init {
        val config =
            HikariConfig().apply {
                jdbcUrl =
                    "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
                driverClassName = "org.h2.Driver"
                username = "sa"
                password = ""
                maximumPoolSize = 3
            }
        dataSource = HikariDataSource(config)
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/common", "classpath:db/migration/h2")
            .cleanDisabled(false)
            .load()
            .also { flyway ->
                flyway.clean()
                flyway.migrate()
            }
        database = Database.connect(dataSource)
    }

    override fun close() {
        dataSource.close()
    }
}
