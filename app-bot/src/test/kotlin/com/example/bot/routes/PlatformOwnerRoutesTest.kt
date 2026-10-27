package com.example.bot.routes

import com.example.bot.audit.AuditLogRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.installJsonErrorPages
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.security.rbac.authorize
import com.example.bot.testing.createInitData
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlatformOwnerRoutesTest {
    @Test
    fun `owner payload with signature included in HMAC succeeds through both headers`() =
        withApp {
            val signed = signedAt(Instant.now().epochSecond)
            listOf("X-Telegram-Init-Data", "X-Telegram-InitData").forEach { name ->
                val response = client.get(PATH) { header(name, signed) }
                assertEquals(HttpStatusCode.OK, response.status)
                response.assertPrivate()
                assertEquals("""{"platformOwner":true}""", response.bodyAsText())
            }
        }

    @Test
    fun `signed configured owner succeeds without a database role and exposes no identity`() =
        withApp {
            listOf("X-Telegram-Init-Data", "X-Telegram-InitData").forEach { name ->
                val response = client.get(PATH) { header(name, createInitData(userId = OWNER)) }
                assertEquals(HttpStatusCode.OK, response.status)
                response.assertPrivate()
                assertEquals("""{"platformOwner":true}""", response.bodyAsText())
            }
        }

    @Test
    fun `other signed identity and client claims do not grant platform access`() =
        withApp {
            val response =
                client.get("$PATH?mode=platform-owner&role=OWNER&scope=GLOBAL&userId=$OWNER") {
                    header("X-Telegram-Init-Data", signedAt(Instant.now().epochSecond, userId = OTHER))
                    header("X-Telegram-Id", OWNER.toString())
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            response.assertCode("forbidden")
            response.assertPrivate()
            assertFalse(response.bodyAsText().contains(OWNER.toString()))
        }

    @Test
    fun `missing zero and negative configured identity deny even signed matching or positive ids`() {
        listOf(null, 0L, -1L).forEach { configured ->
            withApp(configuredOwner = configured) {
                listOf(OWNER, configured ?: OWNER).forEach { userId ->
                    val response = client.get(PATH) { header("X-Telegram-Init-Data", createInitData(userId = userId)) }
                    assertEquals(HttpStatusCode.Forbidden, response.status)
                    response.assertCode("forbidden")
                    response.assertPrivate()
                }
            }
        }
    }

    @Test
    fun `missing forged expired and future init data are rejected`() =
        withApp {
            val good = signedAt(Instant.now().epochSecond)
            val expired = signedAt(Instant.now().minusSeconds(90_000).epochSecond)
            val future = signedAt(Instant.now().plusSeconds(3_600).epochSecond)
            val wrongToken = signedAt(Instant.now().epochSecond, botToken = "$TEST_BOT_TOKEN-wrong")
            val tamperedSignature = good.replace(SYNTHETIC_SIGNATURE, "tampered_signature")
            val missingHash = good.substringBefore("&hash=")
            val forgedHash = "$missingHash&hash=${"00".repeat(32)}"
            listOf(null, "", "$good-invalid", expired, future, wrongToken, tamperedSignature, missingHash, forgedHash)
                .forEach { raw ->
                    val response =
                        client.get(PATH) {
                            if (raw != null) header("X-Telegram-Init-Data", raw)
                            header("X-Telegram-Id", OWNER.toString())
                        }
                    assertEquals(HttpStatusCode.Unauthorized, response.status)
                    response.assertCode("unauthorized")
                    response.assertPrivate()
                }
        }

    @Test
    fun `query credential transport is denied even with a valid header and body is not accepted`() =
        withApp {
            val signed = signedAt(Instant.now().epochSecond)
            listOf("initData=${signed.encodeURLParameter()}", "initData=", "%69nitData=bad").forEach { query ->
                val response = client.get("$PATH?$query") { header("X-Telegram-Init-Data", signed) }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
                response.assertPrivate()
                assertFalse(response.bodyAsText().contains(signed))
            }
            val response =
                client.get(PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"initData":"$signed","userId":$OWNER,"role":"OWNER"}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val formResponse =
                client.get(PATH) {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("initData=${signed.encodeURLParameter()}")
                }
            assertEquals(HttpStatusCode.Unauthorized, formResponse.status)
            assertEquals(HttpStatusCode.MethodNotAllowed, client.post(PATH).status)
        }

    @Test
    fun `legacy GLOBAL OWNER does not imply platform owner and legacy authority remains unchanged`() =
        withApp {
            application {
                val users = mockk<UserRepository>()
                val roles = mockk<UserRoleRepository>()
                coEvery { users.getByTelegramId(OTHER) } returns User(1L, OTHER, "legacy-owner")
                coEvery { roles.listRoles(1L) } returns setOf(Role.OWNER)
                coEvery { roles.listClubIdsFor(1L) } returns emptySet()
                install(RbacPlugin) {
                    userRepository = users
                    userRoleRepository = roles
                    auditLogRepository = mockk<AuditLogRepository>(relaxed = true)
                    principalExtractor = { call ->
                        call.attributes.getOrNull(MiniAppUserKey)?.let { TelegramPrincipal(it.id, it.username) }
                    }
                }
                routing {
                    route("/legacy-owner-check") {
                        withMiniAppAuth { TEST_BOT_TOKEN }
                        authorize(Role.OWNER) { get { call.respond(HttpStatusCode.OK) } }
                    }
                }
            }
            val signed = createInitData(userId = OTHER)
            assertEquals(
                HttpStatusCode.OK,
                client.get("/legacy-owner-check") { header("X-Telegram-Init-Data", signed) }.status,
            )
            assertEquals(HttpStatusCode.Forbidden, client.get(PATH) { header("X-Telegram-Init-Data", signed) }.status)
        }

    private fun withApp(
        configuredOwner: Long? = OWNER,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            installJsonErrorPages()
            platformOwnerRoutes(configuredOwnerId = configuredOwner, botTokenProvider = { TEST_BOT_TOKEN })
        }
        block()
    }

    private suspend fun HttpResponse.assertCode(code: String) {
        assertEquals(
            code,
            Json
                .parseToJsonElement(bodyAsText())
                .jsonObject
                .getValue("code")
                .jsonPrimitive.content,
        )
    }

    private fun HttpResponse.assertPrivate() {
        assertEquals("no-store", headers["Cache-Control"])
        assertEquals("X-Telegram-Init-Data", headers["Vary"])
    }

    private fun signedAt(
        authDate: Long,
        userId: Long = OWNER,
        botToken: String = TEST_BOT_TOKEN,
    ): String =
        WebAppInitDataTestHelper.createInitData(
            botToken,
            mapOf(
                "user" to WebAppInitDataTestHelper.encodeUser(userId),
                "auth_date" to authDate.toString(),
                "signature" to SYNTHETIC_SIGNATURE,
            ),
        )

    private companion object {
        const val PATH = "/api/platform/access"
        const val OWNER = 42L
        const val OTHER = 43L
        const val SYNTHETIC_SIGNATURE = "synthetic_signature-CLB91"
    }
}
