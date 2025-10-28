package com.example.bot.routes

import com.example.bot.plugins.DataSourceHolder
import com.example.bot.plugins.MigrationState
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.TimeoutException
import javax.sql.DataSource

private const val HEALTH_TIMEOUT_FALLBACK_MS: Long = 150L

private fun healthDbTimeoutMs(): Long =
    System.getenv("HEALTH_DB_TIMEOUT_MS")?.toLongOrNull() ?: HEALTH_TIMEOUT_FALLBACK_MS

fun Route.healthRoute() {
    get("/health") {
        val ds: DataSource? = DataSourceHolder.dataSource
        if (ds == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, "NO_DATASOURCE")
            return@get
        }
        val ok =
            try {
                withTimeout(healthDbTimeoutMs()) {
                    withContext(Dispatchers.IO) {
                        ds.connection.use { conn: Connection ->
                            conn.prepareStatement("SELECT 1").use { st ->
                                st.execute()
                            }
                        }
                    }
                    true
                }
            } catch (_: SQLException) {
                false
            } catch (_: TimeoutException) {
                false
            } catch (_: TimeoutCancellationException) {
                false
            } catch (_: IllegalStateException) {
                false
            }
        if (ok) {
            call.respond(HttpStatusCode.OK, "OK")
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, "DB_UNAVAILABLE")
        }
    }
}

fun Route.readinessRoute() {
    get("/ready") {
        if (MigrationState.migrationsApplied) {
            call.respond(HttpStatusCode.OK, "READY")
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, "MIGRATIONS_NOT_APPLIED")
        }
    }
}
