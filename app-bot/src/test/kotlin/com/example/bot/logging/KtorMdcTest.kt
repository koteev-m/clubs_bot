package com.example.bot.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.plugins.ActorMdcPlugin
import com.example.bot.security.auth.TelegramPrincipal
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.SecureRandom

private val testSecureRandom = SecureRandom()
private val requestIdRegex = Regex("^[A-Za-z0-9._-]{1,64}")

class KtorMdcTest : StringSpec({
    "request without header gets generated request id and logs request_id" {
        withCallLoggingAppender { appender ->
            testApplication {
                application { configureTestApp(withActorMdc = false) }

                val response = client.get("/ping")
                val generatedId = response.headers["X-Request-Id"]
                generatedId shouldNotBe null
                generatedId!!.length shouldBe 20

                val event = appender.eventsWithRequestId().first()
                event.mdcPropertyMap["request_id"] shouldBe generatedId
            }
        }
    }

    "request with header preserves request id and logs it" {
        withCallLoggingAppender { appender ->
            testApplication {
                application { configureTestApp(withActorMdc = false) }

                val expectedId = "abc-123-xyz"
                val response = client.get("/ping") {
                    header("X-Request-Id", expectedId)
                }
                response.headers["X-Request-Id"] shouldBe expectedId

                val event = appender.eventsWithRequestId().first { it.mdcPropertyMap["request_id"] == expectedId }
                event.mdcPropertyMap["request_id"] shouldBe expectedId
            }
        }
    }

    "authenticated request adds actor_id to MDC" {
        withCallLoggingAppender { appender ->
            testApplication {
                application { configureTestApp(withActorMdc = true) }

                val response = client.get("/auth/ping")
                response.status.value shouldBe 200

                val event = appender.eventsWithRequestId().first { it.mdcPropertyMap.containsKey("actor_id") }
                event.mdcPropertyMap["actor_id"] shouldBe TEST_USER_ID.toString()
            }
        }
    }
})

private fun Application.configureTestApp(withActorMdc: Boolean) {
    install(CallId) {
        header("X-Request-Id")
        header("X-Request-ID")
        generate { generateRequestId() }
        verify { id -> id.isNotBlank() && requestIdRegex.matches(id) }
        reply { call, id -> call.response.header("X-Request-Id", id) }
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

private fun withCallLoggingAppender(block: (ListAppender<ILoggingEvent>) -> Unit) {
    val context = LoggerFactory.getILoggerFactory() as LoggerContext
    val logger = context.getLogger(Logger.ROOT_LOGGER_NAME)
    val appender = ListAppender<ILoggingEvent>().apply {
        this.context = context
        start()
    }
    logger.addAppender(appender)
    try {
        appender.list.clear()
        block(appender)
    } finally {
        logger.detachAppender(appender)
        appender.stop()
    }
}

private fun ListAppender<ILoggingEvent>.eventsWithRequestId(): List<ILoggingEvent> {
    return this.list.filter { event -> event.mdcPropertyMap.containsKey("request_id") }
}
