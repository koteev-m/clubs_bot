package com.example.bot.web

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookingWebAppRoutesPrivacyTest {
    @Test
    fun `hq notification text does not include qr secret and contains safe booking ref`() {
        val booking =
            BookingCreated(
                clubId = 1,
                eventId = 10,
                tableId = 20,
                tableNumber = 7,
                guestsCount = 3,
                minDeposit = "10000",
                totalDeposit = "30000",
                qrSecret = "super-secret-qr",
                bookingRef = "a1b2c3d4",
            )

        val text = buildNotifyText(booking, tgUserId = 1000L, user = "guest", display = "Guest")

        assertFalse(text.contains("super-secret-qr"))
        assertFalse(text.contains("QR:"))
        assertTrue(text.contains("Ref:"))
        assertTrue(text.contains("a1b2c3d4"))
    }
}
