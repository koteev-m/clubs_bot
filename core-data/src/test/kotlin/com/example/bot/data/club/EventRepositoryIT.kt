package com.example.bot.data.club

import com.example.bot.club.Event
import com.example.bot.club.EventRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class EventRepositoryIT : PostgresClubIntegrationTest() {
    private lateinit var repository: EventRepository

    @BeforeEach
    fun initRepository() {
        repository = EventRepositoryImpl(database)
    }

    @Test
    fun `list events by club and date range`() =
        runBlocking {
            val clubId = insertClub(name = "Aurora")
            val otherClubId = insertClub(name = "Nebula", timezone = "Europe/Berlin")
            val insideRangeStart = Instant.parse("2024-03-01T18:00:00Z")
            val insideRangeEnd = Instant.parse("2024-03-02T02:00:00Z")
            val outsideRangeStart = Instant.parse("2024-02-01T18:00:00Z")
            val outsideRangeEnd = Instant.parse("2024-02-02T02:00:00Z")
            val otherClubStart = Instant.parse("2024-03-01T20:00:00Z")
            val otherClubEnd = Instant.parse("2024-03-02T03:00:00Z")

            val inRangeEventId = insertEvent(clubId, "Spring Night", insideRangeStart, insideRangeEnd)
            insertEvent(clubId, "Old Night", outsideRangeStart, outsideRangeEnd)
            insertEvent(otherClubId, "Berlin Night", otherClubStart, otherClubEnd)

            val events = repository.listByClub(clubId, insideRangeStart..insideRangeEnd)

            val expected =
                listOf(
                    Event(
                        id = inRangeEventId,
                        clubId = clubId,
                        title = "Spring Night",
                        startAt = insideRangeStart,
                        endAt = insideRangeEnd,
                        isSpecial = false,
                        posterUrl = null,
                    ),
                )
            assertEquals(expected, events)
        }

    @Test
    fun `load event by id`() =
        runBlocking {
            val clubId = insertClub(name = "Aurora")
            val startAt = Instant.parse("2024-04-05T18:00:00Z")
            val endAt = Instant.parse("2024-04-06T02:00:00Z")
            val eventId = insertEvent(clubId, "Anniversary", startAt, endAt)

            val event = repository.get(eventId)
            assertNotNull(event)
            assertEquals(eventId, event!!.id)
            assertEquals("Anniversary", event.title)

            val missing = repository.get(eventId + 10)
            assertNull(missing)
        }
}
