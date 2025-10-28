@file:Suppress("SpreadOperator")

package com.example.bot.telegram

import com.example.bot.availability.TableAvailabilityDto
import com.example.bot.availability.minDepositMinor
import com.example.bot.telegram.bookings.MyBookingsService
import com.example.bot.text.BotTexts
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import java.util.UUID

/**
 * Factory for inline keyboards used in the guest flow.
 */
class Keyboards(private val texts: BotTexts) {
    /**
     * Main menu keyboard shown on /start.
     */
    fun startMenu(lang: String?): InlineKeyboardMarkup {
        val m = texts.menu(lang)
        return InlineKeyboardMarkup(
            arrayOf(
                InlineKeyboardButton(m.chooseClub).callbackData("menu:clubs"),
            ),
            arrayOf(
                InlineKeyboardButton(m.myBookings).callbackData("menu:bookings"),
            ),
            arrayOf(
                InlineKeyboardButton(m.ask).callbackData("menu:ask"),
            ),
            arrayOf(
                InlineKeyboardButton(m.music).callbackData("menu:music"),
            ),
        )
    }

    /**
     * Keyboard with club choices.
     * Each pair is token to display name.
     */
    fun clubsKeyboard(clubs: List<Pair<String, String>>): InlineKeyboardMarkup {
        val rows =
            clubs.map { (token, name) ->
                arrayOf(InlineKeyboardButton(name).callbackData("club:$token"))
            }
        return InlineKeyboardMarkup(*rows.toTypedArray())
    }

    /**
     * Keyboard listing nights.
     */
    fun nightsKeyboard(nights: List<Pair<String, String>>): InlineKeyboardMarkup {
        val rows =
            nights.map { (token, label) ->
                arrayOf(InlineKeyboardButton(label).callbackData("night:$token"))
            }
        return InlineKeyboardMarkup(*rows.toTypedArray())
    }

    /**
     * Keyboard for tables with simple pagination.
     */
    fun tablesKeyboard(
        tables: List<TableAvailabilityDto>,
        page: Int,
        pageSize: Int,
        lang: String?,
        encode: (TableAvailabilityDto) -> String,
    ): InlineKeyboardMarkup {
        val start = (page - 1) * pageSize
        val slice = tables.drop(start).take(pageSize)
        val rows =
            slice
                .map { t ->
                    arrayOf(
                        InlineKeyboardButton(texts.tableLabel(lang, t.tableNumber, t.minDepositMinor()))
                            .callbackData(encode(t).ensureTablePrefix()),
                    )
                }.toMutableList()
        val totalPages = (tables.size + pageSize - 1) / pageSize
        if (totalPages > 1) {
            val nav = mutableListOf<InlineKeyboardButton>()
            if (page > 1) {
                nav +=
                    InlineKeyboardButton("⬅️")
                        .callbackData("pg:${page - 1}")
            }
            nav += InlineKeyboardButton("$page/$totalPages").callbackData("noop")
            if (page < totalPages) {
                nav +=
                    InlineKeyboardButton("➡️")
                        .callbackData("pg:${page + 1}")
            }
            rows += nav.toTypedArray()
        }
        return InlineKeyboardMarkup(*rows.toTypedArray())
    }

    /**
     * Keyboard for selecting number of guests up to [capacity].
     */
    fun guestsKeyboard(
        capacity: Int,
        encode: (Int) -> String,
    ): InlineKeyboardMarkup {
        val rows = mutableListOf<Array<InlineKeyboardButton>>()
        var row = mutableListOf<InlineKeyboardButton>()
        for (i in 1..capacity) {
            row += InlineKeyboardButton(i.toString()).callbackData(encode(i).ensureGuestPrefix())
            if (row.size == GUESTS_PER_ROW) {
                rows += row.toTypedArray()
                row = mutableListOf()
            }
        }
        if (row.isNotEmpty()) rows += row.toTypedArray()
        return InlineKeyboardMarkup(*rows.toTypedArray())
    }

    fun myBookingsKeyboard(
        lang: String?,
        bookings: List<MyBookingsService.BookingInfo>,
        page: Int,
        hasPrev: Boolean,
        hasNext: Boolean,
        encodeShow: (UUID) -> String,
        encodeCancel: (UUID) -> String,
    ): InlineKeyboardMarkup {
        val rows =
            bookings
                .map { booking ->
                    arrayOf(
                        InlineKeyboardButton(texts.myBookingsMoreButton(lang)).callbackData(encodeShow(booking.id)),
                        InlineKeyboardButton(texts.myBookingsCancelButton(lang)).callbackData(encodeCancel(booking.id)),
                    )
                }
                .toMutableList()
        if (hasPrev || hasNext) {
            val navButtons = mutableListOf<InlineKeyboardButton>()
            if (hasPrev) {
                navButtons +=
                    InlineKeyboardButton(texts.myBookingsPrev(lang))
                        .callbackData("bk:list:${(page - 1).coerceAtLeast(1)}")
            }
            if (hasNext) {
                navButtons +=
                    InlineKeyboardButton(texts.myBookingsNext(lang))
                        .callbackData("bk:list:${page + 1}")
            }
            rows += navButtons.toTypedArray()
        }
        return InlineKeyboardMarkup(*rows.toTypedArray())
    }

    fun myBookingDetailsKeyboard(
        lang: String?,
        bookingId: UUID,
        originatingPage: Int,
        encodeCancel: (UUID) -> String,
    ): InlineKeyboardMarkup {
        val cancel = InlineKeyboardButton(texts.myBookingsCancelButton(lang)).callbackData(encodeCancel(bookingId))
        val back = InlineKeyboardButton(texts.myBookingsBack(lang)).callbackData("bk:list:${originatingPage.coerceAtLeast(1)}")
        return InlineKeyboardMarkup(arrayOf(cancel, back))
    }
}

private const val GUESTS_PER_ROW = 4
private const val TABLE_PREFIX = "tbl:"
private const val GUEST_PREFIX = "g:"

private fun String.ensureTablePrefix(): String {
    return if (startsWith(TABLE_PREFIX)) this else TABLE_PREFIX + this
}

private fun String.ensureGuestPrefix(): String {
    return if (startsWith(GUEST_PREFIX)) this else GUEST_PREFIX + this
}
