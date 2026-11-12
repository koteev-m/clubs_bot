package com.example.bot.plugins.target

import com.example.bot.http.ErrorCodes
import com.example.bot.http.respondError
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.TelegramMiniUser
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CanonicalTargetAudienceRouteTest {
    @Test
    fun `test only adapter verifies canonical audience on IO with no legacy fallback or permission issuance`() =
        testApplication {
            val fixture = CanonicalTargetAudienceFixture()
            val canary = "synthetic_canonical_provider_error"
            application {
                install(ContentNegotiation) { json() }
                routing {
                    post("/test-only-canonical-audience") {
                        call.attributes.put(MiniAppUserKey, TelegramMiniUser(99, username = "forged"))
                        val identity = verify(fixture, call.request.headers, call.request.queryString())
                        if (identity == null) {
                            call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.unauthorized)
                        } else {
                            call.respondText(
                                "${identity.telegramUserId}/${identity.context.clientId}/${identity.context.clubId}",
                            )
                        }
                    }
                }
            }
            val success =
                client.post("/test-only-canonical-audience") {
                    header("X-Telegram-Init-Data", fixture.identity.signed())
                    header("X-Debug-Roles", "OWNER,GLOBAL_ADMIN")
                    setBody("{\"clientId\":102,\"clubId\":2001,\"userId\":99}")
                }
            assertEquals(HttpStatusCode.OK, success.status)
            assertEquals("7/101/1001", success.bodyAsText())
            fixture.onRead = { throw SQLException(canary) }
            val denied =
                client.post("/test-only-canonical-audience") {
                    header("X-Telegram-Init-Data", fixture.identity.signed())
                }
            assertEquals(HttpStatusCode.Unauthorized, denied.status)
            assertFalse(denied.bodyAsText().contains(canary))
            fixture.onRead = {}
            fixture.current = null
            val revoked =
                client.post("/test-only-canonical-audience") {
                    header("X-Telegram-Init-Data", fixture.identity.signed())
                }
            assertEquals(HttpStatusCode.Unauthorized, revoked.status)
        }

    private suspend fun verify(
        fixture: CanonicalTargetAudienceFixture,
        headers: Headers,
        rawQuery: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): TargetVerifiedIdentity? = withContext(dispatcher) { fixture.verifier.verify(headers, rawQuery, fixture.context) }
}
