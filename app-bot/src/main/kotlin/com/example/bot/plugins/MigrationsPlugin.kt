package com.example.bot.plugins

import com.example.bot.data.db.DbConfig
import com.example.bot.data.db.FlywayConfig
import com.example.bot.data.db.MigrationRunner
import com.example.bot.data.db.HikariFactory
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import javax.sql.DataSource

object MigrationState {
    @Volatile
    var migrationsApplied: Boolean = false
}

/**
 * Единый DataSource + миграции Flyway + подключение Exposed.
 * - Локации миграций берутся так: ENV(Flyway) -> application.conf -> авто-детект по JDBC (H2/PG).
 * - Любые "многозначные" локации нормализуются до одной вендорной (h2|postgresql), чтобы избежать конфликтов V1.
 * - Flyway выполняется под TCCL приложения, пул соединений закрывается на ApplicationStopped.
 */
fun Application.installMigrationsAndDatabase() {
    val log = LoggerFactory.getLogger("Migrations")

    // 1) Конфиг БД и пул соединений
    val dbCfg: DbConfig = DbConfig.fromEnv()
    val ds: DataSource = HikariFactory.dataSource(dbCfg)

    // 2) Локации миграций (ENV/HOCON)
    val rawLocations: String? =
        System.getenv("FLYWAY_LOCATIONS")
            ?: environment.config.propertyOrNull("flyway.locations")?.getString()
    val flywayConfig =
        FlywayConfig.fromEnv(
            envProvider = System::getenv,
            propertyProvider = System::getProperty,
            locationsOverride = rawLocations,
        )

    log.info("Flyway locations (raw): {}", rawLocations ?: "<auto>")
    log.info("Flyway locations (effective): {}", flywayConfig.locations.joinToString(","))

    try {
        // 3) Выполняем миграции под класслоадером приложения (важно для загрузки ресурсов из JAR)
        val appCl = this::class.java.classLoader ?: Thread.currentThread().contextClassLoader
        val originalCl = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = appCl
        try {
            val result = MigrationRunner(ds, flywayConfig).run()
            if (result != null) {
                MigrationState.migrationsApplied = true
            }
        } finally {
            Thread.currentThread().contextClassLoader = originalCl
        }

        log.info("Flyway completed successfully")
    } catch (e: Exception) {
        log.error("Migrations failed, stopping application", e)
        // Закрываем пул, если упали
        (ds as? AutoCloseable)?.let {
            try {
                it.close()
            } catch (_: Throwable) {
                // ignore
            }
        }
        throw e
    }

    // 5) Подключаем Exposed к уже инициализированному DataSource и сохраняем его в holder
    Database.connect(ds)
    DataSourceHolder.dataSource = ds

    // 4) Закрываем пул при остановке приложения
    this.environment.monitor.subscribe(ApplicationStopped) {
        try {
            (ds as? AutoCloseable)?.close()
        } catch (_: Throwable) {
            // ignore
        } finally {
            DataSourceHolder.dataSource = null
        }
    }
}
