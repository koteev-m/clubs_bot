package com.example.bot.security.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class InitDataHmacRegressionTest {
    private val token = "999999:SYNTHETIC_CLB91_TOKEN"
    private val authInstant = Instant.ofEpochSecond(1793059200)
    private val clock = Clock.fixed(authInstant, ZoneOffset.UTC)
    private val initData = independentVector()

    @Test
    fun `accepts independent Telegram HMAC vector including signature`() {
        val user = InitDataValidator.validate(initData, token, clock = clock)

        assertEquals(TelegramUser(42, "synthetic_owner"), user)
    }

    @Test
    fun `rejects changed or removed signature without recomputing hash`() {
        val changed = initData.replace("synthetic_signature-CLB91", "tampered_signature")
        val removed = initData.split('&').filterNot { it.startsWith("signature=") }.joinToString("&")

        listOf(changed, removed).forEach { raw ->
            assertNull(InitDataValidator.validate(raw, token, clock = clock))
        }
    }

    @Test
    fun `rejects independent vector with wrong bot token`() {
        assertNull(InitDataValidator.validate(initData, "$token-wrong", clock = clock))
    }

    @Test
    fun `rejects expired and too future signed vector`() {
        // Keep the HMAC valid and move only the verifier's clock outside the allowed windows.
        listOf(authInstant.plusSeconds(86_401), authInstant.minusSeconds(121)).forEach { now ->
            assertNull(InitDataValidator.validate(initData, token, clock = Clock.fixed(now, ZoneOffset.UTC)))
        }
    }

    @Test
    fun `rejects missing empty malformed and forged hash`() {
        val withoutHash = initData.substringBefore("&hash=")
        val invalid = listOf(withoutHash) + listOf("", "abc", "zz", "00".repeat(32)).map { "$withoutHash&hash=$it" }

        invalid.forEach { raw ->
            assertNull(InitDataValidator.validate(raw, token, clock = clock))
        }
    }

    private fun independentVector(): String {
        // Synthetic vector computed independently with Python stdlib hmac + hashlib.sha256:
        // secret = hmac.new(b"WebAppData", token, sha256).digest()
        // hash = hmac.new(secret, data_check_string.encode(), sha256).hexdigest()
        // Explicit alphabetical data-check-string includes signature; only hash is omitted.
        val dataCheckString =
            "auth_date=1793059200\n" +
                "query_id=synthetic-query\n" +
                "signature=synthetic_signature-CLB91\n" +
                "user={\"id\":42,\"username\":\"synthetic_owner\"}"
        val encoded =
            dataCheckString.lines().joinToString("&") { field ->
                val (key, value) = field.split('=', limit = 2)
                "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
            }
        return "$encoded&hash=7277050e6df375cf240c6f3205aa4bdcd376c116191d12cca888d583f11e79ef"
    }
}
