package com.example.bot.notifications

import com.example.bot.opschat.OpsDomainNotification
import com.example.bot.opschat.OpsNotificationEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class OpsNotificationRendererTest {
    private val occurredAt = Instant.parse("2025-02-03T10:15:30Z")

    @Test
    fun `render is deterministic`() {
        val notification =
            OpsDomainNotification(
                clubId = 10,
                event = OpsNotificationEvent.BOOKING_CREATED,
                subjectId = "123",
                occurredAt = occurredAt,
            )

        val first = OpsNotificationRenderer.render(notification)
        val second = OpsNotificationRenderer.render(notification)

        assertEquals(first, second)
    }

    @Test
    fun `rendered text does not include forbidden tokens`() {
        val forbidden =
            listOf(
                "gl1:",
                "initdata",
                "bot token",
                "telegram_bot_token",
                "qr",
            )

        OpsNotificationEvent.values().forEach { event ->
            val notification =
                OpsDomainNotification(
                    clubId = 2,
                    event = event,
                    subjectId = "subject-9",
                    occurredAt = occurredAt,
                )
            val text = OpsNotificationRenderer.render(notification)
            val lower = text.lowercase()
            forbidden.forEach { token ->
                assertTrue(
                    token !in lower,
                    "Forbidden token '$token' found in rendered text for event $event",
                )
            }
        }
    }

    @Test
    fun `renders event-specific labels`() {
        val expectations =
            mapOf(
                OpsNotificationEvent.BOOKING_CREATED to "Создано бронирование",
                OpsNotificationEvent.BOOKING_CANCELLED to "Отмена бронирования",
                OpsNotificationEvent.BOOKING_SEATED to "Гость рассажен",
                OpsNotificationEvent.GUEST_ARRIVED to "Гость прибыл",
                OpsNotificationEvent.GUEST_DENIED to "Отказ в доступе гостю",
                OpsNotificationEvent.GUEST_LATE to "Гость опаздывает",
                OpsNotificationEvent.QUESTION_FROM_USER to "Вопрос от пользователя",
            )

        expectations.forEach { (event, label) ->
            val notification =
                OpsDomainNotification(
                    clubId = 3,
                    event = event,
                    subjectId = "77",
                    occurredAt = occurredAt,
                )
            val text = OpsNotificationRenderer.render(notification)
            assertTrue(text.contains(label), "Expected label '$label' in rendered text for $event")
        }
    }
}
