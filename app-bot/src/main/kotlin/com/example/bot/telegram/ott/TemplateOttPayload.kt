package com.example.bot.telegram.ott

import java.time.Instant

/** Payload stored inside OTT callback tokens for template actions. */
sealed interface TemplateOttPayload : OttPayload {
    data class Selection(val templateId: Long) : TemplateOttPayload

    data class Booking(
        val templateId: Long,
        val clubId: Long,
        val tableId: Long,
        val slotStart: Instant,
        val slotEnd: Instant,
        val guests: Int?,
    ) : TemplateOttPayload
}
