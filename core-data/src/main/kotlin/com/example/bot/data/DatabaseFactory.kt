package com.example.bot.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

private const val MAX_POOL_SIZE = 3

object DatabaseFactory {
    fun connect(
        url: String,
        driver: String,
        user: String? = null,
        password: String? = null,
    ): Database {
        val config =
            HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                driverClassName = driver
                maximumPoolSize = MAX_POOL_SIZE
            }
        val dataSource = HikariDataSource(config)
        Flyway
            .configure()
            .dataSource(dataSource)
            .load()
            .migrate()
        return Database.connect(dataSource)
    }
}
