package com.example.bot.plugins.target

import com.example.bot.data.client.bindings.BindingStorageSchema
import com.example.bot.data.client.bindings.InactiveMaintenanceAction
import com.example.bot.data.client.onboarding.OnboardingFailure
import com.example.bot.http.ErrorCodes
import com.example.bot.http.respondError
import com.example.bot.routes.ClientOnboardingRoutesTestBase
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.queryString
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Test host only; there is no production maintenance endpoint or body/root selector. */
internal class TargetCommitMaintenanceRouteTest : ClientOnboardingRoutesTestBase() {
    @Test fun `signed HTTP identity is rechecked for mutation and replay while body cannot choose root`() =
        database { source ->
            val f = MaintenanceDatabaseFixture(source)
            val gate = SyntheticMaintenanceMutationLease(f.identity)
            val pin =
                runBlocking {
                    assertIs<MaintenanceSelection.Selected>(
                        f.identity.selector(f.jdbc).select(f.identity.identity()),
                    ).pin
                }
            val schema = BindingStorageSchema.fromServerConfiguration(source.connection.use { checkNotNull(it.schema) })
            val adapter =
                TargetCommitTimeMaintenanceAdapter(
                    source,
                    schema,
                    f.identity.connection,
                    f.identity.verifier,
                    gate,
                    f.identity.signing.clock,
                )
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        post("/__synthetic/commit-maintenance") {
                            try {
                                adapter.execute(
                                    pin,
                                    InactiveMaintenanceAction.DISABLE,
                                    "test-command",
                                    call.request.headers,
                                    call.request.queryString(),
                                )
                                call.respondText("committed-metadata")
                            } catch (_: OnboardingFailure) {
                                call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                            }
                        }
                    }
                }

                suspend fun request(raw: String) =
                    client.post("/__synthetic/commit-maintenance") {
                        header("X-Telegram-Init-Data", raw)
                        setBody("{\"rootId\":\"foreign-untrusted-hint\"}")
                    }
                val before = f.snapshot()
                assertEquals(HttpStatusCode.Forbidden, request(f.identity.signing.signed(user = "{\"id\":8}")).status)
                assertEquals(before, f.snapshot())
                assertEquals(HttpStatusCode.OK, request(f.identity.signing.signed()).status)
                val committed = f.snapshot()
                assertEquals(HttpStatusCode.OK, request(f.identity.signing.signed()).status)
                assertEquals(committed, f.snapshot())
                gate.change { f.identity.owner = null }
                assertEquals(HttpStatusCode.Forbidden, request(f.identity.signing.signed()).status)
                assertEquals(committed, f.snapshot())
            }
        }
}
