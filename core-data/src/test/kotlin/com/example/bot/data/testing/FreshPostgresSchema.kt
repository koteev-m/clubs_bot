package com.example.bot.data.testing

import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

/** Test-owned container only. Rebuild fixtures instead of weakening terminal metadata/TRUNCATE guards. */
internal fun freshPostgresSchema(container: PostgreSQLContainer<*>) {
    check(container.isRunning)
    Flyway
        .configure()
        .dataSource(container.jdbcUrl, container.username, container.password)
        .locations("classpath:db/migration/common", "classpath:db/migration/postgresql")
        .cleanDisabled(false)
        .load()
        .also {
            it.clean()
            it.migrate()
            it.validate()
        }
    // Remove only known V2 demonstration rows, retaining migrated permission/role catalogues.
    DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use { statement ->
            listOf("user_roles", "events", "tables", "zones", "clubs", "users").forEach {
                statement.executeUpdate("DELETE FROM $it")
            }
            listOf("events", "tables", "zones", "clubs", "users").forEach {
                statement.execute("SELECT setval(pg_get_serial_sequence('$it','id'),1,false)")
            }
        }
    }
}
