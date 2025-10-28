package com.example.bot.text

@Suppress("TooManyFunctions")
class BotTexts {
    // ====== Общие ======
    fun greeting(lang: String?): String =
        if (isEn(lang)) {
            "Welcome!"
        } else {
            "Привет!"
        }

    fun isEn(lang: String?): Boolean = lang?.startsWith("en", true) == true

    // ====== Меню / кнопки ======
    data class Menu(val chooseClub: String, val myBookings: String, val ask: String, val music: String)

    fun menu(lang: String?): Menu =
        if (isEn(lang)) {
            Menu("Choose club", "My bookings", "Ask a question", "Music")
        } else {
            Menu("Выбрать клуб", "Мои бронирования", "Задать вопрос", "Музыка")
        }

    // ====== Заголовки шагов ======
    fun chooseNight(lang: String?): String =
        if (isEn(lang)) {
            "Choose a night:"
        } else {
            "Выберите ночь:"
        }

    fun chooseTable(lang: String?): String =
        if (isEn(lang)) {
            "Choose a table:"
        } else {
            "Выберите стол:"
        }

    fun chooseGuests(lang: String?): String =
        if (isEn(lang)) {
            "Choose number of guests:"
        } else {
            "Выберите количество гостей:"
        }

    fun tableLabel(
        lang: String?,
        tableNumber: Int,
        minDepositMinor: Long,
    ): String = tableLabel(lang, tableNumber.toString(), minDepositMinor)

    fun tableLabel(
        lang: String?,
        tableNumber: String,
        minDepositMinor: Long,
    ): String {
        val locale = BotLocales.resolve(lang)
        val amount = BotLocales.money(minDepositMinor, locale)
        return if (isEn(lang)) {
            "Table $tableNumber · from $amount"
        } else {
            "Стол $tableNumber · от $amount${receiptCurrencySuffix(lang)}"
        }
    }

    fun receiptCurrencySuffix(lang: String?): String = if (isEn(lang)) "" else " ₽"

    // ====== Фоллбеки/ошибки ======
    fun sessionExpired(lang: String?): String =
        if (isEn(lang)) {
            "The session has expired, please start over."
        } else {
            "Сессия устарела, начните выбор заново."
        }

    fun buttonExpired(lang: String?): String =
        if (isEn(lang)) {
            "The button has expired, please refresh the screen."
        } else {
            "Кнопка устарела, обновите экран."
        }

    fun nightsLoadError(lang: String?): String =
        if (isEn(lang)) {
            "Failed to load nights. Please try again."
        } else {
            "Не получилось загрузить ночи. Попробуйте ещё раз."
        }

    fun noNights(lang: String?): String =
        if (isEn(lang)) {
            "No open nights available right now. Please check back later."
        } else {
            "Сейчас нет ночей с открытым бронированием. Загляните позже."
        }

    fun noTables(lang: String?): String =
        if (isEn(lang)) {
            "No free tables for this night."
        } else {
            "Свободных столов на эту ночь нет."
        }

    fun tableTaken(lang: String?): String =
        if (isEn(lang)) {
            "This table is already taken. Please choose another one."
        } else {
            "Стол уже занят. Выберите другой, пожалуйста."
        }

    fun myBookingsTitle(lang: String?): String =
        if (isEn(lang)) {
            "Your active bookings"
        } else {
            "Ваши активные бронирования"
        }

    fun myBookingsEmpty(lang: String?): String =
        if (isEn(lang)) {
            "You have no active bookings yet."
        } else {
            "У вас нет активных броней."
        }

    fun myBookingsMoreButton(lang: String?): String = if (isEn(lang)) "Details" else "Подробнее"

    fun myBookingsCancelButton(lang: String?): String = if (isEn(lang)) "Cancel" else "Отменить"

    fun myBookingsPrev(lang: String?): String = if (isEn(lang)) "⟵ Back" else "⟵ Назад"

    fun myBookingsNext(lang: String?): String = if (isEn(lang)) "Next ⟶" else "Дальше ⟶"

    fun myBookingsBack(lang: String?): String = if (isEn(lang)) "⟵ Back" else "⟵ Назад"

    fun myBookingsCancelOk(lang: String?, shortId: String): String =
        if (isEn(lang)) {
            "Booking #$shortId was cancelled."
        } else {
            "Бронь #$shortId отменена."
        }

    fun myBookingsCancelAlready(lang: String?): String =
        if (isEn(lang)) {
            "This booking is already cancelled."
        } else {
            "Эта бронь уже отменена."
        }

    fun myBookingsCancelNotFound(lang: String?): String =
        if (isEn(lang)) {
            "Booking not found."
        } else {
            "Бронь не найдена."
        }

    fun myBookingsCancelTitle(lang: String?, shortId: String): String =
        if (isEn(lang)) {
            "Booking #$shortId cancelled"
        } else {
            "Бронь #$shortId отменена"
        }

    fun myBookingsCancelAmount(lang: String?): String =
        if (isEn(lang)) {
            "Amount"
        } else {
            "Сумма"
        }

    fun myBookingsCancelledBy(lang: String?): String =
        if (isEn(lang)) {
            "Cancelled by"
        } else {
            "Отменил"
        }

    fun tooManyRequests(lang: String?): String =
        if (isEn(lang)) {
            "Too many requests. Please try again."
        } else {
            "Слишком много запросов. Попробуйте ещё раз."
        }

    fun holdExpired(lang: String?): String =
        if (isEn(lang)) {
            "Hold expired. Please try again."
        } else {
            "Пауза истекла. Попробуйте снова."
        }

    fun bookingNotFound(lang: String?): String =
        if (isEn(lang)) {
            "Booking not found. Please try again."
        } else {
            "Бронь не найдена. Попробуйте ещё раз."
        }

    fun unknownError(lang: String?): String =
        if (isEn(lang)) {
            "Unexpected error. Please try later."
        } else {
            "Неожиданная ошибка. Пожалуйста, попробуйте позже."
        }

    // ====== Чеки/подтверждения ======
    fun bookingConfirmedTitle(lang: String?): String =
        if (isEn(lang)) {
            "Booking confirmed ✅"
        } else {
            "Бронь подтверждена ✅"
        }

    fun receiptClub(lang: String?): String =
        if (isEn(lang)) {
            "Club"
        } else {
            "Клуб"
        }

    fun receiptDate(lang: String?): String =
        if (isEn(lang)) {
            "Date"
        } else {
            "Дата"
        }

    fun receiptTable(lang: String?): String =
        if (isEn(lang)) {
            "Table"
        } else {
            "Стол"
        }

    fun receiptGuests(lang: String?): String =
        if (isEn(lang)) {
            "Guests"
        } else {
            "Гостей"
        }

    fun receiptDepositFrom(lang: String?): String =
        if (isEn(lang)) {
            "Deposit from"
        } else {
            "Депозит от"
        }
}
