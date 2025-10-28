package com.example.bot.telegram.tokens

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableSelectCodecTest {
    @Test
    fun `encode decode round trip`() {
        val start = Instant.parse("2024-03-01T18:00:00Z")
        val end = start.plusSeconds(4 * 3600)
        val token = TableSelectCodec.encode(clubId = 9876L, startUtc = start, endUtc = end, tableId = 45L)
        val callback = token
        assertTrue(callback.length <= 64)
        val decoded = TableSelectCodec.decode(callback)
        assertNotNull(decoded)
        assertEquals(9876L, decoded.clubId)
        assertEquals(start, decoded.startUtc)
        assertEquals(end, decoded.endUtc)
        assertEquals(45L, decoded.tableId)
    }

    @Test
    fun `decode rejects malformed tokens`() {
        assertNull(TableSelectCodec.decode(""))
        assertNull(TableSelectCodec.decode("tbl:"))
        assertNull(TableSelectCodec.decode("tbl:a.b"))
        assertNull(TableSelectCodec.decode("tbl:a.b.c.d.e"))
    }

    @Test
    fun `decode rejects invalid numbers`() {
        assertNull(TableSelectCodec.decode("tbl:-1.a.a.a"))
        assertNull(TableSelectCodec.decode("tbl:a.-1.a.a"))
        assertNull(TableSelectCodec.decode("tbl:a.a.-1.a"))
        assertNull(TableSelectCodec.decode("tbl:a.a.a.-1"))
    }
}
