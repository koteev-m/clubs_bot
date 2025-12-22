package com.example.bot.plugins

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetricsPluginTest {
    @Test
    fun `exposes hikari pool metrics`() =
        testApplication {
            application {
                val hikari =
                    HikariDataSource(
                        HikariConfig().apply {
                            jdbcUrl = "jdbc:h2:mem:hikari-metrics;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
                            username = "sa"
                            password = ""
                            poolName = "primary"
                            maximumPoolSize = 4
                            minimumIdle = 1
                        },
                    )
                hikari.connection.use { /* init pool */ }

                DataSourceHolder.dataSource = hikari
                environment.monitor.subscribe(io.ktor.server.application.ApplicationStopped) {
                    hikari.close()
                    DataSourceHolder.dataSource = null
                }

                installMetrics()
            }

            val response = client.get("/metrics")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = response.bodyAsText()
            assertTrue(body.contains("db_pool_active_connections") && body.contains("pool=\"primary\""))
        }
}
