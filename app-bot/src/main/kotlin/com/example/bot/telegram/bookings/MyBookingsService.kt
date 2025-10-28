package com.example.bot.telegram.bookings

import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.BookingsTable
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.booking.core.OutboxRepository
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.text.BotLocales
import com.example.bot.text.BotTexts
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Application service powering Telegram "My bookings" flow.
 */
class MyBookingsService(
    private val database: org.jetbrains.exposed.sql.Database,
    private val userRepository: UserRepository,
    private val outboxRepository: OutboxRepository,
    private val metrics: MyBookingsMetrics,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(MyBookingsService::class.java)

    suspend fun list(
        telegramUserId: Long,
        page: Int,
        pageSize: Int,
    ): BookingPage {
        val user = userRepository.getByTelegramId(telegramUserId) ?: return BookingPage.empty(page)
        val safePage = page.coerceAtLeast(1)
        val offset = (safePage - 1) * pageSize
        val records = loadBookings(user.id, limit = pageSize + 1, offset = offset.toLong())
        if (records.isEmpty()) {
            return BookingPage(emptyList(), safePage, hasPrev = safePage > 1, hasNext = false)
        }
        val hasNext = records.size > pageSize
        val visible = records.take(pageSize)
        return BookingPage(visible, safePage, hasPrev = safePage > 1, hasNext = hasNext)
    }

    suspend fun loadBooking(
        telegramUserId: Long,
        bookingId: UUID,
    ): BookingInfo? {
        val user = userRepository.getByTelegramId(telegramUserId) ?: return null
        val record = loadBookingById(user.id, bookingId) ?: return null
        return record
    }

    suspend fun cancel(
        telegramUserId: Long,
        bookingId: UUID,
        texts: BotTexts,
        lang: String?,
    ): CancelResult {
        val user = userRepository.getByTelegramId(telegramUserId) ?: return CancelResult.NotFound
        val info = loadBookingById(user.id, bookingId) ?: return CancelResult.NotFound

        metrics.incCancelRequested(info.clubId)

        return when (info.status) {
            BookingStatus.CANCELLED -> {
                metrics.incCancelAlready(info.clubId)
                logger.info("mybookings.cancel: already booking={} user={}", bookingId, telegramUserId)
                CancelResult.Already(info)
            }

            BookingStatus.BOOKED -> performCancellation(user, info, texts, lang)
            BookingStatus.SEATED, BookingStatus.NO_SHOW -> {
                metrics.incCancelAlready(info.clubId)
                logger.info("mybookings.cancel: already booking={} user={} status={}", bookingId, telegramUserId, info.status)
                CancelResult.Already(info)
            }
        }
    }

    private suspend fun performCancellation(
        user: User,
        info: BookingInfo,
        texts: BotTexts,
        lang: String?,
    ): CancelResult {
        val updated =
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                val affected =
                    BookingsTable.update({ BookingsTable.id eq info.id }) { row ->
                        row[status] = BookingStatus.CANCELLED.name
                        row[updatedAt] = Instant.now(clock).atOffset(ZoneOffset.UTC)
                    }
                affected > 0
            }
        if (!updated) {
            metrics.incCancelAlready(info.clubId)
            logger.info("mybookings.cancel: already booking={} user={} race", info.id, user.telegramId)
            return CancelResult.Already(info.copy(status = BookingStatus.CANCELLED))
        }

        val message = buildCancelMessage(info, user, texts, lang)
        enqueueOutbox(info, message)
        metrics.incCancelOk(info.clubId)
        logger.info("mybookings.cancel: ok booking={} user={}", info.id, user.telegramId)
        return CancelResult.Ok(info.copy(status = BookingStatus.CANCELLED))
    }

    private suspend fun loadBookings(
        userId: Long,
        limit: Int,
        offset: Long,
    ): List<BookingInfo> {
        return newSuspendedTransaction(context = Dispatchers.IO, db = database) {
            val rows =
                BookingsTable
                    .selectAll()
                    .where {
                        (BookingsTable.guestUserId eq userId) and (BookingsTable.status inList ACTIVE_STATUSES)
                    }
                    .orderBy(BookingsTable.slotStart to SortOrder.ASC)
                    .limit(limit, offset)
                    .toList()
            mapRows(rows)
        }
    }

    private suspend fun loadBookingById(
        userId: Long,
        bookingId: UUID,
    ): BookingInfo? {
        return newSuspendedTransaction(context = Dispatchers.IO, db = database) {
            val row =
                BookingsTable
                    .selectAll()
                    .where{
                        (BookingsTable.id eq bookingId) and (BookingsTable.guestUserId eq userId) }
                    .limit(1)
                    .firstOrNull()
            if (row == null) {
                null
            } else {
                mapRows(listOf(row)).firstOrNull()
            }
        }
    }

    private fun buildCancelMessage(
        info: BookingInfo,
        user: User,
        texts: BotTexts,
        lang: String?,
    ): String {
        val locale = BotLocales.resolve(lang)
        val zone = info.timezone
        val date = BotLocales.dateDMmm(info.slotStart, zone, locale)
        val start = BotLocales.timeHHmm(info.slotStart, zone, locale)
        val end = BotLocales.timeHHmm(info.slotEnd, zone, locale)
        val amount = BotLocales.money(info.totalMinor, locale) + texts.receiptCurrencySuffix(lang)
        val userLabel =
            user.username?.takeIf { it.isNotBlank() }?.let { "@${it}" }
                ?: "tg:${user.telegramId}"
        return buildString {
            append("❌ ")
            append(texts.myBookingsCancelTitle(lang, info.shortId))
            append('\n')
            append(info.clubName)
            append('\n')
            append(texts.receiptDate(lang))
            append(": ")
            append("$date · $start–$end")
            append('\n')
            append(texts.receiptTable(lang))
            append(": #")
            append(info.tableNumber)
            append('\n')
            append(texts.receiptGuests(lang))
            append(": ")
            append(info.guests)
            append('\n')
            append(texts.myBookingsCancelAmount(lang))
            append(": ")
            append(amount)
            append('\n')
            append(texts.myBookingsCancelledBy(lang))
            append(' ')
            append(userLabel)
        }
    }

    private suspend fun enqueueOutbox(
        info: BookingInfo,
        message: String,
    ) {
        val payload: JsonObject =
            buildJsonObject {
                put("bookingId", info.id.toString())
                put("clubId", info.clubId)
                info.adminChatId?.let { put("chatId", it) }
                put("text", message)
                put("dedup", "booking:${info.id}:cancel")
            }
        outboxRepository.enqueue("booking.cancelled", payload)
    }

    private fun mapRows(rows: List<ResultRow>): List<BookingInfo> {
        if (rows.isEmpty()) return emptyList()
        val clubIds = rows.map { it[BookingsTable.clubId] }.toSet()
        val clubMap = loadClubs(clubIds)
        val eventIds = rows.map { it[BookingsTable.eventId] }.toSet()
        val events = loadEvents(eventIds)
        return rows.mapNotNull { row ->
            val club = clubMap[row[BookingsTable.clubId]] ?: return@mapNotNull null
            val event = events[row[BookingsTable.eventId]]
            row.toBookingInfo(club, event)
        }
    }

    private fun loadClubs(ids: Set<Long>): Map<Long, ClubRow> {
        if (ids.isEmpty()) return emptyMap()
        val results =
            ClubsLite
                .selectAll()
                .where{
                    ClubsLite.id inList ids.toList()
                }
                .associateBy({ it[ClubsLite.id] }, { ClubRow.fromRow(it) })
        return results
    }

    private fun loadEvents(ids: Set<Long>): Map<Long, EventRow> {
        if (ids.isEmpty()) return emptyMap()
        return EventsTable
            .selectAll()
            .where{
                EventsTable.id inList ids.toList()
            }
            .associateBy({ it[EventsTable.id] }, { EventRow.fromRow(it) })
    }

    private fun ResultRow.toBookingInfo(
        club: ClubRow,
        event: EventRow?,
    ): BookingInfo {
        val id = this[BookingsTable.id]
        val totalMinor = this[BookingsTable.totalDeposit].toMinor()
        val slotStart = this[BookingsTable.slotStart].toInstant()
        val slotEnd = this[BookingsTable.slotEnd].toInstant()
        val start = event?.start ?: slotStart
        val end = event?.end ?: slotEnd
        val shortId = id.toString().replace("-", "").take(SHORT_ID_LENGTH).uppercase()
        val timezone = resolveZone(club.timezone)
        return BookingInfo(
            id = id,
            shortId = shortId,
            clubId = this[BookingsTable.clubId],
            clubName = club.name,
            tableNumber = this[BookingsTable.tableNumber],
            guests = this[BookingsTable.guestsCount],
            totalMinor = totalMinor,
            slotStart = start,
            slotEnd = end,
            timezone = timezone,
            adminChatId = club.adminChatId,
            status = BookingStatus.valueOf(this[BookingsTable.status]),
        )
    }

    data class BookingInfo(
        val id: UUID,
        val shortId: String,
        val clubId: Long,
        val clubName: String,
        val tableNumber: Int,
        val guests: Int,
        val totalMinor: Long,
        val slotStart: Instant,
        val slotEnd: Instant,
        val timezone: ZoneId,
        val adminChatId: Long?,
        val status: BookingStatus,
    )

    data class BookingPage(
        val bookings: List<BookingInfo>,
        val page: Int,
        val hasPrev: Boolean,
        val hasNext: Boolean,
    ) {
        companion object {
            fun empty(page: Int) = BookingPage(emptyList(), page, hasPrev = page > 1, hasNext = false)
        }
    }

    sealed interface CancelResult {
        data class Ok(val info: BookingInfo) : CancelResult

        data class Already(val info: BookingInfo) : CancelResult

        data object NotFound : CancelResult
    }

    private data class ClubRow(
        val id: Long,
        val name: String,
        val timezone: String?,
        val adminChatId: Long?,
    ) {
        companion object {
            fun fromRow(row: ResultRow): ClubRow {
                return ClubRow(
                    id = row[ClubsLite.id],
                    name = row[ClubsLite.name],
                    timezone = row[ClubsLite.timezone],
                    adminChatId = row[ClubsLite.adminChatId],
                )
            }
        }
    }

    private data class EventRow(
        val id: Long,
        val start: Instant,
        val end: Instant,
    ) {
        companion object {
            fun fromRow(row: ResultRow): EventRow {
                return EventRow(
                    id = row[EventsTable.id],
                    start = row[EventsTable.startAt].toInstant(),
                    end = row[EventsTable.endAt].toInstant(),
                )
            }
        }
    }

    private fun resolveZone(raw: String?): ZoneId {
        if (raw.isNullOrBlank()) {
            return ZoneOffset.UTC
        }
        return try {
            ZoneId.of(raw)
        } catch (_: Exception) {
            ZoneOffset.UTC
        }
    }

    private fun BigDecimal.toMinor(): Long {
        val scaled = this.setScale(2, RoundingMode.HALF_UP)
        return scaled.movePointRight(2).longValueExact()
    }

    companion object {
        private val ACTIVE_STATUSES = listOf(BookingStatus.BOOKED.name, BookingStatus.SEATED.name)
        private const val SHORT_ID_LENGTH = 6
    }
}

