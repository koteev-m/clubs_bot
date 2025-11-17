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
 * - Локации миграций берутся так: ENV(Flyway) -> application.conf -> авто-детект по JDBC (H2/PG).
 * - Любые "многозначные" локации нормализуются до одной вендорной (h2|postgresql), чтобы избежать конфликтов V1.
 * - Flyway выполняется под TCCL приложения, пул соединений закрывается на ApplicationStopped.
 */
fun Application.installMigrationsAndDatabase() {
    val log = LoggerFactory.getLogger("Migrations")

    // 1) Конфиг БД и пул соединений
    val dbCfg: DbConfig = DbConfig.fromEnv()
    val ds: DataSource = HikariFactory.dataSource(dbCfg)

    // 2) Определяем "сырые" локации и вендор из JDBC
    val rawLocations: String? =
        System.getenv("FLYWAY_LOCATIONS")
            ?: environment.config.propertyOrNull("flyway.locations")?.getString()

    val jdbcLower: String =
        (System.getenv("DATABASE_URL")
            ?: environment.config.propertyOrNull("db.jdbcUrl")?.getString()
            ?: "").lowercase()

    val vendor: String = when {
        jdbcLower.startsWith("jdbc:h2:") -> "h2"
        jdbcLower.contains("postgres") || jdbcLower.startsWith("jdbc:postgresql:") -> "postgresql"
        else -> "postgresql" // дефолт: PG
    }

    // 3) Нормализуем локации: оставляем ровно один вендор
    fun sanitizeLocations(raw: String?, vendor: String): Array<String> {
        if (raw.isNullOrBlank()) {
            return arrayOf("classpath:db/migration/$vendor")
        }
        // Разбиваем по запятым и приводим к нормальному виду
        val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        // Если указаны явные вендорные локации — оставляем только нашу
        val vendorOnly = parts.filter { it.endsWith("/$vendor") || it.contains("/$vendor/") }
        if (vendorOnly.isNotEmpty()) {
            return vendorOnly.distinct().toTypedArray()
        }

        // Если указан корень 'db/migration' — заменяем его на вендор
        val hasRoot = parts.any { it.endsWith("db/migration") || it.endsWith("db/migration/") }
        if (hasRoot) {
            return arrayOf("classpath:db/migration/$vendor")
        }

        // Иначе оставляем как есть (но это редкий случай для сторонних путей)
        return parts.distinct().toTypedArray()
    }

    val locations: Array<String> = sanitizeLocations(rawLocations, vendor)

    log.info("Flyway locations (raw): {}", rawLocations ?: "<auto>")
    log.info("Flyway locations (effective): {}", locations.joinToString(","))

    try {
        // 4) Выполняем миграции под класслоадером приложения (важно для загрузки ресурсов из JAR)
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

    // 5) Подключаем Exposed к уже инициализированному DataSource и сохраняем его в holder
    Database.connect(ds)
    DataSourceHolder.dataSource = ds

    // 6) Закрываем пул при остановке приложения
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
