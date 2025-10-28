package com.example.notifications.support

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.PostgreSQLContainer

/** Base class that manages lifecycle of a PostgreSQL test container. */
abstract class PgContainer {
    companion object {
        @JvmStatic
        protected val PG: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:15-alpine")
                .withDatabaseName("testdb")
                .withUsername("test")
                .withPassword("test")

        @JvmStatic
        @BeforeAll
        fun startPg() {
            PG.start()
        }

        @JvmStatic
        @AfterAll
        fun stopPg() {
            PG.stop()
        }
    }
}
