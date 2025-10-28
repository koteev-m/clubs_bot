package com.example.bot

import com.example.bot.polling.PollingRunner
import com.example.bot.telegram.TelegramClient
import com.pengrad.telegrambot.model.Update
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

class PollingRunnerTest :
    StringSpec({
        val client = mockk<TelegramClient>()
        val handled = mutableListOf<Long>()
        val runner = PollingRunner(client, handler = { upd: Update -> handled += upd.updateId().toLong() })

        "processes updates and advances offset" {
            val u1 = mockk<Update> { every { updateId() } returns 1 }
            val u2 = mockk<Update> { every { updateId() } returns 2 }
            val slot = slot<List<String>>()
            coEvery { client.getUpdates(0, capture(slot)) } returns listOf(u1, u2)
            runner.runOnce()
            handled shouldBe listOf(1, 2)
            slot.captured shouldBe
                listOf(
                    "message",
                    "edited_message",
                    "callback_query",
                    "contact",
                    "pre_checkout_query",
                    "successful_payment",
                )
            coEvery { client.getUpdates(3, any()) } returns emptyList()
            runner.runOnce()
            handled shouldBe listOf(1, 2)
        }
    })