class MyBookingsMetrics(private val registry: io.micrometer.core.instrument.MeterRegistry?) {
    fun incOpen() {
        registry?.counter("ui.mybookings.open.total", SOURCE_TAG, SOURCE_VALUE)?.increment()
    }

    fun incCancelRequested(clubId: Long) {
        counter("ui.mybookings.cancel.requested", clubId).increment()
    }

    fun incCancelOk(clubId: Long) {
        counter("booking.cancel.ok", clubId).increment()
    }

    fun incCancelAlready(clubId: Long) {
        counter("booking.cancel.already", clubId).increment()
    }

    private fun counter(name: String, clubId: Long): io.micrometer.core.instrument.Counter {
        return registry?.counter(name, SOURCE_TAG, SOURCE_VALUE, CLUB_TAG, clubId.toString())
            ?: NoopCounter
    }

    private object NoopCounter : io.micrometer.core.instrument.Counter {
        override fun count(): Double = 0.0

        override fun increment(amount: Double) {}

        override fun increment() {}

        override fun measure(): MutableIterable<io.micrometer.core.instrument.Measurement> = mutableListOf()

        override fun getId(): io.micrometer.core.instrument.Meter.Id {
            throw UnsupportedOperationException("Noop counter has no id")
        }
    }

    companion object {
        private const val SOURCE_TAG = "source"
        private const val SOURCE_VALUE = "telegram"
        private const val CLUB_TAG = "club_id"
    }
}

private object ClubsLite : Table("clubs") {
    val id: Column<Long> = long("id")
    val name: Column<String> = text("name")
    val timezone: Column<String?> = text("timezone").nullable()
    val adminChatId: Column<Long?> = long("admin_chat_id").nullable()
}
