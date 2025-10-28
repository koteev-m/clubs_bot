package com.example.bot.telegram.ott

import com.example.bot.telegram.MenuCallbacksHandler
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Update
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.jupiter.api.Test

class CallbackQueryHandlerTest {
    private val bot: TelegramBot = mockk(relaxed = true)
    private val tokenService: CallbackTokenService = mockk(relaxed = true)
    private val menuCallbacksHandler: MenuCallbacksHandler = mockk(relaxed = true)

    private val handler = CallbackQueryHandler(bot, tokenService, menuCallbacksHandler)

    @Test
    fun `routes night table and guests callbacks to menu handler`() {
        val nightUpdate = updateWithData("night:42")
        val tableUpdate = updateWithData("tbl:42:11")
        val guestsUpdate = updateWithData("g:42:11:4")

        handler.handle(nightUpdate)
        handler.handle(tableUpdate)
        handler.handle(guestsUpdate)

        verifySequence {
            menuCallbacksHandler.handle(nightUpdate)
            menuCallbacksHandler.handle(tableUpdate)
            menuCallbacksHandler.handle(guestsUpdate)
        }
        verify(exactly = 0) { tokenService.consume(any()) }
    }

    private fun updateWithData(data: String): Update {
        val callbackQuery = mockk<CallbackQuery>()
        every { callbackQuery.data() } returns data
        return mockk {
            every { callbackQuery() } returns callbackQuery
        }
    }
}
