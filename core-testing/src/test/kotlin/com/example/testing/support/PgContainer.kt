package com.example.testing.support

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Base class managing PostgreSQL Testcontainers lifecycle.
 * Starts single container for all tests extending this class.
 */
abstract class PgContainer {
    companion object {
        private val container = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        lateinit var dataSource: HikariDataSource
        lateinit var database: Database

        @JvmStatic
        @BeforeAll
        fun startContainer() {
            val dockerAvailable =
                try {
                    DockerClientFactory.instance().client()
                    true
                } catch (_: Throwable) {
                    false
                }
            assumeTrue(dockerAvailable, "Docker is not available on this host; skipping IT.")
            container.start()
            val config =
                HikariConfig().apply {
                    jdbcUrl = container.jdbcUrl
                    username = container.username
                    password = container.password
                    driverClassName = container.driverClassName
                }
            dataSource = HikariDataSource(config)
            database = Database.connect(dataSource)
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() {
            if (this::dataSource.isInitialized) {
                dataSource.close()
            }
            container.stop()
        }
    }
}
