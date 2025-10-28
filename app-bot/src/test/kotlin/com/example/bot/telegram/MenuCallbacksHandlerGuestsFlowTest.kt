@file:Suppress("DEPRECATION")

package com.example.bot.telegram

import com.example.bot.availability.AvailabilityService
import com.example.bot.availability.NightDto
import com.example.bot.availability.TableAvailabilityDto
import com.example.bot.availability.TableStatus
import com.example.bot.booking.BookingCmdResult
import com.example.bot.booking.BookingService
import com.example.bot.booking.HoldRequest
import com.example.bot.data.repo.ClubDto
import com.example.bot.data.repo.ClubRepository
import com.example.bot.telegram.bookings.MyBookingsMetrics
import com.example.bot.telegram.bookings.MyBookingsService
import com.example.bot.telegram.tokens.GuestsSelectCodec
import com.example.bot.telegram.ui.ChatUiSessionStore
import com.example.bot.text.BotTexts
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Chat
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.User
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import com.pengrad.telegrambot.response.SendResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MenuCallbacksHandlerGuestsFlowTest {
    private val texts = BotTexts()
    private val keyboards = Keyboards(texts)

    @Test
    fun `guest callback completes booking with friendly receipt`() =
        runTest {
            val bot = mockk<TelegramBot>()
            val clubRepository = mockk<ClubRepository>()
            val availability = mockk<AvailabilityService>()
            val bookingService = mockk<BookingService>()
            val chatUiSession = mockk<ChatUiSessionStore>(relaxed = true)
            val myBookingsService = mockk<MyBookingsService>(relaxed = true)
            val myBookingsMetrics = mockk<MyBookingsMetrics>(relaxed = true)

            val chatId = 1001L
            val fromId = 501L
            val clubId = 42L
            val tableId = 7L
            val guests = 3
            val start = Instant.parse("2025-05-01T20:00:00Z")
            val end = start.plusSeconds(14_400)
            val holdId = UUID.randomUUID()
            val bookingId = UUID.randomUUID()
            val night =
                NightDto(
                    eventStartUtc = start,
                    eventEndUtc = end.plusSeconds(7_200),
                    isSpecial = false,
                    arrivalByUtc = start.minusSeconds(1_800),
                    openLocal = LocalDateTime.of(2025, 5, 1, 23, 0),
                    closeLocal = LocalDateTime.of(2025, 5, 2, 6, 0),
                    timezone = "Europe/Moscow",
                )
            val table =
                TableAvailabilityDto(
                    tableId = tableId,
                    tableNumber = "15",
                    zone = "Main",
                    capacity = 6,
                    minDeposit = 150,
                    status = TableStatus.FREE,
                )
            val token = GuestsSelectCodec.encode(clubId, start, end, tableId, guests)
            val update = buildCallbackUpdate(token, chatId, lang = "ru", fromId = fromId)

            val sendMessages = mutableListOf<SendMessage>()
            every { bot.execute(any<BaseRequest<*, *>>()) } answers {
                when (val request = firstArg<BaseRequest<*, *>>()) {
                    is SendMessage -> {
                        sendMessages += request
                        mockk<SendResponse>(relaxed = true)
                    }

                    else -> mockk<BaseResponse>(relaxed = true)
                }
            }
            coEvery { availability.listFreeTables(clubId, start) } returns listOf(table)
            coEvery { availability.listOpenNights(clubId, 8) } returns listOf(night)
            coEvery { clubRepository.listClubs(32) } returns listOf(ClubDto(clubId, "Orion", null))
            val holdRequest = slot<HoldRequest>()
            val holdKey = slot<String>()
            val confirmKey = slot<String>()
            coEvery {
                bookingService.hold(capture(holdRequest), capture(holdKey))
            } returns BookingCmdResult.HoldCreated(holdId)
            coEvery {
                bookingService.confirm(holdId, capture(confirmKey))
            } returns BookingCmdResult.Booked(bookingId)
            coEvery { bookingService.finalize(bookingId, fromId) } returns BookingCmdResult.Booked(bookingId)

            val handler =
                MenuCallbacksHandler(
                    bot = bot,
                    keyboards = keyboards,
                    texts = texts,
                    clubRepository = clubRepository,
                    availability = availability,
                    bookingService = bookingService,
                    chatUiSession = chatUiSession,
                    uiScope = this,
                    myBookingsService = myBookingsService,
                    myBookingsMetrics = myBookingsMetrics,
                )

            handler.handle(update)
            advanceUntilIdle()
            this.coroutineContext[Job]?.children?.forEach { it.join() }

            val expectedIdem =
                "uiflow:$chatId:$clubId:$tableId:${start.epochSecond}:$guests"
            assertTrue(holdKey.isCaptured, "hold should be invoked")
            val capturedHold = holdRequest.captured
            assertEquals(clubId, capturedHold.clubId)
            assertEquals(tableId, capturedHold.tableId)
            assertEquals(start, capturedHold.slotStart)
            assertEquals(end, capturedHold.slotEnd)
            assertEquals(guests, capturedHold.guestsCount)
            assertEquals("$expectedIdem:hold", holdKey.captured)
            assertTrue(confirmKey.isCaptured, "confirm should be invoked")
            assertEquals("$expectedIdem:confirm", confirmKey.captured)
            coVerify(exactly = 1) { bookingService.finalize(bookingId, fromId) }

            assertTrue(sendMessages.isNotEmpty(), "expected receipt message to be sent")
            val receiptMessage = sendMessages.last()
            val receiptText = receiptMessage.getParameters()["text"] as String
            assertTrue(receiptText.contains(texts.receiptDepositFrom("ru")))
            assertTrue(receiptText.contains("₽"))
            assertTrue(receiptText.contains("#${table.tableNumber}"))
            val recipient = receiptMessage.getParameters()["chat_id"] as Long
            assertEquals(chatId, recipient)
        }

    @Test
    fun `guest callback is idempotent when confirm already booked`() =
        runTest {
            val bot = mockk<TelegramBot>()
            val clubRepository = mockk<ClubRepository>()
            val availability = mockk<AvailabilityService>()
            val bookingService = mockk<BookingService>()
            val chatUiSession = mockk<ChatUiSessionStore>(relaxed = true)
            val myBookingsService = mockk<MyBookingsService>(relaxed = true)
            val myBookingsMetrics = mockk<MyBookingsMetrics>(relaxed = true)

            val chatId = 777L
            val fromId = 900L
            val clubId = 5L
            val tableId = 11L
            val guests = 4
            val start = Instant.parse("2025-06-01T18:00:00Z")
            val end = start.plusSeconds(14_400)
            val holdId = UUID.randomUUID()
            val bookingId = UUID.randomUUID()
            val table =
                TableAvailabilityDto(
                    tableId = tableId,
                    tableNumber = "8",
                    zone = "VIP",
                    capacity = 6,
                    minDeposit = 200,
                    status = TableStatus.FREE,
                )
            val token = GuestsSelectCodec.encode(clubId, start, end, tableId, guests)
            val update = buildCallbackUpdate(token, chatId, lang = "en", fromId = fromId)

            val sendMessages = mutableListOf<SendMessage>()
            every { bot.execute(any<BaseRequest<*, *>>()) } answers {
                when (val request = firstArg<BaseRequest<*, *>>()) {
                    is SendMessage -> {
                        sendMessages += request
                        mockk<SendResponse>(relaxed = true)
                    }

                    else -> mockk<BaseResponse>(relaxed = true)
                }
            }
            coEvery { availability.listFreeTables(clubId, start) } returns listOf(table)
            coEvery { availability.listOpenNights(clubId, any()) } returns emptyList()
            coEvery { clubRepository.listClubs(any()) } returns emptyList()
            val confirmKey = slot<String>()
            coEvery {
                bookingService.hold(any(), any())
            } returns BookingCmdResult.HoldCreated(holdId)
            coEvery {
                bookingService.confirm(holdId, capture(confirmKey))
            } returns BookingCmdResult.AlreadyBooked(bookingId)
            coEvery { bookingService.finalize(bookingId, fromId) } returns BookingCmdResult.Booked(bookingId)

            val handler =
                MenuCallbacksHandler(
                    bot = bot,
                    keyboards = keyboards,
                    texts = texts,
                    clubRepository = clubRepository,
                    availability = availability,
                    bookingService = bookingService,
                    chatUiSession = chatUiSession,
                    uiScope = this,
                    myBookingsService = myBookingsService,
                    myBookingsMetrics = myBookingsMetrics,
                )

            handler.handle(update)
            advanceUntilIdle()
            this.coroutineContext[Job]?.children?.forEach { it.join() }

            val expectedIdem =
                "uiflow:$chatId:$clubId:$tableId:${start.epochSecond}:$guests"
            assertTrue(confirmKey.isCaptured, "confirm should be invoked")
            assertEquals("$expectedIdem:confirm", confirmKey.captured)
            coVerify(exactly = 1) { bookingService.finalize(bookingId, fromId) }
            assertTrue(sendMessages.isNotEmpty(), "expected idempotent receipt message to be sent")
            val receiptText = sendMessages.last().getParameters()["text"] as String
            assertTrue(receiptText.contains(texts.bookingConfirmedTitle("en")))
            assertFalse(receiptText.contains("₽"))
        }

    @Test
    fun `guest callback surfaces booking not found error`() =
        runTest {
            val bot = mockk<TelegramBot>()
            val clubRepository = mockk<ClubRepository>()
            val availability = mockk<AvailabilityService>()
            val bookingService = mockk<BookingService>()
            val chatUiSession = mockk<ChatUiSessionStore>(relaxed = true)
            val myBookingsService = mockk<MyBookingsService>(relaxed = true)
            val myBookingsMetrics = mockk<MyBookingsMetrics>(relaxed = true)

            val chatId = 500L
            val clubId = 12L
            val tableId = 21L
            val guests = 2
            val start = Instant.parse("2025-07-01T18:00:00Z")
            val end = start.plusSeconds(14_400)
            val holdId = UUID.randomUUID()
            val table =
                TableAvailabilityDto(
                    tableId = tableId,
                    tableNumber = "3",
                    zone = "Hall",
                    capacity = 4,
                    minDeposit = 120,
                    status = TableStatus.FREE,
                )
            val token = GuestsSelectCodec.encode(clubId, start, end, tableId, guests)
            val update = buildCallbackUpdate(token, chatId, lang = "ru", fromId = 333L)

            val sendMessages = mutableListOf<SendMessage>()
            every { bot.execute(any<BaseRequest<*, *>>()) } answers {
                when (val request = firstArg<BaseRequest<*, *>>()) {
                    is SendMessage -> {
                        sendMessages += request
                        mockk<SendResponse>(relaxed = true)
                    }

                    else -> mockk<BaseResponse>(relaxed = true)
                }
            }
            coEvery { availability.listFreeTables(clubId, start) } returns listOf(table)
            coEvery { availability.listOpenNights(clubId, any()) } returns emptyList()
            coEvery { clubRepository.listClubs(any()) } returns emptyList()
            coEvery { bookingService.hold(any(), any()) } returns BookingCmdResult.HoldCreated(holdId)
            coEvery { bookingService.confirm(holdId, any()) } returns BookingCmdResult.NotFound

            val handler =
                MenuCallbacksHandler(
                    bot = bot,
                    keyboards = keyboards,
                    texts = texts,
                    clubRepository = clubRepository,
                    availability = availability,
                    bookingService = bookingService,
                    chatUiSession = chatUiSession,
                    uiScope = this,
                    myBookingsService = myBookingsService,
                    myBookingsMetrics = myBookingsMetrics,
                )

            handler.handle(update)
            advanceUntilIdle()
            this.coroutineContext[Job]?.children?.forEach { it.join() }

            assertTrue(sendMessages.isNotEmpty(), "expected bookingNotFound message")
            val messageText = sendMessages.single().getParameters()["text"] as String
            assertEquals(texts.bookingNotFound("ru"), messageText)
            coVerify(exactly = 0) { bookingService.finalize(any(), any()) }
        }

    private fun buildCallbackUpdate(
        data: String,
        chatId: Long,
        lang: String,
        fromId: Long,
    ): Update {
        val chat =
            mockk<Chat> {
                every { id() } returns chatId
            }
        val message =
            mockk<Message> {
                every { chat() } returns chat
                every { messageThreadId() } returns null
            }
        val from =
            mockk<User> {
                every { id() } returns fromId
                every { languageCode() } returns lang
            }
        val callbackQuery =
            mockk<CallbackQuery> {
                every { id() } returns "cb-$chatId"
                every { data() } returns data
                every { message() } returns message
                every { from() } returns from
            }
        return mockk {
            every { callbackQuery() } returns callbackQuery
        }
    }
}
