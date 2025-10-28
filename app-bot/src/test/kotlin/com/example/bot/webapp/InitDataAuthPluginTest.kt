package com.example.bot.webapp

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals

private fun hmacSha256(
    key: ByteArray,
    data: ByteArray,
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

private fun hexLower(bytes: ByteArray): String {
    val builder = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
        val value = byte.toInt() and 0xFF
        builder.append(Character.forDigit(value ushr 4, 16))
        builder.append(Character.forDigit(value and 0x0F, 16))
    }
    return builder.toString()
}

private fun buildDataCheckString(params: Map<String, String>): String {
    return params
        .filterKeys { it != "hash" }
        .toSortedMap()
        .entries
        .joinToString("\n") { (key, value) -> "$key=$value" }
}

private fun buildInitData(
    botToken: String,
    map: Map<String, String>,
): String {
    val withoutHash = map.filterKeys { it != "hash" }
    val secretKey =
        hmacSha256(
            botToken.toByteArray(StandardCharsets.UTF_8),
            "WebAppData".toByteArray(StandardCharsets.UTF_8),
        )
    val dataCheckString = buildDataCheckString(withoutHash)
    val hashHex = hexLower(hmacSha256(secretKey, dataCheckString.toByteArray(StandardCharsets.UTF_8)))
    val builder = StringBuilder()
    var first = true
    for ((key, value) in map) {
        if (key == "hash") {
            continue
        }
        if (!first) {
            builder.append('&')
        } else {
            first = false
        }
        builder.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
        builder.append('=')
        builder.append(URLEncoder.encode(value, StandardCharsets.UTF_8))
    }
    if (!first) {
        builder.append('&')
    }
    builder.append("hash=")
    builder.append(hashHex)
    return builder.toString()
}

class InitDataAuthPluginTest {
    private val botToken = TEST_BOT_TOKEN
    private val fixedNow = Instant.parse("2025-09-25T12:00:00Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val json = Json { encodeDefaults = false }

    @Serializable
    private data class TestUser(
        val id: Long,
        val username: String,
        @SerialName("first_name") val firstName: String,
        @SerialName("last_name") val lastName: String,
    )

    private fun validInitData(): String {
        val authDate = fixedNow.epochSecond - 120
        val userJson =
            json.encodeToString(
                TestUser.serializer(),
                TestUser(
                    id = 123456789,
                    username = "entry_mgr",
                    firstName = "Alex",
                    lastName = "S",
                ),
            )
        val map =
            linkedMapOf(
                "user" to userJson,
                "auth_date" to authDate.toString(),
            )
        return buildInitData(botToken, map)
    }

    @Test
    fun `plugin authorizes valid init data`() =
        testApplication {
            application {
                routing {
                    route("/t") {
                        install(InitDataAuthPlugin) {
                            botTokenProvider = { botToken }
                            maxAge = Duration.ofHours(24)
                            this.clock = this@InitDataAuthPluginTest.clock
                        }
                        get {
                            if (call.attributes.contains(InitDataPrincipalKey)) {
                                call.respond(HttpStatusCode.OK)
                            } else {
                                call.respond(HttpStatusCode.Unauthorized)
                            }
                        }
                    }
                }
            }

            val header = validInitData()
            val success =
                client.get("/t") {
                    header("X-Telegram-Init-Data", header)
                }
            assertEquals(HttpStatusCode.OK, success.status)

            val alias =
                client.get("/t") {
                    header("X-Telegram-InitData", header)
                }
            assertEquals(HttpStatusCode.OK, alias.status)

            val missing = client.get("/t")
            assertEquals(HttpStatusCode.Unauthorized, missing.status)

            val invalid =
                client.get("/t") {
                    header(
                        "X-Telegram-Init-Data",
                        header.replaceRange(header.length - 1, header.length, "0"),
                    )
                }
            assertEquals(HttpStatusCode.Unauthorized, invalid.status)
        }
}
