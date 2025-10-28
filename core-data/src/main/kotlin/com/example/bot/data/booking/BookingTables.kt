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
