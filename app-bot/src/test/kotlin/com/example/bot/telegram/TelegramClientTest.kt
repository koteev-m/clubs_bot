package com.example.bot.telegram

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.request.GetMe
import com.pengrad.telegrambot.response.GetMeResponse
import com.pengrad.telegrambot.utility.BotUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class TelegramClientTest {
    @Test
    fun `successful getMe returns numeric bot id and sequential calls use cache`() =
        runBlocking {
            val bot = mockk<TelegramBot>()
            every { bot.execute(any<GetMe>()) } returns successfulGetMeResponse(BOT_ID)
            val client = TelegramClient(bot)

            assertEquals(BOT_ID, client.currentBotUserId())
            assertEquals(BOT_ID, client.currentBotUserId())
            assertEquals(BOT_ID, client.currentBotUserId())

            verify(exactly = 1) { bot.execute(any<GetMe>()) }
        }

    @Test
    fun `controlled concurrent calls converge on one getMe lookup`() =
        runBlocking {
            val bot = mockk<TelegramBot>()
            val lookupStarted = CountDownLatch(1)
            val releaseLookup = CountDownLatch(1)
            every { bot.execute(any<GetMe>()) } answers {
                lookupStarted.countDown()
                check(releaseLookup.await(5, TimeUnit.SECONDS)) { "getMe test lookup was not released" }
                successfulGetMeResponse(BOT_ID)
            }
            val client = TelegramClient(bot)

            val calls = List(8) { async(start = CoroutineStart.UNDISPATCHED) { client.currentBotUserId() } }
            assertTrue(lookupStarted.await(5, TimeUnit.SECONDS))
            releaseLookup.countDown()

            assertEquals(List(8) { BOT_ID }, calls.awaitAll())
            verify(exactly = 1) { bot.execute(any<GetMe>()) }
        }

    @Test
    fun `non success response is not cached and a later call retries`() =
        runBlocking {
            val bot = mockk<TelegramBot>()
            every { bot.execute(any<GetMe>()) } returnsMany
                listOf(
                    getMeResponse("""{"ok":false,"error_code":401,"description":"Unauthorized"}"""),
                    successfulGetMeResponse(BOT_ID),
                )
            val client = TelegramClient(bot)

            assertThrows(IllegalStateException::class.java) {
                runBlocking { client.currentBotUserId() }
            }
            assertEquals(BOT_ID, client.currentBotUserId())
            assertEquals(BOT_ID, client.currentBotUserId())

            verify(exactly = 2) { bot.execute(any<GetMe>()) }
        }

    @Test
    fun `missing and non positive getMe user ids fail closed`() {
        val invalidResponses =
            listOf(
                "missing result" to """{"ok":true}""",
                "missing id" to """{"ok":true,"result":{"is_bot":true,"first_name":"Bot"}}""",
                "zero id" to """{"ok":true,"result":{"id":0,"is_bot":true,"first_name":"Bot"}}""",
                "negative id" to """{"ok":true,"result":{"id":-1,"is_bot":true,"first_name":"Bot"}}""",
            )

        invalidResponses.forEach { (caseName, json) ->
            val bot = mockk<TelegramBot>()
            every { bot.execute(any<GetMe>()) } returns getMeResponse(json)
            val client = TelegramClient(bot)

            assertThrows(IllegalStateException::class.java, { runBlocking { client.currentBotUserId() } }, caseName)
            verify(exactly = 1) { bot.execute(any<GetMe>()) }
        }
    }

    @Test
    fun `getMe cancellation is rethrown and can be retried`() =
        runBlocking {
            val bot = mockk<TelegramBot>()
            val cancellation = CancellationException("cancel getMe")
            every { bot.execute(any<GetMe>()) } throws cancellation andThen successfulGetMeResponse(BOT_ID)
            val client = TelegramClient(bot)

            val thrown =
                assertThrows(CancellationException::class.java) {
                    runBlocking { client.currentBotUserId() }
                }
            assertEquals(cancellation.message, thrown.message)
            assertEquals(BOT_ID, client.currentBotUserId())

            verify(exactly = 2) { bot.execute(any<GetMe>()) }
        }

    private fun successfulGetMeResponse(botId: Long): GetMeResponse =
        getMeResponse(
            """{"ok":true,"result":{"id":$botId,"is_bot":true,"first_name":"Current Bot"}}""",
        )

    private fun getMeResponse(json: String): GetMeResponse = BotUtils.fromJson(json, GetMeResponse::class.java)

    private companion object {
        const val BOT_ID = 7_770_001L
    }
}
