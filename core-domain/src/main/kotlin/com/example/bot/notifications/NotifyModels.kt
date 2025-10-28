package com.example.bot.notifications

import kotlinx.serialization.Serializable

// Domain events that may trigger notifications
sealed interface TxEvent {
    data class BookingCreated(val bookingId: String) : TxEvent

    data class BookingCancelled(val bookingId: String) : TxEvent

    data class BookingSeated(val bookingId: String) : TxEvent

    data class GuestArrived(val guestId: String) : TxEvent

    data class GuestDenied(val guestId: String) : TxEvent

    data class GuestLate(val guestId: String) : TxEvent

    data class QuestionFromUser(val userId: String, val question: String) : TxEvent
}

// Notification send method
@Serializable
enum class NotifyMethod {
    TEXT,
    PHOTO,
    ALBUM,
}

// Simplified parse modes for outgoing messages
@Serializable
enum class ParseMode {
    MARKDOWNV2,
    HTML,
}

// Simple representation of media for albums
// type can be "photo", "video", etc.
@Serializable
data class MediaItem(val type: String, val url: String, val caption: String? = null, val parseMode: ParseMode? = null)

// Minimal keyboard specification: rows of button labels
@Serializable
data class InlineKeyboardSpec(val rows: List<List<String>>)

// Unified notification message
// Depending on method, text/photoUrl/album are used
// parseMode applies to text or captions where relevant
@Serializable
data class NotifyMessage(
    val chatId: Long,
    val messageThreadId: Int?,
    val method: NotifyMethod,
    val text: String?,
    val parseMode: ParseMode?,
    val photoUrl: String?,
    val album: List<MediaItem>?,
    val buttons: InlineKeyboardSpec?,
    val dedupKey: String?,
)
