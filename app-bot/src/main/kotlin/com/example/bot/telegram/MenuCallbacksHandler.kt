package com.example.bot.telegram

import com.example.bot.availability.AvailabilityService
import com.example.bot.availability.NightDto
import com.example.bot.availability.TableAvailabilityDto
import com.example.bot.availability.minDepositMinor
import com.example.bot.booking.BookingCmdResult
import com.example.bot.booking.BookingService
import com.example.bot.booking.HoldRequest
import com.example.bot.data.repo.ClubDto
import com.example.bot.data.repo.ClubRepository
import com.example.bot.metrics.UiBookingMetrics
import com.example.bot.telegram.bookings.MyBookingsMetrics
import com.example.bot.telegram.bookings.MyBookingsService
import com.example.bot.telegram.tokens.ClubTokenCodec
import com.example.bot.telegram.tokens.DecodedGuests
import com.example.bot.telegram.tokens.GuestsSelectCodec
import com.example.bot.telegram.tokens.NightTokenCodec
import com.example.bot.telegram.tokens.TableSelectCodec
import com.example.bot.telegram.ui.ChatUiSessionStore
import com.example.bot.text.BotLocales
import com.example.bot.text.BotTexts
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.SendMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Handles callback-based navigation for inline menus.
 */
@Suppress("LargeClass", "TooManyFunctions")
class MenuCallbacksHandler(
    private val bot: TelegramBot,
    private val keyboards: Keyboards,
    private val texts: BotTexts,
    private val clubRepository: ClubRepository,
    private val availability: AvailabilityService,
    private val bookingService: BookingService,
    private val chatUiSession: ChatUiSessionStore,
    private val uiScope: CoroutineScope,
    private val myBookingsService: MyBookingsService,
    private val myBookingsMetrics: MyBookingsMetrics,
) {
    private val logger = LoggerFactory.getLogger(MenuCallbacksHandler::class.java)

    fun handle(update: Update) {
        val callbackQuery: CallbackQuery = update.callbackQuery() ?: return
        val data = callbackQuery.data() ?: return

        // безопасное извлечение chatId/threadId без повсеместного использования deprecated-метода
        val (chatId, threadId) = extractChatAndThread(callbackQuery)
        val lang = callbackQuery.from()?.languageCode()

        // Stop Telegram's spinner immediately to avoid blocking UI.
        bot.execute(AnswerCallbackQuery(callbackQuery.id()))

        val routeTag =
            when {
                data == MENU_CLUBS -> MENU_CLUBS
                data == MENU_BOOKINGS -> MENU_BOOKINGS
                data.startsWith(BOOKINGS_PREFIX) -> BOOKINGS_PREFIX
                data.startsWith(CLUB_PREFIX) -> CLUB_PREFIX
                data.startsWith(NIGHT_PREFIX) -> NIGHT_PREFIX
                data.startsWith(PAGE_PREFIX) -> PAGE_PREFIX
                data.startsWith(TABLE_PREFIX) -> TABLE_PREFIX
                data.startsWith(GUEST_PREFIX) -> GUEST_PREFIX
                else -> null
            }
        if (routeTag != null) {
            UiBookingMetrics.incMenuClicks()
            logger.info("ui.menu.route route={}", routeTag)
        }

        when {
            data == MENU_CLUBS && chatId != null ->
                uiScope.launch {
                    val clubs = safeLoadClubs()
                    val text = buildClubSelectionMessage(clubs, lang)
                    val clubButtons = clubs.map { club -> ClubTokenCodec.encode(club.id) to club.name }
                    val markup = keyboards.clubsKeyboard(clubButtons)
                    send(chatId, threadId, text, markup)
                    UiBookingMetrics.incNightsRendered()
                }

            data == MENU_BOOKINGS && chatId != null ->
                uiScope.launch { handleMyBookingsMenu(callbackQuery, chatId, threadId, lang) }

            data.startsWith(BOOKINGS_LIST_PREFIX) && chatId != null ->
                uiScope.launch {
                    val pageNumber = data.removePrefix(BOOKINGS_LIST_PREFIX).toIntOrNull()
                    if (pageNumber == null) {
                        logger.warn("ui.menu.malformed tokenType=bk:list")
                        send(chatId, threadId, texts.sessionExpired(lang), keyboards.startMenu(lang))
                        return@launch
                    }
                    handleMyBookingsPage(callbackQuery, chatId, threadId, lang, pageNumber)
                }

            data.startsWith(BOOKINGS_SHOW_PREFIX) && chatId != null ->
                uiScope.launch {
                    val parsed = parsePageAndId(data, BOOKINGS_SHOW_PREFIX)
                    if (parsed == null) {
                        logger.warn("ui.menu.malformed tokenType=bk:show")
                        send(chatId, threadId, texts.sessionExpired(lang), keyboards.startMenu(lang))
                        return@launch
                    }
                    handleMyBookingsShow(callbackQuery, chatId, threadId, lang, parsed)
                }

            data.startsWith(BOOKINGS_CANCEL_PREFIX) && chatId != null ->
                uiScope.launch {
                    val parsed = parsePageAndId(data, BOOKINGS_CANCEL_PREFIX)
                    if (parsed == null) {
                        logger.warn("ui.menu.malformed tokenType=bk:cancel")
                        send(chatId, threadId, texts.sessionExpired(lang), keyboards.startMenu(lang))
                        return@launch
                    }
                    handleMyBookingsCancel(callbackQuery, chatId, threadId, lang, parsed)
                }

            data.startsWith(CLUB_PREFIX) && chatId != null ->
                uiScope.launch {
                    val token = data.removePrefix(CLUB_PREFIX)
                    val clubId = ClubTokenCodec.decode(token)
                    if (clubId == null) {
                        logger.warn("ui.menu.malformed tokenType=club")
                        send(chatId, threadId, texts.buttonExpired(lang))
                        return@launch
                    }

                    val nights = safeLoadNights(clubId)
                    if (nights == null) {
                        send(chatId, threadId, texts.nightsLoadError(lang))
                        return@launch
                    }
                    if (nights.isEmpty()) {
                        send(chatId, threadId, texts.noNights(lang))
                        return@launch
                    }

                    val buttons =
                        nights.map { night ->
                            NightTokenCodec.encode(clubId, night.eventStartUtc) to formatNightLabel(night, lang)
                        }
                    val text = buildNightsSelectionMessage(nights, lang)
                    val markup = keyboards.nightsKeyboard(buttons)
                    send(chatId, threadId, text, markup)
                    UiBookingMetrics.incNightsRendered()
                }

            data.startsWith(NIGHT_PREFIX) && chatId != null ->
                uiScope.launch {
                    val token = data.removePrefix(NIGHT_PREFIX)
                    val decoded = NightTokenCodec.decode(token)
                    if (decoded == null) {
                        logger.warn("ui.menu.malformed tokenType=night")
                        send(chatId, threadId, texts.sessionExpired(lang), keyboards.startMenu(lang))
                        return@launch
                    }
                    val (clubId, startUtc) = decoded
                    chatUiSession.putNightContext(chatId, threadId, clubId, startUtc)
                    logger.info("ui.night.select clubId={} start={}", clubId, startUtc)
                    val tables =
                        withContext(Dispatchers.IO) {
                            UiBookingMetrics.timeListTables { availability.listFreeTables(clubId, startUtc) }
                        }
                    renderTablesPage(chatId, threadId, lang, clubId, startUtc, page = 1, preloadedTables = tables)
                }

            data.startsWith(PAGE_PREFIX) && chatId != null ->
                uiScope.launch {
                    val pageNumber = data.removePrefix(PAGE_PREFIX).toIntOrNull()
                    if (pageNumber == null) {
                        logger.warn("ui.menu.malformed tokenType=page")
                        send(chatId, threadId, texts.sessionExpired(lang), keyboards.startMenu(lang))
                        return@launch
                    }
                    val context = chatUiSession.getNightContext(chatId, threadId)
                    if (context == null) {
                        logger.warn("ui.menu.context_missing route=pg")
                        send(chatId, threadId, texts.sessionExpired(lang), keyboards.startMenu(lang))
                        return@launch
                    }
                    chatUiSession.putNightContext(chatId, threadId, context.clubId, context.startUtc)
                    val rendered =
                        renderTablesPage(
                            chatId,
                            threadId,
                            lang,
                            context.clubId,
                            context.startUtc,
                            pageNumber,
                        )
                    if (rendered) {
                        UiBookingMetrics.incPagesRendered()
                    }
                }

            data.startsWith(TABLE_PREFIX) && chatId != null ->
                uiScope.launch {
                    val decodedTable = TableSelectCodec.decode(data)
                    if (decodedTable == null) {
                        logger.warn("ui.menu.malformed tokenType=tbl")
                        send(chatId, threadId, texts.buttonExpired(lang))
                        return@launch
                    }

                    val clubId = decodedTable.clubId
                    val startUtc = decodedTable.startUtc
                    val endUtc = decodedTable.endUtc
                    val tableId = decodedTable.tableId
                    val tables = safeLoadTables(clubId, startUtc)
                    val table = tables.firstOrNull { it.tableId == tableId }
                    if (table == null || table.capacity <= 0) {
                        logger.info(
                            "ui.tbl.unavailable clubId={} tableId={} startSec={}",
                            clubId,
                            tableId,
                            startUtc.epochSecond,
                        )
                        send(chatId, threadId, texts.tableTaken(lang))
                        return@launch
                    }

                    val markup =
                        keyboards.guestsKeyboard(table.capacity) { guests ->
                            GuestsSelectCodec.encode(clubId, startUtc, endUtc, tableId, guests)
                        }
                    send(chatId, threadId, texts.chooseGuests(lang), markup)
                    UiBookingMetrics.incTableChosen()
                }

            data.startsWith(GUEST_PREFIX) && chatId != null ->
                uiScope.launch {
                    val decoded = GuestsSelectCodec.decode(data)
                    if (decoded == null) {
                        logger.warn("ui.menu.malformed tokenType=g")
                        send(chatId, threadId, texts.buttonExpired(lang))
                        return@launch
                    }
                    handleGuestSelection(callbackQuery, chatId, threadId, lang, decoded)
                }

            data == NOOP_CALLBACK -> Unit

            else -> Unit
        }
    }

    private suspend fun handleMyBookingsMenu(
        callbackQuery: CallbackQuery,
        chatId: Long,
        threadId: Int?,
        lang: String?,
    ) {
        val telegramUserId = callbackQuery.from()?.id()?.toLong()
        if (telegramUserId == null) {
            send(chatId, threadId, texts.sessionExpired(lang), keyboards.startMenu(lang))
            return
        }
        myBookingsMetrics.incOpen()
        renderMyBookingsPage(chatId, threadId, lang, telegramUserId, page = 1)
    }

    private suspend fun handleMyBookingsPage(
        callbackQuery: CallbackQuery,
        chatId: Long,
        threadId: Int?,
        lang: String?,
        page: Int,
    ) {
        val telegramUserId = callbackQuery.from()?.id()?.toLong()
        if (telegramUserId == null) {
            send(chatId, threadId, texts.sessionExpired(lang), keyboards.startMenu(lang))
            return
        }
        renderMyBookingsPage(chatId, threadId, lang, telegramUserId, page)
    }

    private suspend fun handleMyBookingsShow(
        callbackQuery: CallbackQuery,
        chatId: Long,
        threadId: Int?,
        lang: String?,
        payload: Pair<Int, UUID>,
    ) {
        val telegramUserId = callbackQuery.from()?.id()?.toLong()
        if (telegramUserId == null) {
            send(chatId, threadId, texts.sessionExpired(lang), keyboards.startMenu(lang))
            return
        }
        val (page, bookingId) = payload
        val info =
            try {
                myBookingsService.loadBooking(telegramUserId, bookingId)
            } catch (ex: Exception) {
                logger.warn(
                    "ui.mybookings.show.failed booking={} user={}",
                    bookingId,
                    telegramUserId,
                    ex,
                )
                null
            }
        if (info == null) {
            send(chatId, threadId, texts.myBookingsCancelNotFound(lang))
            return
        }
        val text = buildBookingDetails(info, lang)
        val markup =
            keyboards.myBookingDetailsKeyboard(
                lang = lang,
                bookingId = info.id,
                originatingPage = page,
                encodeCancel = { id -> encodeCancelCallback(page, id) },
            )
        send(chatId, threadId, text, markup)
    }

    private suspend fun handleMyBookingsCancel(
        callbackQuery: CallbackQuery,
        chatId: Long,
        threadId: Int?,
        lang: String?,
        payload: Pair<Int, UUID>,
    ) {
        val telegramUserId = callbackQuery.from()?.id()?.toLong()
        if (telegramUserId == null) {
            send(chatId, threadId, texts.sessionExpired(lang), keyboards.startMenu(lang))
            return
        }
        val (page, bookingId) = payload
        val result =
            try {
                myBookingsService.cancel(telegramUserId, bookingId, texts, lang)
            } catch (ex: Exception) {
                logger.warn(
                    "ui.mybookings.cancel.failed booking={} user={}",
                    bookingId,
                    telegramUserId,
                    ex,
                )
                null
            }
        when (result) {
            is MyBookingsService.CancelResult.Ok ->
                send(chatId, threadId, texts.myBookingsCancelOk(lang, result.info.shortId))

            is MyBookingsService.CancelResult.Already ->
                send(chatId, threadId, texts.myBookingsCancelAlready(lang))

            MyBookingsService.CancelResult.NotFound ->
                send(chatId, threadId, texts.myBookingsCancelNotFound(lang))

            null -> send(chatId, threadId, texts.unknownError(lang))
        }
        if (result != null) {
            renderMyBookingsPage(chatId, threadId, lang, telegramUserId, page)
        }
    }

    private suspend fun renderMyBookingsPage(
        chatId: Long,
        threadId: Int?,
        lang: String?,
        telegramUserId: Long,
        page: Int,
    ) {
        val safePage = page.coerceAtLeast(1)
        val listing =
            try {
                myBookingsService.list(telegramUserId, safePage, MY_BOOKINGS_PAGE_SIZE)
            } catch (ex: Exception) {
                logger.warn(
                    "ui.mybookings.list.failed user={} page={}",
                    telegramUserId,
                    safePage,
                    ex,
                )
                null
            }
        if (listing == null) {
            send(chatId, threadId, texts.unknownError(lang))
            return
        }
        val text =
            if (listing.bookings.isEmpty()) {
                texts.myBookingsEmpty(lang)
            } else {
                val lines = listing.bookings.joinToString("\n") { booking -> formatBookingLine(booking, lang) }
                buildString {
                    append(texts.myBookingsTitle(lang))
                    append("\n\n")
                    append(lines)
                }
            }
        val markup =
            if (listing.bookings.isEmpty()) {
                null
            } else {
                keyboards.myBookingsKeyboard(
                    lang = lang,
                    bookings = listing.bookings,
                    page = listing.page,
                    hasPrev = listing.hasPrev,
                    hasNext = listing.hasNext,
                    encodeShow = { id -> encodeShowCallback(listing.page, id) },
                    encodeCancel = { id -> encodeCancelCallback(listing.page, id) },
                )
            }
        send(chatId, threadId, text, markup)
    }

    private fun formatBookingLine(
        booking: MyBookingsService.BookingInfo,
        lang: String?,
    ): String {
        val locale = BotLocales.resolve(lang)
        val date = BotLocales.dateDMmm(booking.slotStart, booking.timezone, locale)
        val amount = BotLocales.money(booking.totalMinor, locale) + texts.receiptCurrencySuffix(lang)
        val guests = guestsLabel(lang, booking.guests)
        val tableLabel = "${texts.receiptTable(lang)} #${booking.tableNumber}"
        return "#${booking.shortId} • $date • ${booking.clubName} • $tableLabel • $guests • $amount"
    }

    private fun buildBookingDetails(
        booking: MyBookingsService.BookingInfo,
        lang: String?,
    ): String {
        val locale = BotLocales.resolve(lang)
        val zone = booking.timezone
        val day = BotLocales.dayNameShort(booking.slotStart, zone, locale)
        val date = BotLocales.dateDMmm(booking.slotStart, zone, locale)
        val start = BotLocales.timeHHmm(booking.slotStart, zone, locale)
        val end = BotLocales.timeHHmm(booking.slotEnd, zone, locale)
        val amount = BotLocales.money(booking.totalMinor, locale) + texts.receiptCurrencySuffix(lang)
        return listOf(
            "#${booking.shortId} • ${booking.clubName}",
            "${texts.receiptDate(lang)}: $day, $date · $start–$end",
            "${texts.receiptTable(lang)}: #${booking.tableNumber}",
            "${texts.receiptGuests(lang)}: ${booking.guests}",
            "${texts.myBookingsCancelAmount(lang)}: $amount",
        ).joinToString("\n")
    }

    private fun guestsLabel(
        lang: String?,
        guests: Int,
    ): String = if (texts.isEn(lang)) "$guests guests" else "$guests гостей"

    private fun encodeShowCallback(
        page: Int,
        id: UUID,
    ): String = "$BOOKINGS_SHOW_PREFIX${page.coerceAtLeast(1)}:$id"

    private fun encodeCancelCallback(
        page: Int,
        id: UUID,
    ): String = "$BOOKINGS_CANCEL_PREFIX${page.coerceAtLeast(1)}:$id"

    private fun parsePageAndId(
        data: String,
        prefix: String,
    ): Pair<Int, UUID>? {
        if (!data.startsWith(prefix)) return null
        val payload = data.removePrefix(prefix)
        val parts = payload.split(':', limit = 2)
        if (parts.size != 2) return null
        val page = parts[0].toIntOrNull() ?: return null
        val id = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: return null
        return page to id
    }

    private suspend fun attemptHold(
        decoded: DecodedGuests,
        slotEnd: Instant,
        idemKey: String,
        chatId: Long,
        threadId: Int?,
        lang: String?,
    ): BookingCmdResult {
        val holdResult =
            withContext(Dispatchers.IO) {
                bookingService.hold(
                    HoldRequest(
                        clubId = decoded.clubId,
                        tableId = decoded.tableId,
                        slotStart = decoded.startUtc,
                        slotEnd = slotEnd,
                        guestsCount = decoded.guests,
                        ttl = HOLD_TTL,
                    ),
                    "$idemKey:hold",
                )
            }
        logger.info(
            "ui.booking.hold status={} clubId={} tableId={} startSec={} guests={}",
            holdResult::class.simpleName,
            decoded.clubId,
            decoded.tableId,
            decoded.startUtc.epochSecond,
            decoded.guests,
        )
        return when (holdResult) {
            is BookingCmdResult.HoldCreated -> holdResult
            BookingCmdResult.DuplicateActiveBooking -> {
                send(chatId, threadId, texts.tableTaken(lang))
                holdResult
            }

            BookingCmdResult.IdempotencyConflict -> {
                send(chatId, threadId, texts.tooManyRequests(lang))
                holdResult
            }

            else -> {
                logger.warn(
                    "Unexpected hold result {} clubId={} tableId={} startSec={}",
                    holdResult::class.simpleName,
                    decoded.clubId,
                    decoded.tableId,
                    decoded.startUtc.epochSecond,
                )
                send(chatId, threadId, texts.unknownError(lang))
                holdResult
            }
        }
    }

    private suspend fun attemptConfirm(
        holdId: UUID,
        decoded: DecodedGuests,
        idemKey: String,
        chatId: Long,
        threadId: Int?,
        lang: String?,
    ): BookingCmdResult {
        val confirmResult =
            withContext(Dispatchers.IO) {
                bookingService.confirm(holdId, "$idemKey:confirm")
            }
        logger.info(
            "ui.booking.confirm status={} clubId={} tableId={} startSec={} guests={}",
            confirmResult::class.simpleName,
            decoded.clubId,
            decoded.tableId,
            decoded.startUtc.epochSecond,
            decoded.guests,
        )
        return when (confirmResult) {
            is BookingCmdResult.Booked -> confirmResult
            is BookingCmdResult.AlreadyBooked -> confirmResult
            BookingCmdResult.HoldExpired -> {
                send(chatId, threadId, texts.holdExpired(lang))
                confirmResult
            }

            BookingCmdResult.DuplicateActiveBooking -> {
                send(chatId, threadId, texts.tableTaken(lang))
                confirmResult
            }

            BookingCmdResult.IdempotencyConflict -> {
                send(chatId, threadId, texts.tooManyRequests(lang))
                confirmResult
            }

            BookingCmdResult.NotFound -> {
                logger.warn(
                    "booking.confirm not_found clubId={} tableId={} startSec={} guests={}",
                    decoded.clubId,
                    decoded.tableId,
                    decoded.startUtc.epochSecond,
                    decoded.guests,
                )
                send(chatId, threadId, texts.bookingNotFound(lang))
                confirmResult
            }

            else -> {
                logger.warn(
                    "Unexpected confirm result {} clubId={} tableId={} startSec={}",
                    confirmResult::class.simpleName,
                    decoded.clubId,
                    decoded.tableId,
                    decoded.startUtc.epochSecond,
                )
                send(chatId, threadId, texts.unknownError(lang))
                confirmResult
            }
        }
    }

    private suspend fun attemptFinalize(
        bookingId: UUID,
        decoded: DecodedGuests,
        slotEnd: Instant,
        table: TableAvailabilityDto,
        callbackQuery: CallbackQuery,
        chatId: Long,
        threadId: Int?,
        lang: String?,
    ): BookingCmdResult {
        val finalizeResult =
            withContext(Dispatchers.IO) {
                bookingService.finalize(
                    bookingId,
                    telegramUserId = callbackQuery.from()?.id()?.toLong(),
                )
            }
        logger.info(
            "ui.booking.finalize status={} clubId={} tableId={} startSec={} guests={}",
            finalizeResult::class.simpleName,
            decoded.clubId,
            decoded.tableId,
            decoded.startUtc.epochSecond,
            decoded.guests,
        )

        when (finalizeResult) {
            is BookingCmdResult.Booked -> {
                val receipt = buildReceipt(lang, decoded, slotEnd, table)
                send(chatId, threadId, receipt)
            }
            is BookingCmdResult.AlreadyBooked -> {
                val receipt = buildReceipt(lang, decoded, slotEnd, table)
                send(chatId, threadId, receipt)
            }

            BookingCmdResult.NotFound -> {
                logger.warn(
                    "booking.finalize not_found clubId={} tableId={} startSec={} guests={}",
                    decoded.clubId,
                    decoded.tableId,
                    decoded.startUtc.epochSecond,
                    decoded.guests,
                )
                send(chatId, threadId, texts.bookingNotFound(lang))
            }

            BookingCmdResult.IdempotencyConflict -> {
                send(chatId, threadId, texts.tooManyRequests(lang))
            }

            BookingCmdResult.DuplicateActiveBooking -> {
                send(chatId, threadId, texts.tableTaken(lang))
            }

            else -> {
                logger.warn(
                    "Unexpected finalize result {} clubId={} tableId={} startSec={} guests={}",
                    finalizeResult::class.simpleName,
                    decoded.clubId,
                    decoded.tableId,
                    decoded.startUtc.epochSecond,
                    decoded.guests,
                )
                send(chatId, threadId, texts.unknownError(lang))
            }
        }
        return finalizeResult
    }

    private suspend fun safeLoadClubs(limit: Int = CLUB_LIST_LIMIT): List<ClubDto> =
        withContext(Dispatchers.IO) {
            try {
                clubRepository.listClubs(limit)
            } catch (ex: Exception) {
                logger.error("Failed to load clubs", ex)
                emptyList()
            }
        }

    private suspend fun safeLoadNights(
        clubId: Long,
        limit: Int = NIGHT_LIST_LIMIT,
    ): List<NightDto>? =
        withContext(Dispatchers.IO) {
            try {
                availability.listOpenNights(clubId, limit)
            } catch (ex: Exception) {
                logger.error("Failed to load nights for club {}", clubId, ex)
                null
            }
        }

    private fun send(
        chatId: Long,
        threadId: Int?,
        text: String,
        markup: InlineKeyboardMarkup? = null,
    ) {
        val request = SendMessage(chatId, text)
        markup?.let { request.replyMarkup(it) }
        threadId?.let { request.messageThreadId(it) }
        bot.execute(request)
    }

    private suspend fun renderTablesPage(
        chatId: Long,
        threadId: Int?,
        lang: String?,
        clubId: Long,
        startUtc: Instant,
        page: Int,
        preloadedTables: List<TableAvailabilityDto>? = null,
    ): Boolean {
        val tables = preloadedTables ?: safeLoadTables(clubId, startUtc)
        val totalTables = tables.size
        if (totalTables == 0) {
            logger.info("ui.tables.page page={} size={} total={}", 1, TABLES_PAGE_SIZE, totalTables)
            send(chatId, threadId, texts.noTables(lang))
            return false
        }
        val endUtc = resolveNightEndUtc(clubId, startUtc) ?: startUtc.plus(DEFAULT_NIGHT_DURATION)
        val totalPages = maxOf((totalTables + TABLES_PAGE_SIZE - 1) / TABLES_PAGE_SIZE, 1)
        val targetPage = page.coerceIn(1, totalPages)
        val markup =
            keyboards.tablesKeyboard(tables, targetPage, TABLES_PAGE_SIZE, lang) { dto ->
                TableSelectCodec.encode(clubId, startUtc, endUtc, dto.tableId)
            }
        logger.info("ui.tables.page page={} size={} total={}", targetPage, TABLES_PAGE_SIZE, totalTables)
        send(chatId, threadId, texts.chooseTable(lang), markup)
        UiBookingMetrics.incTablesRendered()
        return true
    }

    private suspend fun handleGuestSelection(
        callbackQuery: CallbackQuery,
        chatId: Long,
        threadId: Int?,
        lang: String?,
        decoded: DecodedGuests,
    ) {
        UiBookingMetrics.incGuestsChosen()
        val mdcPairs =
            listOf(
                "clubId" to decoded.clubId.toString(),
                "tableId" to decoded.tableId.toString(),
                "startUtcSec" to decoded.startUtc.epochSecond.toString(),
                "step" to "booking",
            )
        mdcPairs.forEach { (key, value) -> MDC.put(key, value) }
        val outcome =
            try {
                UiBookingMetrics.timeBookingTotal {
                    processBookingFlow(callbackQuery, chatId, threadId, lang, decoded)
                }
            } catch (ex: Exception) {
                logger.warn("ui.booking.exception", ex)
                send(chatId, threadId, texts.unknownError(lang))
                BookingOutcome(success = false, reason = "unexpected")
            } finally {
                mdcPairs.forEach { (key, _) -> MDC.remove(key) }
            }
        if (outcome.success) {
            UiBookingMetrics.incBookingSuccess()
        } else {
            UiBookingMetrics.incBookingError()
        }
        logger.info(
            "ui.booking.outcome outcome={} reason={}",
            if (outcome.success) "success" else "error",
            outcome.reason,
        )
    }

    private suspend fun processBookingFlow(
        callbackQuery: CallbackQuery,
        chatId: Long,
        threadId: Int?,
        lang: String?,
        decoded: DecodedGuests,
    ): BookingOutcome {
        val tables = safeLoadTables(decoded.clubId, decoded.startUtc)
        val table =
            tables.firstOrNull { it.tableId == decoded.tableId && it.capacity > 0 }
                ?: run {
                    logger.info(
                        "ui.tbl.unavailable clubId={} tableId={} startSec={}",
                        decoded.clubId,
                        decoded.tableId,
                        decoded.startUtc.epochSecond,
                    )
                    send(chatId, threadId, texts.tableTaken(lang))
                    return BookingOutcome(success = false, reason = "not_found")
                }
        val slotEnd =
            if (decoded.endUtc.isAfter(decoded.startUtc)) {
                decoded.endUtc
            } else {
                decoded.startUtc.plus(DEFAULT_NIGHT_DURATION)
            }
        val idemKey =
            "uiflow:$chatId:${decoded.clubId}:${decoded.tableId}:${decoded.startUtc.epochSecond}:${decoded.guests}"
        logger.info(
            "ui.tbl.select clubId={} tableId={} startSec={} guests={}",
            decoded.clubId,
            decoded.tableId,
            decoded.startUtc.epochSecond,
            decoded.guests,
        )

        val holdResult =
            attemptHold(
                decoded = decoded,
                slotEnd = slotEnd,
                idemKey = idemKey,
                chatId = chatId,
                threadId = threadId,
                lang = lang,
            )
        val holdStageOutcome = holdOutcome(holdResult)
        return if (holdStageOutcome != null) {
            holdStageOutcome
        } else {
            continueBookingFlow(
                callbackQuery = callbackQuery,
                chatId = chatId,
                threadId = threadId,
                lang = lang,
                decoded = decoded,
                table = table,
                slotEnd = slotEnd,
                idemKey = idemKey,
                holdResult = holdResult as BookingCmdResult.HoldCreated,
            )
        }
    }

    private data class BookingOutcome(
        val success: Boolean,
        val reason: String,
    )

    private fun holdOutcome(result: BookingCmdResult): BookingOutcome? =
        when (result) {
            is BookingCmdResult.HoldCreated -> null
            BookingCmdResult.DuplicateActiveBooking -> BookingOutcome(success = false, reason = "duplicate")
            BookingCmdResult.IdempotencyConflict -> BookingOutcome(success = false, reason = "conflict")
            else -> BookingOutcome(success = false, reason = "unexpected")
        }

    private fun confirmOutcome(result: BookingCmdResult): BookingOutcome? =
        when (result) {
            is BookingCmdResult.Booked -> null
            is BookingCmdResult.AlreadyBooked -> null
            BookingCmdResult.HoldExpired -> BookingOutcome(success = false, reason = "expired")
            BookingCmdResult.DuplicateActiveBooking -> BookingOutcome(success = false, reason = "duplicate")
            BookingCmdResult.IdempotencyConflict -> BookingOutcome(success = false, reason = "conflict")
            BookingCmdResult.NotFound -> BookingOutcome(success = false, reason = "not_found")
            else -> BookingOutcome(success = false, reason = "unexpected")
        }

    private fun finalizeOutcome(result: BookingCmdResult): BookingOutcome =
        when (result) {
            is BookingCmdResult.Booked -> BookingOutcome(success = true, reason = "booked")
            is BookingCmdResult.AlreadyBooked -> BookingOutcome(success = true, reason = "already_booked")
            BookingCmdResult.NotFound -> BookingOutcome(success = false, reason = "not_found")
            BookingCmdResult.IdempotencyConflict -> BookingOutcome(success = false, reason = "conflict")
            BookingCmdResult.DuplicateActiveBooking -> BookingOutcome(success = false, reason = "duplicate")
            else -> BookingOutcome(success = false, reason = "unexpected")
        }

    private suspend fun continueBookingFlow(
        callbackQuery: CallbackQuery,
        chatId: Long,
        threadId: Int?,
        lang: String?,
        decoded: DecodedGuests,
        table: TableAvailabilityDto,
        slotEnd: Instant,
        idemKey: String,
        holdResult: BookingCmdResult.HoldCreated,
    ): BookingOutcome {
        val confirmResult =
            attemptConfirm(
                holdId = holdResult.holdId,
                decoded = decoded,
                idemKey = idemKey,
                chatId = chatId,
                threadId = threadId,
                lang = lang,
            )
        val confirmOutcome = confirmOutcome(confirmResult)
        if (confirmOutcome != null) {
            return confirmOutcome
        }
        val bookingId =
            when (confirmResult) {
                is BookingCmdResult.Booked -> confirmResult.bookingId
                is BookingCmdResult.AlreadyBooked -> confirmResult.bookingId
                else -> null
            }
        val finalOutcome =
            if (bookingId != null) {
                val finalizeResult =
                    attemptFinalize(
                        bookingId = bookingId,
                        decoded = decoded,
                        slotEnd = slotEnd,
                        table = table,
                        callbackQuery = callbackQuery,
                        chatId = chatId,
                        threadId = threadId,
                        lang = lang,
                    )
                finalizeOutcome(finalizeResult)
            } else {
                BookingOutcome(success = false, reason = "unexpected")
            }
        return finalOutcome
    }

    private suspend fun safeLoadTables(
        clubId: Long,
        startUtc: Instant,
    ): List<TableAvailabilityDto> =
        withContext(Dispatchers.IO) {
            try {
                UiBookingMetrics.timeListTables { availability.listFreeTables(clubId, startUtc) }
            } catch (ex: Exception) {
                logger.error("Failed to load tables for club {} start {}", clubId, startUtc, ex)
                emptyList()
            }
        }

    private suspend fun resolveNightEndUtc(
        clubId: Long,
        startUtc: Instant,
    ): Instant? {
        return findNight(clubId, startUtc)?.eventEndUtc
    }

    private suspend fun findNight(
        clubId: Long,
        startUtc: Instant,
    ): NightDto? {
        val nights = safeLoadNights(clubId) ?: return null
        return nights.firstOrNull { it.eventStartUtc == startUtc }
    }

    private suspend fun buildReceipt(
        lang: String?,
        decoded: DecodedGuests,
        slotEnd: Instant,
        table: TableAvailabilityDto,
    ): String {
        val clubName = resolveClubName(decoded.clubId) ?: decoded.clubId.toString()
        val night = findNight(decoded.clubId, decoded.startUtc)
        val zone = resolveZone(night?.timezone)
        val endUtc = if (night != null) night.eventEndUtc else slotEnd
        val safeEnd = if (endUtc.isAfter(decoded.startUtc)) endUtc else decoded.startUtc.plus(DEFAULT_NIGHT_DURATION)
        val depositFromMinor = table.minDepositMinor() * decoded.guests
        return formatReceipt(
            lang = lang,
            clubName = clubName,
            zone = zone,
            startUtc = decoded.startUtc,
            endUtc = safeEnd,
            tableNumber = table.tableNumber,
            guests = decoded.guests,
            depositFromMinor = depositFromMinor,
        )
    }

    private fun formatReceipt(
        lang: String?,
        clubName: String,
        zone: ZoneId,
        startUtc: Instant,
        endUtc: Instant,
        tableNumber: String,
        guests: Int,
        depositFromMinor: Long,
    ): String {
        val locale = BotLocales.resolve(lang)
        val day = BotLocales.dayNameShort(startUtc, zone, locale)
        val date = BotLocales.dateDMmm(startUtc, zone, locale)
        val start = BotLocales.timeHHmm(startUtc, zone, locale)
        val end = BotLocales.timeHHmm(endUtc, zone, locale)
        val dateLine = "$day, $date · $start–$end"
        val depositDisplay = BotLocales.money(depositFromMinor, locale) + texts.receiptCurrencySuffix(lang)
        return listOf(
            texts.bookingConfirmedTitle(lang),
            "${texts.receiptClub(lang)}: $clubName",
            "${texts.receiptDate(lang)}: $dateLine",
            "${texts.receiptTable(lang)}: #$tableNumber",
            "${texts.receiptGuests(lang)}: $guests",
            "${texts.receiptDepositFrom(lang)}: $depositDisplay",
        ).joinToString("\n")
    }

    private suspend fun resolveClubName(clubId: Long): String? {
        val clubs = safeLoadClubs(CLUB_LOOKUP_LIMIT)
        return clubs.firstOrNull { it.id == clubId }?.name
    }

    private fun buildClubSelectionMessage(
        clubs: List<ClubDto>,
        lang: String?,
    ): String {
        val header = texts.menu(lang).chooseClub
        if (clubs.isEmpty()) return header
        val details =
            clubs.joinToString("\n") { club ->
                buildString {
                    append("• ")
                    append(club.name)
                    val description = club.shortDescription?.takeIf { it.isNotBlank() }
                    if (description != null) {
                        append(" — ")
                        append(description)
                    }
                }
            }
        return "$header\n\n$details"
    }

    private fun buildNightsSelectionMessage(
        nights: List<NightDto>,
        lang: String?,
    ): String {
        val header = texts.chooseNight(lang)
        if (nights.isEmpty()) return header
        val details = nights.joinToString("\n") { night -> "• ${formatNightLabel(night, lang)}" }
        return "$header\n\n$details"
    }

    private fun formatNightLabel(
        night: NightDto,
        lang: String?,
    ): String {
        val locale = BotLocales.resolve(lang)
        val zone = resolveZone(night.timezone)
        val day = BotLocales.dayNameShort(night.eventStartUtc, zone, locale)
        val date = BotLocales.dateDMmm(night.eventStartUtc, zone, locale)
        val start = BotLocales.timeHHmm(night.eventStartUtc, zone, locale)
        val end = BotLocales.timeHHmm(night.eventEndUtc, zone, locale)
        val base = "$day, $date · $start–$end"
        return if (night.isSpecial) "✨ $base" else base
    }

    private fun resolveZone(timezone: String?): ZoneId =
        timezone?.let {
            try {
                ZoneId.of(it)
            } catch (_: Exception) {
                ZoneOffset.UTC
            }
        } ?: ZoneOffset.UTC

    private companion object {
        private const val MENU_CLUBS = "menu:clubs"
        private const val MENU_BOOKINGS = "menu:bookings"
        private const val CLUB_PREFIX = "club:"
        private const val NIGHT_PREFIX = "night:"
        private const val PAGE_PREFIX = "pg:"
        private const val TABLE_PREFIX = "tbl:"
        private const val GUEST_PREFIX = "g:"
        private const val BOOKINGS_PREFIX = "bk:"
        private const val BOOKINGS_LIST_PREFIX = "bk:list:"
        private const val BOOKINGS_SHOW_PREFIX = "bk:show:"
        private const val BOOKINGS_CANCEL_PREFIX = "bk:cancel:"
        private const val TABLES_PAGE_SIZE = 8
        private const val MY_BOOKINGS_PAGE_SIZE = 8
        private const val NOOP_CALLBACK = "noop"
        private val HOLD_TTL: Duration = Duration.ofMinutes(7)
        private val DEFAULT_NIGHT_DURATION: Duration = Duration.ofHours(8)
        private const val CLUB_LIST_LIMIT = 8
        private const val CLUB_LOOKUP_LIMIT = 32
        private const val NIGHT_LIST_LIMIT = 8
    }
}
