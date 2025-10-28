package com.example.bot.plugins

import com.example.bot.data.db.DbConfig
import com.example.bot.data.db.HikariFactory
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import javax.sql.DataSource

object MigrationState {
    @Volatile
    var migrationsApplied: Boolean = false
}

fun Application.installMigrationsAndDatabase() {
    val log = LoggerFactory.getLogger("Migrations")

    // 1) Конфигурация БД и пул
    val dbCfg = DbConfig.fromEnv()
    val ds: DataSource = HikariFactory.dataSource(dbCfg)

    try {
        // 2) Выбор локаций миграций (PG-only / H2) + миграции в IO-контексте
        runBlocking {
            withContext(Dispatchers.IO) {
                val jdbcUrlEnv = System.getenv("DATABASE_URL")?.lowercase() ?: ""
                val isH2 = jdbcUrlEnv.startsWith("jdbc:h2:")

                val locations: Array<String> =
                    if (isH2) arrayOf("classpath:db/migration/h2")
                    else arrayOf(System.getenv("FLYWAY_LOCATIONS") ?: "classpath:db/migration/postgresql")

                log.info("Flyway locations: {}", locations.joinToString(","))

                Flyway.configure()
                    .dataSource(ds)
                    .locations(*locations)
                    .baselineOnMigrate(true)
                    .load()
                    .migrate()
            }
        }

        MigrationState.migrationsApplied = true
        log.info("Migrations completed successfully")
    } catch (e: Exception) {
        val isFlyway =
            e::class.qualifiedName == "org.flywaydb.core.api.FlywayException" ||
                e.cause?.let { it::class.qualifiedName == "org.flywaydb.core.api.FlywayException" } == true
        if (isFlyway) log.error("Migrations failed (FlywayException), stopping application", e)
        else log.error("Migrations failed, stopping application", e)
        throw e
    }

    // 3) Подключаем Exposed к уже инициализированному DataSource
    Database.connect(ds)
    DataSourceHolder.dataSource = ds

    // 4) Корректное закрытие пула при остановке приложения
    monitor.subscribe(ApplicationStopped) {
        try {
            (ds as? AutoCloseable)?.close()
        } catch (_: Throwable) {
            // ignore
        } finally {
            DataSourceHolder.dataSource = null
        }
    }
}
