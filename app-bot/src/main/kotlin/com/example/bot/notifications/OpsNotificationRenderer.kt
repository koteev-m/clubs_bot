package com.example.bot.notifications

import com.example.bot.opschat.OpsDomainNotification
import com.example.bot.opschat.OpsNotificationEvent

/**
 * Шаблоны сообщений для операционных уведомлений.
 *
 * ВАЖНО: никаких токенов/QR/инициализационных данных.
 */
object OpsNotificationRenderer {
    fun render(notification: OpsDomainNotification): String {
        val subject = notification.subjectId?.let { " (ID: $it)" } ?: ""
        return buildString {
            append(eventLabel(notification.event))
            append(subject)
            append(". Клуб ")
            append(notification.clubId)
            append(". Время ")
            append(notification.occurredAt)
        }
    }

    private fun eventLabel(event: OpsNotificationEvent): String =
        when (event) {
            OpsNotificationEvent.BOOKING_CREATED -> "Создано бронирование"
            OpsNotificationEvent.BOOKING_CANCELLED -> "Отмена бронирования"
            OpsNotificationEvent.BOOKING_SEATED -> "Гость рассажен"
            OpsNotificationEvent.GUEST_ARRIVED -> "Гость прибыл"
            OpsNotificationEvent.GUEST_DENIED -> "Отказ в доступе гостю"
            OpsNotificationEvent.GUEST_LATE -> "Гость опаздывает"
            OpsNotificationEvent.QUESTION_FROM_USER -> "Вопрос от пользователя"
        }
}
