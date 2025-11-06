package com.example.bot.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.plugins.ActorMdcPlugin
import com.example.bot.security.auth.TelegramPrincipal
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.util.AttributeKey
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val testSecureRandom = SecureRandom()
private val requestIdRegex = Regex("^[A-Za-z0-9._-]{1,64}")

class KtorMdcTest {

    private lateinit var rootLogger: Logger
    private lateinit var appender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun setUp() {
        rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        rootLogger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        rootLogger.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `generates X-Request-Id and propagates to MDC`() = testApplication {
        application { configureTestApp(withActorMdc = false) }

        val response = client.get("/ping")
        val generatedId = response.headers[HttpHeaders.XRequestId]
        assertFalse(generatedId.isNullOrBlank(), "X-Request-Id header must be present")

        val events = eventsByRequestId(generatedId!!)
        assertTrue(events.isNotEmpty(), "Expected log events for generated request id")
        assertTrue(events.all { it.mdcPropertyMap["request_id"] == generatedId })
    }

    @Test
    fun `reuses incoming X-Request-Id`() = testApplication {
        application { configureTestApp(withActorMdc = false) }

        val incoming = "abc-123-xyz"
        val response = client.get("/ping") {
            header(HttpHeaders.XRequestId, incoming)
        }

        assertEquals(incoming, response.headers[HttpHeaders.XRequestId])

        val events = eventsByRequestId(incoming)
        assertTrue(events.isNotEmpty(), "Expected log events for provided request id")
        assertTrue(events.all { it.mdcPropertyMap["request_id"] == incoming })
    }

    @Test
    fun `authenticated request adds actor_id to MDC`() = testApplication {
        application { configureTestApp(withActorMdc = true) }

        val response = client.get("/auth/ping")
        assertEquals(200, response.status.value)

        val requestId = response.headers[HttpHeaders.XRequestId]
        assertNotNull(requestId)

        val events = eventsByRequestId(requestId)
        assertTrue(events.isNotEmpty(), "Expected log events for authenticated request")
        assertTrue(events.any { it.mdcPropertyMap["actor_id"] == TEST_USER_ID.toString() })
    }

    private fun eventsByRequestId(requestId: String): List<ILoggingEvent> {
        return appender.list.filter { event -> event.mdcPropertyMap["request_id"] == requestId }
    }
}

private fun Application.configureTestApp(withActorMdc: Boolean) {
    install(CallId) {
        header(HttpHeaders.XRequestId)
        header("X-Request-ID")
        generate { generateRequestId() }
        verify { id -> id.isNotBlank() && requestIdRegex.matches(id) }
        reply { call, id -> call.response.header(HttpHeaders.XRequestId, id) }
    }
    install(CallLogging) { callIdMdc("request_id") }

    if (withActorMdc) {
        install(TestRbacInjectorPlugin)
        install(ActorMdcPlugin)
    }

    routing {
        get("/ping") { call.respondText("pong") }
        get("/auth/ping") { call.respondText("ok") }
    }
}

private fun generateRequestId(): String {
    val bytes = ByteArray(10)
    testSecureRandom.nextBytes(bytes)
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            append(((byte.toInt() and 0xff).toString(16)).padStart(2, '0'))
        }
    }
}

private const val TEST_USER_ID: Long = 100L

private val TestRbacInjectorPlugin = createApplicationPlugin(name = "TestRbacInjector") {
    @Suppress("UNCHECKED_CAST")
    val rbacResolutionKey: AttributeKey<Any> =
        Class.forName("com.example.bot.security.rbac.RbacPluginKt")
            .getDeclaredField("rbacResolutionKey")
            .apply { isAccessible = true }
            .get(null) as AttributeKey<Any>

    val successConstructor =
        Class.forName("com.example.bot.security.rbac.RbacResolution\$Success")
            .declaredConstructors
            .first()
            .apply { isAccessible = true }

    onCall { call ->
        val principal = TelegramPrincipal(TEST_USER_ID, "tester")
        val user = User(id = TEST_USER_ID, telegramId = TEST_USER_ID, username = "tester")
        val roles: Set<Role> = setOf(Role.MANAGER)
        val clubs: Set<Long> = setOf(77L)
        val successInstance = successConstructor.newInstance(principal, user, roles, clubs)
        call.attributes.put(rbacResolutionKey, successInstance)
    }
}
