@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("unused")

package com.example.bot.telegram

import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.BookingsTable
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.booking.TablesTable
import com.example.bot.data.booking.core.BookingOutboxTable
import com.example.bot.data.booking.core.OutboxRepository
import com.example.bot.data.security.ExposedUserRepository
import com.example.bot.telegram.bookings.MyBookingsMetrics
import com.example.bot.telegram.bookings.MyBookingsService
import com.example.bot.text.BotTexts
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.User
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import com.pengrad.telegrambot.response.SendResponse
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class MenuMyBookingsTest : StringSpec({
    val dataSource =
        JdbcDataSource().apply {
            setURL("jdbc:h2:mem:my_bookings_${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
    val database = Database.connect(dataSource)

    transaction(database) {
        SchemaUtils.create(
            MyBookingsUsersTable,
            MyBookingsClubsTable,
            EventsTable,
            TablesTable,
            BookingsTable,
            BookingOutboxTable,
        )
    }

    val texts = BotTexts()
    val keyboards = Keyboards(texts)
    val registry = SimpleMeterRegistry()
    val metrics = MyBookingsMetrics(registry)
    val userRepository = ExposedUserRepository(database)
    val outboxRepository = OutboxRepository(database)
    val service = MyBookingsService(database, userRepository, outboxRepository, metrics)

    val availability = mockk<com.example.bot.availability.AvailabilityService>()
    val bookingService = mockk<com.example.bot.booking.BookingService>()
    val chatSession = mockk<com.example.bot.telegram.ui.ChatUiSessionStore>(relaxed = true)
    val clubRepository = mockk<com.example.bot.data.repo.ClubRepository>()

    lateinit var bot: TelegramBot
    lateinit var handler: MenuCallbacksHandler
    lateinit var testScope: TestScope

    beforeTest {
        transaction(database) {
            BookingOutboxTable.deleteAll()
            BookingsTable.deleteAll()
            EventsTable.deleteAll()
            TablesTable.deleteAll()
            MyBookingsClubsTable.deleteAll()
            MyBookingsUsersTable.deleteAll()
        }
        registry.clear()
        bot = mockk(relaxed = true)
        testScope = TestScope(UnconfinedTestDispatcher())
        handler =
            MenuCallbacksHandler(
                bot = bot,
                keyboards = keyboards,
                texts = texts,
                clubRepository = clubRepository,
                availability = availability,
                bookingService = bookingService,
                chatUiSession = chatSession,
                uiScope = testScope,
                myBookingsService = service,
                myBookingsMetrics = metrics,
            )
    }

    "empty list renders fallback" {
        insertUser(telegramId = 1001L, userName = "guest") // сайд-эффект
        val messages = captureMessages(bot)

        runHandler(
            handler,
            testScope,
            buildCallback("menu:bookings", chatId = 1L, fromId = 1001L),
        )

        eventually(1.seconds) { messages shouldHaveSize 1 }
        val message = messages.single()
        message.parameters["text"] shouldBe texts.myBookingsEmpty("ru")
        registry.counter("ui.mybookings.open.total", "source", "telegram").count() shouldBeExactly 1.0
    }

    "pagination splits bookings by 8" {
        val telegramId = 2002L
        val userId = insertUser(telegramId, "pager")
        val clubId = insertClub(id = 1L, name = "Orion", timezone = "UTC")
        repeat(10) { index ->
            val tableId =
                insertTable(
                    clubId,
                    tableNumber = index + 1,
                    capacity = 4,
                    deposit = BigDecimal("1000.00"),
                )
            val start = Instant.parse("2025-05-${(index + 1).toString().padStart(2, '0')}T18:00:00Z")
            val end = start.plusSeconds(3_600)
            val eventId = insertEvent(clubId, start, end)
            insertBooking(
                id = UUID.randomUUID(),
                eventId = eventId,
                clubId = clubId,
                tableId = tableId,
                tableNumber = index + 1,
                userId = userId,
                slotStart = start,
                slotEnd = end,
                totalDeposit = BigDecimal("5000.00"),
            )
        }
        val messages = captureMessages(bot)

        runHandler(handler, testScope, buildCallback("menu:bookings", chatId = 10L, fromId = telegramId))
        eventually(1.seconds) { messages.shouldHaveSize(1) }
        val firstPageText = messages.last().parameters["text"] as String
        firstPageText.lines().count { it.startsWith("#") } shouldBe 8

        runHandler(handler, testScope, buildCallback("bk:list:2", chatId = 10L, fromId = telegramId))
        eventually(1.seconds) { messages.shouldHaveSize(2) }
        val secondPage = messages.last().parameters["text"] as String
        secondPage.lines().count { it.startsWith("#") } shouldBe 2
    }

    "cancel marks booking and enqueues outbox" {
        val telegramId = 3003L
        val userId = insertUser(telegramId, "canceller")
        val clubId = insertClub(id = 5L, name = "Luna", timezone = "UTC", adminChat = 555L)
        val tableId = insertTable(clubId, tableNumber = 12, capacity = 6, deposit = BigDecimal("2000.00"))
        val start = Instant.parse("2025-06-15T20:00:00Z")
        val end = start.plusSeconds(7_200)
        val eventId = insertEvent(clubId, start, end)
        val bookingId = UUID.randomUUID()
        insertBooking(
            id = bookingId,
            eventId = eventId,
            clubId = clubId,
            tableId = tableId,
            tableNumber = 12,
            userId = userId,
            slotStart = start,
            slotEnd = end,
            totalDeposit = BigDecimal("8000.00"),
        )
        val messages = captureMessages(bot)

        runHandler(
            handler,
            testScope,
            buildCallback("bk:cancel:1:$bookingId", chatId = 77L, fromId = telegramId),
        )

        eventually(2.seconds) { messages.shouldHaveSize(2) }
        val resultText = messages.first().parameters["text"] as String
        val expectedShortId = bookingId.toString().replace("-", "").take(6).uppercase()
        resultText shouldBe texts.myBookingsCancelOk("ru", expectedShortId)

        transaction(database) {
            val status =
                BookingsTable
                    .selectAll()
                    .where { BookingsTable.id eq bookingId }
                    .single()[BookingsTable.status]
            status shouldBe BookingStatus.CANCELLED.name

            BookingOutboxTable
                .selectAll()
                .toList() shouldHaveSize 1
        }
        registry.counter("ui.mybookings.cancel.requested", "source", "telegram", "club_id", clubId.toString())
            .count() shouldBeExactly 1.0
        registry.counter("booking.cancel.ok", "source", "telegram", "club_id", clubId.toString())
            .count() shouldBeExactly 1.0
        registry.counter("booking.cancel.already", "source", "telegram", "club_id", clubId.toString())
            .count() shouldBeExactly 0.0
    }

    "repeat cancel is idempotent" {
        val telegramId = 4004L
        val userId = insertUser(telegramId, "repeat")
        val clubId = insertClub(id = 7L, name = "Nova", timezone = "UTC", adminChat = 777L)
        val tableId = insertTable(clubId, 9, 4, BigDecimal("1500.00"))
        val start = Instant.parse("2025-07-01T21:00:00Z")
        val end = start.plusSeconds(5_400)
        val eventId = insertEvent(clubId, start, end)
        val bookingId = UUID.randomUUID()
        insertBooking(
            id = bookingId,
            eventId = eventId,
            clubId = clubId,
            tableId = tableId,
            tableNumber = 9,
            userId = userId,
            slotStart = start,
            slotEnd = end,
            totalDeposit = BigDecimal("6000.00"),
        )
        val messages = captureMessages(bot)

        runHandler(
            handler,
            testScope,
            buildCallback("bk:cancel:1:$bookingId", chatId = 88L, fromId = telegramId),
        )
        eventually(2.seconds) {
            transaction(database) {
                val current =
                    BookingsTable
                        .selectAll()
                        .where { BookingsTable.id eq bookingId }
                        .single()[BookingsTable.status]
                current shouldBe BookingStatus.CANCELLED.name
            }
        }

        runHandler(
            handler,
            testScope,
            buildCallback("bk:cancel:1:$bookingId", chatId = 88L, fromId = telegramId),
        )

        eventually(2.seconds) { messages.shouldHaveSize(4) }
        val payloads = messages.map { it.parameters["text"] as String }
        payloads shouldContain texts.myBookingsCancelAlready("ru")
        val shortId = bookingId.toString().replace("-", "").take(6).uppercase()
        payloads.any { it.contains(shortId) && it.startsWith("Бронь") } shouldBe true
        payloads.count { it == texts.myBookingsEmpty("ru") } shouldBe 2

        transaction(database) {
            BookingOutboxTable.selectAll().toList() shouldHaveSize 1
        }
        registry.counter("booking.cancel.ok", "source", "telegram", "club_id", clubId.toString())
            .count() shouldBeExactly 1.0
        registry.counter("booking.cancel.already", "source", "telegram", "club_id", clubId.toString())
            .count() shouldBeExactly 1.0
    }
})

private fun captureMessages(bot: TelegramBot): MutableList<SendMessage> {
    val sent = mutableListOf<SendMessage>()
    every { bot.execute(any<BaseRequest<*, *>>()) } answers {
        val request = firstArg<BaseRequest<*, *>>()
        when (request) {
            is SendMessage -> {
                sent += request
                mockk<SendResponse>(relaxed = true)
            }
            is AnswerCallbackQuery -> mockk<BaseResponse>(relaxed = true)
            else -> mockk<BaseResponse>(relaxed = true)
        }
    }
    return sent
}

private fun runHandler(
    handler: MenuCallbacksHandler,
    scope: TestScope,
    update: Update,
) {
    handler.handle(update)
    scope.advanceUntilIdle()
}

@Suppress("DEPRECATION") // pengrad: message() помечен deprecated в Java, для моков ок
private fun buildCallback(
    data: String,
    chatId: Long,
    fromId: Long,
    lang: String? = "ru",
): Update {
    val message = mockk<Message>(relaxed = true)
    every { message.messageId() } returns 1
    every { message.chat()?.id() } returns chatId

    val from = mockk<User>()
    every { from.id() } returns fromId
    every { from.languageCode() } returns lang

    val callback = mockk<CallbackQuery>(relaxed = true)
    every { callback.from() } returns from
    every { callback.data() } returns data
    every { callback.id() } returns "cb-$data"
    every { callback.message() } returns message

    val update = mockk<Update>()
    every { update.callbackQuery() } returns callback
    return update
}

private fun insertUser(
    telegramId: Long,
    userName: String,
): Long =
    transaction {
        MyBookingsUsersTable.insertAndGetId { row ->
            row[telegramUserId] = telegramId
            row[username] = userName
        }.value
    }

private fun insertClub(
    id: Long,
    name: String,
    timezone: String,
    adminChat: Long? = null,
): Long =
    transaction {
        MyBookingsClubsTable.insert { row ->
            row[MyBookingsClubsTable.id] = id
            row[MyBookingsClubsTable.name] = name
            row[MyBookingsClubsTable.timezone] = timezone
            row[MyBookingsClubsTable.adminChatId] = adminChat
        }
        id
    }

private fun insertEvent(
    clubId: Long,
    start: Instant,
    end: Instant,
): Long =
    transaction {
        EventsTable.insert { row ->
            row[EventsTable.clubId] = clubId
            row[EventsTable.startAt] = OffsetDateTime.ofInstant(start, ZoneOffset.UTC)
            row[EventsTable.endAt] = OffsetDateTime.ofInstant(end, ZoneOffset.UTC)
            row[EventsTable.title] = "Party"
            row[EventsTable.isSpecial] = false
            row[EventsTable.posterUrl] = null
        }[EventsTable.id]
    }

private fun insertTable(
    clubId: Long,
    tableNumber: Int,
    capacity: Int,
    deposit: BigDecimal,
): Long =
    transaction {
        TablesTable.insert { row ->
            row[TablesTable.clubId] = clubId
            row[TablesTable.tableNumber] = tableNumber
            row[TablesTable.capacity] = capacity
            row[TablesTable.minDeposit] = deposit
            row[TablesTable.active] = true
        }[TablesTable.id]
    }

private fun insertBooking(
    id: UUID,
    eventId: Long,
    clubId: Long,
    tableId: Long,
    tableNumber: Int,
    userId: Long,
    slotStart: Instant,
    slotEnd: Instant,
    totalDeposit: BigDecimal,
) {
    transaction {
        BookingsTable.insert { row ->
            row[BookingsTable.id] = id
            row[BookingsTable.eventId] = eventId
            row[BookingsTable.clubId] = clubId
            row[BookingsTable.tableId] = tableId
            row[BookingsTable.tableNumber] = tableNumber
            row[BookingsTable.guestUserId] = userId
            row[BookingsTable.guestName] = null
            row[BookingsTable.phoneE164] = null
            row[BookingsTable.promoterUserId] = null
            row[BookingsTable.guestsCount] = 2
            row[BookingsTable.minDeposit] = totalDeposit
            row[BookingsTable.totalDeposit] = totalDeposit
            row[BookingsTable.slotStart] = OffsetDateTime.ofInstant(slotStart, ZoneOffset.UTC)
            row[BookingsTable.slotEnd] = OffsetDateTime.ofInstant(slotEnd, ZoneOffset.UTC)
            row[BookingsTable.arrivalBy] = null
            row[BookingsTable.status] = BookingStatus.BOOKED.name
            row[BookingsTable.qrSecret] = UUID.randomUUID().toString().replace("-", "")
            row[BookingsTable.idempotencyKey] = "idem-$id"
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            row[BookingsTable.createdAt] = now
            row[BookingsTable.updatedAt] = now
        }
    }
}

private object MyBookingsUsersTable : LongIdTable("users") {
    val telegramUserId = long("telegram_user_id")
    val username = text("username").nullable()
}

private object MyBookingsClubsTable : org.jetbrains.exposed.sql.Table("clubs") {
    val id = long("id")
    val name = text("name")
    val timezone = text("timezone")
    val adminChatId = long("admin_chat_id").nullable()
    override val primaryKey = PrimaryKey(id)
}
