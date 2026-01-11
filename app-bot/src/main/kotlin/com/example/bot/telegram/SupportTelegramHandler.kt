package com.example.bot.telegram

import com.example.bot.data.security.UserRepository
import com.example.bot.support.SupportService
import com.example.bot.support.SupportServiceError
import com.example.bot.support.SupportServiceResult
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.EditMessageReplyMarkup
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.response.BaseResponse
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

class SupportTelegramHandler(
    private val send: suspend (BaseRequest<*, *>) -> BaseResponse,
    private val supportService: SupportService,
    private val userRepository: UserRepository,
) {
    private val logger = LoggerFactory.getLogger("SupportTelegramHandler")

    suspend fun handle(update: Update) {
        val callbackQuery = update.callbackQuery() ?: return
        val data = callbackQuery.data() ?: return
        if (!data.startsWith(CALLBACK_PREFIX)) return

        val parsed = parseCallbackData(data)
        if (parsed == null) {
            answer(callbackQuery.id(), ERROR_TEXT)
            bestEffort { clearInlineKeyboard(callbackQuery.message()) }
            return
        }
        val telegramUserId = callbackQuery.from()?.id() ?: return
        val user = userRepository.getByTelegramId(telegramUserId)
        if (user == null) {
            answer(callbackQuery.id(), FORBIDDEN_TEXT)
            return
        }

        var answered = false
        try {
            val result = supportService.setResolutionRating(parsed.ticketId, user.id, parsed.rating)
            val (text, logResult) =
                when (result) {
                    is SupportServiceResult.Success -> SUCCESS_TEXT to "success"
                    is SupportServiceResult.Failure ->
                        when (result.error) {
                            SupportServiceError.RatingAlreadySet -> ALREADY_SET_TEXT to "already_set"
                            else -> ERROR_TEXT to "error"
                        }
                }
            answer(callbackQuery.id(), text)
            answered = true
            logger.info("support.rating ticket_id={} result={}", parsed.ticketId, logResult)
            bestEffort { clearInlineKeyboard(callbackQuery.message()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            if (!answered) {
                answer(callbackQuery.id(), ERROR_TEXT)
            }
        }
    }

    private suspend fun answer(
        callbackId: String,
        text: String,
    ) {
        send(AnswerCallbackQuery(callbackId).text(text))
    }

    private suspend fun clearInlineKeyboard(message: Message?) {
        if (message == null) return
        val chatId = message.chat().id()
        val messageId = message.messageId()
        send(EditMessageReplyMarkup(chatId, messageId).replyMarkup(InlineKeyboardMarkup()))
    }

    private suspend inline fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // ignore (best-effort)
        }
    }

    data class ParsedCallback(
        val ticketId: Long,
        val rating: Int,
    )

    companion object {
        const val CALLBACK_PREFIX = "support_rate:"
        private const val SUCCESS_TEXT = "Спасибо! Оценка сохранена."
        private const val ALREADY_SET_TEXT = "Оценка уже сохранена."
        private const val ERROR_TEXT = "Не удалось сохранить оценку."
        private const val FORBIDDEN_TEXT = "Доступ запрещён"

        fun parseCallbackData(data: String): ParsedCallback? {
            if (!data.startsWith(CALLBACK_PREFIX)) return null
            val parts = data.removePrefix(CALLBACK_PREFIX).split(":", limit = 3)
            if (parts.size != 2) return null
            val ticketId = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val rating =
                when (parts[1]) {
                    "up" -> 1
                    "down" -> -1
                    else -> return null
                }
            return ParsedCallback(ticketId = ticketId, rating = rating)
        }
    }
}
