package com.example.bot.opschat

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class OpsChatModelsTest {
    @Test
    fun clubIdMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) {
            ClubOpsChatConfigUpsert(
                clubId = 0,
                chatId = -100,
                bookingsThreadId = null,
                checkinThreadId = null,
                guestListsThreadId = null,
                supportThreadId = null,
                alertsThreadId = null,
            )
        }
    }

    @Test
    fun chatIdMustNotBeZero() {
        assertThrows(IllegalArgumentException::class.java) {
            ClubOpsChatConfigUpsert(
                clubId = 1,
                chatId = 0,
                bookingsThreadId = null,
                checkinThreadId = null,
                guestListsThreadId = null,
                supportThreadId = null,
                alertsThreadId = null,
            )
        }
    }

    @Test
    fun threadIdsMustBePositiveWhenProvided() {
        assertThrows(IllegalArgumentException::class.java) {
            ClubOpsChatConfig(
                clubId = 1,
                chatId = -100,
                bookingsThreadId = -1,
                checkinThreadId = null,
                guestListsThreadId = null,
                supportThreadId = null,
                alertsThreadId = null,
                updatedAt = Instant.EPOCH,
            )
        }
    }

    @Test
    fun negativeChatIdIsAllowedForSupergroups() {
        assertDoesNotThrow {
            ClubOpsChatConfigUpsert(
                clubId = 42,
                chatId = -1001234567890,
                bookingsThreadId = 10,
                checkinThreadId = 20,
                guestListsThreadId = 30,
                supportThreadId = 40,
                alertsThreadId = 50,
            )
        }
    }
}
