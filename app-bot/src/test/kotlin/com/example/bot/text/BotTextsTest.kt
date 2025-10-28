package com.example.bot.text

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import kotlin.test.assertEquals

class BotTextsTest {
    private val texts = BotTexts()

    @Test
    fun `menu uses localized labels`() {
        val ru = texts.menu(null)
        val en = texts.menu("en")

        assertAll(
            { assertEquals("Выбрать клуб", ru.chooseClub) },
            { assertEquals("Мои бронирования", ru.myBookings) },
            { assertEquals("Задать вопрос", ru.ask) },
            { assertEquals("Музыка", ru.music) },
            { assertEquals("Choose club", en.chooseClub) },
            { assertEquals("My bookings", en.myBookings) },
            { assertEquals("Ask a question", en.ask) },
            { assertEquals("Music", en.music) },
        )
    }

    @Test
    fun `step prompts localized`() {
        assertAll(
            { assertEquals("Выберите ночь:", texts.chooseNight(null)) },
            { assertEquals("Choose a night:", texts.chooseNight("en")) },
            { assertEquals("Выберите стол:", texts.chooseTable(null)) },
            { assertEquals("Choose a table:", texts.chooseTable("en")) },
            { assertEquals("Выберите количество гостей:", texts.chooseGuests(null)) },
            { assertEquals("Choose number of guests:", texts.chooseGuests("en")) },
        )
    }

    @Test
    fun `fallback messages localized`() {
        assertAll(
            { assertEquals("Сессия устарела, начните выбор заново.", texts.sessionExpired(null)) },
            { assertEquals("The session has expired, please start over.", texts.sessionExpired("en")) },
            { assertEquals("Кнопка устарела, обновите экран.", texts.buttonExpired(null)) },
            { assertEquals("The button has expired, please refresh the screen.", texts.buttonExpired("en")) },
            { assertEquals("Сейчас нет ночей с открытым бронированием. Загляните позже.", texts.noNights(null)) },
            { assertEquals("No open nights available right now. Please check back later.", texts.noNights("en")) },
            { assertEquals("Свободных столов на эту ночь нет.", texts.noTables(null)) },
            { assertEquals("No free tables for this night.", texts.noTables("en")) },
            { assertEquals("Стол уже занят. Выберите другой, пожалуйста.", texts.tableTaken(null)) },
            { assertEquals("This table is already taken. Please choose another one.", texts.tableTaken("en")) },
            { assertEquals("Слишком много запросов. Попробуйте ещё раз.", texts.tooManyRequests(null)) },
            { assertEquals("Too many requests. Please try again.", texts.tooManyRequests("en")) },
            { assertEquals("Пауза истекла. Попробуйте снова.", texts.holdExpired(null)) },
            { assertEquals("Hold expired. Please try again.", texts.holdExpired("en")) },
        )
    }

    @Test
    fun `table label localized`() {
        val ruLabel = texts.tableLabel("ru", 7, 150_00L)
        val enLabel = texts.tableLabel("en", 7, 150_00L)

        assertAll(
            { assertEquals("Стол 7 · от 150 ₽", ruLabel) },
            { assertEquals("Table 7 · from 150", enLabel) },
        )
    }

    @Test
    fun `receipt currency suffix localized`() {
        assertAll(
            { assertEquals(" ₽", texts.receiptCurrencySuffix(null)) },
            { assertEquals(" ₽", texts.receiptCurrencySuffix("ru")) },
            { assertEquals("", texts.receiptCurrencySuffix("en")) },
        )
    }

    @Test
    fun `receipt texts localized`() {
        assertAll(
            { assertEquals("Бронь подтверждена ✅", texts.bookingConfirmedTitle(null)) },
            { assertEquals("Booking confirmed ✅", texts.bookingConfirmedTitle("en")) },
            { assertEquals("Клуб", texts.receiptClub(null)) },
            { assertEquals("Club", texts.receiptClub("en")) },
            { assertEquals("Дата", texts.receiptDate(null)) },
            { assertEquals("Date", texts.receiptDate("en")) },
            { assertEquals("Стол", texts.receiptTable(null)) },
            { assertEquals("Table", texts.receiptTable("en")) },
            { assertEquals("Гостей", texts.receiptGuests(null)) },
            { assertEquals("Guests", texts.receiptGuests("en")) },
            { assertEquals("Депозит от", texts.receiptDepositFrom(null)) },
            { assertEquals("Deposit from", texts.receiptDepositFrom("en")) },
        )
    }

    @Test
    fun `formats respect locale`() {
        val instant = java.time.Instant.parse("2024-02-03T18:00:00Z")
        val zone = java.time.ZoneId.of("Europe/Moscow")

        val ruLocale = BotLocales.RU
        val enLocale = BotLocales.EN

        assertAll(
            { assertEquals("Сб", BotLocales.dayNameShort(instant, zone, ruLocale)) },
            { assertEquals("Sat", BotLocales.dayNameShort(instant, zone, enLocale)) },
            { assertEquals("3 февр.", BotLocales.dateDMmm(instant, zone, ruLocale)) },
            { assertEquals("3 Feb", BotLocales.dateDMmm(instant, zone, enLocale)) },
            { assertEquals("21:00", BotLocales.timeHHmm(instant, zone, ruLocale)) },
            { assertEquals("21:00", BotLocales.timeHHmm(instant, zone, enLocale)) },
        )
    }

    @Test
    fun `money formatting uses locale separators`() {
        val amountMinor = 12_345_600L

        val ruFormatted = BotLocales.money(amountMinor, BotLocales.RU)
        val enFormatted = BotLocales.money(amountMinor, BotLocales.EN)

        assertAll(
            { assertEquals("123\u00a0456", ruFormatted) },
            { assertEquals("123,456", enFormatted) },
        )
    }
}
