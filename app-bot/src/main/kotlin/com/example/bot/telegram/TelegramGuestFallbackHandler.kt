package com.example.bot.telegram

import com.example.bot.booking.a3.Booking
import com.example.bot.booking.a3.BookingState
import com.example.bot.booking.a3.BookingStatus
import com.example.bot.booking.a3.QrBookingCodec
import com.example.bot.clubs.ClubsRepository
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.support.SupportService
import com.example.bot.support.SupportServiceResult
import com.example.bot.support.TicketTopic
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Chat
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.request.ForceReply
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TelegramGuestFallbackHandler(
    private val send: suspend (BaseRequest<*, *>) -> BaseResponse,
    private val bookingState: BookingState,
    private val clubsRepository: ClubsRepository,
    private val userRepository: UserRepository,
    private val supportService: SupportService,
    private val botUsername: String?,
    private val qrSecretProvider: () -> String,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(zoneId)

    suspend fun handle(update: Update): Boolean {
        if (handleAskCallback(update.callbackQuery())) {
            return true
        }
        val message = update.message() ?: return false
        val text = message.text()?.trim().orEmpty()

        if (text.equals("/cancel", ignoreCase = true) || text.startsWith("/cancel@", ignoreCase = true)) {
            if (isPrivateChat(message.chat())) {
                sendToMessage(message, "Ок, отменено. Когда будете готовы, используйте /ask.")
                return true
            }
            return false
        }

        if (message.replyToMessage() != null) {
            return handleAskReply(message)
        }

        if (!isPrivateChat(message.chat())) return false

        when {
            isCommand(text, "qr", "my_pass") -> {
                handleQr(message)
                return true
            }
            isCommand(text, "my", "next_booking") -> {
                handleMy(message)
                return true
            }
            isCommand(text, "invites") -> {
                handleInvitesInfo(message)
                return true
            }
            isCommand(text, "ask", "ask_club") -> {
                handleAskStart(message)
                return true
            }
        }

        return false
    }

    private suspend fun handleQr(message: Message) {
        val user = resolveUser(message) ?: return
        val booking = findNearestActiveBooking(user.id)
        if (booking == null) {
            sendToMessage(message, "Активной брони нет. Оформите бронь через miniapp и попробуйте снова.")
            return
        }

        val secret = qrSecretProvider().takeIf { it.isNotBlank() }
        if (secret == null) {
            sendToMessage(message, "Не удалось подготовить пропуск. Попробуйте позже.")
            return
        }

        val payload = QrBookingCodec.encode(booking.id, booking.eventId, booking.updatedAt, secret)
        sendToMessage(
            message,
            "Ваш пропуск:\n$payload\n\nПокажите этот код на входе.",
        )
    }

    private suspend fun handleMy(message: Message) {
        val user = resolveUser(message) ?: return
        val booking = findNearestActiveBooking(user.id)
        if (booking == null) {
            sendToMessage(message, "Ближайших активных броней нет.")
            return
        }
        val clubName = clubsRepository.getById(booking.clubId)?.name ?: "Клуб #${booking.clubId}"
        val text =
            buildString {
                appendLine("Ближайшая бронь")
                appendLine("Клуб: $clubName")
                appendLine("Дата: ${dateFormatter.format(booking.arrivalWindow.first)}")
                appendLine("Окно прибытия: ${dateFormatter.format(booking.arrivalWindow.first)} — ${dateFormatter.format(booking.arrivalWindow.second)}")
                appendLine("Стол: #${booking.tableId}")
                appendLine("Статус: ${booking.status.name}")
                append("Инвайты: переходите по ссылке вида /start inv_<token>")
            }
        sendToMessage(message, text)
    }

    private suspend fun handleInvitesInfo(message: Message) {
        sendToMessage(
            message,
            "Инвайты приходят deep-link'ом в формате /start inv_<token>. Откройте ссылку и подтвердите участие.",
        )
    }

    private suspend fun handleAskStart(message: Message) {
        val user = resolveUser(message) ?: return
        val booking = findNearestActiveBooking(user.id)
        if (booking != null) {
            val clubName = clubsRepository.getById(booking.clubId)?.name ?: "Клуб #${booking.clubId}"
            sendForceReply(
                message,
                "Клуб: $clubName. Ответьте на это сообщение текстом вопроса. clubId:${booking.clubId}",
            )
            return
        }

        val clubs = clubsRepository.list(city = null, query = null, tag = null, genre = null, offset = 0, limit = 8)
        if (clubs.isEmpty()) {
            sendToMessage(message, "Сейчас недоступен список клубов. Попробуйте позже.")
            return
        }
        val rows = clubs.map { club ->
            arrayOf(InlineKeyboardButton(club.name).callbackData("ask:club:${club.id}"))
        }.toTypedArray()
        val request = SendMessage(message.chat().id(), "Выберите клуб для вопроса:")
            .replyMarkup(InlineKeyboardMarkup(*rows))
        applyThread(request, message.threadIdOrNull())
        send(request)
    }

    private suspend fun handleAskCallback(callbackQuery: CallbackQuery?): Boolean {
        if (callbackQuery == null) return false
        val data = callbackQuery.data() ?: return false
        if (!data.startsWith("ask:club:")) return false
        val chat = callbackQuery.message()?.chat() ?: return false
        if (!isPrivateChat(chat)) {
            send(AnswerCallbackQuery(callbackQuery.id()).text("Команда доступна только в личке с ботом."))
            return true
        }
        val clubId = data.removePrefix("ask:club:").toLongOrNull()
        if (clubId == null) {
            send(AnswerCallbackQuery(callbackQuery.id()).text("Некорректный клуб."))
            return true
        }
        val clubName = clubsRepository.getById(clubId)?.name
        if (clubName == null) {
            send(AnswerCallbackQuery(callbackQuery.id()).text("Клуб не найден."))
            return true
        }

        send(AnswerCallbackQuery(callbackQuery.id()).text("Клуб выбран."))
        val message = callbackQuery.message() ?: return false
        val request =
            SendMessage(message.chat().id(), "Вы выбрали $clubName. Ответьте на это сообщение вопросом. clubId:$clubId")
                .replyMarkup(ForceReply())
        applyThread(request, message.threadIdOrNull())
        send(request)
        return true
    }

    private suspend fun handleAskReply(message: Message): Boolean {
        if (!isPrivateChat(message.chat())) return false
        val markerText = message.replyToMessage()?.text().orEmpty()
        val clubId = parseClubIdMarker(markerText)
        if (clubId == null) {
            if (markerText.contains("clubId:", ignoreCase = true)) {
                sendToMessage(message, "Не удалось определить клуб. Начните заново через /ask.")
                return true
            }
            return false
        }
        val user = resolveUser(message) ?: return true
        val question = message.text()?.trim().orEmpty()
        if (question.isBlank()) {
            sendToMessage(message, "Напишите текст вопроса одним сообщением.")
            return true
        }
        val club = clubsRepository.getById(clubId)
        if (club == null) {
            sendToMessage(message, "Выбранный клуб больше неактуален. Начните заново через /ask.")
            return true
        }

        when (
            supportService.createTicket(
                clubId = clubId,
                userId = user.id,
                bookingId = null,
                listEntryId = null,
                topic = TicketTopic.OTHER,
                text = question,
                attachments = null,
            )
        ) {
            is SupportServiceResult.Success -> sendToMessage(message, "Вопрос отправлен в клуб. Мы скоро ответим.")
            is SupportServiceResult.Failure -> sendToMessage(message, "Не удалось отправить вопрос. Попробуйте позже.")
        }
        return true
    }

    private suspend fun sendForceReply(message: Message, text: String) {
        val request = SendMessage(message.chat().id(), text).replyMarkup(ForceReply())
        applyThread(request, message.threadIdOrNull())
        send(request)
    }

    private suspend fun sendToMessage(message: Message, text: String) {
        val request = SendMessage(message.chat().id(), text)
        applyThread(request, message.threadIdOrNull())
        send(request)
    }

    private suspend fun resolveUser(message: Message): User? {
        val telegramUserId = message.from()?.id()
        if (telegramUserId == null) {
            sendToMessage(message, "Не удалось определить пользователя. Напишите /start и попробуйте снова.")
            return null
        }
        val user = userRepository.getByTelegramId(telegramUserId)
        if (user == null) {
            val miniappHint = botUsername?.let { " https://t.me/$it/app" }.orEmpty()
            sendToMessage(
                message,
                "Похоже, вы ещё не зарегистрированы. Напишите /start или откройте miniapp.$miniappHint",
            )
        }
        return user
    }

    private fun findNearestActiveBooking(userId: Long): Booking? {
        val now = bookingState.now()
        return bookingState
            .findUserBookings(userId)
            .asSequence()
            .filter { it.status == BookingStatus.BOOKED }
            .filter { !it.arrivalWindow.second.isBefore(now) }
            .sortedWith(compareBy<Booking> { it.arrivalWindow.first }.thenBy { it.id })
            .firstOrNull()
    }

    private fun isCommand(
        text: String,
        vararg commands: String,
    ): Boolean {
        if (text.isBlank()) return false
        val token = text.substringBefore(' ').trim()
        if (!token.startsWith('/')) return false
        val commandPart = token.removePrefix("/").substringBefore('@').lowercase()
        return commands.any { it.lowercase() == commandPart }
    }

    private fun isPrivateChat(chat: Chat): Boolean {
        val typeName = runCatching { chat.type().name }.getOrNull() ?: return false
        return typeName.equals("Private", ignoreCase = true)
    }

    private fun parseClubIdMarker(text: String): Long? {
        val marker = Regex("clubId:(\\d+)")
        val id = marker.find(text)?.groupValues?.getOrNull(1)
        return id?.toLongOrNull()
    }

    private fun applyThread(
        request: SendMessage,
        threadId: Int?,
    ) {
        if (threadId != null) {
            request.messageThreadId(threadId)
        }
    }
}

@Suppress("DEPRECATION")
private fun Message.threadIdOrNull(): Int? = runCatching { messageThreadId() }.getOrNull()
