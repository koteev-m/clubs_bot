package com.example.bot.telegram

import com.example.bot.booking.a3.Booking
import com.example.bot.booking.a3.BookingState
import com.example.bot.booking.a3.BookingStatus
import com.example.bot.booking.a3.QrBookingCodec
import com.example.bot.clubs.ClubsRepository
import com.example.bot.data.security.User
import com.example.bot.data.security.UserIdentityProvisioner
import com.example.bot.data.security.UserRepository
import com.example.bot.logging.errorSqlSafe
import com.example.bot.support.SupportService
import com.example.bot.support.SupportServiceResult
import com.example.bot.support.TicketTopic
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Chat
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.WebAppInfo
import com.pengrad.telegrambot.model.request.ForceReply
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import org.slf4j.LoggerFactory
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException

class TelegramGuestFallbackHandler(
    private val send: suspend (BaseRequest<*, *>) -> BaseResponse,
    private val bookingState: BookingState,
    private val clubsRepository: ClubsRepository,
    private val userRepository: UserRepository,
    private val userIdentityProvisioner: UserIdentityProvisioner,
    private val supportService: SupportService,
    private val currentBotUserIdProvider: suspend () -> Long,
    private val botUsername: String?,
    private val miniAppUrl: String?,
    private val qrSecretProvider: () -> String,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(zoneId)
    private val logger = LoggerFactory.getLogger("TelegramGuestFallbackHandler")

    suspend fun handle(update: Update): Boolean {
        if (handleAskCallback(update.callbackQuery())) {
            return true
        }
        val message = update.message() ?: return false
        val text = message.text()?.trim().orEmpty()
        val privateChat = isPrivateChat(message.chat())

        if (privateChat && isBareStart(text)) {
            handleBareStart(message)
            return true
        }

        if (text.equals("/cancel", ignoreCase = true) || text.startsWith("/cancel@", ignoreCase = true)) {
            if (privateChat) {
                sendToMessage(message, "Ок, отменено. Когда будете готовы, используйте /ask.")
                return true
            }
            return false
        }

        if (message.replyToMessage() != null) {
            return handleAskReply(message)
        }

        if (!privateChat) return false

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

    private suspend fun handleBareStart(message: Message) {
        val telegramUserId = message.from()?.id()
        if (telegramUserId == null) {
            sendToMessage(message, IDENTITY_PROVISIONING_FAILURE_TEXT)
            return
        }
        try {
            userIdentityProvisioner.ensureMinimalIdentity(telegramUserId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.errorSqlSafe(
                "telegram identity provisioning failed category={}",
                "persistence",
                error,
            )
            sendToMessage(message, IDENTITY_PROVISIONING_FAILURE_TEXT)
            return
        }

        val request = SendMessage(message.chat().id(), WELCOME_TEXT)
        miniAppUrl?.let { configuredMiniAppUrl ->
            val keyboard =
                InlineKeyboardMarkup(
                    arrayOf(
                        InlineKeyboardButton(OPEN_MINI_APP_BUTTON_TEXT)
                            .webApp(WebAppInfo(configuredMiniAppUrl)),
                    ),
                )
            request.replyMarkup(keyboard)
        }
        applyThread(request, message.threadIdOrNull())
        send(request)
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
                appendLine(
                    "Окно прибытия: ${dateFormatter.format(
                        booking.arrivalWindow.first,
                    )} — ${dateFormatter.format(booking.arrivalWindow.second)}",
                )
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
        resolveUser(message) ?: return

        val clubs =
            clubsRepository
                .list(city = null, query = null, tag = null, genre = null, offset = 0, limit = 8)
                .filter { it.id > 0L }
        if (clubs.isEmpty()) {
            sendToMessage(message, "Сейчас недоступен список клубов. Попробуйте позже.")
            return
        }
        val rows =
            clubs
                .map { club ->
                    arrayOf(InlineKeyboardButton(club.name).callbackData(clubCallbackData(club.id)))
                }.toTypedArray()
        val request =
            SendMessage(message.chat().id(), "Выберите клуб для вопроса:")
                .replyMarkup(InlineKeyboardMarkup(*rows))
        applyThread(request, message.threadIdOrNull())
        send(request)
    }

    private suspend fun handleAskCallback(callbackQuery: CallbackQuery?): Boolean {
        if (callbackQuery == null) return false
        val data = callbackQuery.data() ?: return false
        if (!data.startsWith("ask:club:")) return false
        val message = callbackQuery.message()
        val chat = message?.chat()
        if (chat == null) {
            send(
                AnswerCallbackQuery(callbackQuery.id())
                    .text("Не удалось открыть форму вопроса. Откройте чат с ботом и попробуйте снова."),
            )
            return true
        }
        if (!isPrivateChat(chat)) {
            send(AnswerCallbackQuery(callbackQuery.id()).text("Команда доступна только в личке с ботом."))
            return true
        }
        val selection = parseAskCallback(data)
        if (selection == null) {
            send(AnswerCallbackQuery(callbackQuery.id()).text("Некорректный выбор."))
            return true
        }
        val club = clubsRepository.getById(selection.clubId)
        if (club == null) {
            send(AnswerCallbackQuery(callbackQuery.id()).text("Клуб не найден."))
            return true
        }

        when (selection) {
            is AskCallbackSelection.Club -> {
                send(AnswerCallbackQuery(callbackQuery.id()).text("Клуб выбран."))
                val rows =
                    askCategories
                        .map { category ->
                            arrayOf(
                                InlineKeyboardButton(category.label)
                                    .callbackData(categoryCallbackData(selection.clubId, category.topic)),
                            )
                        }.toTypedArray()
                val request =
                    SendMessage(message.chat().id(), "Выберите категорию вопроса:")
                        .replyMarkup(InlineKeyboardMarkup(*rows))
                applyThread(request, message.threadIdOrNull())
                send(request)
            }
            is AskCallbackSelection.Category -> {
                val category = askCategories.firstOrNull { it.topic == selection.topic }
                if (category == null) {
                    send(AnswerCallbackQuery(callbackQuery.id()).text("Некорректная категория."))
                    return true
                }

                send(AnswerCallbackQuery(callbackQuery.id()).text("Категория выбрана."))
                val request =
                    SendMessage(
                        message.chat().id(),
                        formatAskQuestionPrompt(
                            clubName = club.name,
                            clubId = selection.clubId,
                            category = category,
                        ),
                    ).replyMarkup(ForceReply())
                applyThread(request, message.threadIdOrNull())
                send(request)
            }
        }
        return true
    }

    private suspend fun handleAskReply(message: Message): Boolean {
        if (!isPrivateChat(message.chat())) return false
        val replyToMessage = message.replyToMessage() ?: return false
        val markerText = replyToMessage.text().orEmpty()
        if (!isPotentialAskPrompt(markerText)) return false
        val promptAuthor = replyToMessage.from() ?: return false
        if (promptAuthor.isBot() != true || replyToMessage.hasDisallowedAskPromptProvenance()) {
            return false
        }
        val currentBotUserId =
            try {
                currentBotUserIdProvider().takeIf { it > 0L }
                    ?: throw IllegalStateException("Telegram bot identity is unavailable")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                sendToMessage(message, BOT_IDENTITY_FAILURE_TEXT)
                return true
            }
        if (promptAuthor.id() != currentBotUserId) return false

        val context = parseAskReplyContext(markerText)
        if (context == null) {
            sendToMessage(message, "Не удалось определить параметры вопроса. Начните заново через /ask.")
            return true
        }
        val user = resolveUser(message) ?: return true
        val question = message.text()?.trim().orEmpty()
        if (question.isBlank()) {
            sendToMessage(message, "Напишите текст вопроса одним сообщением.")
            return true
        }
        val club = clubsRepository.getById(context.clubId)
        if (club == null) {
            sendToMessage(message, "Выбранный клуб больше неактуален. Начните заново через /ask.")
            return true
        }
        val category = askCategories.firstOrNull { it.topic == context.topic }
        if (
            category == null ||
            markerText !=
            formatAskQuestionPrompt(
                clubName = club.name,
                clubId = club.id,
                category = category,
            )
        ) {
            sendToMessage(message, "Не удалось определить параметры вопроса. Начните заново через /ask.")
            return true
        }

        when (
            supportService.createTicket(
                clubId = context.clubId,
                userId = user.id,
                bookingId = null,
                listEntryId = null,
                topic = context.topic,
                text = question,
                attachments = null,
            )
        ) {
            is SupportServiceResult.Success -> sendToMessage(message, "Вопрос отправлен в клуб. Мы скоро ответим.")
            is SupportServiceResult.Failure -> sendToMessage(message, "Не удалось отправить вопрос. Попробуйте позже.")
        }
        return true
    }

    private suspend fun sendToMessage(
        message: Message,
        text: String,
    ) {
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
            val miniAppHint = miniAppUrl?.let { " Откройте Mini App: $it" }.orEmpty()
            sendToMessage(
                message,
                "Похоже, вы ещё не зарегистрированы. Напишите /start.$miniAppHint",
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

    private fun isBareStart(text: String): Boolean =
        text.equals("/start", ignoreCase = true) || isConfiguredBotStart(text)

    private fun isConfiguredBotStart(text: String): Boolean =
        botUsername
            ?.trim()
            ?.removePrefix("@")
            ?.takeIf { it.isNotBlank() }
            ?.let { text.equals("/start@$it", ignoreCase = true) }
            ?: false

    private fun parseAskCallback(data: String): AskCallbackSelection? {
        val fields = data.split(':')
        return when (fields.size) {
            ASK_CLUB_CALLBACK_FIELD_COUNT -> {
                val fieldsIterator = fields.iterator()
                val namespace = fieldsIterator.next()
                val entity = fieldsIterator.next()
                val clubIdText = fieldsIterator.next()
                clubIdText
                    .takeIf { namespace == "ask" && entity == "club" }
                    ?.let(::parseCanonicalPositiveLong)
                    ?.let(AskCallbackSelection::Club)
            }
            ASK_CATEGORY_CALLBACK_FIELD_COUNT -> {
                val fieldsIterator = fields.iterator()
                val namespace = fieldsIterator.next()
                val entity = fieldsIterator.next()
                val clubIdText = fieldsIterator.next()
                val attribute = fieldsIterator.next()
                val topicWire = fieldsIterator.next()
                clubIdText
                    .takeIf { namespace == "ask" && entity == "club" && attribute == "topic" }
                    ?.let(::parseCanonicalPositiveLong)
                    ?.let { clubId ->
                        TicketTopic.fromWire(topicWire)?.let { topic ->
                            AskCallbackSelection.Category(clubId, topic)
                        }
                    }
            }
            else -> null
        }
    }

    private fun parseAskReplyContext(text: String): AskReplyContext? {
        val fields = text.substringAfterLast('\n').split(':')
        if (fields.size != ASK_CONTEXT_FIELD_COUNT) return null
        val fieldsIterator = fields.iterator()
        val marker = fieldsIterator.next()
        val version = fieldsIterator.next()
        val clubIdText = fieldsIterator.next()
        val topicWire = fieldsIterator.next()
        return clubIdText
            .takeIf { marker == "askContext" && version == ASK_CONTEXT_VERSION }
            ?.let(::parseCanonicalPositiveLong)
            ?.let { clubId ->
                TicketTopic.fromWire(topicWire)?.let { topic ->
                    AskReplyContext(clubId, topic)
                }
            }
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

private sealed interface AskCallbackSelection {
    val clubId: Long

    data class Club(
        override val clubId: Long,
    ) : AskCallbackSelection

    data class Category(
        override val clubId: Long,
        val topic: TicketTopic,
    ) : AskCallbackSelection
}

private data class AskCategory(
    val label: String,
    val topic: TicketTopic,
)

private data class AskReplyContext(
    val clubId: Long,
    val topic: TicketTopic,
)

private val askCategories =
    listOf(
        AskCategory("Адрес / как добраться", TicketTopic.ADDRESS),
        AskCategory("Правила / дресс-код", TicketTopic.DRESSCODE),
        AskCategory("Списки / вход", TicketTopic.INVITE),
        AskCategory("Брони / депозит", TicketTopic.BOOKING),
        AskCategory("Потерял вещь", TicketTopic.LOST_FOUND),
        AskCategory("Жалоба / сервис", TicketTopic.COMPLAINT),
        AskCategory("Другое", TicketTopic.OTHER),
    )

private fun categoryCallbackData(
    clubId: Long,
    topic: TicketTopic,
): String {
    require(clubId > 0L)
    return "$ASK_CLUB_CALLBACK_PREFIX$clubId$ASK_CATEGORY_CALLBACK_SUFFIX${topic.wire}"
}

private fun clubCallbackData(clubId: Long): String {
    require(clubId > 0L)
    return "$ASK_CLUB_CALLBACK_PREFIX$clubId"
}

private fun formatAskQuestionPrompt(
    clubName: String,
    clubId: Long,
    category: AskCategory,
): String {
    require(clubId > 0L)
    return "Клуб: $clubName\n" +
        "Категория: ${category.label}\n" +
        "$ASK_REPLY_INSTRUCTION\n" +
        "askContext:$ASK_CONTEXT_VERSION:$clubId:${category.topic.wire}"
}

private fun parseCanonicalPositiveLong(value: String): Long? =
    value
        .takeIf { canonicalPositiveLongPattern.matches(it) }
        ?.toLongOrNull()
        ?.takeIf { it > 0L }

private fun isPotentialAskPrompt(text: String): Boolean =
    text.startsWith(ASK_PROMPT_CLUB_PREFIX) ||
        text.substringAfterLast('\n').startsWith(ASK_CONTEXT_PREFIX)

private fun Message.hasDisallowedAskPromptProvenance(): Boolean =
    forwardOrigin() != null ||
        senderBusinessBot() != null ||
        businessConnectionId() != null ||
        viaBot() != null ||
        senderChat() != null ||
        isAutomaticForward() == true ||
        isFromOffline() == true ||
        directMessagesTopic() != null ||
        externalReply() != null

private const val WELCOME_TEXT = "Добро пожаловать в Night Concierge!"
private const val OPEN_MINI_APP_BUTTON_TEXT = "Открыть Night Concierge"
private const val IDENTITY_PROVISIONING_FAILURE_TEXT = "Не удалось начать работу. Попробуйте позже."
private const val BOT_IDENTITY_FAILURE_TEXT = "Не удалось подтвердить сообщение бота. Начните заново через /ask."
private const val ASK_CLUB_CALLBACK_PREFIX = "ask:club:"
private const val ASK_CATEGORY_CALLBACK_SUFFIX = ":topic:"
private const val ASK_CLUB_CALLBACK_FIELD_COUNT = 3
private const val ASK_CATEGORY_CALLBACK_FIELD_COUNT = 5
private const val ASK_REPLY_INSTRUCTION = "Ответьте на это сообщение текстом вопроса."
private const val ASK_CONTEXT_VERSION = "v1"
private const val ASK_CONTEXT_PREFIX = "askContext:$ASK_CONTEXT_VERSION:"
private const val ASK_CONTEXT_FIELD_COUNT = 4
private const val ASK_PROMPT_CLUB_PREFIX = "Клуб: "
private val canonicalPositiveLongPattern = Regex("[1-9][0-9]*")

@Suppress("DEPRECATION")
private fun Message.threadIdOrNull(): Int? = runCatching { messageThreadId() }.getOrNull()
