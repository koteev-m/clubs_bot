package com.example.bot.checkin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QrCodecTest {
    private val key = "secret".toByteArray()
    private val codec = QrCodec(key)

    @Test
    fun encodeAndDecode() {
        val data = QrCodec.Data(clubId = 1, eventId = 2, entryId = 3)
        val qr = codec.encode(data)
        val decoded = codec.decode(qr)
        assertEquals(data, decoded)
    }

    @Test
    fun invalidSignatureReturnsNull() {
        val data = QrCodec.Data(1, 2, 3)
        val qr = codec.encode(data)
        val tampered = qr.replaceRange(qr.length - 1, qr.length, "A")
        assertNull(codec.decode(tampered))
    }
}
