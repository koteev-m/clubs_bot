package com.example.bot.security.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.random.Random
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class InitDataValidatorTest {
    private val json = Json
    private val botToken = "999999:TEST"
    private val clock = Clock.fixed(Instant.parse("2024-01-02T00:00:00Z"), ZoneOffset.UTC)

    @Serializable
    private data class TelegramUserPayload(val id: Long, val username: String? = null)

    @Test
    fun `accepts payload signed via WebApp spec and ignores signature field`() {
        val params = linkedMapOf(
            "user" to json.encodeToString(TelegramUserPayload(id = 42, username = "miniapp")),
            "auth_date" to Instant.now(clock).epochSecond.toString(),
            "signature" to "deadbeef",
        )
        val initData = createInitData(botToken, params)

        val user = InitDataValidator.validate(initData, botToken, clock = clock)

        assertEquals(42L, user?.id)
        assertEquals("miniapp", user?.username)
    }

    @Test
    fun `accepts payload with uppercase hash`() {
        val params = linkedMapOf(
            "user" to json.encodeToString(TelegramUserPayload(id = 77)),
            "auth_date" to Instant.now(clock).epochSecond.toString(),
        )
        val initData = createInitData(botToken, params)
        val upper = initData.uppercaseHash()

        val user = InitDataValidator.validate(upper, botToken, clock = clock)

        assertEquals(77L, user?.id)
    }

    @Test
    fun `accepts payload with additional optional fields`() {
        val params = linkedMapOf(
            "user" to json.encodeToString(TelegramUserPayload(id = 88, username = "invite")),
            "auth_date" to Instant.now(clock).epochSecond.toString(),
            "state" to "foo",
            "chat_instance" to "1234567890",
            "query_id" to "q-1",
        )
        val initData = createInitData(botToken, params)

        val user = InitDataValidator.validate(initData, botToken, clock = clock)

        assertEquals(88L, user?.id)
        assertEquals("invite", user?.username)
    }

    @Test
    fun `accepts payload regardless of parameter order`() {
        val params = linkedMapOf(
            "user" to json.encodeToString(TelegramUserPayload(id = 21, username = "order")),
            "auth_date" to Instant.now(clock).epochSecond.toString(),
            "state" to "x",
        )
        val initData = createInitData(botToken, params)
        val shuffled = initData.split("&").shuffled(Random(123)).joinToString("&")

        val user = InitDataValidator.validate(shuffled, botToken, clock = clock)

        assertEquals(21L, user?.id)
        assertEquals("order", user?.username)
    }

    @Test
    fun `accepts payload when duplicate keys are present and last value wins`() {
        val params = linkedMapOf(
            "auth_date" to Instant.now(clock).epochSecond.toString(),
            "state" to "latest",
            "user" to json.encodeToString(TelegramUserPayload(id = 99, username = "dup")),
        )
        val initData = createInitData(botToken, params)
        val duplicateSegments = listOf(
            "state=${URLEncoder.encode("stale", StandardCharsets.UTF_8)}",
            "user=${URLEncoder.encode(json.encodeToString(TelegramUserPayload(id = 1, username = "old")), StandardCharsets.UTF_8)}",
        ).joinToString("&")
        val withDuplicates = "$duplicateSegments&$initData"

        val user = InitDataValidator.validate(withDuplicates, botToken, clock = clock)

        assertEquals(99L, user?.id)
        assertEquals("dup", user?.username)
    }

    @Test
    fun `rejects payload signed with legacy SHA256 scheme`() {
        val params = linkedMapOf(
            "user" to json.encodeToString(TelegramUserPayload(id = 7)),
            "auth_date" to Instant.now(clock).epochSecond.toString(),
        )
        val initData = createInitDataWithShaDigest(botToken, params)

        val user = InitDataValidator.validate(initData, botToken, clock = clock)

        assertNull(user)
    }

    @Test
    fun `rejects payload with odd length hash`() {
        val params = linkedMapOf(
            "user" to json.encodeToString(TelegramUserPayload(id = 70)),
            "auth_date" to Instant.now(clock).epochSecond.toString(),
        )
        val initData = createInitData(botToken, params)
        val tampered = initData.withOddLengthHash()

        val user = InitDataValidator.validate(tampered, botToken, clock = clock)

        assertNull(user)
    }

    @Test
    fun `rejects payload with non hex hash`() {
        val params = linkedMapOf(
            "user" to json.encodeToString(TelegramUserPayload(id = 71)),
            "auth_date" to Instant.now(clock).epochSecond.toString(),
        )
        val initData = createInitData(botToken, params)
        val tampered = initData.withInvalidHexHash()

        val user = InitDataValidator.validate(tampered, botToken, clock = clock)

        assertNull(user)
    }

    @Test
    fun `rejects payload with stale auth_date`() {
        val staleInstant = Instant.now(clock).minus(Duration.ofDays(2))
        val params = linkedMapOf(
            "user" to json.encodeToString(TelegramUserPayload(id = 9)),
            "auth_date" to staleInstant.epochSecond.toString(),
        )
        val initData = createInitData(botToken, params)

        val user = InitDataValidator.validate(initData, botToken, clock = clock)

        assertNull(user)
    }

    @Test
    fun `rejects payload with auth_date too far in the future`() {
        val futureInstant = Instant.now(clock).plus(Duration.ofMinutes(10))
        val params = linkedMapOf(
            "user" to json.encodeToString(TelegramUserPayload(id = 11)),
            "auth_date" to futureInstant.epochSecond.toString(),
        )
        val initData = createInitData(botToken, params)

        val user = InitDataValidator.validate(
            initData,
            botToken,
            clock = clock,
            maxFutureSkew = Duration.ofMinutes(2),
        )

        assertNull(user)
    }

    @Test
    fun `rejects payload missing auth_date`() {
        val params = linkedMapOf(
            "user" to json.encodeToString(TelegramUserPayload(id = 14)),
        )
        val initData = createInitData(botToken, params)

        val user = InitDataValidator.validate(initData, botToken, clock = clock)

        assertNull(user)
    }

    @Test
    fun `rejects payload with non numeric auth_date`() {
        val params = linkedMapOf(
            "user" to json.encodeToString(TelegramUserPayload(id = 15)),
            "auth_date" to "abc",
        )
        val initData = createInitData(botToken, params)

        val user = InitDataValidator.validate(initData, botToken, clock = clock)

        assertNull(user)
    }

    @Test
    fun `rejects payload with malformed percent encoding`() {
        val params = linkedMapOf(
            "user" to json.encodeToString(TelegramUserPayload(id = 16)),
            "auth_date" to Instant.now(clock).epochSecond.toString(),
        )
        val initData = createInitData(botToken, params)
        val mangled = initData.replaceFirst("%22", "%2G")

        val user = InitDataValidator.validate(mangled, botToken, clock = clock)

        assertNull(user)
    }

    @Test
    fun `accepts payload exactly at maxAge boundary`() {
        val boundaryInstant = Instant.now(clock).minus(Duration.ofHours(24))
        val params = linkedMapOf(
            "user" to json.encodeToString(TelegramUserPayload(id = 12)),
            "auth_date" to boundaryInstant.epochSecond.toString(),
        )
        val initData = createInitData(botToken, params)

        val user = InitDataValidator.validate(
            initData,
            botToken,
            clock = clock,
            maxAge = Duration.ofHours(24),
        )

        assertEquals(12L, user?.id)
    }

    @Test
    fun `accepts payload exactly at future skew boundary`() {
        val boundaryInstant = Instant.now(clock).plus(Duration.ofMinutes(2))
        val params = linkedMapOf(
            "user" to json.encodeToString(TelegramUserPayload(id = 13)),
            "auth_date" to boundaryInstant.epochSecond.toString(),
        )
        val initData = createInitData(botToken, params)

        val user = InitDataValidator.validate(
            initData,
            botToken,
            clock = clock,
            maxFutureSkew = Duration.ofMinutes(2),
        )

        assertEquals(13L, user?.id)
    }

    private fun createInitData(botToken: String, raw: Map<String, String>): String {
        val secret = hmacSha256("WebAppData".toByteArray(StandardCharsets.UTF_8), botToken.toByteArray(StandardCharsets.UTF_8))
        val dataCheckString = raw
            .filterKeys { it != "hash" && it != "signature" }
            .toSortedMap()
            .entries
            .joinToString("\n") { (key, value) -> "$key=$value" }
        val hash = hexLower(hmacSha256(secret, dataCheckString.toByteArray(StandardCharsets.UTF_8)))
        val encoded = raw.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
        return "$encoded&hash=$hash"
    }

    private fun String.uppercaseHash(): String {
        val idx = lastIndexOf("hash=")
        if (idx == -1) return this
        val prefix = substring(0, idx + 5)
        val hash = substring(idx + 5)
        return prefix + hash.uppercase(Locale.ROOT)
    }

    private fun String.withOddLengthHash(): String {
        val idx = lastIndexOf("hash=")
        if (idx == -1) return this
        val prefix = substring(0, idx + 5)
        val hash = substring(idx + 5)
        if (hash.isEmpty()) return this
        return prefix + hash.dropLast(1)
    }

    private fun String.withInvalidHexHash(): String {
        val idx = lastIndexOf("hash=")
        if (idx == -1) return this
        val prefix = substring(0, idx + 5)
        val hash = substring(idx + 5)
        if (hash.length < 2) return this
        return prefix + "zz" + hash.drop(2)
    }

    private fun createInitDataWithShaDigest(botToken: String, raw: Map<String, String>): String {
        val secret = java.security.MessageDigest.getInstance("SHA-256").digest(botToken.toByteArray(StandardCharsets.UTF_8))
        val dataCheckString = raw
            .filterKeys { it != "hash" && it != "signature" }
            .toSortedMap()
            .entries
            .joinToString("\n") { (key, value) -> "$key=$value" }
        val hash = hexLower(hmacSha256(secret, dataCheckString.toByteArray(StandardCharsets.UTF_8)))
        val encoded = raw.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
        return "$encoded&hash=$hash"
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
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
        return builder.toString().lowercase(Locale.ROOT)
    }
}
