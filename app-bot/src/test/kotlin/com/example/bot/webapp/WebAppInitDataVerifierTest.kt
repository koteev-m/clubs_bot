package com.example.bot.webapp

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

class WebAppInitDataVerifierTest {
    private val fixedNow = Instant.parse("2025-09-25T12:00:00Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val maxAge = Duration.ofHours(24)
    private val botToken = TEST_BOT_TOKEN
    private val json = Json { encodeDefaults = false }

    @Serializable
    private data class TestUser(
        val id: Long,
        val username: String,
        @SerialName("first_name") val firstName: String,
        @SerialName("last_name") val lastName: String,
    )

    private val userJson: String =
        json.encodeToString(
            TestUser.serializer(),
            TestUser(
                id = 123456789,
                username = "entry_mgr",
                firstName = "Alex",
                lastName = "S",
            ),
        )

    private val authDateEpoch: Long = fixedNow.epochSecond - 3600

    @Test
    fun `happy path returns verified data`() {
        val map =
            linkedMapOf(
                "user" to userJson,
                "auth_date" to authDateEpoch.toString(),
                "query_id" to "Q-42",
            )
        val initData = buildInitData(botToken, map)

        val verified = WebAppInitDataVerifier.verify(initData, botToken, maxAge, clock)

        assertNotNull(verified)
        assertEquals(123456789, verified!!.userId)
        assertEquals("entry_mgr", verified.username)
        assertEquals("Alex", verified.firstName)
        assertEquals("S", verified.lastName)
        assertEquals(Instant.ofEpochSecond(authDateEpoch), verified.authDate)
        assertTrue("hash" !in verified.raw)
        assertEquals(userJson, verified.raw["user"])
        assertEquals("Q-42", verified.raw["query_id"])
    }

    @Test
    fun `bad hash returns null`() {
        val initData =
            buildInitData(
                botToken,
                mapOf(
                    "user" to userJson,
                    "auth_date" to authDateEpoch.toString(),
                ),
            )
        val tampered =
            initData.replaceRange(
                initData.length - 1,
                initData.length,
                if (initData.last() == 'a') "b" else "a",
            )

        val verified = WebAppInitDataVerifier.verify(tampered, botToken, maxAge, clock)

        assertNull(verified)
    }

    @Test
    fun `missing hash returns null`() {
        val initData =
            buildInitData(
                botToken,
                mapOf(
                    "user" to userJson,
                    "auth_date" to authDateEpoch.toString(),
                ),
            )
        val withoutHash = initData.substringBeforeLast("&hash=")

        val verified = WebAppInitDataVerifier.verify(withoutHash, botToken, maxAge, clock)

        assertNull(verified)
    }

    @Test
    fun `expired auth date returns null`() {
        val expiredEpoch = fixedNow.minus(maxAge).minusSeconds(1).epochSecond
        val initData =
            buildInitData(
                botToken,
                mapOf(
                    "user" to userJson,
                    "auth_date" to expiredEpoch.toString(),
                ),
            )

        val verified = WebAppInitDataVerifier.verify(initData, botToken, maxAge, clock)

        assertNull(verified)
    }

    @Test
    fun `future auth date beyond skew returns null`() {
        val futureEpoch = fixedNow.plusSeconds(5 * 60).epochSecond
        val initData =
            buildInitData(
                botToken,
                mapOf(
                    "user" to userJson,
                    "auth_date" to futureEpoch.toString(),
                ),
            )

        val verified = WebAppInitDataVerifier.verify(initData, botToken, maxAge, clock)

        assertNull(verified)
    }

    @Test
    fun `header too long returns null`() {
        val longInput =
            buildString {
                append("a=a")
                repeat(3000) {
                    append("&a=a")
                }
            }

        val verified = WebAppInitDataVerifier.verify(longInput, botToken, maxAge, clock)

        assertNull(verified)
    }

    @Test
    fun `malformed user returns null`() {
        val initData =
            buildInitData(
                botToken,
                mapOf(
                    "user" to "{not json}",
                    "auth_date" to authDateEpoch.toString(),
                ),
            )

        val verified = WebAppInitDataVerifier.verify(initData, botToken, maxAge, clock)

        assertNull(verified)
    }

    @Test
    fun `order independence holds`() {
        val firstOrder =
            linkedMapOf(
                "user" to userJson,
                "query_id" to "Q-42",
                "auth_date" to authDateEpoch.toString(),
            )
        val initData = buildInitData(botToken, firstOrder)

        val secondOrder =
            linkedMapOf(
                "auth_date" to authDateEpoch.toString(),
                "user" to userJson,
                "query_id" to "Q-42",
            )
        val initDataReordered = buildInitData(botToken, secondOrder)

        val verifiedFirst = WebAppInitDataVerifier.verify(initData, botToken, maxAge, clock)
        val verifiedSecond = WebAppInitDataVerifier.verify(initDataReordered, botToken, maxAge, clock)

        assertNotNull(verifiedFirst)
        assertNotNull(verifiedSecond)
        assertEquals(verifiedFirst!!.raw, verifiedSecond!!.raw)
    }

    @Test
    fun `uppercase hash is accepted`() {
        val initData =
            buildInitData(
                botToken,
                mapOf(
                    "user" to userJson,
                    "auth_date" to authDateEpoch.toString(),
                ),
            )
        val uppercased =
            buildString {
                append(initData.substringBeforeLast('='))
                append('=')
                append(initData.substringAfterLast('=').uppercase())
            }

        val verified = WebAppInitDataVerifier.verify(uppercased, botToken, maxAge, clock)

        assertNotNull(verified)
    }
}
