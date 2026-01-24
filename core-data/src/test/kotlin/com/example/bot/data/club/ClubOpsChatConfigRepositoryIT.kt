package com.example.bot.data.club

import com.example.bot.opschat.ClubOpsChatConfigUpsert
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ClubOpsChatConfigRepositoryIT : PostgresClubIntegrationTest() {
    @Test
    fun `upsert creates and updates club ops chat config`() =
        runBlocking {
            val clubId = insertClub(name = "Ops Club")

            val initialRepo = ClubOpsChatConfigRepositoryImpl(database, Clock.fixed(INITIAL_INSTANT, ZoneOffset.UTC))
            val updatedRepo = ClubOpsChatConfigRepositoryImpl(database, Clock.fixed(UPDATED_INSTANT, ZoneOffset.UTC))

            assertNull(initialRepo.getByClubId(clubId))

            val created =
                initialRepo.upsert(
                    ClubOpsChatConfigUpsert(
                        clubId = clubId,
                        chatId = -1001234567890,
                        bookingsThreadId = 101,
                        checkinThreadId = null,
                        guestListsThreadId = 707,
                        supportThreadId = 303,
                        alertsThreadId = null,
                    ),
                )

            assertEquals(clubId, created.clubId)
            assertEquals(-1001234567890, created.chatId)
            assertEquals(101, created.bookingsThreadId)
            assertNull(created.checkinThreadId)
            assertEquals(707, created.guestListsThreadId)
            assertEquals(303, created.supportThreadId)
            assertNull(created.alertsThreadId)
            assertEquals(INITIAL_INSTANT, created.updatedAt)

            val updated =
                updatedRepo.upsert(
                    ClubOpsChatConfigUpsert(
                        clubId = clubId,
                        chatId = -1000000000001,
                        bookingsThreadId = null,
                        checkinThreadId = 202,
                        guestListsThreadId = null,
                        supportThreadId = null,
                        alertsThreadId = 808,
                    ),
                )

            assertEquals(clubId, updated.clubId)
            assertEquals(-1000000000001, updated.chatId)
            assertNull(updated.bookingsThreadId)
            assertEquals(202, updated.checkinThreadId)
            assertNull(updated.guestListsThreadId)
            assertNull(updated.supportThreadId)
            assertEquals(808, updated.alertsThreadId)
            assertEquals(UPDATED_INSTANT, updated.updatedAt)

            val loaded = updatedRepo.getByClubId(clubId)
            assertNotNull(loaded)
            assertEquals(updated, loaded)
        }

    private companion object {
        private val INITIAL_INSTANT: Instant = Instant.parse("2025-01-01T10:00:00Z")
        private val UPDATED_INSTANT: Instant = Instant.parse("2025-01-01T12:00:00Z")
    }
}
