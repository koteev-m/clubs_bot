package com.example.bot.routes

import com.example.bot.plugins.DataSourceHolder
import com.example.bot.plugins.MigrationState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import javax.sql.DataSource

class ObservabilityRoutesTest :
    StringSpec({
        "health returns 200 when db probe passes" {
            val statement = mockk<PreparedStatement>(relaxed = true) { every { execute() } returns true }
            val connection = mockk<Connection>(relaxed = true) { every { prepareStatement("SELECT 1") } returns statement }
            val dataSource = mockk<DataSource> { every { connection } returns connection }

            val previousDataSource = DataSourceHolder.dataSource
            try {
                DataSourceHolder.dataSource = dataSource
                testApplication {
                    application {
                        routing {
                            healthRoute()
                        }
                    }

                    client.get("/health").status shouldBe HttpStatusCode.OK
                }
            } finally {
                DataSourceHolder.dataSource = previousDataSource
            }
        }

        "health returns 503 when db probe fails" {
            val dataSource = mockk<DataSource> { every { connection } throws SQLException("db down") }

            val previousDataSource = DataSourceHolder.dataSource
            try {
                DataSourceHolder.dataSource = dataSource
                testApplication {
                    application {
                        routing {
                            healthRoute()
                        }
                    }

                    client.get("/health").status shouldBe HttpStatusCode.ServiceUnavailable
                }
            } finally {
                DataSourceHolder.dataSource = previousDataSource
            }
        }

        "ready returns 200 only when migrations are applied" {
            val previousState = MigrationState.migrationsApplied
            try {
                MigrationState.migrationsApplied = false
                testApplication {
                    application {
                        routing {
                            readinessRoute()
                        }
                    }

                    client.get("/ready").status shouldBe HttpStatusCode.ServiceUnavailable
                }

                MigrationState.migrationsApplied = true
                testApplication {
                    application {
                        routing {
                            readinessRoute()
                        }
                    }

                    client.get("/ready").status shouldBe HttpStatusCode.OK
                }
            } finally {
                MigrationState.migrationsApplied = previousState
            }
        }
    })
