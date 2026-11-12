package com.example.bot.plugins.target

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.queryString
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** A test-host route only; no maintenance route/DI registration is added to the application. */
internal class TargetMaintenanceRouteTest {
    @Test fun `test transport separates signed authentication metadata selection and absent mutation API`() =
        testApplication {
            val f = MaintenanceSelectorFixture()
            application {
                routing {
                    get("/__synthetic/maintenance") {
                        val identity = f.verifier.verifyMaintenance(call.request.headers, call.request.queryString())
                        val selected = f.selector().select(identity)
                        val status =
                            when {
                                identity == null -> HttpStatusCode.Unauthorized
                                selected is MaintenanceSelection.Selected -> HttpStatusCode.OK
                                else -> HttpStatusCode.Forbidden
                            }
                        call.respondText("metadata-only", status = status)
                    }
                }
            }

            suspend fun request(raw: String) =
                client.get("/__synthetic/maintenance") { header("X-Telegram-Init-Data", raw) }
            assertEquals(HttpStatusCode.OK, request(f.signing.signed()).status)
            assertEquals(HttpStatusCode.Forbidden, request(f.signing.signed(user = "{\"id\":8}")).status)
            assertEquals(HttpStatusCode.Unauthorized, request(f.signing.signed(key = f.signing.token + "wrong")).status)
            f.owner = null
            assertEquals(HttpStatusCode.Forbidden, request(f.signing.signed()).status)
            assertEquals(HttpStatusCode.NotFound, client.get("/__synthetic/maintenance/mutate").status)
        }
}
