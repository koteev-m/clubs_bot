package com.example.bot.telegram

import com.pengrad.telegrambot.model.MenuButtonWebApp
import com.pengrad.telegrambot.model.WebAppInfo
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SetChatMenuButton
import com.pengrad.telegrambot.response.BaseResponse
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

class TelegramCabinetMenuTest {
    @Test
    fun `default menu opens cabinet without requiring a prior private start`() =
        runBlocking<Unit> {
            val requests = mutableListOf<BaseRequest<*, *>>()
            val installed =
                installDefaultCabinetMenu("https://synthetic.example/app") { request ->
                    requests += request
                    response(true)
                }
            assertTrue(installed)
            val request = requests.single() as SetChatMenuButton
            assertFalse(request.parameters.containsKey("chat_id"))
            assertEquals(
                MenuButtonWebApp("Открыть кабинет", WebAppInfo("https://synthetic.example/app?mode=cabinet")),
                request.parameters["menu_button"],
            )
        }

    @Test
    fun `unsafe or credential carrying default URLs never call Telegram`() =
        runBlocking<Unit> {
            listOf(
                null,
                "",
                "http://synthetic.example/app",
                "https://user:password@synthetic.example/app",
                "https://synthetic.example/app?token=sensitive",
                "https://synthetic.example/app#initData",
                "javascript:alert(1)",
                "/app",
            ).forEach { url ->
                assertFalse(installDefaultCabinetMenu(url) { error("must not send") })
            }
        }

    @Test
    fun `failure does not prevent direct entry and cancellation remains cancellation`() =
        runBlocking<Unit> {
            assertFalse(installDefaultCabinetMenu("https://synthetic.example/app") { response(false) })
            assertFalse(installDefaultCabinetMenu("https://synthetic.example/app") { error("synthetic transport") })
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    installDefaultCabinetMenu("https://synthetic.example/app") { throw CancellationException() }
                }
            }
        }

    private fun response(ok: Boolean): BaseResponse = mockk<BaseResponse>().also { every { it.isOk } returns ok }
}
