package com.example.bot.plugins

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.TestApplication
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JsonErrorPagesTest {
    @get:Rule
    val deadline: Timeout = Timeout.seconds(20)

    @Test
    fun `fatal error reaches outer observer without API envelope and test host closes`() {
        val fatal = OutOfMemoryError("synthetic fatal error without memory exhaustion")
        val observed = AtomicReference<Throwable?>()
        val fatalObserver =
            createApplicationPlugin("FatalObserver") {
                on(CallFailed) { call, cause ->
                    observed.set(cause)
                    call.respondText("fatal-observer", status = HttpStatusCode.ServiceUnavailable)
                }
            }
        val app =
            TestApplication {
                application {
                    install(ContentNegotiation) { json() }
                    // Ktor 3.3.1 CallFailed hooks wrap the remaining pipeline in
                    // registration order: this observer is outside StatusPages.
                    install(fatalObserver)
                    installJsonErrorPages()
                    routing {
                        get("/api/fatal") { throw fatal }
                    }
                }
            }
        runBlocking {
            try {
                withTimeout(5_000) {
                    app.start()
                    val response = app.client.get("/api/fatal")
                    val propagated = assertIs<OutOfMemoryError>(observed.get())
                    // Coroutine stack-trace recovery can copy the throwable, retaining
                    // the original as its cause. The exact original must still escape.
                    assertSame(fatal, generateSequence<Throwable>(propagated) { it.cause }.last())
                    assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                    assertEquals("fatal-observer", response.bodyAsText())
                }
            } finally {
                withContext(NonCancellable) {
                    withTimeout(5_000) { app.stop() }
                }
            }
        }
    }

    @Test
    fun `api bad request returns unified json envelope`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installJsonErrorPages()
                routing {
                    get("/api/test") {
                        throw BadRequestException("broken")
                    }
                }
            }

            val response = client.get("/api/test")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"code\":\"validation_error\""))
            assertTrue(body.contains("\"status\":400"))
            assertTrue(body.contains("\"message\":\"broken\""))
        }
}
