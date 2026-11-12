package com.example.bot.plugins.target

import com.example.bot.security.auth.InitDataValidator
import io.ktor.http.Headers
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TargetIdentityCredentialTest {
    @Test
    fun `duplicate form fields including optional fields are rejected without changing legacy last wins`() {
        val f = TargetIdentityFixture()
        val signed = f.signed(extras = mapOf("state" to "last"))
        for (part in listOf("state=first&", "user=%7B%22id%22%3A99%7D&", "auth_date=0&", "hash=bad&")) {
            val raw = part + signed
            assertNotNull(InitDataValidator.validate(raw, f.token, clock = f.clock))
            assertNull(f.verify(raw))
        }
        val caseVariant = f.signed(extras = mapOf("Signature" to "ambiguous"))
        assertNotNull(InitDataValidator.validate(caseVariant, f.token, clock = f.clock))
        assertNull(f.verify(caseVariant))
        assertEquals(0, f.tokenReads)
    }

    @Test
    fun `duplicate JSON keys including escaped spellings nested objects and arrays are rejected`() {
        val f = TargetIdentityFixture()
        val invalid =
            listOf(
                "{\"id\":8,\"id\":7}",
                "{\"id\":7,\"\\u0069d\":7}",
                "{\"id\":7,\"x\":{\"name\":1,\"name\":2}}",
                "{\"id\":7,\"x\":[{\"a\":1,\"a\":2}]}",
            )
        for (user in invalid) {
            val raw = f.signed(user = user)
            assertNotNull(InitDataValidator.validate(raw, f.token, clock = f.clock))
            assertNull(f.verify(raw))
        }
        assertNotNull(f.verify(f.signed(user = "{\"id\":7,\"x\":[{\"a\":1},{\"a\":2}]}")))
    }

    @Test
    fun `malformed and deeply nested JSON is rejected before crypto without raw exceptions`() {
        val f = TargetIdentityFixture()
        val invalid =
            listOf(
                "null",
                "[]",
                "\"user\"",
                "{",
                "{\"id\":7,}",
                "{\"id\":7}{}",
                "{\"id\":7,\"x\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}",
            )
        for (user in invalid) assertNull(f.verify(f.signed(user = user)))
        assertEquals(0, f.tokenReads)
    }

    @Test
    fun `malformed UTF8 is rejected even when legacy replacement decoding has a valid HMAC`() {
        val f = TargetIdentityFixture()
        val valid = f.signed(extras = mapOf("state" to "\uFFFD("))
        val malformed = valid.replace("state=%EF%BF%BD%28", "state=%C3%28")
        assertNotNull(InitDataValidator.validate(malformed, f.token, clock = f.clock))
        assertNull(f.verify(malformed))
        assertEquals(0, f.tokenReads)
    }

    @Test
    fun `malformed percent encoding encoded field names empty fields and controls are rejected`() {
        val f = TargetIdentityFixture()
        val raw = f.signed()
        val invalid =
            listOf(
                "$raw&state=%",
                "$raw&state=%G0",
                "$raw&state=%0",
                "$raw&state=%00",
                "$raw&state=%0A",
                "$raw&%73tate=x",
                "$raw&Auth_date=100",
                "$raw&state",
                "$raw&",
                "&$raw",
                "$raw&&state=x",
            )
        for (credential in invalid) assertNull(f.verify(credential))
        assertEquals(0, f.tokenReads)
    }

    @Test
    fun `malformed date integer overflow and noncanonical numeric spelling are rejected`() {
        val f = TargetIdentityFixture()
        for (date in listOf("", "-1", "+1", "1.0", "01", "9223372036854775808", Long.MAX_VALUE.toString())) {
            assertNull(f.verify(f.signed(date = date)))
        }
        assertEquals(0, f.tokenReads)
    }

    @Test
    fun `header must be singular canonical and contain no alternative credential or query`() {
        val f = TargetIdentityFixture()
        val raw = f.signed()
        assertNull(f.verifier.verify(Headers.Empty, "", f.context))
        for (name in listOf("X-Telegram-InitData", "X-Init-Data", "Authorization")) {
            val headers =
                Headers.build {
                    append("X-Telegram-Init-Data", raw)
                    append(name, "synthetic")
                }
            assertNull(f.verifier.verify(headers, "", f.context))
        }
        val duplicated =
            Headers.build {
                append("X-Telegram-Init-Data", raw)
                append("x-telegram-init-data", raw)
            }
        assertNull(f.verifier.verify(duplicated, "", f.context))
        for (query in listOf("initData=x", "%69nitData=%GG", "clientId=102", "clubId=2001", "&")) {
            assertNull(f.verifier.verify(f.headers(), query, f.context))
        }
        assertNotNull(f.verifier.verify(Headers.build { append("x-telegram-init-data", raw) }, "", f.context))
    }

    @Test
    fun `invalid raw header bytes combined values whitespace and oversize are rejected`() {
        val f = TargetIdentityFixture()
        val raw = f.signed()
        for (value in listOf(
            "",
            " $raw",
            "$raw ",
            "$raw, $raw",
            "$raw\r\n",
            "$raw\u0000",
            "$raw\t",
            "x".repeat(8193),
        )) {
            val headers = mockk<Headers>()
            every { headers.getAll("X-Telegram-Init-Data") } returns listOf(value)
            every { headers.contains(any()) } returns false
            assertNull(f.verifier.verify(headers, "", f.context))
        }
    }

    @Test
    fun `invalid duplicate credentials do not fall through to a looser general validator`() {
        val f = TargetIdentityFixture()
        val raw = "state=first&" + f.signed(extras = mapOf("state" to "last"))
        assertNotNull(
            f.connection.verifyWithPolicy(
                raw,
                Clock.fixed(f.now, ZoneOffset.UTC),
                java.time.Duration.ofSeconds(300),
                java.time.Duration.ZERO,
            ),
        )
        assertNull(f.verify(raw))
    }
}
