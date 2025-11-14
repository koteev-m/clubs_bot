package com.example.bot.plugins

import com.example.bot.data.db.DbConfig
import com.example.bot.data.db.HikariFactory
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import javax.sql.DataSource

object MigrationState {
    @Volatile
    var migrationsApplied: Boolean = false
}

/**
 * Единый DataSource + миграции Flyway + подключение Exposed.
 * - Локации миграций: env -> application.conf -> авто-детект по JDBC (H2/PG).
 * - Для корректной загрузки ресурсов Flyway выполняется под TCCL приложения.
 * - Пул соединений корректно закрывается на ApplicationStopped.
 */
fun Application.installMigrationsAndDatabase() {
    val log = LoggerFactory.getLogger("Migrations")

    // 1) Конфиг БД и пул соединений
    val dbCfg: DbConfig = DbConfig.fromEnv()
    val ds: DataSource = HikariFactory.dataSource(dbCfg)

    // 2) Локации миграций
    val locations: Array<String> = run {
        // Явная переопределялка через env/config
        val explicit: String? =
            System.getenv("FLYWAY_LOCATIONS")
                ?: environment.config.propertyOrNull("flyway.locations")?.getString()

        if (!explicit.isNullOrBlank()) {
            explicit.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toTypedArray()
        } else {
            // Авто-детект по JDBC URL: выбираем ровно ОДИН вендорский каталог,
            // чтобы избежать конфликта «Found more than one migration with version 1».
            val jdbc = (
                System.getenv("DATABASE_URL")
                    ?: environment.config.propertyOrNull("db.jdbcUrl")?.getString()
                    ?: ""
                ).lowercase()

            val isH2 = jdbc.startsWith("jdbc:h2:")
            if (isH2) {
                arrayOf("classpath:db/migration/h2")
            } else {
                arrayOf("classpath:db/migration/postgresql")
            }
        }
    }

    log.info("Flyway locations: {}", locations.joinToString(","))

    try {
        // 3) Выполняем миграции под класслоадером приложения
        val appCl = this::class.java.classLoader ?: Thread.currentThread().contextClassLoader
        val originalCl = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = appCl
        try {
            Flyway.configure()
                .dataSource(ds)
                .locations(*locations)
                .baselineOnMigrate(true)
                .validateOnMigrate(true)
                .load()
                .migrate()
        } finally {
            Thread.currentThread().contextClassLoader = originalCl
        }

        MigrationState.migrationsApplied = true
        log.info("Migrations completed successfully")
    } catch (e: Exception) {
        log.error("Migrations failed, stopping application", e)
        // Закрываем пул, если упали
        (ds as? AutoCloseable)?.let {
            try { it.close() } catch (_: Throwable) { /* ignore */ }
        }
        throw e
    }

    // 4) Подключаем Exposed к уже инициализированному DataSource и сохраняем его в holder
    Database.connect(ds)
    DataSourceHolder.dataSource = ds

    // 5) Закрываем пул при остановке приложения
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
