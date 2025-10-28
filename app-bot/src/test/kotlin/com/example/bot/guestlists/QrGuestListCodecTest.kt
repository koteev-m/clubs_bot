package com.example.bot.guestlists

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QrGuestListCodecTest {
    private val secret = "super_secret_qr"
    private val fixedIssued = Instant.parse("2025-09-25T00:00:00Z")
    private val now = fixedIssued.plusSeconds(30)
    private val ttl = Duration.ofHours(12)
    private val skew = Duration.ofMinutes(2)

    @Test
    fun `happy path`() {
        val token =
            QrGuestListCodec.encode(
                listId = 12345,
                entryId = 6789,
                issuedAt = fixedIssued,
                secret = secret,
            )

        val decoded = QrGuestListCodec.verify(token, now, ttl, secret, skew)

        assertNotNull(decoded)
        assertTrue(token.startsWith("GL:"))
        assertTrue(token.length < 100)
        assertEquals(12345L, decoded!!.listId)
        assertEquals(6789L, decoded.entryId)
        assertEquals(fixedIssued, decoded.issuedAt)
    }

    @Test
    fun `bad prefix or shape`() {
        val hmacPlaceholder = "0".repeat(64)
        val invalidTokens =
            listOf(
                "",
                "GX:12345:6789:${fixedIssued.epochSecond}:$hmacPlaceholder",
                "GL:only:two:parts",
                "GL:12345::${fixedIssued.epochSecond}:$hmacPlaceholder",
                "GL:12345:6789::$hmacPlaceholder",
                "GL:12345:6789:${fixedIssued.epochSecond}",
            )

        invalidTokens.forEach { token ->
            assertNull(QrGuestListCodec.verify(token, now, ttl, secret, skew))
        }
    }

    @Test
    fun `non numeric identifiers`() {
        val hmacPlaceholder = "0".repeat(64)
        val tokens =
            listOf(
                "GL:abc:6789:${fixedIssued.epochSecond}:$hmacPlaceholder",
                "GL:12345:NaN:${fixedIssued.epochSecond}:$hmacPlaceholder",
                "GL:12345:6789:abc:$hmacPlaceholder",
            )

        tokens.forEach { token ->
            assertNull(QrGuestListCodec.verify(token, now, ttl, secret, skew))
        }
    }

    @Test
    fun `non positive identifiers`() {
        val hmacPlaceholder = "0".repeat(64)
        val tokens =
            listOf(
                "GL:0:6789:${fixedIssued.epochSecond}:$hmacPlaceholder",
                "GL:-1:6789:${fixedIssued.epochSecond}:$hmacPlaceholder",
                "GL:12345:0:${fixedIssued.epochSecond}:$hmacPlaceholder",
                "GL:12345:-10:${fixedIssued.epochSecond}:$hmacPlaceholder",
            )

        tokens.forEach { token ->
            assertNull(QrGuestListCodec.verify(token, now, ttl, secret, skew))
        }
    }

    @Test
    fun `bad hmac`() {
        val token =
            QrGuestListCodec.encode(
                listId = 321,
                entryId = 654,
                issuedAt = fixedIssued,
                secret = secret,
            )
        val tampered = token.dropLast(1) + if (token.last() == 'a') 'b' else 'a'

        assertNull(QrGuestListCodec.verify(tampered, now, ttl, secret, skew))
    }

    @Test
    fun `expired token`() {
        val token =
            QrGuestListCodec.encode(
                listId = 321,
                entryId = 654,
                issuedAt = fixedIssued,
                secret = secret,
            )
        val expiredNow = fixedIssued.plus(ttl).plusSeconds(1)

        assertNull(QrGuestListCodec.verify(token, expiredNow, ttl, secret, skew))
    }

    @Test
    fun `issued beyond allowed skew`() {
        val futureIssued = now.plus(skew).plusSeconds(1)
        val token =
            QrGuestListCodec.encode(
                listId = 999,
                entryId = 888,
                issuedAt = futureIssued,
                secret = secret,
            )

        assertNull(QrGuestListCodec.verify(token, now, ttl, secret, skew))
    }

    @Test
    fun `uppercase hmac accepted`() {
        val token =
            QrGuestListCodec.encode(
                listId = 77,
                entryId = 88,
                issuedAt = fixedIssued,
                secret = secret,
            )
        val prefixLength = token.lastIndexOf(':') + 1
        val uppercased = token.substring(0, prefixLength) + token.substring(prefixLength).uppercase()

        val decoded = QrGuestListCodec.verify(uppercased, now, ttl, secret, skew)

        assertNotNull(decoded)
    }

    @Test
    fun `fuzz flip in numeric section invalidates token`() {
        val token =
            QrGuestListCodec.encode(
                listId = 12345,
                entryId = 67890,
                issuedAt = fixedIssued,
                secret = secret,
            )
        val indexToFlip = token.indexOfFirst { it.isDigit() }
        val mutated =
            if (indexToFlip >= 0) {
                val original = token[indexToFlip]
                val replacement = if (original == '9') '0' else (original + 1)
                StringBuilder(token).apply { setCharAt(indexToFlip, replacement) }.toString()
            } else {
                token
            }

        assertNull(QrGuestListCodec.verify(mutated, now, ttl, secret, skew))
    }
}
