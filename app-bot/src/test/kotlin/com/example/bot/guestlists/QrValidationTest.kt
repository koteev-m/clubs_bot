package com.example.bot.guestlists

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QrValidationTest {
    @Test
    fun `rejects empty`() {
        assertEquals("empty_qr", quickValidateQr(""))
    }

    @Test
    fun `rejects too short or too long`() {
        assertEquals("invalid_qr_length", quickValidateQr("short"))

        val tooLong = "GL:1:1:1:" + "a".repeat(MAX_QR_LEN)
        assertEquals("invalid_qr_length", quickValidateQr(tooLong))
    }

    @Test
    fun `rejects bad format`() {
        assertEquals("invalid_qr_format", quickValidateQr("GL:abc:1:2:zz"))
    }

    @Test
    fun `accepts valid`() {
        assertNull(quickValidateQr("GL:123:456:1732390400:deadbeefdeadbeef"))
    }

    @Test
    fun `accepts min and mixed case hmac but rejects too short hmac`() {
        val minHmac = "GL:1:1:1:" + "a".repeat(16)
        assertNull(quickValidateQr(minHmac))

        val shortHmac = "GL:1:1:1:" + "a".repeat(15)
        assertEquals("invalid_qr_format", quickValidateQr(shortHmac))

        val mixedCase = "GL:1:1:1:" + "AaBbCcDdEeFf0011"
        assertNull(quickValidateQr(mixedCase))
    }

    @Test
    fun `accepts max length`() {
        val prefix = "GL:1:1:1:"
        val hmacLength = MAX_QR_LEN - prefix.length
        val maxHmac = "a".repeat(hmacLength)
        val qr = prefix + maxHmac

        assertEquals(MAX_QR_LEN, qr.length)
        assertNull(quickValidateQr(qr))
    }
}
