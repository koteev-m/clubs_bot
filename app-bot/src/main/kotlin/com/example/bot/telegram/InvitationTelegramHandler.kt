package com.example.bot.telegram

import com.example.bot.club.InvitationCard
import com.example.bot.club.InvitationResponse
import com.example.bot.club.InvitationService
import com.example.bot.club.InvitationServiceError
import com.example.bot.club.InvitationServiceResult
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.EditMessageReplyMarkup
import com.pengrad.telegrambot.request.EditMessageText
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import io.micrometer.core.instrument.MeterRegistry
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException

private const val START_PREFIX = "inv_"
private const val CALLBACK_CONFIRM_PREFIX = "inv_confirm:"
private const val CALLBACK_DECLINE_PREFIX = "inv_decline:"
private const val CALLBACK_MAX_BYTES = 64

private suspend inline fun bestEffort(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        // ignore (best-effort)
    }
}

class InvitationTelegramHandler(
    private val send: suspend (BaseRequest<*, *>) -> BaseResponse,
    private val invitationService: InvitationService,
    private val meterRegistry: MeterRegistry,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(zoneId)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(zoneId)

    suspend fun handle(update: Update) {
        handleStartInvite(update)
        handleCallback(update)
    }

    private suspend fun handleStartInvite(update: Update) {
        val message = update.message() ?: return
        val text = message.text() ?: return
        val token = parseStartToken(text) ?: return
        val chatId = message.chat().id()
        val threadId = message.threadIdOrNull()

        when (val result = invitationService.resolveInvitation(token)) {
            is InvitationServiceResult.Success -> {
                val cardText = buildCardText(result.value)
                val keyboard = buildInviteKeyboard(token)
                val request = SendMessage(chatId, cardText)
                if (threadId != null) request.messageThreadId(threadId)
                if (keyboard != null) request.replyMarkup(keyboard)
                send(request)
            }

            is InvitationServiceResult.Failure -> {
                val errorText = errorText(result.error)
                val request = SendMessage(chatId, errorText)
                if (threadId != null) request.messageThreadId(threadId)
                send(request)
            }
        }
    }

    private suspend fun handleCallback(update: Update) {
        val callbackQuery = update.callbackQuery() ?: return
        val data = callbackQuery.data()
        val callback = data?.let { parseCallbackData(it) }
        if (callback == null) {
            send(
                AnswerCallbackQuery(callbackQuery.id())
                    .text("Кнопка устарела")
                    .showAlert(false),
            )
            bestEffort { clearInlineKeyboard(callbackQuery.message()) }
            return
        }
        val telegramUserId = callbackQuery.from()?.id() ?: return

        when (val result = invitationService.respondToInvitation(callback.token, telegramUserId, callback.response)) {
            is InvitationServiceResult.Success -> {
                when (callback.response) {
                    InvitationResponse.CONFIRM -> meterRegistry.counter("invitation.confirmed").increment()
                    InvitationResponse.DECLINE -> meterRegistry.counter("invitation.declined").increment()
                }
                val cardText = buildCardText(result.value)
                send(AnswerCallbackQuery(callbackQuery.id()).text("Готово"))
                bestEffort { editCallbackMessage(callbackQuery.message(), cardText) }
            }

            is InvitationServiceResult.Failure -> {
                val errorText = errorText(result.error)
                send(
                    AnswerCallbackQuery(callbackQuery.id())
                        .text(errorText)
                        .showAlert(true),
                )
                bestEffort { editCallbackMessage(callbackQuery.message(), errorText) }
            }
        }
    }

    private suspend fun editCallbackMessage(
        message: Message?,
        text: String,
    ) {
        if (message == null) return
        val chatId = message.chat().id()
        val messageId = message.messageId()
        val request =
            EditMessageText(chatId, messageId, text)
                .replyMarkup(InlineKeyboardMarkup())
        send(request)
    }

    private suspend fun clearInlineKeyboard(message: Message?) {
        if (message == null) return
        val chatId = message.chat().id()
        val messageId = message.messageId()
        val request =
            EditMessageReplyMarkup(chatId, messageId)
                .replyMarkup(InlineKeyboardMarkup())
        send(request)
    }

    private fun buildCardText(card: InvitationCard): String =
        buildString {
            appendLine("Приглашение")
            val clubName = card.clubName?.takeIf { it.isNotBlank() } ?: "Клуб #${card.clubId}"
            appendLine("Клуб: $clubName")
            if (card.arrivalWindowStart != null) {
                appendLine("Дата: ${dateFormatter.format(card.arrivalWindowStart)}")
            } else {
                appendLine("Ивент: #${card.eventId}")
            }
            if (card.arrivalWindowStart != null && card.arrivalWindowEnd != null) {
                val startTime = timeFormatter.format(card.arrivalWindowStart)
                val endTime = timeFormatter.format(card.arrivalWindowEnd)
                appendLine("Окно прибытия: $startTime–$endTime")
            }
            appendLine("Имя: ${card.displayName}")
            append("Статус: ${card.entryStatus.name}")
        }

    private fun buildInviteKeyboard(token: String): InlineKeyboardMarkup? {
        val confirm = "$CALLBACK_CONFIRM_PREFIX$token"
        val decline = "$CALLBACK_DECLINE_PREFIX$token"
        if (!fitsCallbackData(confirm) || !fitsCallbackData(decline)) {
            return null
        }
        return InlineKeyboardMarkup(
            arrayOf(InlineKeyboardButton("✅ Я приду").callbackData(confirm)),
            arrayOf(InlineKeyboardButton("❌ Не смогу").callbackData(decline)),
        )
    }

    private fun fitsCallbackData(data: String): Boolean = data.toByteArray(Charsets.UTF_8).size <= CALLBACK_MAX_BYTES

    private fun errorText(error: InvitationServiceError): String =
        when (error) {
            InvitationServiceError.INVITATION_INVALID -> "Приглашение недействительно."
            InvitationServiceError.INVITATION_EXPIRED -> "Срок действия приглашения истёк."
            InvitationServiceError.INVITATION_REVOKED -> "Приглашение было отозвано."
            InvitationServiceError.INVITATION_ALREADY_USED -> "Приглашение уже использовано на входе."
            InvitationServiceError.GUEST_LIST_ENTRY_NOT_FOUND -> "Приглашение недействительно."
            InvitationServiceError.GUEST_LIST_NOT_ACTIVE -> "Приглашение недействительно."
        }

    data class ParsedCallback(
        val token: String,
        val response: InvitationResponse,
    )

    companion object {
        fun parseStartToken(text: String): String? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("/start")) return null
            val parts = trimmed.split(Regex("\\s+"), limit = 3)
            val startParam = parts.getOrNull(1) ?: return null
            if (!startParam.startsWith(START_PREFIX)) return null
            val token = startParam.removePrefix(START_PREFIX)
            return token.takeIf { it.isNotBlank() }
        }

        fun parseCallbackData(data: String): ParsedCallback? =
            when {
                data.startsWith(CALLBACK_CONFIRM_PREFIX) -> {
                    val token = data.removePrefix(CALLBACK_CONFIRM_PREFIX)
                    token.takeIf { it.isNotBlank() }?.let { ParsedCallback(it, InvitationResponse.CONFIRM) }
                }

                data.startsWith(CALLBACK_DECLINE_PREFIX) -> {
                    val token = data.removePrefix(CALLBACK_DECLINE_PREFIX)
                    token.takeIf { it.isNotBlank() }?.let { ParsedCallback(it, InvitationResponse.DECLINE) }
                }

                else -> null
            }
    }
}

@Suppress("DEPRECATION")
private fun Message.threadIdOrNull(): Int? = runCatching { messageThreadId() }.getOrNull()
