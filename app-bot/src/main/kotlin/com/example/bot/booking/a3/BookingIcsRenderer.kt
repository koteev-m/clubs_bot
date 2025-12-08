package com.example.bot.booking.a3

import com.example.bot.clubs.Club
import com.example.bot.clubs.Event
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object BookingIcsRenderer {
    private const val PROD_ID = "-//ClubsBot//Bookings//EN"
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)

    fun render(booking: Booking, event: Event?, club: Club?): String {
        val start = booking.arrivalWindow.first
        val end = booking.arrivalWindow.second
        val summary = event?.title?.takeIf { it.isNotBlank() } ?: "Night at ${club?.name ?: "Club"}"
        val location = club?.name ?: ""
        val description =
            buildString {
                append("Booking for table ")
                append(booking.tableId)
                if (club?.name != null) {
                    append(" at club ")
                    append(club.name)
                }
            }

        return buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:$PROD_ID")
            appendLine("BEGIN:VEVENT")
            appendLine("UID:booking-${booking.id}@clubs")
            appendLine("DTSTAMP:${formatInstant(booking.updatedAt)}")
            appendLine("DTSTART:${formatInstant(start)}")
            appendLine("DTEND:${formatInstant(end)}")
            appendLine("SUMMARY:${summary}")
            if (description.isNotBlank()) {
                appendLine("DESCRIPTION:${description}")
            }
            if (location.isNotBlank()) {
                appendLine("LOCATION:${location}")
            }
            appendLine("END:VEVENT")
            appendLine("END:VCALENDAR")
        }
    }

    private fun formatInstant(instant: Instant): String = formatter.format(instant)
}
