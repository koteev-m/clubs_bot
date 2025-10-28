@file:Suppress("DEPRECATION")

package com.example.bot.telegram

import com.example.bot.availability.AvailabilityService
import com.example.bot.availability.NightDto
import com.example.bot.availability.TableAvailabilityDto
import com.example.bot.availability.TableStatus
import com.example.bot.booking.BookingCmdResult
import com.example.bot.booking.BookingService
import com.example.bot.data.repo.ClubDto
import com.example.bot.data.repo.ClubRepository
import com.example.bot.telegram.bookings.MyBookingsMetrics
import com.example.bot.telegram.bookings.MyBookingsService
import com.example.bot.telegram.tokens.ClubTokenCodec
import com.example.bot.telegram.tokens.GuestsSelectCodec
import com.example.bot.telegram.tokens.NightTokenCodec
import com.example.bot.telegram.tokens.TableSelectCodec
import com.example.bot.telegram.ui.ChatUiSessionStore
import com.example.bot.telegram.ui.InMemoryChatUiSessionStore
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MenuCallbacksHandlerSmokeTest {
    private val texts = BotTexts()
    private val keyboards = Keyboards(texts)

    @Test
    fun `menu callbacks flow completes booking end-to-end`() =
        runTest {
            val bot = mockk<TelegramBot>()
            val clubRepository = mockk<ClubRepository>()
            val availability = mockk<AvailabilityService>()
            val bookingService = mockk<BookingService>()
            val chatUiSession = InMemoryChatUiSessionStore()
            val myBookingsService = mockk<MyBookingsService>(relaxed = true)
            val myBookingsMetrics = mockk<MyBookingsMetrics>(relaxed = true)

            val chatId = 2024L
            val fromId = 77L
            val clubId = 42L
            val tableId = 5L
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
                    tableNumber = "8",
                    zone = "Main",
                    capacity = 6,
                    minDeposit = 150,
                    status = TableStatus.FREE,
                )
            val clubs = listOf(ClubDto(clubId, "Nebula", "Rooftop"))
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
            coEvery { clubRepository.listClubs(8) } returns clubs
            coEvery { clubRepository.listClubs(32) } returns clubs
            coEvery { availability.listOpenNights(clubId, 8) } returns listOf(night)
            coEvery { availability.listFreeTables(clubId, start) } returns listOf(table)
            coEvery { bookingService.hold(any(), any()) } returns BookingCmdResult.HoldCreated(holdId)
            coEvery { bookingService.confirm(holdId, any()) } returns BookingCmdResult.Booked(bookingId)
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

            handler.handle(buildCallbackUpdate("menu:clubs", chatId, lang = "en", fromId = fromId))
            awaitUi()

            val clubToken = ClubTokenCodec.encode(clubId)
            handler.handle(buildCallbackUpdate("club:$clubToken", chatId, lang = "en", fromId = fromId))
            awaitUi()

            val nightToken = NightTokenCodec.encode(clubId, start)
            handler.handle(buildCallbackUpdate("night:$nightToken", chatId, lang = "en", fromId = fromId))
            awaitUi()

            val tableToken = TableSelectCodec.encode(clubId, start, end, tableId)
            handler.handle(buildCallbackUpdate(tableToken, chatId, lang = "en", fromId = fromId))
            awaitUi()

            val guestToken = GuestsSelectCodec.encode(clubId, start, end, tableId, guests)
            handler.handle(buildCallbackUpdate(guestToken, chatId, lang = "en", fromId = fromId))
            awaitUi()

            assertEquals(5, sendMessages.size, "expected prompts plus receipt to be sent")
            val clubsPrompt = sendMessages[0].getParameters()["text"] as String
            assertTrue(clubsPrompt.contains(texts.menu("en").chooseClub))
            val nightsPrompt = sendMessages[1].getParameters()["text"] as String
            assertTrue(nightsPrompt.contains(texts.chooseNight("en")))
            val tablesPrompt = sendMessages[2].getParameters()["text"] as String
            assertEquals(texts.chooseTable("en"), tablesPrompt)
            val guestsPrompt = sendMessages[3].getParameters()["text"] as String
            assertEquals(texts.chooseGuests("en"), guestsPrompt)
            val receiptText = sendMessages.last().getParameters()["text"] as String
            assertTrue(receiptText.contains(texts.bookingConfirmedTitle("en")))
            assertTrue(receiptText.contains("#${table.tableNumber}"))
            assertTrue(receiptText.contains(texts.receiptGuests("en")))

            coVerify(exactly = 1) { bookingService.hold(any(), any()) }
            coVerify(exactly = 1) { bookingService.confirm(holdId, any()) }
            coVerify(exactly = 1) { bookingService.finalize(bookingId, fromId) }
        }

    @Test
    fun `table callback with malformed token returns expired message`() =
        runTest {
            val bot = mockk<TelegramBot>()
            val clubRepository = mockk<ClubRepository>(relaxed = true)
            val availability = mockk<AvailabilityService>(relaxed = true)
            val bookingService = mockk<BookingService>(relaxed = true)
            val chatUiSession = mockk<ChatUiSessionStore>(relaxed = true)
            val myBookingsService = mockk<MyBookingsService>(relaxed = true)
            val myBookingsMetrics = mockk<MyBookingsMetrics>(relaxed = true)

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

            handler.handle(buildCallbackUpdate("tbl:broken", 101L, lang = "en", fromId = 1L))
            awaitUi()

            assertEquals(1, sendMessages.size)
            val messageText = sendMessages.single().getParameters()["text"] as String
            assertEquals(texts.buttonExpired("en"), messageText)
            coVerify(exactly = 0) { bookingService.hold(any(), any()) }
        }

    @Test
    fun `guest callback with malformed token returns expired message`() =
        runTest {
            val bot = mockk<TelegramBot>()
            val clubRepository = mockk<ClubRepository>(relaxed = true)
            val availability = mockk<AvailabilityService>(relaxed = true)
            val bookingService = mockk<BookingService>(relaxed = true)
            val chatUiSession = mockk<ChatUiSessionStore>(relaxed = true)
            val myBookingsService = mockk<MyBookingsService>(relaxed = true)
            val myBookingsMetrics = mockk<MyBookingsMetrics>(relaxed = true)

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

            handler.handle(buildCallbackUpdate("g:/tbl:", 303L, lang = "en", fromId = 11L))
            awaitUi()

            assertEquals(1, sendMessages.size)
            val messageText = sendMessages.single().getParameters()["text"] as String
            assertEquals(texts.buttonExpired("en"), messageText)
            coVerify(exactly = 0) { bookingService.hold(any(), any()) }
        }

    @Test
    fun `table callback shows friendly message when table unavailable`() =
        runTest {
            val bot = mockk<TelegramBot>()
            val clubRepository = mockk<ClubRepository>(relaxed = true)
            val availability = mockk<AvailabilityService>()
            val bookingService = mockk<BookingService>(relaxed = true)
            val chatUiSession = mockk<ChatUiSessionStore>(relaxed = true)

            val chatId = 404L
            val clubId = 7L
            val tableId = 9L
            val start = Instant.parse("2025-04-01T18:00:00Z")
            val end = start.plusSeconds(14_400)
            val unavailable =
                TableAvailabilityDto(
                    tableId = tableId,
                    tableNumber = "12",
                    zone = "Hall",
                    capacity = 0,
                    minDeposit = 100,
                    status = TableStatus.FREE,
                )

            val myBookingsService = mockk<MyBookingsService>(relaxed = true)
            val myBookingsMetrics = mockk<MyBookingsMetrics>(relaxed = true)

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
            coEvery { availability.listFreeTables(clubId, start) } returns listOf(unavailable)

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

            val tableToken = TableSelectCodec.encode(clubId, start, end, tableId)
            handler.handle(buildCallbackUpdate(tableToken, chatId, lang = "en", fromId = 2L))
            awaitUi()

            assertEquals(1, sendMessages.size)
            val messageText = sendMessages.single().getParameters()["text"] as String
            assertEquals(texts.tableTaken("en"), messageText)
            coVerify(exactly = 0) { bookingService.hold(any(), any()) }
        }

    @Test
    fun `guest callback surfaces hold expired error`() =
        runTest {
            val bot = mockk<TelegramBot>()
            val clubRepository = mockk<ClubRepository>(relaxed = true)
            val availability = mockk<AvailabilityService>()
            val bookingService = mockk<BookingService>()
            val chatUiSession = mockk<ChatUiSessionStore>(relaxed = true)
            val myBookingsService = mockk<MyBookingsService>(relaxed = true)
            val myBookingsMetrics = mockk<MyBookingsMetrics>(relaxed = true)

            val chatId = 505L
            val fromId = 808L
            val clubId = 19L
            val tableId = 3L
            val guests = 2
            val start = Instant.parse("2025-09-01T19:00:00Z")
            val end = start.plusSeconds(14_400)
            val holdId = UUID.randomUUID()
            val table =
                TableAvailabilityDto(
                    tableId = tableId,
                    tableNumber = "4",
                    zone = "VIP",
                    capacity = 4,
                    minDeposit = 120,
                    status = TableStatus.FREE,
                )
            val night =
                NightDto(
                    eventStartUtc = start,
                    eventEndUtc = end.plusSeconds(3_600),
                    isSpecial = false,
                    arrivalByUtc = start,
                    openLocal = LocalDateTime.of(2025, 9, 1, 22, 0),
                    closeLocal = LocalDateTime.of(2025, 9, 2, 5, 0),
                    timezone = "Europe/Moscow",
                )
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
            coEvery { availability.listOpenNights(clubId, any()) } returns listOf(night)
            coEvery { clubRepository.listClubs(any()) } returns listOf(ClubDto(clubId, "Nova", null))
            coEvery { bookingService.hold(any(), any()) } returns BookingCmdResult.HoldCreated(holdId)
            coEvery { bookingService.confirm(holdId, any()) } returns BookingCmdResult.HoldExpired

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

            val guestToken = GuestsSelectCodec.encode(clubId, start, end, tableId, guests)
            handler.handle(buildCallbackUpdate(guestToken, chatId, lang = "en", fromId = fromId))
            awaitUi()

            assertEquals(1, sendMessages.size)
            val messageText = sendMessages.single().getParameters()["text"] as String
            assertEquals(texts.holdExpired("en"), messageText)
            coVerify(exactly = 1) { bookingService.hold(any(), any()) }
            coVerify(exactly = 1) { bookingService.confirm(holdId, any()) }
            coVerify(exactly = 0) { bookingService.finalize(any(), any()) }
        }

    @Test
    fun `guest callback handles idempotency conflict without duplicate booking`() =
        runTest {
            val bot = mockk<TelegramBot>()
            val clubRepository = mockk<ClubRepository>()
            val availability = mockk<AvailabilityService>()
            val bookingService = mockk<BookingService>()
            val chatUiSession = mockk<ChatUiSessionStore>(relaxed = true)
            val myBookingsService = mockk<MyBookingsService>(relaxed = true)
            val myBookingsMetrics = mockk<MyBookingsMetrics>(relaxed = true)

            val chatId = 606L
            val fromId = 909L
            val clubId = 27L
            val tableId = 14L
            val guests = 4
            val start = Instant.parse("2025-10-01T18:30:00Z")
            val end = start.plusSeconds(14_400)
            val holdId = UUID.randomUUID()
            val bookingId = UUID.randomUUID()
            val table =
                TableAvailabilityDto(
                    tableId = tableId,
                    tableNumber = "21",
                    zone = "Lounge",
                    capacity = 6,
                    minDeposit = 180,
                    status = TableStatus.FREE,
                )
            val night =
                NightDto(
                    eventStartUtc = start,
                    eventEndUtc = end.plusSeconds(7_200),
                    isSpecial = true,
                    arrivalByUtc = start.minusSeconds(900),
                    openLocal = LocalDateTime.of(2025, 10, 1, 21, 30),
                    closeLocal = LocalDateTime.of(2025, 10, 2, 6, 30),
                    timezone = "Europe/Moscow",
                )
            val clubs = listOf(ClubDto(clubId, "Pulse", "Downtown"))
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
            coEvery { availability.listOpenNights(clubId, any()) } returns listOf(night)
            coEvery { clubRepository.listClubs(any()) } returns clubs
            var holdCalls = 0
            coEvery { bookingService.hold(any(), any()) } answers {
                holdCalls += 1
                if (holdCalls == 1) {
                    BookingCmdResult.HoldCreated(holdId)
                } else {
                    BookingCmdResult.IdempotencyConflict
                }
            }
            coEvery { bookingService.confirm(holdId, any()) } returns BookingCmdResult.Booked(bookingId)
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

            val guestToken = GuestsSelectCodec.encode(clubId, start, end, tableId, guests)
            val update = buildCallbackUpdate(guestToken, chatId, lang = "en", fromId = fromId)
            handler.handle(update)
            awaitUi()

            val repeatUpdate = buildCallbackUpdate(guestToken, chatId, lang = "en", fromId = fromId)
            handler.handle(repeatUpdate)
            awaitUi()

            assertEquals(2, sendMessages.size)
            val errorText = sendMessages.last().getParameters()["text"] as String
            assertEquals(texts.tooManyRequests("en"), errorText)
            coVerify(exactly = 2) { bookingService.hold(any(), any()) }
            coVerify(exactly = 1) { bookingService.confirm(holdId, any()) }
            coVerify(exactly = 1) { bookingService.finalize(bookingId, fromId) }
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private suspend fun kotlinx.coroutines.test.TestScope.awaitUi() {
    advanceUntilIdle()
    runCurrent()
    coroutineContext[Job]?.children?.forEach { it.join() }
}
