package com.example.bot.plugins

import com.example.bot.data.db.DbConfig
import com.example.bot.data.db.HikariFactory
import com.example.bot.data.db.FlywayConfig
import com.example.bot.data.db.MigrationRunner
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
 * - Конфигурация Flyway берётся из FlywayConfig (APP_ENV/APP_PROFILE, FLYWAY_MODE, FLYWAY_OUT_OF_ORDER, FLYWAY_LOCATIONS, FLYWAY_SCHEMAS, FLYWAY_BASELINE_ON_MIGRATE).
 * - Локации миграций: ENV(Flyway) -> application.conf -> авто-детект по JDBC (H2/PG) с нормализацией до вендорных путей.
 * - Prod/Stage: на старте только validate, при pending миграциях старт запрещён; реальные миграции идут через CI workflow db-migrate.
 * - Flyway выполняется под TCCL приложения; пул соединений закрывается на ApplicationStopped, DataSourceHolder очищается при ошибках.
 */
fun Application.installMigrationsAndDatabase() {
    val log = LoggerFactory.getLogger("Migrations")

    // 1) Конфиг БД и пул соединений
    val dbCfg: DbConfig = DbConfig.fromEnv()
    val ds: DataSource = HikariFactory.dataSource(dbCfg)

    // 2) Локации миграций (ENV/HOCON)
    val rawLocations: String? =
        System.getenv("FLYWAY_LOCATIONS") ?: environment.config.propertyOrNull("flyway.locations")?.getString()
    val flywayConfig =
        FlywayConfig.fromEnv(
            envProvider = System::getenv,
            propertyProvider = System::getProperty,
            locationsOverride = rawLocations,
        )
    log.info(
        "Flyway config: appEnv={} mode={} effectiveMode={} outOfOrder={} locations={} schemas={}",
        flywayConfig.appEnv,
        flywayConfig.mode,
        flywayConfig.effectiveMode,
        flywayConfig.outOfOrderEnabled,
        flywayConfig.locations.joinToString(","),
        flywayConfig.schemas.joinToString(",").ifEmpty { "<default>" },
    )

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
        DataSourceHolder.dataSource = null
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
