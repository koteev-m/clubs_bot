package com.example.bot.data.booking

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Enumeration of booking statuses stored in the database.
 */
enum class BookingStatus {
    BOOKED,
    SEATED,
    NO_SHOW,
    CANCELLED,
}

/** Events available for booking. */
object EventsTable : Table("events") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val title = text("title").nullable()
    val startAt = timestampWithTimeZone("start_at")
    val endAt = timestampWithTimeZone("end_at")
    val isSpecial = bool("is_special").default(false)
    val posterUrl = text("poster_url").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Physical tables inside clubs. */
object TablesTable : Table("tables") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val zoneId = long("zone_id").nullable()
    val tableNumber = integer("table_number")
    val capacity = integer("capacity")
    val minDeposit = decimal("min_deposit", 12, 2)
    val active = bool("active").default(true)
    override val primaryKey = PrimaryKey(id)
}

/** Holds placed on tables before confirming a booking. */
object BookingHoldsTable : Table("booking_holds") {
    val id = uuid("id")
    val eventId = long("event_id")
    val tableId = long("table_id")
    val holderUserId = long("holder_user_id").nullable()
    val guestsCount = integer("guests_count")
    val minDeposit = decimal("min_deposit", 12, 2)
    val slotStart = timestampWithTimeZone("slot_start")
    val slotEnd = timestampWithTimeZone("slot_end")
    val expiresAt = timestampWithTimeZone("expires_at")
    val idempotencyKey = text("idempotency_key")
    override val primaryKey = PrimaryKey(id)
}

/** Confirmed bookings. */
object BookingsTable : Table("bookings") {
    val id = uuid("id")
    val eventId = long("event_id")
    val clubId = long("club_id")
    val tableId = long("table_id")
    val tableNumber = integer("table_number")
    val guestUserId = long("guest_user_id").nullable()
    val guestName = text("guest_name").nullable()
    val phoneE164 = text("phone_e164").nullable()
    val promoterUserId = long("promoter_user_id").nullable()
    val guestsCount = integer("guests_count")
    val minDeposit = decimal("min_deposit", 12, 2)
    val totalDeposit = decimal("total_deposit", 12, 2)
    val slotStart = timestampWithTimeZone("slot_start")
    val slotEnd = timestampWithTimeZone("slot_end")
    val arrivalBy = timestampWithTimeZone("arrival_by").nullable()
    val status = text("status")
    val qrSecret = varchar("qr_secret", 64)
    val idempotencyKey = text("idempotency_key")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object TableSessionsTable : Table("table_sessions") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val nightStartUtc = timestampWithTimeZone("night_start_utc")
    val tableId = long("table_id")
    val status = text("status")
    val openMarker = short("open_marker").nullable()
    val openedAt = timestampWithTimeZone("opened_at")
    val closedAt = timestampWithTimeZone("closed_at").nullable()
    val openedBy = long("opened_by")
    val closedBy = long("closed_by").nullable()
    val note = text("note").nullable()
    override val primaryKey = PrimaryKey(id)
}

object TableDepositsTable : Table("table_deposits") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val nightStartUtc = timestampWithTimeZone("night_start_utc")
    val tableId = long("table_id")
    val tableSessionId = long("table_session_id")
    val paymentId = uuid("payment_id").nullable()
    val bookingId = uuid("booking_id").nullable()
    val guestUserId = long("guest_user_id").nullable()
    val amountMinor = long("amount_minor")
    val createdAt = timestampWithTimeZone("created_at")
    val createdBy = long("created_by")
    val updatedAt = timestampWithTimeZone("updated_at")
    val updatedBy = long("updated_by")
    val updateReason = text("update_reason").nullable()
    override val primaryKey = PrimaryKey(id)
}

object TableDepositAllocationsTable : Table("table_deposit_allocations") {
    val id = long("id").autoIncrement()
    val depositId = long("deposit_id")
    val categoryCode = text("category_code")
    val amountMinor = long("amount_minor")
    override val primaryKey = PrimaryKey(id)
}
