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
        if (!SupportCallbacks.isRateCallback(data)) return

        val callbackId = callbackQuery.id()
        val message = callbackQuery.message()
        var answered = false
        var logResult: String? = null
        val parsed = SupportCallbacks.parseRate(data)
        try {
            val (text, result) =
                when {
                    parsed == null -> INVALID_TEXT to "invalid"
                    callbackQuery.from()?.id() == null -> ERROR_TEXT to "error"
                    else -> {
                        val telegramUserId = callbackQuery.from().id()
                        val user = userRepository.getByTelegramId(telegramUserId)
                        if (user == null) {
                            FORBIDDEN_TEXT to "forbidden"
                        } else {
                            when (val rateResult = supportService.setResolutionRating(parsed.ticketId, user.id, parsed.rating)) {
                                is SupportServiceResult.Success -> SUCCESS_TEXT to "success"
                                is SupportServiceResult.Failure ->
                                    when (rateResult.error) {
                                        SupportServiceError.RatingAlreadySet -> ALREADY_SET_TEXT to "already_set"
                                        else -> ERROR_TEXT to "error"
                                    }
                            }
                        }
                    }
                }
            answered = true
            answer(callbackId, text)
            logResult = result
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            if (!answered) {
                answered = true
                answer(callbackId, ERROR_TEXT)
                logResult = "error"
            }
        } finally {
            if (answered) {
                bestEffort { clearInlineKeyboard(message) }
            }
            if (logResult != null) {
                logger.info("support.rating ticket_id={} result={}", parsed?.ticketId, logResult)
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

    companion object {
        private const val SUCCESS_TEXT = "Спасибо! Оценка сохранена."
        private const val ALREADY_SET_TEXT = "Оценка уже сохранена."
        private const val ERROR_TEXT = "Не удалось сохранить оценку."
        private const val FORBIDDEN_TEXT = "Доступ запрещён"
        private const val INVALID_TEXT = "Не удалось сохранить оценку."
    }
}
